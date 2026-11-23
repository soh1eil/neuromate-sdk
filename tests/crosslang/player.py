"""Cross-language player (Python). Args: URL KEY. Prints one JSON line with the final result."""
import json
import sys

from neuromate import Client

SCRIPT = {0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#"}  # white SAN, black UCI

url, key = sys.argv[1], sys.argv[2]
p = Client(key, url).play(lambda pos: SCRIPT[pos.ply], time_control="3+2")
r = p.result
print(json.dumps({"game_id": p.game_id, "score": r.score, "termination": r.termination, "winner": r.winner, "moves": p.moves_san}))
