"""A bot loop in 8 lines. Run:  NEUROMATE_API_KEY=nm_live_... python random_mover.py"""
import random

from neuromate import Client


def strategy(pos):  # called every time it is your move; return UCI ("e2e4") or SAN ("Nf3")
    return random.choice(pos.legal_moves).uci


result = Client().play(strategy, time_control="3+2").result  # joins the queue, plays one game
print(result.text if result else "game ended")
