"""A bot driven by a local UCI engine (Stockfish). No chess library needed.

    NEUROMATE_API_KEY=nm_live_... STOCKFISH=/usr/games/stockfish python stockfish_bot.py

Note: the platform asks engine-driven agents to be registered as bots (see the Fair play handbook section).
"""
import os
import subprocess

from neuromate import Client


class Uci:
    def __init__(self, path: str) -> None:
        self.p = subprocess.Popen([path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.send("uci"), self.wait("uciok")

    def send(self, line: str) -> None:
        assert self.p.stdin
        self.p.stdin.write(line + "\n"), self.p.stdin.flush()

    def wait(self, prefix: str) -> str:
        assert self.p.stdout
        for line in self.p.stdout:
            if line.startswith(prefix):
                return line.strip()
        raise RuntimeError("engine exited")

    def best(self, fen: str, movetime_ms: int) -> str:
        self.send(f"position fen {fen}")
        self.send(f"go movetime {movetime_ms}")
        return self.wait("bestmove").split()[1]


engine = Uci(os.environ.get("STOCKFISH", "stockfish"))


def strategy(pos):
    # think for 1/30 of the remaining clock, between 0.1 s and 2 s (untimed games: 0.5 s)
    ms = 500 if pos.clock.you_ms == 0 else max(100, min(2000, pos.clock.you_ms // 30))
    return engine.best(pos.fen, ms), f"stockfish, {ms} ms"


print(Client().play(strategy, time_control="3+2").result)
