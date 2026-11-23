from __future__ import annotations

import copy
from typing import Any

import pytest

from neuromate import ConflictError, NotAllowedError, Position

from conftest import FakeServer, fixture, tool_result, wait_result

TID = "t_k3m9x2qa"
TREF = {"id": TID, "name": "Friday Blitz Swiss", "round": 1}


def standings(status: str = "running", joined: bool = True) -> dict[str, Any]:
    r = copy.deepcopy(tool_result("tournament_standings"))
    t = r["structuredContent"]["tournament"]
    t["status"] = status
    if not joined:
        t.pop("me")
    return r


def game_wait(reason: str, gid: str, **patch: Any) -> dict[str, Any]:
    return wait_result(reason, "get_game_start", game_id=gid, tournament=TREF, **patch)


def over(gid: str) -> dict[str, Any]:
    r = wait_result("game_over", "resign", game_id=gid, tournament=TREF)
    r["structuredContent"]["events"] = [{"seq": 5, "type": "tournament_game_over", "data": {"tournament_id": TID}, "at": ""}]
    return r


def timeout() -> dict[str, Any]:
    return tool_result("wait_timeout")


BASE = {"whoami": [tool_result("whoami_idle")]}


def test_list_join_withdraw_standings_parse() -> None:
    f = FakeServer({"list_tournaments": [tool_result("tournaments_list")], "join_tournament": [tool_result("tournament_join")],
                    "withdraw_tournament": [tool_result("tournament_withdraw")], "tournament_standings": [tool_result("tournament_standings")]})
    c = f.client()
    ts = c.list_tournaments(status="registering,running", kind="swiss")
    assert [t.id for t in ts] == [TID, "t_arena0001"] and ts[0].me and ts[0].me.rank == 3 and ts[1].kind == "arena"
    assert ts[0].time_control["initial_ms"] == 180000 and ts[0].joined and not ts[0].is_over
    assert f.calls[-1] == ("list_tournaments", {"status": "registering,running", "kind": "swiss"})
    j = c.join_tournament(TID)
    assert j.joined and j.tournament.status == "registering" and "wait_for_turn" in j.next
    w = c.withdraw_tournament(TID)
    assert w.withdrawn and not w.tournament.joined
    s = c.tournament_standings(TID, round=2)
    assert f.calls[-1] == ("tournament_standings", {"id": TID, "round": 2})
    assert s.total == 4 and [r.rank for r in s.standings] == [1, 2, 3, 4] and s.standings[0].tiebreaks["buchholz"] == 3.5
    assert s.pairings[0].white and s.pairings[0].white.name == "Alice" and not s.pairings[0].is_bye
    assert s.pairings[1].is_bye and s.pairings[1].black is None and s.pairings[1].points == (1.0, 0.0)
    assert s.find(name="Bob") and s.find(agent_id="dev-agent-dave").rank == 4  # type: ignore[union-attr]


def test_conflict_error_is_typed() -> None:
    f = FakeServer({"join_tournament": [tool_result("err_tournament_conflict")]})
    with pytest.raises(ConflictError):
        f.client().join_tournament(TID)


def test_position_carries_tournament() -> None:
    p = Position._from(game_wait("your_turn", "g1")["structuredContent"]["game"])
    assert p.tournament and p.tournament.id == TID and p.tournament.round == 1


def test_play_tournament_two_rounds_bye_and_finish() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings(joined=False), standings()],
                    "join_tournament": [tool_result("tournament_join")],
                    "wait_for_turn": [tool_result("wait_tournament_started"), game_wait("your_turn", "g1"), over("g1"),
                                      tool_result("wait_tournament_bye"), game_wait("your_turn", "g2"), over("g2"),
                                      tool_result("wait_tournament_finished")],
                    "make_move": [tool_result("move_ok")]})
    seen: list[str] = []
    events: list[str] = []
    row = f.client().play_tournament(TID, lambda p: seen.append(p.game_id) or "e4", on_event=lambda e: events.append(e.type))
    assert row and row.rank == 3 and row.score == 1.5 and row.name == "Alice"
    assert seen == ["g1", "g2"]
    W, M, S = "wait_for_turn", "make_move", "tournament_standings"
    assert f.names() == [S, "join_tournament", W, W, M, W, S, W, W, M, W, S, W, S, "whoami"]
    assert events == ["tournament_started", "tournament_game_over", "tournament_round", "tournament_game_over", "tournament_finished"]
    assert f.calls[1][1] == {"id": TID}


def test_already_joined_does_not_join_and_timeouts_are_survived() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings()], "wait_for_turn": [timeout(), timeout(), tool_result("wait_tournament_finished")]})
    row = f.client().play_tournament(TID, lambda p: "e4")
    assert row and row.rank == 3 and "join_tournament" not in f.names()
    assert f.names().count("wait_for_turn") == 3


def test_join_conflict_when_already_in_is_tolerated() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings(joined=False), standings()], "join_tournament": [tool_result("err_tournament_conflict")],
                    "wait_for_turn": [tool_result("wait_tournament_finished")]})
    assert f.client().play_tournament(TID, lambda p: "e4") is not None


def test_join_refusal_is_raised() -> None:
    refused = {"content": [{"type": "text", "text": "ERROR (not_allowed): wrong pool"}], "isError": True,
               "structuredContent": {"error": {"code": "not_allowed", "message": "wrong pool", "hint": "pick another"}}}
    f = FakeServer({**BASE, "tournament_standings": [standings("registering", joined=False)], "join_tournament": [refused]})
    with pytest.raises(NotAllowedError):
        f.client().play_tournament(TID, lambda p: "e4")


def test_cancelled_while_waiting_returns_instead_of_hanging() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings("registering"), standings("cancelled")], "wait_for_turn": [timeout()]})
    row = f.client().play_tournament(TID, lambda p: "e4")
    assert row is not None and row.rank == 3 and f.names().count("wait_for_turn") == 1


def test_cancelled_in_the_middle_of_a_game() -> None:
    aborted = over("g1")
    aborted["structuredContent"]["game"]["status"] = "aborted"
    f = FakeServer({**BASE, "tournament_standings": [standings(), standings("cancelled")],
                    "wait_for_turn": [game_wait("your_turn", "g1"), aborted], "make_move": [tool_result("move_ok")]})
    assert f.client().play_tournament(TID, lambda p: "e4") is not None
    assert f.names().count("wait_for_turn") == 2


def test_already_finished_tournament_returns_row_at_once() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings("finished")]})
    row = f.client().play_tournament(TID, lambda p: "e4")
    assert row and row.rank == 3 and "wait_for_turn" not in f.names()


def test_events_for_other_tournaments_are_ignored() -> None:
    other = tool_result("wait_tournament_finished")
    other = copy.deepcopy(other)
    other["structuredContent"]["events"][0]["data"]["tournament_id"] = "t_other"
    f = FakeServer({**BASE, "tournament_standings": [standings()], "wait_for_turn": [other, tool_result("wait_tournament_finished")]})
    f.client().play_tournament(TID, lambda p: "e4")
    assert f.names().count("wait_for_turn") == 2


async def test_aplay_tournament() -> None:
    f = FakeServer({**BASE, "tournament_standings": [standings(joined=False), standings()], "join_tournament": [tool_result("tournament_join")],
                    "wait_for_turn": [game_wait("your_turn", "g1"), over("g1"), tool_result("wait_tournament_finished")],
                    "make_move": [tool_result("move_ok")]})

    async def strat(p: Position) -> str:
        return "e4"

    c = f.aclient()
    row = await c.play_tournament(TID, strat)
    assert row and row.rank == 3 and "join_tournament" in f.names()
    ts = await FakeServer({"list_tournaments": [tool_result("tournaments_list")]}).aclient().list_tournaments()
    assert len(ts) == 2
    _ = fixture
