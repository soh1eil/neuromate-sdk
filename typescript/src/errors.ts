import type { Position } from "./types.js";

/** Base class of everything this SDK throws. `String(err.message)` is the server's teaching text. */
export class NeuromateError extends Error {
  /** True when repeating the same call may succeed (network blips, 5xx, rate limits). */
  retryable = false;
  constructor(message: string) {
    super(message);
    this.name = new.target.name;
  }
}

/** The request never got an answer (connection refused, timeout, DNS). */
export class TransportError extends NeuromateError {
  override retryable = true;
}

/** The server answered with an HTTP error status. */
export class HTTPError extends NeuromateError {
  constructor(message: string, public status: number, public body = "") {
    super(message);
  }
}

/**
 * HTTP 401: the API key is missing, wrong or revoked. Servers that send the
 * unified JSON body fill `code` ("unauthorized") and `hint`.
 */
export class AuthError extends HTTPError {
  code = "unauthorized";
  hint = "";
}

/** HTTP 5xx, or the platform reported an INTERNAL ERROR. Safe to retry. */
export class ServerError extends HTTPError {
  override retryable = true;
  constructor(message: string, status = 500, body = "") {
    super(message, status, body);
  }
}

/** Too many requests. `retryAfter` is in seconds. */
export class RateLimitedError extends NeuromateError {
  override retryable = true;
  constructor(message: string, public retryAfter = 1) {
    super(message);
  }
}

/** A JSON-RPC level error (unknown tool, invalid params, unreadable reply). */
export class ProtocolError extends NeuromateError {
  constructor(message: string, public code = 0, public data?: unknown) {
    super(message);
  }
  /** The stable snake_case code (`unknown_tool`, `invalid_params`...) when the server sent one. */
  get errorCode(): string | null {
    const c = (this.data as { code?: unknown } | undefined)?.code;
    return typeof c === "string" ? c : null;
  }
  get hint(): string {
    const h = (this.data as { hint?: unknown } | undefined)?.hint;
    return typeof h === "string" ? h : "";
  }
}

export interface ToolErrorInit {
  code?: string | null;
  hint?: string;
  legalMoves?: string[];
  position?: Position | null;
  text?: string;
  tool?: string;
  context?: Record<string, unknown>;
}

/** A tool ran and said no. The server explains what was wrong and what to do. */
export class ToolError extends NeuromateError {
  static code: string | null = null;
  /** What was wrong. */
  detail: string;
  /**
   * Stable machine code: `illegal_move`, `not_your_turn`, `invalid_params`, `no_game`...
   * (full list in docs/MCP.md).
   */
  code: string | null;
  /** The server's "WHAT TO DO" line. */
  hint: string;
  /** UCI moves that would have been accepted. */
  legalMoves: string[];
  /** The current game when the server attached it. */
  position: Position | null;
  /** The complete server text. */
  text: string;
  tool: string;
  /** Every other field of the error (`illegal_attempts_remaining`, `game_id`, `retry_after_seconds`...). */
  context: Record<string, unknown>;
  constructor(detail: string, init: ToolErrorInit = {}) {
    super(init.text || detail);
    this.detail = detail;
    this.code = init.code ?? (new.target as typeof ToolError).code;
    this.hint = init.hint ?? "";
    this.legalMoves = init.legalMoves ?? [];
    this.position = init.position ?? null;
    this.text = init.text || detail;
    this.tool = init.tool ?? "";
    this.context = init.context ?? {};
  }
}

export class IllegalMoveError extends ToolError { static override code = "illegal_move"; }
export class NotYourTurnError extends ToolError { static override code = "not_your_turn"; }
/** `expectedPly` no longer matches: the game moved on. Re-read it. */
export class StalePlyError extends ToolError { static override code = "stale_ply"; }
export class GameOverError extends ToolError { static override code = "game_over"; }
export class ForbiddenError extends ToolError { static override code = "forbidden"; }
export class NotFoundError extends ToolError { static override code = "not_found"; }
/** A parameter was missing, unknown, of the wrong type or out of range. */
export class InvalidParamsError extends ToolError { static override code = "invalid_params"; }
/** Legacy name: servers before the unified error codes said `bad_request`. */
export class BadRequestError extends InvalidParamsError { static override code = "bad_request"; }
/** You have no game at all (`gameId` is required). */
export class NoGameError extends ToolError { static override code = "no_game"; }
export class NotAllowedError extends ToolError { static override code = "not_allowed"; }
export class ConflictError extends ToolError { static override code = "conflict"; }

/** Internal: the strategy did not answer before the move deadline. */
export class StrategyTimeout extends NeuromateError {}

/** Throw this inside a strategy to resign the game. */
export class Resign extends Error {
  constructor() {
    super("resign");
    this.name = "Resign";
  }
}

const BY_CODE: Record<string, typeof ToolError> = Object.fromEntries(
  [IllegalMoveError, NotYourTurnError, StalePlyError, GameOverError, ForbiddenError, NotFoundError, InvalidParamsError, BadRequestError, NoGameError, NotAllowedError, ConflictError]
    .map((c) => [c.code as string, c as typeof ToolError]),
);

export function toolErrorClass(code: string | null | undefined): typeof ToolError {
  return (code && BY_CODE[code]) || ToolError;
}
