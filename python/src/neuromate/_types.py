"""Typed results. Plain dataclasses, no validation library."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Mapping


def _s(d: Mapping[str, Any], k: str, default: str = "") -> str:
    v = d.get(k)
    return default if v is None else str(v)


def _i(d: Mapping[str, Any], k: str) -> int:
    v = d.get(k)
    return int(v) if isinstance(v, (int, float)) else 0


@dataclass(frozen=True, slots=True)
class Player:
    color: str = ""
    name: str = ""
    kind: str = ""
    model_label: str = ""
    rating: float = 0.0

    @classmethod
    def _from(cls, d: Mapping[str, Any] | None) -> "Player":
        d = d or {}
        return cls(_s(d, "color"), _s(d, "name"), _s(d, "kind"), _s(d, "model_label"), float(d.get("rating") or 0))


@dataclass(frozen=True, slots=True)
class LegalMove:
    uci: str
    san: str = ""

    def __str__(self) -> str:
        return self.uci


@dataclass(frozen=True, slots=True)
class MoveInfo:
    ply: int
    uci: str
    san: str
    color: str = ""
    think_ms: int = 0
    note: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any] | None) -> "MoveInfo | None":
        if not d:
            return None
        return cls(_i(d, "ply"), _s(d, "uci"), _s(d, "san"), _s(d, "color"), _i(d, "think_ms"), _s(d, "note"))


@dataclass(frozen=True, slots=True)
class Clock:
    """Clocks in milliseconds, from your point of view."""

    you_ms: int = 0
    opponent_ms: int = 0
    you_bank_ms: int = 0
    opponent_bank_ms: int = 0
    running: str = ""  # "you", "opponent" or ""
    turn_time_left_ms: int = 0  # time left for the current move when your clock runs
    move_cap_ms: int = 0
    first_move_time_left_ms: int = 0

    @classmethod
    def _from(cls, d: Mapping[str, Any] | None) -> "Clock":
        d = d or {}
        return cls(
            _i(d, "you_ms"), _i(d, "opponent_ms"), _i(d, "you_bank_ms"), _i(d, "opponent_bank_ms"),
            _s(d, "running"), _i(d, "your_turn_time_left_ms"), _i(d, "move_cap_ms"), _i(d, "first_move_time_left_ms"),
        )

    @property
    def you(self) -> float:
        """Your remaining main time in seconds."""
        return self.you_ms / 1000

    @property
    def opponent(self) -> float:
        return self.opponent_ms / 1000

    @property
    def seconds_for_this_move(self) -> float | None:
        """Hard budget for the move you owe right now, in seconds (None = untimed / not running)."""
        left = [x for x in (self.turn_time_left_ms, self.first_move_time_left_ms) if x > 0]
        if self.running == "you" and self.turn_time_left_ms > 0:
            left = [self.turn_time_left_ms]
        if not left:
            return None
        if self.move_cap_ms > 0:
            left.append(self.move_cap_ms)
        return min(left) / 1000


@dataclass(frozen=True, slots=True)
class Result:
    score: str = ""  # "1-0", "0-1", "1/2-1/2"
    winner: str = ""  # "white", "black" or ""
    termination: str = ""
    text: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any] | None) -> "Result | None":
        if not d:
            return None
        return cls(_s(d, "score"), _s(d, "winner"), _s(d, "termination"), _s(d, "text"))


@dataclass(frozen=True, slots=True)
class IllegalAttempt:
    move: str
    error: str


@dataclass(frozen=True, slots=True)
class Position:
    """Everything about a game from your seat. This is what your strategy receives."""

    game_id: str
    status: str  # created, started, finished, aborted
    variant: str
    time_control: str
    rated: bool
    pool: str
    you: Player
    opponent: Player
    turn: str  # "white" / "black"
    your_turn: bool
    ply: int
    fen: str
    board_ascii: str
    in_check: bool
    legal_moves: list[LegalMove]
    last_move: MoveInfo | None
    moves_san: list[str]
    clock: Clock
    draw_offer: str  # "", "from_opponent", "from_you"
    takeback_request: str
    claimable_draws: list[str]
    result: Result | None
    next: str
    text: str = ""  # the server's full text rendering, handy for LLM prompts
    illegal_attempts: tuple[IllegalAttempt, ...] = ()
    received_at: float = field(default_factory=time.monotonic, compare=False)
    raw: Mapping[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @property
    def color(self) -> str:
        return self.you.color

    @property
    def is_over(self) -> bool:
        return self.status in ("finished", "aborted")

    @property
    def legal_uci(self) -> list[str]:
        return [m.uci for m in self.legal_moves]

    @property
    def legal_san(self) -> list[str]:
        return [m.san for m in self.legal_moves]

    @property
    def deadline(self) -> float | None:
        """``time.monotonic()`` instant by which your move must be sent (None = no clock)."""
        s = self.clock.seconds_for_this_move
        return None if s is None else self.received_at + s

    @classmethod
    def _from(cls, d: Mapping[str, Any], text: str = "") -> "Position":
        return cls(
            game_id=_s(d, "game_id"), status=_s(d, "status"), variant=_s(d, "variant"),
            time_control=_s(d, "time_control"), rated=bool(d.get("rated")), pool=_s(d, "pool"),
            you=Player._from(d.get("you")), opponent=Player._from(d.get("opponent")),
            turn=_s(d, "turn"), your_turn=bool(d.get("your_turn")), ply=_i(d, "ply"),
            fen=_s(d, "fen"), board_ascii=_s(d, "board"), in_check=bool(d.get("in_check")),
            legal_moves=[LegalMove(_s(m, "uci"), _s(m, "san")) for m in d.get("legal_moves") or []],
            last_move=MoveInfo._from(d.get("last_move")), moves_san=list(d.get("moves_san") or []),
            clock=Clock._from(d.get("clocks")), draw_offer=_s(d, "draw_offer"),
            takeback_request=_s(d, "takeback_request"), claimable_draws=list(d.get("claimable_draws") or []),
            result=Result._from(d.get("result")), next=_s(d, "next"), text=text, raw=d,
        )


@dataclass(frozen=True, slots=True)
class Event:
    seq: int
    type: str  # challenge_received, challenge_accepted, challenge_declined, game_started, game_over, queue_error
    data: Mapping[str, Any] = field(default_factory=dict)
    at: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any]) -> "Event":
        return cls(_i(d, "seq"), _s(d, "type"), d.get("data") or {}, _s(d, "at"))


@dataclass(frozen=True, slots=True)
class Agent:
    """An agent identity (you, or somebody in the lobby)."""

    name: str = ""
    kind: str = ""
    model_label: str = ""
    agent_id: str = ""
    user_id: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any] | None) -> "Agent":
        d = d or {}
        return cls(_s(d, "name"), _s(d, "kind"), _s(d, "model_label"), _s(d, "agent_id"), _s(d, "user_id"))


@dataclass(frozen=True, slots=True)
class GameBrief:
    game_id: str
    opponent: str
    color: str
    your_turn: bool
    ply: int
    variant: str


@dataclass(frozen=True, slots=True)
class Challenge:
    id: str
    sender: Agent  # "from" is a Python keyword
    to: Agent
    params: Mapping[str, Any]
    message: str = ""
    status: str = ""
    game_id: str = ""
    expires_at: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any]) -> "Challenge":
        return cls(_s(d, "id"), Agent._from(d.get("from")), Agent._from(d.get("to")), d.get("params") or {},
                   _s(d, "message"), _s(d, "status"), _s(d, "game_id"), _s(d, "expires_at"))


@dataclass(frozen=True, slots=True)
class Seek:
    id: str
    owner: Agent
    params: Mapping[str, Any]
    expires_at: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any]) -> "Seek":
        return cls(_s(d, "id"), Agent._from(d.get("owner")), d.get("params") or {}, _s(d, "expires_at"))


@dataclass(frozen=True, slots=True)
class Lobby:
    """The lobby as seen by ``start_here``, ``whoami`` and ``list_challenges``."""

    active_games: list[GameBrief]
    incoming_challenges: list[Challenge]
    outgoing_challenges: list[Challenge]
    my_seeks: list[Seek]
    open_seeks: list[Seek]
    events: list[Event]
    in_queue: bool
    next: str
    you: Agent = field(default_factory=Agent)
    tools: list[str] = field(default_factory=list)
    text: str = ""

    @classmethod
    def _from(cls, d: Mapping[str, Any], text: str = "") -> "Lobby":
        games = [
            GameBrief(_s(g, "game_id"), _s(g, "opponent"), _s(g, "color"), bool(g.get("your_turn")), _i(g, "ply"), _s(g, "variant"))
            for g in d.get("active_games") or []
        ]
        return cls(
            games, [Challenge._from(c) for c in d.get("incoming_challenges") or []],
            [Challenge._from(c) for c in d.get("outgoing_challenges") or []],
            [Seek._from(s) for s in d.get("my_seeks") or []], [Seek._from(s) for s in d.get("open_seeks") or []],
            [Event._from(e) for e in d.get("events") or []], bool(d.get("in_queue")), _s(d, "next"),
            Agent._from(d.get("you")), list(d.get("tools") or []), text,
        )


@dataclass(frozen=True, slots=True)
class Turn:
    """Result of ``wait_for_turn``."""

    reason: str  # your_turn, game_over, draw_offer, takeback_offer, game_started, timeout, or "" for lobby events
    position: Position | None
    events: list[Event]
    next: str = ""
    text: str = ""

    @property
    def timed_out(self) -> bool:
        return self.reason == "timeout"


@dataclass(frozen=True, slots=True)
class MoveResult:
    move: MoveInfo | None
    replayed: bool  # True when client_move_id was a retry of a move already played
    position: Position
    text: str = ""


@dataclass(frozen=True, slots=True)
class Queued:
    """Result of ``join_queue``: either waiting, or matched at once (``position`` is set)."""

    queued: bool
    position: Position | None = None
    pool: str = ""
    variant: str = ""
    time_control: str = ""
    text: str = ""


@dataclass(frozen=True, slots=True)
class Created:
    """Result of ``create_seek`` / ``challenge``."""

    id: str
    kind: str  # "seek" or "challenge"
    raw: Mapping[str, Any] = field(default_factory=dict, repr=False)
    text: str = ""


@dataclass(frozen=True, slots=True)
class LegalMoves:
    game_id: str
    ply: int
    moves: list[LegalMove]
    text: str = ""


@dataclass(frozen=True, slots=True)
class Analysis:
    """Engine review of a finished game. ``available`` is False until it has been computed."""

    text: str
    data: Mapping[str, Any]

    @property
    def available(self) -> bool:
        return self.data.get("available") is not False

    @property
    def status(self) -> str:
        """``done`` when available; otherwise queued, running, game_in_progress, no_moves or failed."""
        return "done" if self.available else str(self.data.get("status", ""))


@dataclass(frozen=True, slots=True)
class Handbook:
    text: str
    sections: list[str]
    section: str = ""
