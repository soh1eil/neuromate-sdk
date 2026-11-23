from __future__ import annotations

import copy
from typing import Any

from conftest import FakeServer, tool_result


def reply(structured: dict[str, Any], text: str = "OK") -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "structuredContent": structured}


def test_user_locale_on_positions_challenges_and_lobby() -> None:
    g = copy.deepcopy(tool_result("get_game_start")["structuredContent"])
    g["game"].update(user_locale="fa", opponent_locale="de")
    pos = FakeServer({"get_game": [reply(g)]}).client().get_game()
    assert (pos.user_locale, pos.opponent_locale) == ("fa", "de")
    plain = FakeServer({"get_game": [tool_result("get_game_start")]}).client().get_game()
    assert (plain.user_locale, plain.opponent_locale) == ("", "")

    lobby = FakeServer({"list_challenges": [reply({
        "user_locale": "ja",
        "incoming_challenges": [{"id": "ch_1", "from": {"name": "Hanna", "kind": "human"}, "to": {"name": "me", "kind": "agent"}, "params": {},
                                 "message": "", "status": "pending", "game_id": "", "expires_at": "", "from_locale": "de"}],
    })]}).client().list_challenges()
    assert lobby.user_locale == "ja" and lobby.incoming_challenges[0].from_locale == "de"


def test_coach_sessions_wait_context_say_progress() -> None:
    f = FakeServer({
        "coach_sessions": [reply({"sessions": [{"id": "cs_1", "kind": "guided", "status": "active", "hint_mode": "ask", "locale": "fa", "game_id": "g1", "topic": ""}]})],
        "coach_wait": [
            reply({"user_locale": "fa", "next": "coach_get_context", "events": [{"type": "student_message", "session_id": "cs_1", "kind": "chat", "text": "help", "untrusted_text": True}]}),
            reply({"timeout": True, "events": [], "user_locale": "fa", "next": "call coach_wait again"}),
        ],
        "coach_get_context": [reply({"session": {"id": "cs_1"}, "user_locale": "fa", "policy": "ASK mode"})],
        "coach_say": [reply({"message": {"id": 1}}, "SENT")],
        "coach_mark_progress": [reply({"topic": "fork"}, "RECORDED")],
    })
    c = f.client()
    s = c.coach_sessions().sessions[0]
    assert (s.id, s.kind, s.hint_mode, s.locale, s.game_id) == ("cs_1", "guided", "ask", "fa", "g1")
    w = c.coach_wait(timeout_seconds=5)
    assert not w.timed_out and w.events[0].type == "student_message" and w.events[0].session_id == "cs_1"
    assert w.events[0].data["text"] == "help" and w.events[0].data["untrusted_text"] is True
    assert c.coach_wait().timed_out
    cx = c.coach_context("cs_1")
    assert (cx.session_id, cx.user_locale, cx.policy) == ("cs_1", "fa", "ASK mode")
    c.coach_say("نگاه کن", "cs_1", kind="suggestion", arrows=[{"from": "e2", "to": "e4", "color": "primary"}], circles=[{"square": "f7"}])
    c.coach_mark_progress("fork", 50, note="ok")
    say = next(a for t, a in f.calls if t == "coach_say")
    assert say["text"] == "نگاه کن" and say["kind"] == "suggestion" and say["arrows"][0]["to"] == "e4" and say["session_id"] == "cs_1"
    assert next(a for t, a in f.calls if t == "coach_wait") == {"timeout_seconds": 5}
    assert next(a for t, a in f.calls if t == "coach_mark_progress") == {"topic": "fork", "mastery": 50, "note": "ok"}
