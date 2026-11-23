from __future__ import annotations

import time

import httpx
import pytest

from neuromate import Move, NeuromateError, Position, Resign, TransportError

from conftest import FakeServer, tool_result, wait_result

# black's seat, game g, white to have moved e4:  wait_your_turn -> make_move(e5) -> move_black
YOUR_TURN = wait_result("your_turn", "get_game_start")  # white to move at ply 0
GAME_OVER = wait_result("game_over", "resign", game_id=YOUR_TURN["structuredContent"]["game"]["game_id"])
GID = YOUR_TURN["structuredContent"]["game"]["game_id"]
SCRIPT_BASE = {
    "whoami": [tool_result("whoami_idle")],
    "join_queue": [tool_result("join_queue_waiting")],
}


def base(**more: list) -> dict:  # type: ignore[type-arg]
    return {**SCRIPT_BASE, **more}


def test_queue_then_play_until_game_over() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=[tool_result("move_ok")]))
    seen: list[Position] = []

    def strat(p: Position) -> Move:
        seen.append(p)
        return Move("e4", "centre")

    final = f.client().play(strat, time_control="3+0")
    assert final.is_over and len(seen) == 1
    assert f.names() == ["whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"]
    mv = f.calls[3][1]
    assert mv["move"] == "e4" and mv["note"] == "centre" and mv["expected_ply"] == 0
    assert mv["client_move_id"].startswith(seen[0].game_id) and f.calls[1][1] == {"time_control": "3+0"}


def test_existing_active_game_is_resumed_without_queueing() -> None:
    busy = tool_result("whoami_idle")
    busy = {**busy, "structuredContent": {**busy["structuredContent"], "active_games": [
        {"game_id": GID, "opponent": "Bob", "color": "white", "your_turn": True, "ply": 0, "variant": "standard"}]}}
    f = FakeServer({"whoami": [busy], "wait_for_turn": [YOUR_TURN, GAME_OVER], "make_move": [tool_result("move_ok")]})
    f.client().play(lambda p: "e4")
    assert "join_queue" not in f.names() and f.calls[2][1]["game_id"] == GID


def test_illegal_move_is_fed_back_and_retried_with_a_new_id() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=[tool_result("err_illegal_move"), tool_result("move_ok")]))
    tries: list[tuple[str, ...]] = []

    def strat(p: Position) -> str:
        tries.append(tuple(a.error for a in p.illegal_attempts))
        return "e2e5" if not p.illegal_attempts else "e2e4"

    f.client().play(strat)
    assert tries[0] == () and "pawn on e2 cannot move to e5" in tries[1][0]
    ids = [a["client_move_id"] for n, a in f.calls if n == "make_move"]
    assert len(set(ids)) == 2


def test_illegal_budget_falls_back_to_a_legal_move() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=[tool_result("err_illegal_move")] * 3 + [tool_result("move_ok")]))
    f.client().play(lambda p: "zzzz", max_illegal=3)
    moves = [a["move"] for n, a in f.calls if n == "make_move"]
    assert moves[:3] == ["zzzz"] * 3 and moves[3] in YOUR_TURN["structuredContent"]["game"]["legal_moves"].__repr__()


def test_transient_errors_retry_with_the_same_client_move_id() -> None:
    flaky = [httpx.ConnectError("boom"), (503, "unavailable"), tool_result("move_ok")]
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=flaky))
    t0 = time.monotonic()
    f.client().play(lambda p: "e4")
    ids = {a["client_move_id"] for n, a in f.calls if n == "make_move"}
    assert len([n for n in f.names() if n == "make_move"]) == 3 and len(ids) == 1
    assert time.monotonic() - t0 < 5


def test_transient_errors_give_up_after_max_retries() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN], make_move=[httpx.ConnectError("down")]))
    with pytest.raises(TransportError):
        f.client().play(lambda p: "e4", max_retries=1)


def test_stale_ply_rereads_the_game() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=[tool_result("err_stale_ply"), tool_result("move_ok")],
                        get_game=[tool_result("get_game_start")]))
    n = []
    f.client().play(lambda p: n.append(1) or "e4")
    assert "get_game" in f.names() and len(n) == 2


def test_slow_strategy_hits_the_clock_and_a_fallback_move_is_sent() -> None:
    g = {"clocks": {"you_ms": 2500, "opponent_ms": 9000, "running": "you", "your_turn_time_left_ms": 1500}}
    f = FakeServer(base(wait_for_turn=[wait_result("your_turn", "get_game_start", **g), GAME_OVER], make_move=[tool_result("move_ok")]))
    t0 = time.monotonic()
    f.client().play(lambda p: time.sleep(5) or "e4", safety_margin=1.0, fallback=lambda p: "d2d4")
    assert time.monotonic() - t0 < 3
    assert [a["move"] for n, a in f.calls if n == "make_move"] == ["d2d4"]


def test_no_time_left_skips_the_strategy() -> None:
    g = {"clocks": {"you_ms": 400, "running": "you", "your_turn_time_left_ms": 400}}
    f = FakeServer(base(wait_for_turn=[wait_result("your_turn", "get_game_start", **g), GAME_OVER], make_move=[tool_result("move_ok")]))
    called = []
    f.client().play(lambda p: called.append(1) or "e4", fallback=lambda p: "c2c4")
    assert not called and [a["move"] for n, a in f.calls if n == "make_move"] == ["c2c4"]


def test_resign() -> None:
    def strat(p: Position) -> str:
        raise Resign()

    f = FakeServer(base(wait_for_turn=[YOUR_TURN], resign=[tool_result("resign")]))
    final = f.client().play(strat)
    assert final.is_over and "resign" in f.names()


def test_draw_offer_is_declined_by_default_and_can_be_accepted() -> None:
    offer = tool_result("wait_draw_offer")
    f = FakeServer(base(wait_for_turn=[offer, GAME_OVER], respond_draw=[tool_result("respond_draw_decline")]))
    f.client().play(lambda p: "e4")
    assert f.calls[3] == ("respond_draw", {"accept": False, "game_id": f.calls[3][1]["game_id"]})
    f2 = FakeServer(base(wait_for_turn=[offer, GAME_OVER], respond_draw=[tool_result("respond_draw_decline")]))
    f2.client().play(lambda p: "e4", on_draw_offer=lambda p: True)
    assert f2.calls[3][1]["accept"] is True


def test_bad_strategy_return_is_explained() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN]))
    with pytest.raises(NeuromateError, match="your strategy returned"):
        f.client().play(lambda p: None)


def test_ctrl_c_while_queued_leaves_the_queue() -> None:
    def interrupted(*a, **k):  # type: ignore[no-untyped-def]
        raise KeyboardInterrupt

    f = FakeServer(base(wait_for_turn=[tool_result("wait_timeout")], leave_queue=[tool_result("leave_queue")]))
    cl = f.client()
    orig = cl.wait_for_turn
    cl.wait_for_turn = interrupted  # type: ignore[method-assign]
    with pytest.raises(KeyboardInterrupt):
        cl.play(lambda p: "e4")
    assert "leave_queue" in f.names()
    cl.wait_for_turn = orig  # type: ignore[method-assign]


async def test_async_client_and_aplay_with_async_strategy() -> None:
    f = FakeServer(base(wait_for_turn=[YOUR_TURN, GAME_OVER], make_move=[tool_result("move_ok")]))
    cl = f.aclient()

    async def strat(p: Position) -> str:
        return "e4"

    final = await cl.play(strat)
    assert final.is_over and f.names().count("make_move") == 1
    await cl.aclose()


async def test_aplay_timeout_uses_fallback() -> None:
    import asyncio

    g = {"clocks": {"you_ms": 2500, "running": "you", "your_turn_time_left_ms": 1500}}
    f = FakeServer(base(wait_for_turn=[wait_result("your_turn", "get_game_start", **g), GAME_OVER], make_move=[tool_result("move_ok")]))

    async def slow(p: Position) -> str:
        await asyncio.sleep(5)
        return "e4"

    t0 = time.monotonic()
    await f.aclient().play(slow, fallback=lambda p: "d2d4")
    assert time.monotonic() - t0 < 3 and [a["move"] for n, a in f.calls if n == "make_move"] == ["d2d4"]
