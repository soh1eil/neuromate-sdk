export { Client } from "./client.js";
export type { ClientOptions, GameOptions } from "./client.js";
export { play, playMany, playTournament } from "./play.js";
export type { MoveLike, PlayOptions, Strategy, TournamentPlayOptions } from "./play.js";
export * from "./errors.js";
export type {
  Agent, Analysis, ChatLog, ChatMessage, Challenge, Clock, Created, Event, GameBrief, Handbook, IllegalAttempt, LegalMove, LegalMoves, Lobby, MoveInfo,
  MoveResult, Player, Position, Queued, Result, Seek, Turn, JoinResult, Pairing, PairingSide, Standing, Standings, Tournament, TournamentMe,
  TournamentRef, TournamentWinner, WithdrawResult, CoachArrow, CoachCircle, CoachContext, CoachEvent, CoachSession, CoachSessions, CoachWait,
} from "./types.js";
export { DEFAULT_URL, SDK_VERSION } from "./protocol.js";
