"""Typed errors. ``str(error)`` is always the server's own teaching message."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from ._types import Position


class NeuromateError(Exception):
    """Base class of everything this SDK raises."""

    #: True when repeating the same call may succeed (network blips, 5xx, rate limits).
    retryable: bool = False


class TransportError(NeuromateError):
    """The request never got an answer (connection refused, timeout, DNS)."""

    retryable = True


class HTTPError(NeuromateError):
    """The server answered with an HTTP error status."""

    def __init__(self, message: str, status: int, body: str = "") -> None:
        super().__init__(message)
        self.status = status
        self.body = body


class AuthError(HTTPError):
    """HTTP 401: the API key is missing, wrong or revoked.

    Servers that send the unified JSON body fill ``code`` (``unauthorized``)
    and ``hint``; ``str(error)`` is then ``"<message> <hint>"``.
    """

    code: str = "unauthorized"
    hint: str = ""


class ServerError(HTTPError):
    """HTTP 5xx, or the platform reported an INTERNAL ERROR. Safe to retry."""

    retryable = True

    def __init__(self, message: str, status: int = 500, body: str = "") -> None:
        super().__init__(message, status, body)


class RateLimitedError(NeuromateError):
    """Too many requests. ``retry_after`` is in seconds."""

    retryable = True

    def __init__(self, message: str, retry_after: float = 1.0) -> None:
        super().__init__(message)
        self.retry_after = retry_after


class ProtocolError(NeuromateError):
    """A JSON-RPC level error (unknown tool, invalid params, unreadable reply)."""

    def __init__(self, message: str, code: int = 0, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data

    @property
    def error_code(self) -> str | None:
        """The stable snake_case code (``unknown_tool``, ``invalid_params``...) when the server sent one."""
        c = self.data.get("code") if isinstance(self.data, dict) else None
        return c if isinstance(c, str) else None

    @property
    def hint(self) -> str:
        h = self.data.get("hint") if isinstance(self.data, dict) else None
        return h if isinstance(h, str) else ""


class ToolError(NeuromateError):
    """A tool ran and said no. The server explains what was wrong and what to do.

    Attributes:
        message: what was wrong.
        code: stable machine code such as ``illegal_move``, ``not_your_turn``,
            ``invalid_params``, ``no_game`` (see docs/MCP.md for the full list).
        hint: the server's "WHAT TO DO" line.
        context: every other field of the error (``illegal_attempts_remaining``,
            ``game_id``, ``retry_after_seconds``...).
        legal_moves: UCI moves that would have been accepted (illegal-move errors).
        position: the current game when the server attached it.
        text: the complete server text, as an LLM would read it.
    """

    code: str | None = None

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        hint: str = "",
        legal_moves: list[str] | None = None,
        position: Position | None = None,
        text: str = "",
        tool: str = "",
        context: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(text or message)
        self.message = message
        if code is not None:
            self.code = code
        self.hint = hint
        self.legal_moves = legal_moves or []
        self.position = position
        self.text = text or message
        self.tool = tool
        self.context: dict[str, Any] = context or {}


class IllegalMoveError(ToolError):
    code = "illegal_move"


class NotYourTurnError(ToolError):
    code = "not_your_turn"


class StalePlyError(ToolError):
    """``expected_ply`` no longer matches: the game moved on. Re-read it."""

    code = "stale_ply"


class GameOverError(ToolError):
    code = "game_over"


class ForbiddenError(ToolError):
    code = "forbidden"


class NotFoundError(ToolError):
    code = "not_found"


class InvalidParamsError(ToolError):
    """A parameter was missing, unknown, of the wrong type or out of range."""

    code = "invalid_params"


class BadRequestError(InvalidParamsError):
    """Legacy name: servers before the unified error codes said ``bad_request``."""

    code = "bad_request"


class NoGameError(ToolError):
    """You have no game at all (``game_id`` is required)."""

    code = "no_game"


class NotAllowedError(ToolError):
    code = "not_allowed"


class ConflictError(ToolError):
    code = "conflict"


class StrategyTimeout(NeuromateError):
    """Internal: your strategy did not answer before the move deadline."""


class Resign(Exception):
    """Raise this inside a strategy to resign the game."""


_BY_CODE: dict[str, type[ToolError]] = {
    c.code: c
    for c in (
        IllegalMoveError,
        NotYourTurnError,
        StalePlyError,
        GameOverError,
        ForbiddenError,
        NotFoundError,
        InvalidParamsError,
        BadRequestError,
        NoGameError,
        NotAllowedError,
        ConflictError,
    )
}


def tool_error_class(code: str | None) -> type[ToolError]:
    return _BY_CODE.get(code or "", ToolError)
