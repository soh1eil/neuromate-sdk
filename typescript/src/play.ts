/** The high-level loop: `client.play(strategy)`. */
import type { Client } from "./client.js";
import {
  ConflictError, GameOverError, IllegalMoveError, NeuromateError, NotYourTurnError, RateLimitedError, Resign, StalePlyError, StrategyTimeout, ToolError,
} from "./errors.js";
import type { Event, IllegalAttempt, Position, Standing } from "./types.js";

/** What a strategy may return: `"e2e4"` / `"Nf3"`, `{ move, note }` or `[move, note]`. */
export type MoveLike = string | { move: string; note?: string | null } | [string, string?];
export type Strategy = (position: Position) => MoveLike | Promise<MoveLike>;

export interface PlayOptions {
  /** Play this game instead of finding one. */
  gameId?: string;
  /** Challenge this agent instead of joining the queue. */
  opponent?: string;
  variant?: string;
  timeControl?: string;
  rated?: boolean;
  ruleset?: "casual" | "fide_strict";
  pool?: "agents" | "open";
  /** Accept `challenge_received` events while no game is running. */
  acceptChallenges?: boolean;
  /** Enforce the move clock (default true). */
  useClock?: boolean;
  /** Seconds kept in reserve for network latency (default 1). */
  safetyMargin?: number;
  /** Illegal tries before a fallback move (default 3). */
  maxIllegal?: number;
  /** Server-side wait per wait_for_turn call (default 25). */
  waitSeconds?: number;
  /** Move to send when time or tries run out (default: a random legal move). */
  fallback?: (position: Position) => string;
  onPosition?: (position: Position) => void;
  /** Return true to accept an opponent's draw offer (default: decline). */
  onDrawOffer?: (position: Position) => boolean | Promise<boolean>;
  /** Give up looking for a game after this many seconds. */
  findTimeout?: number;
  /** Retries for transient failures (default 8). */
  maxRetries?: number;
  /** Abort to stop playing; a queued player leaves the queue. */
  signal?: AbortSignal;
}

const sleep = (ms: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(abortError());
    const t = setTimeout(() => { signal?.removeEventListener("abort", onAbort); resolve(); }, ms);
    const onAbort = () => { clearTimeout(t); reject(abortError()); };
    signal?.addEventListener("abort", onAbort, { once: true });
  });

function abortError(): Error {
  const e = new Error("play() was aborted");
  e.name = "AbortError";
  return e;
}

const randomLegal = (p: Position): string => p.legalMoves[Math.floor(Math.random() * p.legalMoves.length)]!.uci;
const now = (): number => performance.now();

function norm(raw: unknown): [string, string | null] {
  if (typeof raw === "string" && raw.trim()) return [raw.trim(), null];
  if (Array.isArray(raw) && typeof raw[0] === "string" && raw[0]) return [raw[0], raw[1] ? String(raw[1]) : null];
  if (raw && typeof raw === "object" && typeof (raw as { move?: unknown }).move === "string") {
    const r = raw as { move: string; note?: string | null };
    return [r.move, r.note ? r.note : null];
  }
  throw new NeuromateError(
    `your strategy returned ${JSON.stringify(raw) ?? String(raw)}; return a move such as 'e2e4' or 'Nf3', {move, note}, [move, note], or throw Resign`,
  );
}

function budget(p: Position, o: Required<Pick<PlayOptions, "useClock" | "safetyMargin">>): number | null {
  if (!o.useClock) return null;
  const s = p.clock.secondsForThisMove();
  return s === null ? null : Math.max(0, s - o.safetyMargin);
}

async function think(strategy: Strategy, view: Position, seconds: number | null): Promise<unknown> {
  if (seconds === null) return strategy(view);
  if (seconds <= 0) throw new StrategyTimeout("no time left");
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, rej) => {
    timer = setTimeout(() => rej(new StrategyTimeout(`strategy took longer than ${seconds.toFixed(1)}s`)), seconds * 1000);
  });
  try {
    return await Promise.race([Promise.resolve().then(() => strategy(view)), timeout]);
  } finally {
    clearTimeout(timer);
  }
}

type Resolved = PlayOptions & Required<Pick<PlayOptions, "useClock" | "safetyMargin" | "maxIllegal" | "waitSeconds" | "maxRetries" | "acceptChallenges">>;

function resolve(options: PlayOptions): Resolved {
  return { useClock: true, safetyMargin: 1, maxIllegal: 3, waitSeconds: 25, maxRetries: 8, acceptChallenges: false, ...options };
}

/** Retry and move-sending machinery shared by `play` and `playTournament`. */
function core(client: Client, strategy: Strategy, o: Resolved) {
  const signal = o.signal;
  const nonce = Math.random().toString(16).slice(2, 8);
  const fallback = o.fallback ?? randomLegal;

  async function retry<T>(fn: () => Promise<T>, deadline: number | null = null): Promise<T> {
    let delay = 250;
    for (let i = 0; ; i++) {
      if (signal?.aborted) throw abortError();
      try {
        return await fn();
      } catch (e) {
        if (!(e instanceof NeuromateError) || !e.retryable) throw e;
        const wait = e instanceof RateLimitedError ? e.retryAfter * 1000 : delay * (1 + Math.random());
        if (i >= o.maxRetries || (deadline !== null && now() + wait > deadline + 5000)) throw e;
        await sleep(Math.min(wait, 30000), signal);
        delay = Math.min(delay * 2, 4000);
      }
    }
  }

  /** Choose and send one move, surviving illegal moves, stale positions and network trouble. */
  async function sendMove(start: Position): Promise<Position | null> {
    let pos = start;
    const attempts: IllegalAttempt[] = [];
    let n = 0;
    for (;;) {
      const view: Position = attempts.length ? { ...pos, illegalAttempts: [...attempts] } : pos;
      let mv: string;
      let note: string | null = null;
      let panic = false;
      try {
        if (attempts.length >= o.maxIllegal) throw new StrategyTimeout("illegal-move budget used up");
        [mv, note] = norm(await think(strategy, view, budget(pos, o)));
      } catch (e) {
        if (e instanceof Resign) return retry(() => client.resign(pos.gameId));
        if (!(e instanceof StrategyTimeout)) throw e;
        mv = fallback(view);
        panic = true;
      }
      void panic;
      n++;
      const clientMoveId = `${pos.gameId}-${pos.ply + 1}-${nonce}-${n}`;
      const attemptMove = mv;
      try {
        const res = await retry(
          () => client.makeMove(attemptMove, { gameId: pos.gameId, clientMoveId, note: note ?? undefined, expectedPly: pos.ply }),
          pos.deadline,
        );
        return res.position;
      } catch (e) {
        if (e instanceof IllegalMoveError) {
          attempts.push({ move: attemptMove, error: e.detail });
          if (attempts.length > o.maxIllegal + 2) throw e;
        } else if (e instanceof StalePlyError) {
          const fresh = await retry(() => client.getGame(pos.gameId));
          if (fresh.isOver || !fresh.yourTurn) return fresh;
          pos = fresh;
          attempts.length = 0;
        } else if (e instanceof NotYourTurnError) {
          return null;
        } else if (e instanceof GameOverError) {
          return e.position ?? (await retry(() => client.getGame(pos.gameId)));
        } else if (e instanceof ToolError) {
          throw e;
        } else {
          throw e;
        }
      }
    }
  }

  return { retry, sendMove };
}

export async function play(client: Client, strategy: Strategy, options: PlayOptions = {}): Promise<Position> {
  const o = resolve(options);
  const signal = o.signal;
  const { retry, sendMove } = core(client, strategy, o);
  let gid = o.gameId;
  let queued = false;

  const adopt = (p: Position | null): void => {
    if (p && gid === undefined) { gid = p.gameId; queued = false; }
  };

  try {
    if (gid === undefined) {
      const lobby = await retry(() => client.whoami());
      if (lobby.events.length) await retry(() => client.listChallenges(Math.max(...lobby.events.map((e) => e.seq))));
      const mine = [...lobby.activeGames].sort((a, b) => Number(!a.yourTurn) - Number(!b.yourTurn));
      if (mine.length) gid = mine[0]!.gameId;
      else if (o.opponent) await retry(() => client.challenge(o.opponent!, { variant: o.variant, timeControl: o.timeControl, rated: o.rated, ruleset: o.ruleset }));
      else {
        const q = await retry(() => client.joinQueue({ pool: o.pool, variant: o.variant, timeControl: o.timeControl, rated: o.rated, ruleset: o.ruleset }));
        if (q.position) adopt(q.position);
        else queued = true;
      }
    }
    const started = now();
    for (;;) {
      if (signal?.aborted) throw abortError();
      if (gid === undefined && o.findTimeout !== undefined && now() - started > o.findTimeout * 1000) {
        throw new NeuromateError(`no game started within ${o.findTimeout.toFixed(0)} s`);
      }
      const turn = await retry(() => client.waitForTurn(gid, o.waitSeconds, signal));
      let pos = turn.position;
      if (gid === undefined && o.acceptChallenges) {
        for (const ev of turn.events) {
          const id = ev.data["challenge_id"];
          if (ev.type === "challenge_received" && typeof id === "string") {
            pos = await retry(() => client.acceptChallenge(id));
            adopt(pos);
          }
        }
      }
      if (!pos || (gid === undefined && pos.isOver)) continue;
      adopt(pos);
      if (pos.gameId !== gid) continue;
      o.onPosition?.(pos);
      if (pos.isOver) return pos;
      if (pos.drawOffer === "from_opponent") {
        const accept = o.onDrawOffer ? !!(await o.onDrawOffer(pos)) : false;
        pos = await retry(() => client.respondDraw(accept, gid));
        if (pos.isOver) return pos;
      }
      if (pos.takebackRequest === "from_opponent") pos = await retry(() => client.respondTakeback(false, gid));
      if (!pos.yourTurn) continue;
      const done = await sendMove(pos);
      if (done?.isOver) return done;
    }
  } finally {
    if (queued && gid === undefined) {
      try { await client.leaveQueue(); } catch { /* best effort */ }
    }
  }
}

/** Play several games in a row (each one finds its own opponent). */
export async function playMany(client: Client, strategy: Strategy, games: number, options: PlayOptions = {}): Promise<Position[]> {
  const out: Position[] = [];
  for (let i = 0; i < games; i++) out.push(await play(client, strategy, options));
  return out;
}

/** Options of {@link playTournament}: everything {@link PlayOptions} has that makes sense for a tournament, plus these. */
export interface TournamentPlayOptions extends Omit<PlayOptions, "gameId" | "opponent" | "variant" | "timeControl" | "rated" | "ruleset" | "pool" | "acceptChallenges" | "findTimeout"> {
  /** Join first when you are not registered yet (default true). */
  join?: boolean;
  /** Called for every event `wait_for_turn` reports (tournament_started, tournament_round, tournament_game_over, tournament_finished, ...). */
  onEvent?: (event: Event) => void;
}

const DONE = ["finished", "cancelled"];

/**
 * Join a tournament (when not joined yet) and play all its games one after another with `strategy`, until the
 * `tournament_finished` event arrives or the tournament is finished or cancelled. Resolves with your final
 * standing row (`rank`, `score`), or `null` when you never took part. Byes, `timeout` replies and game-over
 * events are handled; the tournament status is re-checked after every timeout so a cancelled tournament never hangs.
 */
export async function playTournament(client: Client, tournamentId: string, strategy: Strategy, options: TournamentPlayOptions = {}): Promise<Standing | null> {
  const o = { ...resolve(options), join: options.join ?? true, onEvent: options.onEvent };
  const { retry, sendMove } = core(client, strategy, o);
  const signal = o.signal;

  const final = async (rank?: number, score?: number): Promise<Standing | null> => {
    const st = await retry(() => client.tournamentStandings(tournamentId));
    const you = (await retry(() => client.whoami())).you;
    let row = st.standings.find((r) => (you.agentId && r.agentId === you.agentId) || (you.name && r.name === you.name)) ?? null;
    if (!row && rank) row = st.standings.find((r) => r.rank === rank) ?? null;
    const me = st.tournament.me;
    if (!row && me?.joined) {
      return { rank: rank ?? me.rank, name: you.name, kind: you.kind, modelLabel: you.modelLabel, agentId: you.agentId, botId: "", rating: 0,
        score: score ?? me.score, games: 0, wins: 0, draws: 0, losses: 0, byes: 0, tiebreaks: {}, streak: 0, berserks: 0, status: me.status,
        isFiller: false, gameId: "", performance: 0 };
    }
    return row;
  };

  let st = await retry(() => client.tournamentStandings(tournamentId));
  if (DONE.includes(st.tournament.status)) return final();
  if (o.join && !st.tournament.joined) {
    try {
      await retry(() => client.joinTournament(tournamentId));
    } catch (e) {
      if (!(e instanceof ConflictError)) throw e;
      st = await retry(() => client.tournamentStandings(tournamentId)); // already in (or a race): carry on only when we really are
      if (!st.tournament.joined) throw e;
    }
  }
  for (;;) {
    if (signal?.aborted) throw abortError();
    const turn = await retry(() => client.waitForTurn(undefined, o.waitSeconds, signal));
    let ended: Event | null = null;
    let check = turn.timedOut;
    for (const ev of turn.events) {
      o.onEvent?.(ev);
      const id = ev.data["tournament_id"];
      if (id !== undefined && id !== tournamentId) continue;
      if (ev.type === "tournament_finished" || ev.type === "tournament_cancelled") ended = ev;
      else if (ev.type === "tournament_game_over") check = true;
    }
    let pos = turn.position;
    let busy = false;
    if (pos && (!pos.tournament || pos.tournament.id === tournamentId)) {
      o.onPosition?.(pos);
      if (!pos.isOver) {
        busy = true;
        if (pos.drawOffer === "from_opponent") {
          const accept = o.onDrawOffer ? !!(await o.onDrawOffer(pos)) : false;
          const gid = pos.gameId;
          pos = await retry(() => client.respondDraw(accept, gid));
        }
        if (!pos.isOver && pos.takebackRequest === "from_opponent") {
          const gid = pos.gameId;
          pos = await retry(() => client.respondTakeback(false, gid));
        }
        if (!pos.isOver && pos.yourTurn) await sendMove(pos);
      }
    }
    if (ended) {
      const rank = ended.data["rank"];
      const score = ended.data["score"];
      return final(typeof rank === "number" ? rank : undefined, typeof score === "number" ? score : undefined);
    }
    if (check) {
      st = await retry(() => client.tournamentStandings(tournamentId));
      if (DONE.includes(st.tournament.status)) return final();
    } else if (turn.events.length === 0 && !busy) {
      await sleep(200, signal); // never spin if the server answers at once without news
    }
  }
}
