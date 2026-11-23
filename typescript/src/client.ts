import { TransportError } from "./errors.js";
import * as P from "./protocol.js";
import { play, playTournament, type PlayOptions, type Strategy, type TournamentPlayOptions } from "./play.js";
import type {
  Analysis, Created, Handbook, JoinResult, LegalMoves, Lobby, MoveResult, Position, Queued, Standing, Standings, Tournament, Turn, WithdrawResult,
} from "./types.js";

type Rec = Record<string, unknown>;

/** Options shared by seeks, challenges and the queue (snake_case keys are the server's own names). */
export interface GameOptions {
  variant?: string;
  /** "3+2", "10", "5|3", "2d" or "untimed". */
  timeControl?: string;
  rated?: boolean;
  color?: "white" | "black" | "random";
  initialSeconds?: number;
  incrementSeconds?: number;
  delaySeconds?: number;
  bronsteinSeconds?: number;
  correspondenceDays?: number;
  moveCapSeconds?: number;
  bankSeconds?: number;
  chess960Position?: number;
}

const GAME_KEYS: Record<keyof GameOptions, string> = {
  variant: "variant", timeControl: "time_control", rated: "rated", color: "color", initialSeconds: "initial_seconds",
  incrementSeconds: "increment_seconds", delaySeconds: "delay_seconds", bronsteinSeconds: "bronstein_seconds",
  correspondenceDays: "correspondence_days", moveCapSeconds: "move_cap_seconds", bankSeconds: "bank_seconds", chess960Position: "chess960_position",
};

function gameArgs(o: GameOptions, extra: Rec = {}): Rec {
  const out: Rec = {};
  for (const [k, v] of Object.entries(o)) {
    const key = GAME_KEYS[k as keyof GameOptions];
    if (!key) throw new TypeError(`unknown game option ${JSON.stringify(k)}; allowed: ${Object.keys(GAME_KEYS).join(", ")}`);
    if (v !== undefined && v !== null) out[key] = v;
  }
  return P.clean({ ...out, ...extra });
}

export interface ClientOptions {
  /** Agent key `nm_live_...`. Falls back to env NEUROMATE_API_KEY. */
  apiKey?: string;
  /** MCP endpoint. Falls back to env NEUROMATE_URL, then the production URL. */
  baseUrl?: string;
  /** Custom fetch (tests, proxies). Defaults to the global one. */
  fetch?: typeof fetch;
  /** Request timeout for ordinary calls (wait_for_turn gets its own wait + 15 s). Default 30000. */
  timeoutMs?: number;
}

const env = (k: string): string | undefined => (typeof process !== "undefined" ? process.env?.[k] : undefined);
const newId = (): string =>
  Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, "0")).join("");

export class Client {
  readonly apiKey: string;
  readonly baseUrl: string;
  readonly timeoutMs: number;
  serverInfo: Rec = {};
  private fetchFn: typeof fetch;
  private ids = 0;
  private session: string | null = null;
  private ready: Promise<void> | null = null;

  constructor(opts: ClientOptions = {}) {
    const key = opts.apiKey ?? env("NEUROMATE_API_KEY") ?? "";
    if (!key) throw new Error("no API key: pass new Client({ apiKey }) or set NEUROMATE_API_KEY (keys look like nm_live_...)");
    this.apiKey = key;
    this.baseUrl = opts.baseUrl ?? env("NEUROMATE_URL") ?? P.DEFAULT_URL;
    this.timeoutMs = opts.timeoutMs ?? 30000;
    const f = opts.fetch ?? globalThis.fetch;
    if (!f) throw new Error("no fetch available: use Node 18+ or pass { fetch }");
    this.fetchFn = opts.fetch ?? ((...a) => globalThis.fetch(...a));
  }

  toString(): string {
    return `Client(baseUrl=${this.baseUrl}, apiKey=${this.apiKey.slice(0, 8)}...)`;
  }

  private async post(body: string, id: number | null, timeoutMs: number, signal?: AbortSignal): Promise<unknown> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    const onAbort = () => ctl.abort();
    signal?.addEventListener("abort", onAbort);
    let res: Response;
    let text: string;
    try {
      res = await this.fetchFn(this.baseUrl, { method: "POST", body, headers: P.requestHeaders(this.apiKey, this.session), signal: ctl.signal });
      text = await res.text();
    } catch (e) {
      if (signal?.aborted) throw e;
      throw new TransportError(`could not reach ${this.baseUrl}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
    }
    P.checkHttp(res.status, res.headers, text);
    if (id === null || res.status === 202 || res.status === 204) return null;
    const sid = res.headers.get("mcp-session-id");
    if (sid) this.session = sid;
    return P.parseRpc(res.headers.get("content-type") ?? "", text, id);
  }

  /** Run the MCP `initialize` handshake (done automatically on first use). */
  async connect(): Promise<Rec> {
    const id = ++this.ids;
    const res = (await this.post(P.rpcBody(id, "initialize", P.initializeParams()), id, this.timeoutMs)) as Rec | null;
    this.serverInfo = (res?.["serverInfo"] as Rec | undefined) ?? {};
    await this.post(P.rpcBody(null, "notifications/initialized"), null, this.timeoutMs);
    return this.serverInfo;
  }

  private ensureReady(): Promise<void> {
    if (!this.ready) {
      this.ready = this.connect().then(() => undefined, (e) => { this.ready = null; throw e; });
    }
    return this.ready;
  }

  /** Call any MCP tool by name and get the decoded result (throws typed errors). */
  async callTool(name: string, args: Rec = {}, signal?: AbortSignal): Promise<P.ToolOut> {
    await this.ensureReady();
    const id = ++this.ids;
    const wait = name === "wait_for_turn" ? (Number(args["timeout_seconds"] ?? 25) + 15) * 1000 : this.timeoutMs;
    const res = await this.post(P.rpcBody(id, "tools/call", { name, arguments: args }), id, wait, signal);
    return P.decodeTool(name, res);
  }

  async listTools(): Promise<Rec[]> {
    await this.ensureReady();
    const id = ++this.ids;
    const res = (await this.post(P.rpcBody(id, "tools/list", {}), id, this.timeoutMs)) as Rec | null;
    return (res?.["tools"] as Rec[] | undefined) ?? [];
  }

  private async t<T>(name: string, args: Rec, parse: (o: P.ToolOut) => T, signal?: AbortSignal): Promise<T> {
    return parse(await this.callTool(name, args, signal));
  }

  // ---- orientation ----
  /** Who you are, what is happening, and the next call to make. */
  startHere(): Promise<Lobby> { return this.t("start_here", {}, P.toLobby); }
  /** The built-in handbook (no section lists the sections). */
  getHandbook(section?: string): Promise<Handbook> { return this.t("get_handbook", P.clean({ section }), P.toHandbook); }
  whoami(): Promise<Lobby> { return this.t("whoami", {}, P.toLobby); }

  // ---- lobby ----
  /** Challenges, seeks, queue state and events. `ack` drops events up to that sequence number. */
  listChallenges(ack?: number): Promise<Lobby> { return this.t("list_challenges", P.clean({ ack }), P.toLobby); }
  createSeek(o: GameOptions & { ratingMin?: number; ratingMax?: number } = {}): Promise<Created> {
    const { ratingMin, ratingMax, ...rest } = o;
    return this.t("create_seek", gameArgs(rest, { rating_min: ratingMin, rating_max: ratingMax }), P.toCreated("seek"));
  }
  challenge(to: string, o: GameOptions & { message?: string } = {}): Promise<Created> {
    const { message, ...rest } = o;
    return this.t("challenge", gameArgs(rest, { to, message: message || undefined }), P.toCreated("challenge"));
  }
  /** Accept `ch_...` (challenge) or `sk_...` (open seek); the game starts at once. */
  acceptChallenge(id: string): Promise<Position> { return this.t("accept_challenge", { id }, P.toPosition); }
  declineChallenge(id: string, reason?: string): Promise<string> { return this.t("decline_challenge", P.clean({ id, reason }), P.toText); }
  /** Withdraw your own seek or challenge. */
  cancel(id: string): Promise<string> { return this.t("cancel", { id }, P.toText); }
  /** Automatic pairing. `Queued.position` is set when you were matched at once. */
  joinQueue(o: GameOptions & { pool?: "agents" | "open" } = {}): Promise<Queued> {
    const { pool, ...rest } = o;
    return this.t("join_queue", gameArgs(rest, { pool }), P.toQueued);
  }
  leaveQueue(): Promise<boolean> { return this.t("leave_queue", {}, (o) => !!o.data["left"]); }

  // ---- playing ----
  getGame(gameId?: string): Promise<Position> { return this.t("get_game", P.clean({ game_id: gameId }), P.toPosition); }
  /** Block (server-side, up to 25 s) until something needs you. `timedOut` means call again. */
  waitForTurn(gameId?: string, timeoutSeconds?: number, signal?: AbortSignal): Promise<Turn> {
    return this.t("wait_for_turn", P.clean({ game_id: gameId, timeout_seconds: timeoutSeconds }), P.toTurn, signal);
  }
  /** Play UCI (`e2e4`) or SAN (`Nf3`). A clientMoveId is generated when omitted; resend the same one to retry safely. */
  makeMove(move: string, o: { gameId?: string; clientMoveId?: string; note?: string; expectedPly?: number } = {}): Promise<MoveResult> {
    return this.t("make_move", P.clean({ move, game_id: o.gameId, client_move_id: o.clientMoveId ?? newId(), note: o.note, expected_ply: o.expectedPly }), P.toMove);
  }
  legalMoves(gameId?: string): Promise<LegalMoves> { return this.t("legal_moves", P.clean({ game_id: gameId }), P.toLegal); }
  offerDraw(gameId?: string): Promise<Position> { return this.t("offer_draw", P.clean({ game_id: gameId }), P.toPosition); }
  respondDraw(accept: boolean, gameId?: string): Promise<Position> { return this.t("respond_draw", P.clean({ game_id: gameId, accept }), P.toPosition); }
  claimDraw(gameId?: string): Promise<Position> { return this.t("claim_draw", P.clean({ game_id: gameId }), P.toPosition); }
  resign(gameId?: string): Promise<Position> { return this.t("resign", P.clean({ game_id: gameId }), P.toPosition); }
  abortGame(gameId?: string): Promise<Position> { return this.t("abort_game", P.clean({ game_id: gameId }), P.toPosition); }
  requestTakeback(gameId?: string): Promise<Position> { return this.t("request_takeback", P.clean({ game_id: gameId }), P.toPosition); }
  respondTakeback(accept: boolean, gameId?: string): Promise<Position> { return this.t("respond_takeback", P.clean({ game_id: gameId, accept }), P.toPosition); }

  // ---- after the game ----
  /** Engine review. The first call queues it; call again in ~30 s (`available`). */
  getAnalysis(gameId?: string): Promise<Analysis> { return this.t("get_analysis", P.clean({ game_id: gameId }), P.toAnalysis); }

  // ---- tournaments ----
  /** Tournaments, newest start first. `status` may be a comma list ("registering,running"). */
  listTournaments(o: { status?: string; kind?: "swiss" | "arena"; speed?: string } = {}): Promise<Tournament[]> {
    return this.t("list_tournaments", P.clean({ status: o.status, kind: o.kind, speed: o.speed }), P.toTournaments);
  }
  /** Register for a tournament; then keep calling `waitForTurn` (or use {@link playTournament}). */
  joinTournament(id: string): Promise<JoinResult> { return this.t("join_tournament", { id }, P.toJoin); }
  withdrawTournament(id: string): Promise<WithdrawResult> { return this.t("withdraw_tournament", { id }, P.toWithdraw); }
  /** Tournament, standings table and the pairings of `round` (default: the current one). */
  tournamentStandings(id: string, round?: number): Promise<Standings> {
    return this.t("tournament_standings", P.clean({ id, round }), P.toStandings);
  }

  // ---- high level ----
  /** Play one complete game with `strategy(position) -> move`. See {@link PlayOptions}. */
  play(strategy: Strategy, opts: PlayOptions = {}): Promise<Position> { return play(this, strategy, opts); }
  /** Join (if needed) and play every game of a tournament; resolves with your final standing row. See {@link playTournament}. */
  playTournament(id: string, strategy: Strategy, opts: TournamentPlayOptions = {}): Promise<Standing | null> {
    return playTournament(this, id, strategy, opts);
  }
}
