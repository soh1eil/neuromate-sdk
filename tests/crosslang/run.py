#!/usr/bin/env python3
"""Cross-language integration: SDKs from different languages play each other to checkmate on the real Go handler.

    python3 sdk/tests/crosslang/run.py            # all pairs: python-vs-typescript, go-vs-python (+ ts-vs-go)

Builds the dev server, the TypeScript package and the Go player first. Needs go, node/npm, uv.
"""
import json, os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[3]  # repo root
SDK = ROOT / "sdk"
ENV = {**os.environ, "UV_CACHE_DIR": "/data2/neuromate-build/uv", "npm_config_cache": "/data2/neuromate-build/npm"}
for line in pathlib.Path("/data2/neuromate-build/env.sh").read_text().splitlines() if pathlib.Path("/data2/neuromate-build/env.sh").exists() else []:
    m = re.match(r"export (\w+)=(.*)", line)
    if m:
        ENV[m.group(1)] = m.group(2)
tmp = pathlib.Path(ENV.get("TMPDIR", "/tmp")) / "nm-crosslang"
tmp.mkdir(parents=True, exist_ok=True)
nice = ["nice", "-n", "19"]


def sh(cmd, cwd):
    subprocess.run(nice + cmd, cwd=cwd, env=ENV, check=True)


sh(["go", "build", "-o", str(tmp / "devserver"), "./cmd/mcp-devserver"], ROOT / "server")
sh(["go", "build", "-o", str(tmp / "goplayer"), "./cmd/crosslang-player"], SDK / "go")
sh(["npm", "run", "build"], SDK / "typescript")

PLAYERS = {
    "python": (["uv", "run", "--project", str(SDK / "python"), "python", str(SDK / "tests/crosslang/player.py")], SDK / "python"),
    "typescript": (["node", str(SDK / "typescript/tests/crosslang-player.mjs")], SDK / "typescript"),
    "go": ([str(tmp / "goplayer")], SDK / "go"),
}


def run_pair(a: str, b: str) -> None:
    srv = subprocess.Popen([str(tmp / "devserver")], env={**ENV, "NM_DEV": "1"}, stdout=subprocess.PIPE, text=True)
    try:
        m = re.search(r"url=(\S+) keys=(\S+)", srv.stdout.readline())
        url, keys = m.group(1), m.group(2).split(",")
        procs = []
        for who, key in ((a, keys[0]), (b, keys[1])):
            cmd, cwd = PLAYERS[who]
            procs.append((who, subprocess.Popen(cmd + [url, key], cwd=cwd, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)))
        results = []
        for who, p in procs:
            out, err = p.communicate(timeout=120)
            assert p.returncode == 0, f"{who} failed:\n{err}\n{out}"
            results.append((who, json.loads(out.strip().splitlines()[-1])))
        (wa, ra), (wb, rb) = results
        assert ra == rb, f"players disagree: {results}"
        assert ra["termination"] == "checkmate" and ra["winner"] == "white" and ra["moves"][-1] == "Qxf7#", ra
        print(f"OK  {a} vs {b}: {ra['score']} by {ra['termination']} in {len(ra['moves'])} plies ({ra['game_id']})")
    finally:
        srv.terminate()
        srv.wait(10)


pairs = [("python", "typescript"), ("go", "python"), ("typescript", "go")]
if len(sys.argv) > 1:
    pairs = [tuple(a.split("-vs-")) for a in sys.argv[1:]]
for a, b in pairs:
    run_pair(a, b)
