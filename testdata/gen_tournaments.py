"""Write the hand-made tournament fixtures (tournament_*.json) from the contract in docs/API.md.

    python3 gen_tournaments.py

The tournament MCP tools are not recorded from a server (record.py); these files follow the documented
shapes and share the {"request","status","response"} format of the recorded fixtures.
"""
import json, pathlib

OUT = pathlib.Path(__file__).parent
T = {"id": "t_k3m9x2qa", "name": "Friday Blitz Swiss", "kind": "swiss", "status": "running", "variant": "standard",
     "time_control": {"kind": "realtime", "initial_ms": 180000, "increment_ms": 2000}, "time_control_label": "3+2",
     "speed": "blitz", "pool": "agents", "rated": True, "starts_at": "2026-12-01T18:00:00Z", "ends_at": None,
     "duration_seconds": 0, "rounds": 3, "round": 2, "next_round_at": "2026-12-01T18:09:00Z", "players": 4,
     "min_players": 2, "max_players": 64, "entry": {"min_games": 0, "rating_min": 0, "rating_max": 0},
     "allow_bots": True, "berserk": False, "season_id": "", "created_at": "2026-11-30T10:00:00Z", "finished_at": None,
     "winner": None}
ME = {"joined": True, "status": "active", "rank": 3, "score": 1.5}


def row(rank, name, agent_id, score):
    return {"rank": rank, "name": name, "kind": "agent", "model_label": "Dev Model", "agent_id": agent_id, "bot_id": "",
            "rating": 1500 + 10 * (4 - rank), "score": score, "games": 2, "wins": 1, "draws": 1, "losses": 0, "byes": 0,
            "tiebreaks": {"buchholz": 3.5, "sonneborn_berger": 2.25}, "streak": 0, "berserks": 0, "status": "active",
            "is_filler": False, "game_id": "", "performance": 1600}


def side(name, agent_id, rating):
    return {"name": name, "kind": "agent", "model_label": "Dev Model", "agent_id": agent_id, "rating": rating}


STAND = [row(1, "Bob", "dev-agent-bob", 2.5), row(2, "Carol", "dev-agent-carol", 2.0),
         row(3, "Alice", "dev-agent-alice", 1.5), row(4, "Dave", "dev-agent-dave", 0.5)]
PAIR = [{"round": 2, "board": 1, "white": side("Alice", "dev-agent-alice", 1520), "black": side("Bob", "dev-agent-bob", 1530),
         "game_id": "gabcdefghi", "status": "playing", "result": "*", "points": [0, 0], "berserk": [False, False], "termination": ""},
        {"round": 2, "board": 2, "white": side("Carol", "dev-agent-carol", 1510), "black": None, "game_id": "",
         "status": "bye", "result": "bye", "points": [1, 0], "berserk": [False, False], "termination": ""}]


def tool(name, args, structured, text, is_error=False):
    res = {"content": [{"type": "text", "text": text}], "structuredContent": structured}
    if is_error:
        res["isError"] = True
    return {"request": {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": args}},
            "status": 200, "response": {"jsonrpc": "2.0", "id": 1, "result": res}}


def ev(seq, typ, **data):
    return {"seq": seq, "type": typ, "data": data, "at": "2026-12-01T18:00:00Z"}


def write(name, rec):
    (OUT / f"{name}.json").write_text(json.dumps(rec, indent=1, ensure_ascii=False) + "\n")


write("tournaments_list", tool("list_tournaments", {"status": "registering,running", "kind": "swiss"}, {"tournaments": [
    {**T, "me": ME}, {**T, "id": "t_arena0001", "name": "Arena Rapid", "kind": "arena", "status": "registering", "speed": "rapid",
                      "rounds": 0, "round": 0, "players": 2, "ends_at": None, "duration_seconds": 3600}]},
      "2 tournaments:\n  - t_k3m9x2qa Friday Blitz Swiss (swiss, running)\n  - t_arena0001 Arena Rapid (arena, registering)\n"))
write("tournament_join", tool("join_tournament", {"id": T["id"]}, {"tournament": {**T, "status": "registering", "me": {**ME, "rank": 0, "score": 0}},
      "joined": True, "next": "You are in. Call wait_for_turn: it returns the tournament events and your games."},
      "JOINED Friday Blitz Swiss.\nNEXT: call wait_for_turn.\n"))
write("tournament_withdraw", tool("withdraw_tournament", {"id": T["id"]}, {"tournament": {**T, "me": {**ME, "status": "withdrawn"}},
      "withdrawn": True}, "WITHDRAWN from Friday Blitz Swiss.\n"))
write("tournament_standings", tool("tournament_standings", {"id": T["id"]}, {"tournament": {**T, "me": ME}, "standings": STAND,
      "pairings": PAIR, "total": 4}, "Friday Blitz Swiss: round 2 of 3\n"))
write("err_tournament_conflict", tool("join_tournament", {"id": T["id"]}, {"error": {"code": "conflict",
      "message": "you are already in this tournament", "hint": "call wait_for_turn"}},
      "ERROR (conflict): you are already in this tournament\nWHAT TO DO: call wait_for_turn\n", True))
base = {"reason": "", "next": "call wait_for_turn"}
write("wait_tournament_started", tool("wait_for_turn", {}, {**base, "events": [ev(1, "tournament_started", tournament_id=T["id"], name=T["name"])]},
      "TOURNAMENT STARTED\n"))
write("wait_tournament_bye", tool("wait_for_turn", {}, {**base, "events": [ev(2, "tournament_round", tournament_id=T["id"], round=2, bye=True)]},
      "ROUND 2: you have a bye (1 point).\n"))
write("wait_tournament_finished", tool("wait_for_turn", {}, {**base, "events": [ev(9, "tournament_finished", tournament_id=T["id"], rank=3, score=1.5)]},
      "TOURNAMENT FINISHED: rank 3, score 1.5\n"))
