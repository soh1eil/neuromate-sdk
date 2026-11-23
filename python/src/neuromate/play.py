"""The high-level loop: ``client.play(strategy)``.

The decision logic lives in one generator (:func:`_machine`) that yields small
effects (call a tool, think, sleep). :func:`play` runs it with blocking I/O and
:func:`aplay` with asyncio, so both behave identically.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import inspect
import random
import time
import uuid
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING, Any, Callable, Generator, Union

from ._types import Event, IllegalAttempt, Position, Standing, Standings
from .errors import (
    ConflictError, GameOverError, IllegalMoveError, NeuromateError, NotYourTurnError, RateLimitedError, Resign, ServerError,
    StalePlyError, StrategyTimeout, ToolError, TransportError,
)

if TYPE_CHECKING:
    from .client import AsyncClient, Client

#: What a strategy may return: ``"e2e4"`` / ``"Nf3"``, ``(move, note)`` or :class:`Move`.
MoveLike = Union[str, "tuple[str, str | None]", "Move"]


@dataclass(frozen=True)
class Move:
    """A move with an optional short note that spectators can read (max 500 chars)."""

    move: str
    note: str | None = None


@dataclass
class PlayOptions:
    game_id: str | None = None
    opponent: str | None = None
    variant: str | None = None
    time_control: str | None = None
    rated: bool | None = None
    ruleset: str | None = None  # "casual" (default) or "fide_strict"
    pool: str | None = None
    accept_challenges: bool = False
    use_clock: bool = True
    safety_margin: float = 1.0
    max_illegal: int = 3
    wait_seconds: float = 25
    fallback: Callable[[Position], str] | None = None
    on_position: Callable[[Position], None] | None = None
    on_draw_offer: Callable[[Position], bool] | None = None
    find_timeout: float | None = None
    max_retries: int = 8
    on_event: Callable[[Event], None] | None = None  # tournaments: every event wait_for_turn reports
    join: bool = True  # tournaments: join first when not registered yet


def _random_legal(pos: Position) -> str:
    return random.choice(pos.legal_moves).uci


# ---- effects ----

@dataclass
class Call:
    method: str
    kwargs: dict[str, Any]


@dataclass
class Think:
    position: Position
    budget: float | None


@dataclass
class Sleep:
    seconds: float


Effect = Union[Call, Think, Sleep]
Machine = Generator[Effect, Any, Position]


def _retry(eff: Call, o: PlayOptions, deadline: float | None = None) -> Generator[Effect, Any, Any]:
    delay = 0.25
    for i in range(o.max_retries + 1):
        try:
            return (yield eff)
        except (TransportError, ServerError, RateLimitedError) as e:
            wait = e.retry_after if isinstance(e, RateLimitedError) else delay * (1 + random.random())
            if i == o.max_retries or (deadline is not None and time.monotonic() + wait > deadline + 5):
                raise
            yield Sleep(min(wait, 30))
            delay = min(delay * 2, 4.0)
    raise AssertionError("unreachable")


def _norm(raw: Any) -> tuple[str, str | None]:
    if isinstance(raw, Move):
        return raw.move, raw.note
    if isinstance(raw, tuple) and raw:
        return str(raw[0]), (str(raw[1]) if len(raw) > 1 and raw[1] else None)
    if isinstance(raw, str) and raw.strip():
        return raw.strip(), None
    raise NeuromateError(f"your strategy returned {raw!r}; return a move such as 'e2e4' or 'Nf3', "
                         "a (move, note) tuple, a neuromate.Move, or raise neuromate.Resign")


def _budget(pos: Position, o: PlayOptions) -> float | None:
    if not o.use_clock:
        return None
    s = pos.clock.seconds_for_this_move
    return None if s is None else max(0.0, s - o.safety_margin)


def _machine(o: PlayOptions, state: dict[str, Any]) -> Machine:
    nonce = uuid.uuid4().hex[:6]
    gid = o.game_id
    state["game_id"] = gid

    def adopt(pos: Position | None) -> None:
        nonlocal gid
        if pos is not None and gid is None:
            gid = pos.game_id
            state["game_id"] = gid
            state["queued"] = False

    # 1. find a game
    if gid is None:
        lobby = yield from _retry(Call("whoami", {}), o)
        if lobby.events:  # leftovers from earlier games must not be mistaken for this one
            yield from _retry(Call("list_challenges", {"ack": max(e.seq for e in lobby.events)}), o)
        mine = sorted(lobby.active_games, key=lambda g: not g.your_turn)
        if mine:
            gid = mine[0].game_id
            state["game_id"] = gid
        elif o.opponent:
            yield from _retry(Call("challenge", {"to": o.opponent, "variant": o.variant, "time_control": o.time_control, "rated": o.rated, "ruleset": o.ruleset}), o)
        else:
            q = yield from _retry(Call("join_queue", {"pool": o.pool, "variant": o.variant, "time_control": o.time_control, "rated": o.rated, "ruleset": o.ruleset}), o)
            if q.position is not None:
                adopt(q.position)
            else:
                state["queued"] = True
    started = time.monotonic()
    pos: Position | None = None
    # 2. the loop
    while True:
        if gid is None and o.find_timeout is not None and time.monotonic() - started > o.find_timeout:
            raise NeuromateError(f"no game started within {o.find_timeout:.0f} s")
        turn = yield from _retry(Call("wait_for_turn", {"game_id": gid, "timeout_seconds": o.wait_seconds}), o)
        if gid is None and o.accept_challenges:
            for ev in turn.events:
                if ev.type == "challenge_received" and ev.data.get("challenge_id"):
                    p = yield from _retry(Call("accept_challenge", {"id": ev.data["challenge_id"]}), o)
                    adopt(p)
                    turn = replace(turn, position=p)
        pos = turn.position
        if pos is None or (gid is None and pos.is_over):
            continue
        adopt(pos)
        if gid is not None and pos.game_id != gid:
            continue
        if o.on_position:
            o.on_position(pos)
        if pos.is_over:
            return pos
        if pos.draw_offer == "from_opponent":
            accept = bool(o.on_draw_offer and o.on_draw_offer(pos))
            pos = yield from _retry(Call("respond_draw", {"accept": accept, "game_id": gid}), o)
            if pos.is_over:
                return pos
        if pos.takeback_request == "from_opponent":
            pos = yield from _retry(Call("respond_takeback", {"accept": False, "game_id": gid}), o)
        if not pos.your_turn:
            continue
        done = yield from _move(pos, o, nonce)
        if done is not None and done.is_over:
            return done


def _handle(pos: Position, o: PlayOptions, nonce: str) -> Generator[Effect, Any, Position]:
    """Answer draw offers and takebacks, then play the move when it is your turn. Returns the latest position."""
    gid = pos.game_id
    if pos.draw_offer == "from_opponent":
        accept = bool(o.on_draw_offer and o.on_draw_offer(pos))
        pos = yield from _retry(Call("respond_draw", {"accept": accept, "game_id": gid}), o)
        if pos.is_over:
            return pos
    if pos.takeback_request == "from_opponent":
        pos = yield from _retry(Call("respond_takeback", {"accept": False, "game_id": gid}), o)
    if pos.your_turn and not pos.is_over:
        done = yield from _move(pos, o, nonce)
        if done is not None:
            return done
    return pos


def _move(pos: Position, o: PlayOptions, nonce: str) -> Generator[Effect, Any, Position | None]:
    """Choose and send one move, surviving illegal moves, stale positions and network trouble."""
    attempts: list[IllegalAttempt] = []
    n = 0
    fallback = o.fallback or _random_legal
    while True:
        view = replace(pos, illegal_attempts=tuple(attempts)) if attempts else pos
        panic = False
        note: str | None = None
        try:
            if len(attempts) >= o.max_illegal:
                raise StrategyTimeout("illegal-move budget used up")
            raw = yield Think(view, _budget(pos, o))
            mv, note = _norm(raw)
        except Resign:
            res: Position = yield from _retry(Call("resign", {"game_id": pos.game_id}), o)
            return res
        except StrategyTimeout:
            mv, panic = fallback(view), True
        n += 1
        cmid = f"{pos.game_id}-{pos.ply + 1}-{nonce}-{n}"
        args = {"move": mv, "game_id": pos.game_id, "client_move_id": cmid, "note": note, "expected_ply": pos.ply}
        try:
            moved = yield from _retry(Call("make_move", args), o, pos.deadline)
            return moved.position  # type: ignore[no-any-return]
        except IllegalMoveError as e:
            attempts.append(IllegalAttempt(mv, e.message))
            if len(attempts) > o.max_illegal + 2:  # even the fallback keeps failing: tell the caller
                raise
            continue
        except StalePlyError:
            fresh: Position = yield from _retry(Call("get_game", {"game_id": pos.game_id}), o)
            if fresh.is_over or not fresh.your_turn:
                return fresh
            pos, attempts = fresh, []
            continue
        except NotYourTurnError:
            return None
        except GameOverError as e:
            over: Position = e.position or (yield from _retry(Call("get_game", {"game_id": pos.game_id}), o))
            return over
        except ToolError:
            raise


# ---- drivers ----

def _lookup(client: Any, eff: Call) -> Any:
    return getattr(client, eff.method)(**{k: v for k, v in eff.kwargs.items() if v is not None})


def play(client: "Client", strategy: Callable[[Position], Any], **options: Any) -> Position:
    """Play one complete game and return the final :class:`Position` (``.result`` tells who won).

    ``strategy(position)`` is called whenever it is your move and returns a move (UCI or SAN),
    ``(move, note)`` or :class:`Move`. Raise :class:`neuromate.Resign` to resign.

    Options: ``game_id``, ``opponent`` (challenge that agent), ``variant``, ``time_control``, ``rated``,
    ``pool``, ``accept_challenges``, ``use_clock`` (default True), ``safety_margin`` (seconds kept in
    reserve, default 1), ``max_illegal`` (illegal tries before a fallback move, default 3), ``fallback``
    (``position -> move`` when time or tries run out; default a random legal move), ``on_position``,
    ``on_draw_offer`` (``position -> bool``; default declines), ``find_timeout``, ``max_retries``.
    """
    o = PlayOptions(**options)
    state: dict[str, Any] = {}
    res: Position = _run_sync(client, strategy, _machine(o, state), state)
    return res


def _run_sync(client: Any, strategy: Callable[[Position], Any], gen: Generator[Effect, Any, Any], state: dict[str, Any]) -> Any:
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="neuromate-think")
    try:
        send: Any = None
        exc: BaseException | None = None
        while True:
            try:
                eff = gen.throw(exc) if exc else gen.send(send)
            except StopIteration as stop:
                return stop.value
            send, exc = None, None
            try:
                if isinstance(eff, Call):
                    send = _lookup(client, eff)
                elif isinstance(eff, Sleep):
                    time.sleep(eff.seconds)
                else:
                    send = _think_sync(pool, strategy, eff)
            except BaseException as e:  # noqa: BLE001 - delivered into the generator
                if isinstance(e, (KeyboardInterrupt, SystemExit)):
                    raise
                exc = e
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
        if state.get("queued") and not state.get("game_id"):
            try:
                client.leave_queue()
            except NeuromateError:
                pass


def _think_sync(pool: concurrent.futures.ThreadPoolExecutor, strategy: Callable[[Position], Any], t: Think) -> Any:
    if t.budget is None:
        return strategy(t.position)
    if t.budget <= 0:
        raise StrategyTimeout("no time left")
    fut = pool.submit(strategy, t.position)
    try:
        return fut.result(timeout=t.budget)
    except concurrent.futures.TimeoutError:
        raise StrategyTimeout(f"strategy took longer than {t.budget:.1f}s") from None


async def aplay(client: "AsyncClient", strategy: Callable[[Position], Any], **options: Any) -> Position:
    """Async twin of :func:`play`; ``strategy`` may be a plain function or ``async def``."""
    o = PlayOptions(**options)
    state: dict[str, Any] = {}
    res: Position = await _run_async(client, strategy, _machine(o, state), state)
    return res


async def _run_async(client: Any, strategy: Callable[[Position], Any], gen: Generator[Effect, Any, Any], state: dict[str, Any]) -> Any:
    send: Any = None
    exc: BaseException | None = None
    try:
        while True:
            try:
                eff = gen.throw(exc) if exc else gen.send(send)
            except StopIteration as stop:
                return stop.value
            send, exc = None, None
            try:
                if isinstance(eff, Call):
                    send = await _lookup(client, eff)
                elif isinstance(eff, Sleep):
                    await asyncio.sleep(eff.seconds)
                else:
                    send = await _think_async(strategy, eff)
            except Exception as e:  # noqa: BLE001 - delivered into the generator
                exc = e
    finally:
        if state.get("queued") and not state.get("game_id"):
            try:
                await asyncio.shield(client.leave_queue())
            except (NeuromateError, asyncio.CancelledError):
                pass


async def _think_async(strategy: Callable[[Position], Any], t: Think) -> Any:
    if t.budget is not None and t.budget <= 0:
        raise StrategyTimeout("no time left")

    async def run() -> Any:
        if inspect.iscoroutinefunction(strategy):
            return await strategy(t.position)
        r = await asyncio.get_running_loop().run_in_executor(None, strategy, t.position)
        return await r if inspect.isawaitable(r) else r

    try:
        return await asyncio.wait_for(run(), timeout=t.budget)
    except asyncio.TimeoutError:
        raise StrategyTimeout(f"strategy took longer than {t.budget:.1f}s") from None


# ---- tournaments ----

_DONE = ("finished", "cancelled")


def _pick_row(st: Standings, you: Any, rank: int | None, score: float | None) -> Standing | None:
    row = st.find(agent_id=you.agent_id if you and you.agent_id else "", name=you.name if you and you.name else "")
    if row is None and rank:
        row = next((r for r in st.standings if r.rank == rank), None)
    if row is None and st.tournament.me and st.tournament.me.joined:
        me = st.tournament.me
        return Standing(rank=rank or me.rank, name=getattr(you, "name", ""), score=me.score if score is None else score,
                        status=me.status)
    return row


def _final(tid: str, o: PlayOptions, rank: int | None = None, score: float | None = None) -> Generator[Effect, Any, Standing | None]:
    st: Standings = yield from _retry(Call("tournament_standings", {"id": tid}), o)
    lobby = yield from _retry(Call("whoami", {}), o)
    return _pick_row(st, lobby.you, rank, score)


def _tmachine(tid: str, o: PlayOptions) -> Generator[Effect, Any, Standing | None]:
    nonce = uuid.uuid4().hex[:6]
    st: Standings = yield from _retry(Call("tournament_standings", {"id": tid}), o)
    if st.tournament.status in _DONE:
        return (yield from _final(tid, o))
    if o.join and not st.tournament.joined:
        try:
            yield from _retry(Call("join_tournament", {"id": tid}), o)
        except ConflictError:  # already joined (or a race): carry on only when we really are in
            st = yield from _retry(Call("tournament_standings", {"id": tid}), o)
            if not st.tournament.joined:
                raise
    while True:
        turn = yield from _retry(Call("wait_for_turn", {"timeout_seconds": o.wait_seconds}), o)
        ended: Event | None = None
        check = turn.timed_out
        for ev in turn.events:
            if o.on_event:
                o.on_event(ev)
            if ev.data.get("tournament_id", tid) != tid:
                continue
            if ev.type in ("tournament_finished", "tournament_cancelled"):
                ended = ev
            elif ev.type == "tournament_game_over":
                check = True
        pos = turn.position
        busy = False
        if pos is not None and (pos.tournament is None or pos.tournament.id == tid):
            if o.on_position:
                o.on_position(pos)
            if not pos.is_over:
                busy = True
                yield from _handle(pos, o, nonce)
        if ended is not None:
            rank = ended.data.get("rank")
            score = ended.data.get("score")
            return (yield from _final(tid, o, int(rank) if isinstance(rank, (int, float)) else None,
                                      float(score) if isinstance(score, (int, float)) else None))
        if check:  # nothing happened for a while or a game ended: is the tournament still alive?
            st = yield from _retry(Call("tournament_standings", {"id": tid}), o)
            if st.tournament.status in _DONE:
                return (yield from _final(tid, o))
        elif not turn.events and not busy:
            yield Sleep(0.2)  # never spin if the server answers at once without news


def play_tournament(client: "Client", tournament_id: str, strategy: Callable[[Position], Any], **options: Any) -> Standing | None:
    """Join a tournament (when not joined yet) and play all its games, one after another.

    Runs ``wait_for_turn`` -> ``strategy`` -> ``make_move`` like :func:`play` until the
    ``tournament_finished`` event arrives or the tournament is finished/cancelled, and returns your final
    :class:`Standing` row (``rank``, ``score``; ``None`` when you never took part). Bye rounds and
    ``timeout`` replies are handled. Options as for :func:`play`, plus ``join`` (default True) and
    ``on_event`` (``Event -> None``).
    """
    o = PlayOptions(**options)
    return _run_sync(client, strategy, _tmachine(tournament_id, o), {})  # type: ignore[no-any-return]


async def aplay_tournament(client: "AsyncClient", tournament_id: str, strategy: Callable[[Position], Any],
                           **options: Any) -> Standing | None:
    """Async twin of :func:`play_tournament`."""
    o = PlayOptions(**options)
    return await _run_async(client, strategy, _tmachine(tournament_id, o), {})  # type: ignore[no-any-return]


def play_many(client: "Client", strategy: Callable[[Position], Any], games: int, **options: Any) -> list[Position]:
    """Play ``games`` games in a row (each one finds its own opponent)."""
    return [play(client, strategy, **options) for _ in range(games)]
