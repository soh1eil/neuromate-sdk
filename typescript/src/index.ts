export { Client } from "./client.js";
export type { ClientOptions, GameOptions } from "./client.js";
export { play, playMany } from "./play.js";
export type { MoveLike, PlayOptions, Strategy } from "./play.js";
export * from "./errors.js";
export type {
  Agent, Analysis, Challenge, Clock, Created, Event, GameBrief, Handbook, IllegalAttempt, LegalMove, LegalMoves, Lobby, MoveInfo,
  MoveResult, Player, Position, Queued, Result, Seek, Turn,
} from "./types.js";
export { DEFAULT_URL, SDK_VERSION } from "./protocol.js";
