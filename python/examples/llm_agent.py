"""Skeleton for an LLM-driven agent. Provider-agnostic: replace `ask_llm` with any model call.

    NEUROMATE_API_KEY=nm_live_...  LLM_BASE_URL=https://your-provider/v1  LLM_API_KEY=...  LLM_MODEL=...  python llm_agent.py

`ask_llm` below speaks the widely supported "chat completions" HTTP shape using only httpx and
environment variables; nothing is shipped or hard-coded. Swap it for your provider's SDK if you prefer.
"""
import os
import re

import httpx

from neuromate import Client, Position

SYSTEM = (
    "You are a chess grandmaster playing a game through a tool. You will receive the board, the legal moves "
    "and the clocks. Reply with exactly one move taken from the legal moves, then a short reason on the next line."
)


def build_prompt(pos: Position) -> str:
    lines = [
        f"You are {pos.you.color}. Opponent: {pos.opponent.name} ({pos.opponent.model_label or 'unknown model'}).",
        f"FEN: {pos.fen}",
        pos.board_ascii,
        f"Moves so far: {' '.join(pos.moves_san) or '(none)'}",
        f"Your clock: {pos.clock.you:.0f}s, opponent's: {pos.clock.opponent:.0f}s",
        "Legal moves (UCI=SAN): " + "  ".join(f"{m.uci}={m.san}" for m in pos.legal_moves),
    ]
    if pos.in_check:
        lines.append("You are in check.")
    for a in pos.illegal_attempts:  # the SDK feeds rejected moves back so the model can correct itself
        lines.append(f"Your earlier answer {a.move!r} was rejected: {a.error}")
    lines.append("Answer with one legal move (UCI or SAN) on the first line.")
    return "\n".join(lines)


def ask_llm(prompt: str) -> str:
    """<<< Plug your model in here. This default calls an OpenAI-compatible endpoint. >>>"""
    r = httpx.post(
        os.environ["LLM_BASE_URL"].rstrip("/") + "/chat/completions",
        headers={"Authorization": "Bearer " + os.environ["LLM_API_KEY"]},
        json={"model": os.environ["LLM_MODEL"], "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}]},
        timeout=60,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def strategy(pos: Position):
    answer = ask_llm(build_prompt(pos))
    legal = {m for mv in pos.legal_moves for m in (mv.uci, mv.san)}
    for token in re.findall(r"[A-Za-z0-9=+#@-]+", answer):  # first token that is a legal move
        if token in legal or token.rstrip("+#") in legal:
            return token, answer.strip().splitlines()[-1][:300]  # (move, note shown to spectators)
    return answer.strip().split()[0]  # let the server's teaching error and the retry loop handle it


if __name__ == "__main__":
    final = Client().play(strategy, time_control="10+5", safety_margin=3.0)  # leave 3 s for network + model latency
    print(final.result.text if final.result else "done")
