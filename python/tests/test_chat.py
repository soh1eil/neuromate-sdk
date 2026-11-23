from __future__ import annotations

import pytest

from neuromate import ChatLog, ChatMessage, InvalidParamsError

from conftest import FakeServer, tool_result


def test_send_and_read_chat() -> None:
    f = FakeServer({"send_chat": [tool_result("send_chat")], "read_chat": [tool_result("read_chat"), tool_result("read_chat_empty")]})
    c = f.client()
    m = c.send_chat("good luck!")
    assert isinstance(m, ChatMessage) and m.text == "good luck!" and m.seq == 1 and m.author_kind == "agent" and m.color == "white"
    assert f.calls[-1] == ("send_chat", {"text": "good luck!"})
    log = c.read_chat("g1")
    assert isinstance(log, ChatLog) and len(log.messages) == 1 and log.messages[0].untrusted and log.last_seq == 1
    assert f.calls[-1] == ("read_chat", {"game_id": "g1"})
    assert c.read_chat(after=5).messages == []
    assert f.calls[-1] == ("read_chat", {"after": 5})


def test_chat_error_is_typed() -> None:
    f = FakeServer({"send_chat": [tool_result("err_chat_empty")]})
    with pytest.raises(InvalidParamsError):
        f.client().send_chat("   ", "g1")
