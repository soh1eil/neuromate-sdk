"""Transport-independent MCP plumbing: request building, reply parsing, typed results."""

from __future__ import annotations

import json
import re
from typing import Any, Callable, Mapping

from ._types import (
    Analysis, CoachContext, CoachEvent, CoachSession, CoachSessions, CoachWait, ChatLog, ChatMessage, Created, Handbook, JoinResult, LegalMove, LegalMoves, Lobby, MoveInfo, MoveResult, Pairing, Position, Queued,
    Standing, Standings, Tournament, Turn, Event, WithdrawResult,
)
from .errors import AuthError, HTTPError, ProtocolError, RateLimitedError, ServerError, tool_error_class

DEFAULT_URL = "https://api.neuromate.larsima.com/mcp"
PROTOCOL_VERSION = "2025-06-18"
SDK_VERSION = "0.1.0"


def request_headers(api_key: str, session_id: str | None = None) -> dict[str, str]:
    h = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
        "User-Agent": f"neuromate-python/{SDK_VERSION}",
    }
    if session_id:
        h["Mcp-Session-Id"] = session_id
    return h


def rpc_body(req_id: int | None, method: str, params: Mapping[str, Any] | None = None) -> bytes:
    msg: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
    if req_id is not None:
        msg["id"] = req_id
    if params is not None:
        msg["params"] = params
    return json.dumps(msg, separators=(",", ":")).encode()


def initialize_params() -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {},
        "clientInfo": {"name": "neuromate-python", "version": SDK_VERSION},
    }


def _sse_payload(body: str, req_id: int | None) -> str:
    """Pick the JSON-RPC message with our id out of a text/event-stream body."""
    last = ""
    for block in re.split(r"\r?\n\r?\n", body):
        data = "\n".join(line[5:].lstrip() for line in block.splitlines() if line.startswith("data:"))
        if not data:
            continue
        last = data
        try:
            if json.loads(data).get("id") == req_id:
                return data
        except (ValueError, AttributeError):
            continue
    return last


def check_http(status: int, headers: Mapping[str, str], body: str) -> None:
    """Raise the right typed error for a non-200 reply (202 and 204 are fine)."""
    if status in (200, 202, 204):
        return
    snippet = body.strip()[:500]
    if status == 401:
        auth = AuthError(snippet or "invalid or missing API key: send it as Authorization: Bearer nm_live_...", status, body)
        try:
            j = json.loads(body)
        except ValueError:
            j = None
        if isinstance(j, dict):
            e = j.get("error")
            d = (e.get("data") if isinstance(e, dict) else None) or j
            if isinstance(d, dict) and d.get("message"):
                auth.hint = str(d.get("hint") or "")
                auth.args = ((str(d["message"]) + (". " + auth.hint if auth.hint else "")),)
        raise auth
    if status == 429:
        try:
            ra = float(headers.get("retry-after", "1"))
        except ValueError:
            ra = 1.0
        raise RateLimitedError(snippet or "rate limited", ra)
    if status >= 500:
        raise ServerError(snippet or f"server error {status}", status, body)
    # 400-level JSON-RPC errors carry a body we can explain
    try:
        err = json.loads(body).get("error")
    except (ValueError, AttributeError):
        err = None
    if isinstance(err, dict):
        raise ProtocolError(str(err.get("message", snippet)), int(err.get("code", 0) or 0), err.get("data"))
    raise HTTPError(snippet or f"HTTP {status}", status, body)


def parse_rpc(content_type: str, body: str, req_id: int | None) -> Any:
    """Return the ``result`` of a JSON-RPC reply or raise ProtocolError."""
    if "text/event-stream" in content_type:
        body = _sse_payload(body, req_id)
    try:
        msg = json.loads(body)
    except ValueError as e:
        raise ProtocolError(f"the server sent something that is not JSON: {body[:200]!r}") from e
    if not isinstance(msg, dict):
        raise ProtocolError("unexpected JSON-RPC reply shape")
    if msg.get("error"):
        er = msg["error"]
        raise ProtocolError(str(er.get("message", "error")), int(er.get("code", 0) or 0), er.get("data"))
    return msg.get("result")


class ToolOut:
    """Decoded ``tools/call`` result."""

    __slots__ = ("text", "data", "is_error", "tool")

    def __init__(self, tool: str, result: Mapping[str, Any]) -> None:
        self.tool = tool
        self.text = "".join(p.get("text", "") for p in result.get("content") or [] if p.get("type") == "text")
        self.data: Mapping[str, Any] = result.get("structuredContent") or {}
        self.is_error = bool(result.get("isError"))

    def raise_for_error(self) -> None:
        if not self.is_error:
            return
        t = self.text
        err = self.data.get("error") or {}
        code = err.get("code")
        # Servers with unified error codes: decide by code. The text prefixes
        # below are only for older servers.
        if code == "rate_limited" or t.startswith("RATE LIMITED"):
            m = re.search(r"Wait (\d+) seconds", t)
            ra = err.get("retry_after_seconds")
            raise RateLimitedError(t.strip(), float(ra) if ra is not None else float(m.group(1)) if m else 1.0)
        msg = str(err.get("message") or t.strip())
        if code == "internal" or t.startswith("INTERNAL ERROR") or msg == "internal error":
            raise ServerError(t.strip(), 500, t)
        pos = Position._from(self.data["game"]) if isinstance(self.data.get("game"), dict) else None
        cls = tool_error_class(code)
        known = {"code", "message", "hint", "what_to_do", "legal_moves"}
        raise cls(
            msg, code=code, hint=str(err.get("hint") or err.get("what_to_do") or ""),
            legal_moves=list(err.get("legal_moves") or []), position=pos, text=t, tool=self.tool,
            context={k: v for k, v in err.items() if k not in known},
        )


def decode_tool(tool: str, result: Any) -> ToolOut:
    if not isinstance(result, dict):
        raise ProtocolError(f"tools/call {tool} returned no result")
    out = ToolOut(tool, result)
    out.raise_for_error()
    return out


# ---- argument builders ----

_GAME_KEYS = (
    "variant", "time_control", "initial_seconds", "increment_seconds", "delay_seconds", "bronstein_seconds",
    "correspondence_days", "move_cap_seconds", "bank_seconds", "rated", "ruleset", "chess960_position", "color",
)


def clean(**kw: Any) -> dict[str, Any]:
    return {k: v for k, v in kw.items() if v is not None}


# ---- result parsers ----

def parse_position(out: ToolOut) -> Position:
    g = out.data.get("game")
    if not isinstance(g, dict):
        raise ProtocolError(f"{out.tool}: the reply has no game state:\n{out.text[:300]}")
    return Position._from(g, out.text)


def parse_lobby(out: ToolOut) -> Lobby:
    return Lobby._from(out.data, out.text)


def parse_turn(out: ToolOut) -> Turn:
    d = out.data
    g = d.get("game")
    return Turn(
        reason=str(d.get("reason") or ""), position=Position._from(g, out.text) if isinstance(g, dict) else None,
        events=[Event._from(e) for e in d.get("events") or []], next=str(d.get("next") or ""), text=out.text,
    )


def parse_move(out: ToolOut) -> MoveResult:
    return MoveResult(MoveInfo._from(out.data.get("move")), bool(out.data.get("replayed")), parse_position(out), out.text)


def parse_queued(out: ToolOut) -> Queued:
    if isinstance(out.data.get("game"), dict):
        return Queued(False, parse_position(out), text=out.text)
    d = out.data
    return Queued(bool(d.get("queued")), None, str(d.get("pool") or ""), str(d.get("variant") or ""),
                  str(d.get("time_control") or ""), out.text)


def parse_created(kind: str) -> Callable[[ToolOut], Created]:
    def f(out: ToolOut) -> Created:
        raw = out.data.get(kind) or {}
        return Created(str(raw.get("id", "")), kind, raw, out.text)

    return f


def parse_legal(out: ToolOut) -> LegalMoves:
    d = out.data
    return LegalMoves(str(d.get("game_id", "")), int(d.get("ply") or 0),
                      [LegalMove(str(m.get("uci", "")), str(m.get("san", ""))) for m in d.get("legal_moves") or []], out.text)


def parse_analysis(out: ToolOut) -> Analysis:
    return Analysis(out.text, out.data)


def parse_handbook(out: ToolOut) -> Handbook:
    d = out.data
    return Handbook(out.text, list(d.get("sections") or []), str(d.get("section") or ""))


def parse_ack(out: ToolOut) -> str:
    return out.text


def _tournament(out: ToolOut) -> Tournament:
    t = out.data.get("tournament")
    if not isinstance(t, dict):
        raise ProtocolError(f"{out.tool}: the reply has no tournament:\n{out.text[:300]}")
    return Tournament._from(t)


def parse_tournaments(out: ToolOut) -> list[Tournament]:
    return [Tournament._from(t) for t in out.data.get("tournaments") or [] if isinstance(t, dict)]


def parse_join(out: ToolOut) -> JoinResult:
    return JoinResult(_tournament(out), bool(out.data.get("joined", True)), str(out.data.get("next") or ""), out.text)


def parse_withdraw(out: ToolOut) -> WithdrawResult:
    return WithdrawResult(_tournament(out), bool(out.data.get("withdrawn", True)), out.text)


def parse_standings(out: ToolOut) -> Standings:
    d = out.data
    rows = [Standing._from(r) for r in d.get("standings") or [] if isinstance(r, dict)]
    return Standings(_tournament(out), rows, [Pairing._from(p) for p in d.get("pairings") or [] if isinstance(p, dict)],
                     int(d["total"]) if isinstance(d.get("total"), (int, float)) else len(rows), out.text)


# ---- coach mode ----


def parse_coach_sessions(out: ToolOut) -> CoachSessions:
    return CoachSessions([CoachSession._from(x) for x in out.data.get("sessions") or [] if isinstance(x, dict)], out.text)


def parse_coach_wait(out: ToolOut) -> CoachWait:
    events = []
    for e in out.data.get("events") or []:
        if isinstance(e, dict):
            data = {k: v for k, v in e.items() if k not in ("type", "session_id")}
            events.append(CoachEvent(str(e.get("type") or ""), str(e.get("session_id") or ""), data))
    return CoachWait(events, out.data.get("timeout") is True, str(out.data.get("user_locale") or ""), str(out.data.get("next") or ""), out.text)


def parse_coach_context(out: ToolOut) -> CoachContext:
    sess = out.data.get("session") if isinstance(out.data.get("session"), dict) else {}
    return CoachContext(str(sess.get("id") or ""), str(out.data.get("user_locale") or ""), str(out.data.get("policy") or ""), out.data, out.text)


def parse_chat_message(m: Mapping[str, Any]) -> ChatMessage:
    a = m.get("author") or {}
    return ChatMessage(str(m.get("id", "")), int(m.get("seq") or 0), str(m.get("game_id", "")), str(a.get("kind", "")),
                       str(a.get("name", "")), str(a.get("key", "")), str(m.get("color", "")), str(m.get("text", "")),
                       str(m.get("created_at", "")), bool(m.get("untrusted", True)), bool(m.get("hidden", False)))


def parse_chat_sent(out: ToolOut) -> ChatMessage:
    return parse_chat_message(out.data.get("message") or {})


def parse_chat_log(out: ToolOut) -> ChatLog:
    d = out.data
    return ChatLog(str(d.get("game_id", "")), [parse_chat_message(m) for m in d.get("messages") or []], int(d.get("last_seq") or 0), out.text)
