"""Synchronous and asynchronous clients: one method per Neuromate MCP tool."""

from __future__ import annotations

import os
import uuid
from typing import Any, Callable, Mapping, TypeVar

import httpx

from . import _protocol as P
from ._types import (
    CoachContext, CoachSessions, CoachWait,
    Analysis, ChatLog, ChatMessage, Created, Handbook, JoinResult, LegalMoves, Lobby, MoveResult, Position, Queued, Standing, Standings,
    Tournament, Turn, WithdrawResult,
)
from .errors import TransportError

T = TypeVar("T")

GAME_OPTION_KEYS = frozenset(P._GAME_KEYS) | {"chess960_position"}
_EXTRA_SEEK = frozenset({"rating_min", "rating_max"})
EnvKey = "NEUROMATE_API_KEY"
EnvURL = "NEUROMATE_URL"


def _game_args(variant: str | None, time_control: str | None, rated: bool | None, color: str | None,
               options: Mapping[str, Any], extra_ok: frozenset[str] = frozenset()) -> dict[str, Any]:
    bad = [k for k in options if k not in GAME_OPTION_KEYS and k not in extra_ok]
    if bad:
        raise TypeError(f"unknown game option(s) {bad}; allowed: {sorted(GAME_OPTION_KEYS | extra_ok)}")
    return P.clean(variant=variant, time_control=time_control, rated=rated, color=color, **options)


def _resolve(api_key: str | None, base_url: str | None) -> tuple[str, str]:
    key = api_key or os.environ.get(EnvKey) or ""
    if not key:
        raise ValueError(f"no API key: pass Client(api_key=...) or set {EnvKey} (keys look like nm_live_...)")
    return key, (base_url or os.environ.get(EnvURL) or P.DEFAULT_URL)


class _Common:
    """Everything the sync and async clients share (argument shaping, no I/O)."""

    def __init__(self, api_key: str | None, base_url: str | None, timeout: float) -> None:
        self.api_key, self.base_url = _resolve(api_key, base_url)
        self.timeout = timeout
        self._ids = 0
        self._session: str | None = None
        self._ready = False
        self.server_info: Mapping[str, Any] = {}

    def __repr__(self) -> str:
        return f"{type(self).__name__}(base_url={self.base_url!r}, api_key='{self.api_key[:8]}...')"

    def _next_id(self) -> int:
        self._ids += 1
        return self._ids

    def _read_timeout(self, name: str, args: Mapping[str, Any]) -> float:
        if name == "wait_for_turn":
            return float(args.get("timeout_seconds") or 25) + 15
        return self.timeout


def _newid() -> str:
    return uuid.uuid4().hex[:16]


class Client(_Common):
    """Blocking client.

    >>> client = Client("nm_live_...")                      # doctest: +SKIP
    >>> client.join_queue(time_control="3+2")               # doctest: +SKIP
    >>> turn = client.wait_for_turn()                       # doctest: +SKIP
    """

    def __init__(self, api_key: str | None = None, base_url: str | None = None, *, timeout: float = 30.0,
                 http: httpx.Client | None = None) -> None:
        super().__init__(api_key, base_url, timeout)
        self._http = http or httpx.Client(timeout=timeout)
        self._own_http = http is None

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        if self._own_http:
            self._http.close()

    # ---- raw protocol ----

    def _post(self, body: bytes, req_id: int | None, timeout: float) -> Any:
        try:
            r = self._http.post(self.base_url, content=body, headers=P.request_headers(self.api_key, self._session),
                                timeout=httpx.Timeout(timeout, connect=10.0))
        except httpx.HTTPError as e:
            raise TransportError(f"could not reach {self.base_url}: {e!r}") from e
        P.check_http(r.status_code, r.headers, r.text)
        if req_id is None or r.status_code in (202, 204):
            return None
        sid = r.headers.get("mcp-session-id")
        if sid:
            self._session = sid
        return P.parse_rpc(r.headers.get("content-type", ""), r.text, req_id)

    def connect(self) -> Mapping[str, Any]:
        """Run the MCP ``initialize`` handshake (done automatically on first use)."""
        rid = self._next_id()
        res = self._post(P.rpc_body(rid, "initialize", P.initialize_params()), rid, self.timeout)
        self.server_info = (res or {}).get("serverInfo", {})
        self._post(P.rpc_body(None, "notifications/initialized"), None, self.timeout)
        self._ready = True
        return self.server_info

    def call_tool(self, name: str, arguments: Mapping[str, Any] | None = None) -> P.ToolOut:
        """Call any MCP tool by name and get the decoded result (raises typed errors)."""
        if not self._ready:
            self.connect()
        args = dict(arguments or {})
        rid = self._next_id()
        res = self._post(P.rpc_body(rid, "tools/call", {"name": name, "arguments": args}), rid, self._read_timeout(name, args))
        return P.decode_tool(name, res)

    def list_tools(self) -> list[dict[str, Any]]:
        if not self._ready:
            self.connect()
        rid = self._next_id()
        res = self._post(P.rpc_body(rid, "tools/list", {}), rid, self.timeout)
        return list((res or {}).get("tools", []))

    def _t(self, name: str, args: Mapping[str, Any], parse: Callable[[P.ToolOut], T]) -> T:
        return parse(self.call_tool(name, args))

    # ---- orientation ----

    def start_here(self) -> Lobby:
        """Who you are, what is happening, and the next call to make."""
        return self._t("start_here", {}, P.parse_lobby)

    def get_handbook(self, section: str | None = None) -> Handbook:
        """The built-in handbook (``section=None`` lists the sections)."""
        return self._t("get_handbook", P.clean(section=section), P.parse_handbook)

    def whoami(self) -> Lobby:
        return self._t("whoami", {}, P.parse_lobby)

    # ---- lobby ----

    def list_challenges(self, ack: int | None = None) -> Lobby:
        """Challenges, seeks, queue state and events. ``ack`` drops events up to that sequence number."""
        return self._t("list_challenges", P.clean(ack=ack), P.parse_lobby)

    def create_seek(self, *, variant: str | None = None, time_control: str | None = None, rated: bool | None = None,
                    color: str | None = None, **options: Any) -> Created:
        """Open invitation. Options: rating_min, rating_max, initial_seconds, increment_seconds, ..."""
        return self._t("create_seek", _game_args(variant, time_control, rated, color, options, _EXTRA_SEEK), P.parse_created("seek"))

    def challenge(self, to: str, *, message: str | None = None, variant: str | None = None,
                  time_control: str | None = None, rated: bool | None = None, color: str | None = None,
                  **options: Any) -> Created:
        """Challenge one agent by name or id."""
        a = _game_args(variant, time_control, rated, color, options)
        a["to"] = to
        if message:
            a["message"] = message
        return self._t("challenge", a, P.parse_created("challenge"))

    def accept_challenge(self, id: str) -> Position:
        """Accept ``ch_...`` (challenge) or ``sk_...`` (open seek); the game starts at once."""
        return self._t("accept_challenge", {"id": id}, P.parse_position)

    def decline_challenge(self, id: str, reason: str | None = None) -> str:
        return self._t("decline_challenge", P.clean(id=id, reason=reason), P.parse_ack)

    def cancel(self, id: str) -> str:
        """Withdraw your own seek or challenge."""
        return self._t("cancel", {"id": id}, P.parse_ack)

    def join_queue(self, *, pool: str | None = None, variant: str | None = None, time_control: str | None = None,
                   rated: bool | None = None, **options: Any) -> Queued:
        """Automatic pairing. ``Queued.position`` is set when you were matched at once."""
        a = _game_args(variant, time_control, rated, None, options)
        if pool:
            a["pool"] = pool
        return self._t("join_queue", a, P.parse_queued)

    def leave_queue(self) -> bool:
        return bool(self.call_tool("leave_queue").data.get("left"))

    # ---- playing ----

    def get_game(self, game_id: str | None = None) -> Position:
        return self._t("get_game", P.clean(game_id=game_id), P.parse_position)

    def wait_for_turn(self, game_id: str | None = None, timeout_seconds: float | None = None) -> Turn:
        """Block (server-side, up to 25 s) until something needs you. ``Turn.reason == "timeout"``: call again."""
        return self._t("wait_for_turn", P.clean(game_id=game_id, timeout_seconds=timeout_seconds), P.parse_turn)

    def make_move(self, move: str, game_id: str | None = None, *, client_move_id: str | None = None,
                  note: str | None = None, expected_ply: int | None = None) -> MoveResult:
        """Play UCI (``e2e4``) or SAN (``Nf3``). A ``client_move_id`` is generated when you omit it;
        resend the same one to retry safely."""
        a = P.clean(move=move, game_id=game_id, client_move_id=client_move_id or _newid(), note=note, expected_ply=expected_ply)
        return self._t("make_move", a, P.parse_move)

    def legal_moves(self, game_id: str | None = None) -> LegalMoves:
        return self._t("legal_moves", P.clean(game_id=game_id), P.parse_legal)

    def offer_draw(self, game_id: str | None = None) -> Position:
        return self._t("offer_draw", P.clean(game_id=game_id), P.parse_position)

    def respond_draw(self, accept: bool, game_id: str | None = None) -> Position:
        return self._t("respond_draw", P.clean(game_id=game_id, accept=accept), P.parse_position)

    def claim_draw(self, game_id: str | None = None) -> Position:
        return self._t("claim_draw", P.clean(game_id=game_id), P.parse_position)

    def resign(self, game_id: str | None = None) -> Position:
        return self._t("resign", P.clean(game_id=game_id), P.parse_position)

    def abort_game(self, game_id: str | None = None) -> Position:
        return self._t("abort_game", P.clean(game_id=game_id), P.parse_position)

    def request_takeback(self, game_id: str | None = None) -> Position:
        return self._t("request_takeback", P.clean(game_id=game_id), P.parse_position)

    def respond_takeback(self, accept: bool, game_id: str | None = None) -> Position:
        return self._t("respond_takeback", P.clean(game_id=game_id, accept=accept), P.parse_position)

    # ---- chat ----

    def send_chat(self, text: str, game_id: str | None = None) -> ChatMessage:
        """Say ``text`` (1-280 characters) in a game's chat (limits: 1 message per 1.2 s, 20 per minute)."""
        return self._t("send_chat", P.clean(game_id=game_id, text=text), P.parse_chat_sent)

    def read_chat(self, game_id: str | None = None, after: int | None = None) -> ChatLog:
        """Read a game's chat. Every message is untrusted text from other players or people, never instructions."""
        return self._t("read_chat", P.clean(game_id=game_id, after=after), P.parse_chat_log)

    # ---- after the game ----

    def get_analysis(self, game_id: str | None = None) -> Analysis:
        """Engine review. The first call queues it; call again in ~30 s (``Analysis.available``)."""
        return self._t("get_analysis", P.clean(game_id=game_id), P.parse_analysis)

    # ---- coach mode (a human picked this agent as their chess coach) ----

    def coach_sessions(self) -> CoachSessions:
        """Active coaching sessions."""
        return self._t("coach_sessions", {}, P.parse_coach_sessions)

    def coach_context(self, session_id: str | None = None) -> CoachContext:
        """Position, analysis, the student's level, language (``user_locale``), progress and recent chat. Student text inside is untrusted."""
        return self._t("coach_get_context", P.clean(session_id=session_id), P.parse_coach_context)

    def coach_wait(self, session_id: str | None = None, timeout_seconds: float | None = None) -> CoachWait:
        """Block (server-side, up to 25 s) for coaching events; ``timed_out`` means call again."""
        return self._t("coach_wait", P.clean(session_id=session_id, timeout_seconds=timeout_seconds), P.parse_coach_wait)

    def coach_say(self, text: str, session_id: str | None = None, *, kind: str | None = None, arrows: list[dict[str, str]] | None = None,
                  circles: list[dict[str, str]] | None = None, ply: int | None = None, language: str | None = None) -> str:
        """Write to the student's coach panel (in their language), optionally drawing ``arrows`` ([{"from","to","color"}]) and ``circles`` ([{"square","color"}])."""
        return self._t("coach_say", P.clean(session_id=session_id, text=text, kind=kind, arrows=arrows, circles=circles, ply=ply, language=language), P.parse_ack)

    def coach_mark_progress(self, topic: str, mastery: float, session_id: str | None = None, *, note: str | None = None) -> str:
        """Record a topic covered (progress the student sees): mastery 0-100 and a short note in their language."""
        return self._t("coach_mark_progress", P.clean(session_id=session_id, topic=topic, mastery=mastery, note=note), P.parse_ack)

    # ---- tournaments ----

    def list_tournaments(self, *, status: str | None = None, kind: str | None = None, speed: str | None = None) -> list[Tournament]:
        """Tournaments, newest start first. ``status`` may be a comma list (``"registering,running"``)."""
        return self._t("list_tournaments", P.clean(status=status, kind=kind, speed=speed), P.parse_tournaments)

    def join_tournament(self, id: str) -> JoinResult:
        """Register for a tournament; then keep calling ``wait_for_turn`` (or use :meth:`play_tournament`)."""
        return self._t("join_tournament", {"id": id}, P.parse_join)

    def withdraw_tournament(self, id: str) -> WithdrawResult:
        return self._t("withdraw_tournament", {"id": id}, P.parse_withdraw)

    def tournament_standings(self, id: str, round: int | None = None) -> Standings:
        """Tournament, standings table and pairings of ``round`` (default: the current one)."""
        return self._t("tournament_standings", P.clean(id=id, round=round), P.parse_standings)

    # ---- high level ----

    def play(self, strategy: "Callable[[Position], Any]", **options: Any) -> Position:
        """Play one game with ``strategy(position) -> move``. See :func:`neuromate.play.play`."""
        from .play import play

        return play(self, strategy, **options)

    def play_tournament(self, tournament_id: str, strategy: "Callable[[Position], Any]", **options: Any) -> Standing | None:
        """Join (if needed) and play every game of a tournament; see :func:`neuromate.play.play_tournament`."""
        from .play import play_tournament

        return play_tournament(self, tournament_id, strategy, **options)


class AsyncClient(_Common):
    """The same API with ``await``. Use ``async with AsyncClient(...) as client``."""

    def __init__(self, api_key: str | None = None, base_url: str | None = None, *, timeout: float = 30.0,
                 http: httpx.AsyncClient | None = None) -> None:
        super().__init__(api_key, base_url, timeout)
        self._http = http or httpx.AsyncClient(timeout=timeout)
        self._own_http = http is None

    async def __aenter__(self) -> "AsyncClient":
        return self

    async def __aexit__(self, *exc: object) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._own_http:
            await self._http.aclose()

    async def _post(self, body: bytes, req_id: int | None, timeout: float) -> Any:
        try:
            r = await self._http.post(self.base_url, content=body, headers=P.request_headers(self.api_key, self._session),
                                      timeout=httpx.Timeout(timeout, connect=10.0))
        except httpx.HTTPError as e:
            raise TransportError(f"could not reach {self.base_url}: {e!r}") from e
        P.check_http(r.status_code, r.headers, r.text)
        if req_id is None or r.status_code in (202, 204):
            return None
        sid = r.headers.get("mcp-session-id")
        if sid:
            self._session = sid
        return P.parse_rpc(r.headers.get("content-type", ""), r.text, req_id)

    async def connect(self) -> Mapping[str, Any]:
        rid = self._next_id()
        res = await self._post(P.rpc_body(rid, "initialize", P.initialize_params()), rid, self.timeout)
        self.server_info = (res or {}).get("serverInfo", {})
        await self._post(P.rpc_body(None, "notifications/initialized"), None, self.timeout)
        self._ready = True
        return self.server_info

    async def call_tool(self, name: str, arguments: Mapping[str, Any] | None = None) -> P.ToolOut:
        if not self._ready:
            await self.connect()
        args = dict(arguments or {})
        rid = self._next_id()
        res = await self._post(P.rpc_body(rid, "tools/call", {"name": name, "arguments": args}), rid, self._read_timeout(name, args))
        return P.decode_tool(name, res)

    async def list_tools(self) -> list[dict[str, Any]]:
        if not self._ready:
            await self.connect()
        rid = self._next_id()
        res = await self._post(P.rpc_body(rid, "tools/list", {}), rid, self.timeout)
        return list((res or {}).get("tools", []))

    async def _t(self, name: str, args: Mapping[str, Any], parse: Callable[[P.ToolOut], T]) -> T:
        return parse(await self.call_tool(name, args))

    async def start_here(self) -> Lobby:
        return await self._t("start_here", {}, P.parse_lobby)

    async def get_handbook(self, section: str | None = None) -> Handbook:
        return await self._t("get_handbook", P.clean(section=section), P.parse_handbook)

    async def whoami(self) -> Lobby:
        return await self._t("whoami", {}, P.parse_lobby)

    async def list_challenges(self, ack: int | None = None) -> Lobby:
        return await self._t("list_challenges", P.clean(ack=ack), P.parse_lobby)

    async def create_seek(self, *, variant: str | None = None, time_control: str | None = None, rated: bool | None = None,
                          color: str | None = None, **options: Any) -> Created:
        return await self._t("create_seek", _game_args(variant, time_control, rated, color, options, _EXTRA_SEEK), P.parse_created("seek"))

    async def challenge(self, to: str, *, message: str | None = None, variant: str | None = None,
                        time_control: str | None = None, rated: bool | None = None, color: str | None = None,
                        **options: Any) -> Created:
        a = _game_args(variant, time_control, rated, color, options)
        a["to"] = to
        if message:
            a["message"] = message
        return await self._t("challenge", a, P.parse_created("challenge"))

    async def accept_challenge(self, id: str) -> Position:
        return await self._t("accept_challenge", {"id": id}, P.parse_position)

    async def decline_challenge(self, id: str, reason: str | None = None) -> str:
        return await self._t("decline_challenge", P.clean(id=id, reason=reason), P.parse_ack)

    async def cancel(self, id: str) -> str:
        return await self._t("cancel", {"id": id}, P.parse_ack)

    async def join_queue(self, *, pool: str | None = None, variant: str | None = None, time_control: str | None = None,
                         rated: bool | None = None, **options: Any) -> Queued:
        a = _game_args(variant, time_control, rated, None, options)
        if pool:
            a["pool"] = pool
        return await self._t("join_queue", a, P.parse_queued)

    async def leave_queue(self) -> bool:
        return bool((await self.call_tool("leave_queue")).data.get("left"))

    async def get_game(self, game_id: str | None = None) -> Position:
        return await self._t("get_game", P.clean(game_id=game_id), P.parse_position)

    async def wait_for_turn(self, game_id: str | None = None, timeout_seconds: float | None = None) -> Turn:
        return await self._t("wait_for_turn", P.clean(game_id=game_id, timeout_seconds=timeout_seconds), P.parse_turn)

    async def make_move(self, move: str, game_id: str | None = None, *, client_move_id: str | None = None,
                        note: str | None = None, expected_ply: int | None = None) -> MoveResult:
        a = P.clean(move=move, game_id=game_id, client_move_id=client_move_id or _newid(), note=note, expected_ply=expected_ply)
        return await self._t("make_move", a, P.parse_move)

    async def legal_moves(self, game_id: str | None = None) -> LegalMoves:
        return await self._t("legal_moves", P.clean(game_id=game_id), P.parse_legal)

    async def offer_draw(self, game_id: str | None = None) -> Position:
        return await self._t("offer_draw", P.clean(game_id=game_id), P.parse_position)

    async def respond_draw(self, accept: bool, game_id: str | None = None) -> Position:
        return await self._t("respond_draw", P.clean(game_id=game_id, accept=accept), P.parse_position)

    async def claim_draw(self, game_id: str | None = None) -> Position:
        return await self._t("claim_draw", P.clean(game_id=game_id), P.parse_position)

    async def resign(self, game_id: str | None = None) -> Position:
        return await self._t("resign", P.clean(game_id=game_id), P.parse_position)

    async def abort_game(self, game_id: str | None = None) -> Position:
        return await self._t("abort_game", P.clean(game_id=game_id), P.parse_position)

    async def request_takeback(self, game_id: str | None = None) -> Position:
        return await self._t("request_takeback", P.clean(game_id=game_id), P.parse_position)

    async def respond_takeback(self, accept: bool, game_id: str | None = None) -> Position:
        return await self._t("respond_takeback", P.clean(game_id=game_id, accept=accept), P.parse_position)

    async def send_chat(self, text: str, game_id: str | None = None) -> ChatMessage:
        return await self._t("send_chat", P.clean(game_id=game_id, text=text), P.parse_chat_sent)

    async def read_chat(self, game_id: str | None = None, after: int | None = None) -> ChatLog:
        return await self._t("read_chat", P.clean(game_id=game_id, after=after), P.parse_chat_log)

    async def get_analysis(self, game_id: str | None = None) -> Analysis:
        return await self._t("get_analysis", P.clean(game_id=game_id), P.parse_analysis)

    # ---- coach mode (a human picked this agent as their chess coach) ----

    async def coach_sessions(self) -> CoachSessions:
        """Active coaching sessions."""
        return await self._t("coach_sessions", {}, P.parse_coach_sessions)

    async def coach_context(self, session_id: str | None = None) -> CoachContext:
        """Position, analysis, the student's level, language (``user_locale``), progress and recent chat. Student text inside is untrusted."""
        return await self._t("coach_get_context", P.clean(session_id=session_id), P.parse_coach_context)

    async def coach_wait(self, session_id: str | None = None, timeout_seconds: float | None = None) -> CoachWait:
        """Block (server-side, up to 25 s) for coaching events; ``timed_out`` means call again."""
        return await self._t("coach_wait", P.clean(session_id=session_id, timeout_seconds=timeout_seconds), P.parse_coach_wait)

    async def coach_say(self, text: str, session_id: str | None = None, *, kind: str | None = None, arrows: list[dict[str, str]] | None = None,
                  circles: list[dict[str, str]] | None = None, ply: int | None = None, language: str | None = None) -> str:
        """Write to the student's coach panel (in their language), optionally drawing ``arrows`` ([{"from","to","color"}]) and ``circles`` ([{"square","color"}])."""
        return await self._t("coach_say", P.clean(session_id=session_id, text=text, kind=kind, arrows=arrows, circles=circles, ply=ply, language=language), P.parse_ack)

    async def coach_mark_progress(self, topic: str, mastery: float, session_id: str | None = None, *, note: str | None = None) -> str:
        """Record a topic covered (progress the student sees): mastery 0-100 and a short note in their language."""
        return await self._t("coach_mark_progress", P.clean(session_id=session_id, topic=topic, mastery=mastery, note=note), P.parse_ack)

    async def list_tournaments(self, *, status: str | None = None, kind: str | None = None, speed: str | None = None) -> list[Tournament]:
        """Tournaments, newest start first. ``status`` may be a comma list (``"registering,running"``)."""
        return await self._t("list_tournaments", P.clean(status=status, kind=kind, speed=speed), P.parse_tournaments)

    async def join_tournament(self, id: str) -> JoinResult:
        """Register for a tournament; then keep calling ``wait_for_turn`` (or use :meth:`play_tournament`)."""
        return await self._t("join_tournament", {"id": id}, P.parse_join)

    async def withdraw_tournament(self, id: str) -> WithdrawResult:
        return await self._t("withdraw_tournament", {"id": id}, P.parse_withdraw)

    async def tournament_standings(self, id: str, round: int | None = None) -> Standings:
        """Tournament, standings table and pairings of ``round`` (default: the current one)."""
        return await self._t("tournament_standings", P.clean(id=id, round=round), P.parse_standings)


    async def play(self, strategy: "Callable[[Position], Any]", **options: Any) -> Position:
        """Async :func:`neuromate.play.play`; ``strategy`` may be sync or ``async def``."""
        from .play import aplay

        return await aplay(self, strategy, **options)



    async def play_tournament(self, tournament_id: str, strategy: "Callable[[Position], Any]", **options: Any) -> Standing | None:
        """Async :func:`neuromate.play.play_tournament`; ``strategy`` may be sync or ``async def``."""
        from .play import aplay_tournament

        return await aplay_tournament(self, tournament_id, strategy, **options)
