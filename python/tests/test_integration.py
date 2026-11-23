"""Integration: the real Go MCP handler (mcp-devserver), driven by this SDK."""

from __future__ import annotations

import asyncio
import threading

import pytest

from neuromate import AsyncClient, Client, GameOverError, IllegalMoveError, Position

pytestmark = pytest.mark.integration

# Scholar's mate, keyed by ply. White plays SAN, black plays UCI: both notations are exercised.
SCRIPT = {0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#"}


def scripted(p: Position) -> str:
    return SCRIPT[p.ply]


def test_two_sync_clients_play_scholars_mate(devserver) -> None:  # type: ignore[no-untyped-def]
    url, keys = devserver
    out: dict[str, Position] = {}

    def run(name: str, key: str) -> None:
        with Client(key, url) as c:
            out[name] = c.play(scripted, time_control="3+2")

    threads = [threading.Thread(target=run, args=("a", keys[0])), threading.Thread(target=run, args=("b", keys[1]))]
    [t.start() for t in threads]
    [t.join(60) for t in threads]
    for p in out.values():
        assert p.is_over and p.result and p.result.termination == "checkmate" and p.result.winner == "white"
        assert p.moves_san == ["e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"]


async def test_async_play_against_sync_play(devserver) -> None:  # type: ignore[no-untyped-def]
    url, keys = devserver
    sync_result: dict[str, Position] = {}

    def run_sync() -> None:
        with Client(keys[1], url) as c:
            sync_result["p"] = c.play(scripted, time_control="untimed")

    t = threading.Thread(target=run_sync)
    t.start()

    async def astrat(p: Position) -> str:
        await asyncio.sleep(0)
        return SCRIPT[p.ply]

    async with AsyncClient(keys[0], url) as c:
        final = await c.play(astrat, time_control="untimed")
    t.join(60)
    assert final.result and final.result.termination == "checkmate" and sync_result["p"].is_over


def test_errors_and_manual_calls_against_the_real_server(devserver) -> None:  # type: ignore[no-untyped-def]
    url, keys = devserver
    a, b = Client(keys[0], url), Client(keys[2], url)  # alice and carol
    assert a.start_here().you.name == "Alice" and "make_move" in a.start_here().tools
    assert a.list_tools()[0]["name"] == "start_here"
    sk = a.create_seek(time_control="5+0", color="white")
    pos = b.accept_challenge(sk.id)
    assert pos.opponent.name == "Alice"
    gid = pos.game_id
    t = a.wait_for_turn(gid, timeout_seconds=2)
    assert t.reason == "your_turn" and t.position and len(t.position.legal_moves) == 20
    with pytest.raises(IllegalMoveError) as e:
        a.make_move("e2e5", gid)
    assert "legal pawn moves from e2" in str(e.value) and "e2e4" in e.value.legal_moves
    first = a.make_move("e4", gid, client_move_id="same")
    again = a.make_move("e4", gid, client_move_id="same")
    assert not first.replayed and again.replayed
    quiet = b.wait_for_turn(gid, timeout_seconds=1)
    assert quiet.reason == "your_turn"
    wait = a.wait_for_turn(gid, timeout_seconds=1)
    assert wait.timed_out
    assert b.make_move("e5", gid).move
    a.offer_draw(gid)
    assert b.wait_for_turn(gid, 2).reason == "draw_offer"
    b.respond_draw(False, gid)
    res = b.resign(gid)
    assert res.is_over and res.result and res.result.termination == "resignation" and res.result.winner == "white"
    with pytest.raises(GameOverError):
        a.make_move("Nf3", gid)
    assert a.get_analysis(gid).text
    assert "quickstart" in a.get_handbook().sections
    assert "Quickstart" in a.get_handbook("quickstart").text or a.get_handbook("quickstart").text


def test_two_games_in_a_row_ignore_leftover_events(devserver) -> None:  # type: ignore[no-untyped-def]
    from neuromate import play_many

    url, keys = devserver
    out: dict[str, list[Position]] = {}

    def run(name: str, key: str) -> None:
        with Client(key, url) as c:
            out[name] = play_many(c, scripted, 2, time_control="untimed")

    threads = [threading.Thread(target=run, args=("a", keys[0])), threading.Thread(target=run, args=("b", keys[1]))]
    [t.start() for t in threads]
    [t.join(60) for t in threads]
    ids = {p.game_id for ps in out.values() for p in ps}
    assert len(ids) == 2 and all(p.result and p.result.termination == "checkmate" for ps in out.values() for p in ps)
