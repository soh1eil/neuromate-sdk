"""Record real MCP replies from the dev server into JSON fixtures shared by all SDK unit tests.

    NM_DEV=1 go run ./cmd/mcp-devserver &      # from server/
    python3 record.py http://127.0.0.1:27700/mcp

Pure standard library. Fixtures are written next to this file as <name>.json:
{"request": <JSON-RPC request>, "status": 200, "response": <JSON-RPC reply>}.
"""
import json, sys, urllib.request, urllib.error, pathlib, itertools

URL = sys.argv[1]
OUT = pathlib.Path(__file__).parent
KEYS = {"alice": "nm_live_dev_alice", "bob": "nm_live_dev_bob", "carol": "nm_live_dev_carol"}
ids = itertools.count(1)


def post(who, method, params, name=None, key=None):
    req = {"jsonrpc": "2.0", "id": next(ids), "method": method, "params": params}
    r = urllib.request.Request(URL, json.dumps(req).encode(), {
        "Authorization": "Bearer " + (key or KEYS[who]), "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            status, body = resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read().decode()
    try:
        parsed = json.loads(body)
    except ValueError:
        parsed = body
    if name:
        (OUT / f"{name}.json").write_text(json.dumps({"request": req, "status": status, "response": parsed}, indent=1, ensure_ascii=False) + "\n")
    return parsed


def tool(who, name, args=None, rec=None):
    r = post(who, "tools/call", {"name": name, "arguments": args or {}}, rec)
    return r["result"]["structuredContent"] if isinstance(r, dict) and "result" in r else r


post("alice", "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "rec", "version": "0"}}, "initialize")
post("alice", "tools/list", {}, "tools_list")
tool("alice", "start_here", rec="start_here_idle")
tool("alice", "whoami", rec="whoami_idle")
tool("alice", "get_handbook", rec="handbook_index")
tool("alice", "get_handbook", {"section": "quickstart"}, "handbook_quickstart")
tool("alice", "list_challenges", rec="list_challenges_empty")
tool("alice", "join_queue", {"time_control": "3+0"}, "join_queue_waiting")
m = tool("bob", "join_queue", {"time_control": "3+0"}, "join_queue_matched")
gid = m["game"]["game_id"]
tool("alice", "wait_for_turn", {"timeout_seconds": 2}, "wait_game_started")
white, black = ("alice", "bob") if m["game"]["you"]["color"] == "black" else ("bob", "alice")
tool(white, "get_game", {"game_id": gid}, "get_game_start")
tool(white, "legal_moves", rec="legal_moves")
tool(black, "make_move", {"move": "e7e5", "client_move_id": "x1"}, "err_not_your_turn")
tool(white, "make_move", {"move": "e2e5", "client_move_id": "x2"}, "err_illegal_move")
tool(white, "make_move", {"move": "banana"}, "err_unparseable_move")
tool(white, "make_move", {"move": "e4", "client_move_id": "w1", "note": "centre", "expected_ply": 5}, "err_stale_ply")
tool(white, "make_move", {"move": "e4", "client_move_id": "w1", "note": "centre", "expected_ply": 0}, "move_ok")
tool(white, "make_move", {"move": "e4", "client_move_id": "w1", "note": "centre", "expected_ply": 0}, "move_replayed")
tool(white, "make_move", {"move": "e4", "bogus": 1}, "err_unknown_param")
tool(white, "wait_for_turn", {"game_id": gid, "timeout_seconds": 1}, "wait_timeout")
tool(black, "wait_for_turn", {"game_id": gid, "timeout_seconds": 2}, "wait_your_turn")
tool(black, "make_move", {"move": "e5", "client_move_id": "b1"}, "move_black")
tool(white, "offer_draw", rec="offer_draw")
tool(black, "wait_for_turn", {"game_id": gid, "timeout_seconds": 2}, "wait_draw_offer")
tool(black, "respond_draw", {"accept": False}, "respond_draw_decline")
tool(black, "resign", rec="resign")
tool(white, "make_move", {"move": "Nf3", "client_move_id": "w2", "game_id": gid}, "err_game_over")
tool(white, "get_game", {"game_id": gid}, "get_game_finished")
tool(white, "get_analysis", {"game_id": gid}, "analysis_unavailable")
tool(white, "get_analysis", rec="err_analysis_no_game_id")
tool(white, "wait_for_turn", {"timeout_seconds": 2}, "wait_game_over_event")
tool("carol", "create_seek", {"variant": "chess960", "time_control": "5+0", "color": "white"}, "create_seek")
tool("bob", "challenge", {"to": "carol", "message": "fancy a game?", "time_control": "10"}, "challenge")
tool("bob", "challenge", {"to": "nobody"}, "err_unknown_player")
lc = tool("carol", "list_challenges", rec="list_challenges_busy")
tool("alice", "get_game", {}, "get_game_default_finished")
tool("alice", "make_move", {"move": "Nf3"}, "err_game_over_no_id")
tool("carol", "get_game", {}, "err_no_game")
ch = lc["incoming_challenges"][0]["id"]
sk = lc["my_seeks"][0]["id"]
tool("bob", "cancel", {"id": ch}, "cancel")
tool("alice", "accept_challenge", {"id": sk}, "accept_seek")
tool("alice", "leave_queue", rec="leave_queue")
post("alice", "tools/call", {"name": "make_moves", "arguments": {}}, "rpc_unknown_tool")
post("alice", "bogus/method", {}, "rpc_unknown_method")
post("alice", "tools/call", {"name": "whoami", "arguments": {}}, "http_401", key="nm_live_wrong")
print("recorded", len(list(OUT.glob("*.json"))), "fixtures")
