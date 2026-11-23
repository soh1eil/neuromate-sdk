from __future__ import annotations

import pytest

from neuromate import (
    AuthError, GameOverError, IllegalMoveError, InvalidParamsError, NoGameError, NotYourTurnError, ProtocolError,
    RateLimitedError, ServerError, StalePlyError, ToolError,
)

from conftest import FakeServer, tool_result, fixture


def c(script):  # type: ignore[no-untyped-def]
    return FakeServer(script).client()


def test_position_fields_from_a_real_reply() -> None:
    pos = c({"get_game": [tool_result("get_game_start")]}).get_game()
    assert pos.status == "created" and pos.variant == "standard" and pos.ply == 0
    assert pos.fen.startswith("rnbqkbnr/pppppppp") and pos.your_turn and pos.color == "white"
    assert len(pos.legal_moves) == 20 and "e2e4" in pos.legal_uci and "Nf3" in pos.legal_san
    assert "a b c d e f g h" in pos.board_ascii and pos.clock.you_ms == 180000
    assert pos.opponent.name in ("Alice", "Bob") and pos.opponent.name != pos.you.name and pos.opponent.model_label.startswith("Dev Model") and not pos.is_over
    assert pos.clock.seconds_for_this_move is not None and pos.deadline is not None
    assert "YOUR TURN" in pos.text and pos.next.startswith("It is your turn")


def test_move_result_and_replay() -> None:
    ok = c({"make_move": [tool_result("move_ok")]}).make_move("e4", client_move_id="w1")
    assert ok.move and ok.move.uci == "e2e4" and ok.move.note == "centre" and not ok.replayed
    assert not ok.position.your_turn
    again = c({"make_move": [tool_result("move_replayed")]}).make_move("e4", client_move_id="w1")
    assert again.replayed and again.move and again.move.san == "e4"


def test_wait_variants() -> None:
    t = c({"wait_for_turn": [tool_result("wait_timeout")]}).wait_for_turn("g", 1)
    assert t.timed_out and t.position is None
    t = c({"wait_for_turn": [tool_result("wait_your_turn")]}).wait_for_turn()
    assert t.reason == "your_turn" and t.position and t.position.legal_moves
    t = c({"wait_for_turn": [tool_result("wait_draw_offer")]}).wait_for_turn()
    assert t.reason == "draw_offer" and t.position and t.position.draw_offer == "from_opponent"
    t = c({"wait_for_turn": [tool_result("wait_game_started")]}).wait_for_turn()
    assert t.events and t.events[0].type == "game_started" and t.events[0].data["game_id"]


def test_lobby_queue_seek() -> None:
    lobby = c({"start_here": [tool_result("start_here_idle")]}).start_here()
    assert lobby.active_games == [] and "join_queue" in lobby.next and "make_move" in lobby.tools and lobby.you.name == "Alice"
    q = c({"join_queue": [tool_result("join_queue_waiting")]}).join_queue(time_control="3+0")
    assert q.queued and q.position is None and q.time_control
    q = c({"join_queue": [tool_result("join_queue_matched")]}).join_queue()
    assert not q.queued and q.position is not None
    busy = c({"list_challenges": [tool_result("list_challenges_busy")]}).list_challenges()
    assert busy.incoming_challenges and busy.incoming_challenges[0].sender.name == "Bob" and busy.my_seeks
    s = c({"create_seek": [tool_result("create_seek")]}).create_seek(variant="chess960", color="white")
    assert s.id.startswith("sk_") and s.kind == "seek"
    ch = c({"challenge": [tool_result("challenge")]}).challenge("carol", message="hi")
    assert ch.id.startswith("ch_")


def test_handbook_and_analysis() -> None:
    hb = c({"get_handbook": [tool_result("handbook_index")]}).get_handbook()
    assert "quickstart" in hb.sections
    a = c({"get_analysis": [tool_result("analysis_unavailable")]}).get_analysis()
    assert a.text and isinstance(a.available, bool)


def test_finished_game_from_resign() -> None:
    pos = c({"resign": [tool_result("resign")]}).resign()
    assert pos.is_over and pos.result and pos.result.termination == "resignation" and pos.result.winner


def test_typed_tool_errors_carry_the_teaching_message() -> None:
    with pytest.raises(IllegalMoveError) as e:
        c({"make_move": [tool_result("err_illegal_move")]}).make_move("e2e5")
    err = e.value
    assert "pawn on e2 cannot move to e5" in str(err) and err.code == "illegal_move" and "e2e4" in err.message
    assert err.hint and err.position and err.position.your_turn and "e2e4" in err.legal_moves + err.position.legal_uci
    # the remaining illegal-move budget is in the error context
    assert err.context["illegal_attempts_remaining"] == 24 and err.context["illegal_attempts_budget"] == 25
    assert "24 remaining" in err.hint
    with pytest.raises(NotYourTurnError) as e2:
        c({"make_move": [tool_result("err_not_your_turn")]}).make_move("e5")
    assert "wait_for_turn" in e2.value.hint
    with pytest.raises(StalePlyError):
        c({"make_move": [tool_result("err_stale_ply")]}).make_move("e4", expected_ply=5)
    with pytest.raises(GameOverError) as e3:
        c({"make_move": [tool_result("err_game_over")]}).make_move("Nf3")
    assert e3.value.position and e3.value.position.is_over
    with pytest.raises(ToolError) as e4:
        c({"make_move": [tool_result("err_unknown_param")]}).make_move("e4")
    assert type(e4.value) is InvalidParamsError and "allowed parameters" in e4.value.hint and e4.value.code == "invalid_params"
    with pytest.raises(ToolError) as e5:
        c({"challenge": [tool_result("err_unknown_player")]}).challenge("nobody")
    assert e5.value.code == "unknown_player" and e5.value.hint
    with pytest.raises(NoGameError) as e6:
        c({"get_game": [tool_result("err_no_game")]}).get_game()
    assert e6.value.code == "no_game" and "join_queue" in e6.value.hint
    # no game_id after the game ended: the server resolves to the most recent game
    pos = c({"get_game": [tool_result("get_game_default_finished")]}).get_game()
    assert pos.is_over and "most recent game" in pos.text
    with pytest.raises(GameOverError) as e7:
        c({"make_move": [tool_result("err_game_over_no_id")]}).make_move("Nf3")
    assert e7.value.context["game_id"] and e7.value.hint


def test_protocol_http_and_limits() -> None:
    err = fixture("rpc_unknown_tool")["response"]["error"]
    with pytest.raises(ProtocolError) as e:
        c({"nope": [{"__rpc_error__": err}]}).call_tool("nope")
    assert e.value.code == -32602 and "Did you mean make_move" in str(e.value)
    with pytest.raises(AuthError) as a:
        c({"whoami": [(401, fixture("http_401")["response"])]}).whoami()
    assert a.value.status == 401 and a.value.code == "unauthorized" and "Authorization: Bearer" in a.value.hint
    assert "missing or invalid agent API key" in str(a.value)
    with pytest.raises(AuthError) as a2:  # older servers sent plain text
        c({"whoami": [(401, "missing or invalid agent API key\n")]}).whoami()
    assert "missing or invalid" in str(a2.value)
    assert e.value.error_code == "unknown_tool" and e.value.hint
    with pytest.raises(RateLimitedError) as r:
        c({"whoami": [{"content": [{"type": "text", "text": "RATE LIMITED: too many requests. Wait 7 seconds, then retry."}], "isError": True}]}).whoami()
    assert r.value.retry_after == 7
    structured = {"content": [{"type": "text", "text": "ERROR (rate_limited): too many requests\n"}], "isError": True,
                  "structuredContent": {"error": {"code": "rate_limited", "message": "too many requests", "hint": "wait", "retry_after_seconds": 4}}}
    with pytest.raises(RateLimitedError) as r3:
        c({"whoami": [structured]}).whoami()
    assert r3.value.retry_after == 4
    with pytest.raises(RateLimitedError) as r2:
        c({"whoami": [(429, "slow down")]}).whoami()
    assert r2.value.retry_after >= 1
    internal = {"content": [{"type": "text", "text": "INTERNAL ERROR: something went wrong on our side"}], "isError": True,
                "structuredContent": {"error": {"message": "internal error"}}}
    with pytest.raises(ServerError):
        c({"whoami": [internal]}).whoami()
    coded = {"content": [{"type": "text", "text": "ERROR (internal): something went wrong"}], "isError": True,
             "structuredContent": {"error": {"code": "internal", "message": "something went wrong on our side", "hint": "retry"}}}
    with pytest.raises(ServerError):
        c({"whoami": [coded]}).whoami()
    with pytest.raises(ServerError):
        c({"whoami": [(503, "down")]}).whoami()


def test_unknown_game_option_is_a_clear_typeerror() -> None:
    with pytest.raises(TypeError, match="unknown game option"):
        c({}).create_seek(vairant="chess960")


def test_requests_are_well_formed() -> None:
    f = FakeServer({"make_move": [tool_result("move_ok")], "join_queue": [tool_result("join_queue_waiting")]})
    cl = f.client()
    cl.make_move("e4", "g1", note="n", expected_ply=0)
    cl.join_queue(variant="chess960", time_control="3+2", rated=True, pool="agents", increment_seconds=1)
    (n1, a1), (n2, a2) = f.calls
    assert n1 == "make_move" and a1["game_id"] == "g1" and a1["note"] == "n" and a1["expected_ply"] == 0 and len(a1["client_move_id"]) >= 8
    assert a2 == {"variant": "chess960", "time_control": "3+2", "rated": True, "increment_seconds": 1, "pool": "agents"}


def test_api_key_from_env_and_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    from neuromate import Client

    monkeypatch.delenv("NEUROMATE_API_KEY", raising=False)
    with pytest.raises(ValueError, match="NEUROMATE_API_KEY"):
        Client()
    monkeypatch.setenv("NEUROMATE_API_KEY", "nm_live_abc")
    monkeypatch.setenv("NEUROMATE_URL", "http://x/mcp")
    cl = Client()
    assert cl.api_key == "nm_live_abc" and cl.base_url == "http://x/mcp" and "nm_live_abc" not in repr(cl)


def test_strict_ruleset_fields() -> None:
    strict = c({"get_game": [tool_result("get_game_strict")]}).get_game()
    assert strict.ruleset == "fide_strict" and strict.is_strict and strict.hints_allowed is False
    assert strict.time_control_text.startswith("3 minutes each") and "insufficient mating material" in strict.flag_text
    assert strict.control["initial_ms"] == 180000 and "forced off" in strict.rules_text
    # an older reply without the new fields is a casual game
    old = c({"get_game": [tool_result("get_game_start")]}).get_game()
    assert old.ruleset == "casual" and not old.is_strict and old.hints_allowed is True
