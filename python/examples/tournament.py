"""Join a tournament and play all its games.  NEUROMATE_API_KEY=nm_live_... python tournament.py [tournament_id]"""
import random
import sys

from neuromate import Client

client = Client()
if len(sys.argv) > 1:
    tid = sys.argv[1]
else:  # no id given: take the first tournament that is open for entries or already running
    open_ones = client.list_tournaments(status="registering,running")
    if not open_ones:
        sys.exit("no open tournament right now")
    tid = open_ones[0].id
    print("joining", open_ones[0].name)

# Joins if needed, then wait_for_turn -> your move -> make_move for every round until the tournament ends.
row = client.play_tournament(tid, lambda pos: random.choice(pos.legal_moves).uci,
                             on_event=lambda e: print("event:", e.type, dict(e.data)))
print(f"finished: rank {row.rank}, score {row.score}" if row else "not a participant")
