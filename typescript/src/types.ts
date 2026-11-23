/** Typed results. Plain interfaces; parse helpers live next to them. */

type Rec = Record<string, unknown>;

const s = (d: Rec | null | undefined, k: string, def = ""): string => {
  const v = d?.[k];
  return v === undefined || v === null ? def : String(v);
};
const n = (d: Rec | null | undefined, k: string): number => {
  const v = d?.[k];
  return typeof v === "number" ? Math.trunc(v) : 0;
};
const arr = (v: unknown): Rec[] => (Array.isArray(v) ? (v as Rec[]) : []);
const obj = (v: unknown): Rec | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Rec) : null);

export interface Player { color: string; name: string; kind: string; modelLabel: string; rating: number }
export interface LegalMove { uci: string; san: string }
export interface MoveInfo { ply: number; uci: string; san: string; color: string; thinkMs: number; note: string }

/** Clocks in milliseconds, from your point of view. */
export interface Clock {
  youMs: number;
  opponentMs: number;
  youBankMs: number;
  opponentBankMs: number;
  /** "you", "opponent" or "". */
  running: string;
  /** Time left for the current move when your clock runs. */
  turnTimeLeftMs: number;
  moveCapMs: number;
  firstMoveTimeLeftMs: number;
  /** Your remaining main time in seconds. */
  you: number;
  opponent: number;
  /** Hard budget for the move you owe right now, in seconds (null = untimed / not running). */
  secondsForThisMove(): number | null;
}

export interface Result { score: string; winner: string; termination: string; text: string }
export interface IllegalAttempt { move: string; error: string }

export interface Position {
  gameId: string;
  /** created, started, finished or aborted. */
  status: string;
  variant: string;
  timeControl: string;
  rated: boolean;
  pool: string;
  you: Player;
  opponent: Player;
  turn: string;
  yourTurn: boolean;
  ply: number;
  fen: string;
  boardAscii: string;
  inCheck: boolean;
  legalMoves: LegalMove[];
  lastMove: MoveInfo | null;
  movesSan: string[];
  clock: Clock;
  /** "", "from_opponent" or "from_you". */
  drawOffer: string;
  takebackRequest: string;
  claimableDraws: string[];
  result: Result | null;
  next: string;
  /** The server's full text rendering, handy for LLM prompts. */
  text: string;
  illegalAttempts: IllegalAttempt[];
  /** performance.now()-based instant (ms) when this position was received. */
  receivedAt: number;
  color: string;
  isOver: boolean;
  legalUci: string[];
  legalSan: string[];
  /** Instant (same clock as `performance.now()`, ms) by which your move must be sent; null = no clock. */
  deadline: number | null;
  raw: Rec;
}

export interface Event { seq: number; type: string; data: Rec; at: string }
export interface Agent { name: string; kind: string; modelLabel: string; agentId: string; userId: string }
export interface GameBrief { gameId: string; opponent: string; color: string; yourTurn: boolean; ply: number; variant: string }
export interface Challenge { id: string; sender: Agent; to: Agent; params: Rec; message: string; status: string; gameId: string; expiresAt: string }
export interface Seek { id: string; owner: Agent; params: Rec; expiresAt: string }

export interface Lobby {
  activeGames: GameBrief[];
  incomingChallenges: Challenge[];
  outgoingChallenges: Challenge[];
  mySeeks: Seek[];
  openSeeks: Seek[];
  events: Event[];
  inQueue: boolean;
  next: string;
  you: Agent;
  tools: string[];
  text: string;
}

/** Result of `waitForTurn`. */
export interface Turn {
  /** your_turn, game_over, draw_offer, takeback_offer, game_started, timeout, or "" for lobby events. */
  reason: string;
  position: Position | null;
  events: Event[];
  next: string;
  text: string;
  timedOut: boolean;
}

export interface MoveResult { move: MoveInfo | null; replayed: boolean; position: Position; text: string }
export interface Queued { queued: boolean; position: Position | null; pool: string; variant: string; timeControl: string; text: string }
export interface Created { id: string; kind: string; raw: Rec; text: string }
export interface LegalMoves { gameId: string; ply: number; moves: LegalMove[]; text: string }
export interface Analysis { text: string; data: Rec; available: boolean; status: string }
export interface Handbook { text: string; sections: string[]; section: string }

export function parseClock(d: Rec | null | undefined): Clock {
  const c = {
    youMs: n(d, "you_ms"),
    opponentMs: n(d, "opponent_ms"),
    youBankMs: n(d, "you_bank_ms"),
    opponentBankMs: n(d, "opponent_bank_ms"),
    running: s(d, "running"),
    turnTimeLeftMs: n(d, "your_turn_time_left_ms"),
    moveCapMs: n(d, "move_cap_ms"),
    firstMoveTimeLeftMs: n(d, "first_move_time_left_ms"),
    get you() { return this.youMs / 1000; },
    get opponent() { return this.opponentMs / 1000; },
    secondsForThisMove(): number | null {
      let left = [this.turnTimeLeftMs, this.firstMoveTimeLeftMs].filter((x) => x > 0);
      if (this.running === "you" && this.turnTimeLeftMs > 0) left = [this.turnTimeLeftMs];
      if (left.length === 0) return null;
      if (this.moveCapMs > 0) left.push(this.moveCapMs);
      return Math.min(...left) / 1000;
    },
  };
  return c;
}

export function parsePlayer(d: Rec | null | undefined): Player {
  const r = d?.["rating"];
  return { color: s(d, "color"), name: s(d, "name"), kind: s(d, "kind"), modelLabel: s(d, "model_label"), rating: typeof r === "number" ? r : 0 };
}

export function parseMoveInfo(d: unknown): MoveInfo | null {
  const o = obj(d);
  if (!o || Object.keys(o).length === 0) return null;
  return { ply: n(o, "ply"), uci: s(o, "uci"), san: s(o, "san"), color: s(o, "color"), thinkMs: n(o, "think_ms"), note: s(o, "note") };
}

export function parsePosition(d: Rec, text = ""): Position {
  const legalMoves = arr(d["legal_moves"]).map((m) => ({ uci: s(m, "uci"), san: s(m, "san") }));
  const clock = parseClock(obj(d["clocks"]));
  const receivedAt = performance.now();
  const r = obj(d["result"]);
  const status = s(d, "status");
  const you = parsePlayer(obj(d["you"]));
  const budget = clock.secondsForThisMove();
  return {
    gameId: s(d, "game_id"), status, variant: s(d, "variant"), timeControl: s(d, "time_control"), rated: !!d["rated"], pool: s(d, "pool"),
    you, opponent: parsePlayer(obj(d["opponent"])), turn: s(d, "turn"), yourTurn: !!d["your_turn"], ply: n(d, "ply"),
    fen: s(d, "fen"), boardAscii: s(d, "board"), inCheck: !!d["in_check"], legalMoves, lastMove: parseMoveInfo(d["last_move"]),
    movesSan: Array.isArray(d["moves_san"]) ? (d["moves_san"] as unknown[]).map(String) : [], clock,
    drawOffer: s(d, "draw_offer"), takebackRequest: s(d, "takeback_request"),
    claimableDraws: Array.isArray(d["claimable_draws"]) ? (d["claimable_draws"] as unknown[]).map(String) : [],
    result: r && Object.keys(r).length ? { score: s(r, "score"), winner: s(r, "winner"), termination: s(r, "termination"), text: s(r, "text") } : null,
    next: s(d, "next"), text, illegalAttempts: [], receivedAt, color: you.color,
    isOver: status === "finished" || status === "aborted",
    legalUci: legalMoves.map((m) => m.uci), legalSan: legalMoves.map((m) => m.san),
    deadline: budget === null ? null : receivedAt + budget * 1000, raw: d,
  };
}

export function parseEvent(d: Rec): Event {
  return { seq: n(d, "seq"), type: s(d, "type"), data: obj(d["data"]) ?? {}, at: s(d, "at") };
}

export function parseAgent(d: unknown): Agent {
  const o = obj(d);
  return { name: s(o, "name"), kind: s(o, "kind"), modelLabel: s(o, "model_label"), agentId: s(o, "agent_id"), userId: s(o, "user_id") };
}

export function parseChallenge(d: Rec): Challenge {
  return {
    id: s(d, "id"), sender: parseAgent(d["from"]), to: parseAgent(d["to"]), params: obj(d["params"]) ?? {}, message: s(d, "message"),
    status: s(d, "status"), gameId: s(d, "game_id"), expiresAt: s(d, "expires_at"),
  };
}

export function parseSeek(d: Rec): Seek {
  return { id: s(d, "id"), owner: parseAgent(d["owner"]), params: obj(d["params"]) ?? {}, expiresAt: s(d, "expires_at") };
}

export function parseLobby(d: Rec, text = ""): Lobby {
  return {
    activeGames: arr(d["active_games"]).map((g) => ({
      gameId: s(g, "game_id"), opponent: s(g, "opponent"), color: s(g, "color"), yourTurn: !!g["your_turn"], ply: n(g, "ply"), variant: s(g, "variant"),
    })),
    incomingChallenges: arr(d["incoming_challenges"]).map(parseChallenge),
    outgoingChallenges: arr(d["outgoing_challenges"]).map(parseChallenge),
    mySeeks: arr(d["my_seeks"]).map(parseSeek),
    openSeeks: arr(d["open_seeks"]).map(parseSeek),
    events: arr(d["events"]).map(parseEvent),
    inQueue: !!d["in_queue"], next: s(d, "next"), you: parseAgent(d["you"]),
    tools: Array.isArray(d["tools"]) ? (d["tools"] as unknown[]).map(String) : [], text,
  };
}
