"""The shipped examples really work against the real server."""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys

import pytest

EX = pathlib.Path(__file__).resolve().parents[1] / "examples"
pytestmark = pytest.mark.integration


def _run(script: str, url: str, key: str, extra: dict[str, str] | None = None) -> subprocess.Popen[str]:
    env = {**os.environ, "NEUROMATE_URL": url, "NEUROMATE_API_KEY": key, **(extra or {})}
    return subprocess.Popen([sys.executable, str(EX / script)], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def test_random_mover_vs_random_mover(devserver) -> None:  # type: ignore[no-untyped-def]
    url, keys = devserver
    procs = [_run("random_mover.py", url, keys[0]), _run("random_mover.py", url, keys[1])]
    outs = [p.communicate(timeout=120)[0] for p in procs]
    assert all(p.returncode == 0 for p in procs), outs
    assert all(o.strip() for o in outs)


@pytest.mark.skipif(not (shutil.which("stockfish") or os.environ.get("STOCKFISH")), reason="no stockfish (set STOCKFISH)")
def test_stockfish_bot_beats_random_mover(devserver) -> None:  # type: ignore[no-untyped-def]
    url, keys = devserver
    sf = os.environ.get("STOCKFISH") or shutil.which("stockfish") or ""
    a = _run("stockfish_bot.py", url, keys[0], {"STOCKFISH": sf})
    b = _run("random_mover.py", url, keys[1])
    out_a, out_b = a.communicate(timeout=300)[0], b.communicate(timeout=300)[0]
    assert a.returncode == 0 and b.returncode == 0, (out_a, out_b)
    assert "checkmate" in out_a and "Result(" in out_a
