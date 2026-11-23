from __future__ import annotations

import copy
import json
import os
import pathlib
import re
import shutil
import subprocess
import time
from typing import Any, Callable

import httpx
import pytest

from neuromate import AsyncClient, Client

TESTDATA = pathlib.Path(__file__).resolve().parents[2] / "testdata"
REPO = pathlib.Path(__file__).resolve().parents[3]


def fixture(name: str) -> dict[str, Any]:
    return json.loads((TESTDATA / f"{name}.json").read_text())


def tool_result(name: str) -> dict[str, Any]:
    return fixture(name)["response"]["result"]


def with_structured(name: str, **patch: Any) -> dict[str, Any]:
    """A recorded result with its structuredContent patched (deep-merged one level)."""
    r = copy.deepcopy(tool_result(name))
    r["structuredContent"].update(patch)
    return r


def wait_result(reason: str, game_from: str, **game_patch: Any) -> dict[str, Any]:
    """Build a wait_for_turn result around the game of a recorded fixture."""
    g = copy.deepcopy(tool_result(game_from)["structuredContent"]["game"])
    g.update(game_patch)
    return {"content": [{"type": "text", "text": "WAIT"}], "structuredContent": {"reason": reason, "events": [], "game": g}}


class FakeServer:
    """Scripted MCP server behind httpx.MockTransport.

    ``script[tool]`` is a list of replies consumed in order (the last one repeats). A reply is a
    CallToolResult dict, an ``Exception`` (raised as a transport error), or ``(status, body)`` for HTTP errors.
    """

    def __init__(self, script: dict[str, list[Any]]) -> None:
        self.script = {k: list(v) for k, v in script.items()}
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        msg = json.loads(request.content)
        method = msg["method"]
        if method == "initialize":
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": msg["id"], "result": fixture("initialize")["response"]["result"]})
        if "id" not in msg:
            return httpx.Response(202)
        if method == "tools/list":
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": msg["id"], "result": fixture("tools_list")["response"]["result"]})
        name, args = msg["params"]["name"], msg["params"]["arguments"]
        self.calls.append((name, args))
        queue = self.script.get(name)
        if not queue:
            raise AssertionError(f"unexpected tool call {name} {args}")
        reply = queue.pop(0) if len(queue) > 1 else queue[0]
        if isinstance(reply, Exception):
            raise reply
        if isinstance(reply, tuple):
            if isinstance(reply[1], (dict, list)):
                return httpx.Response(reply[0], json=reply[1])
            return httpx.Response(reply[0], text=reply[1])
        if "__rpc_error__" in reply:
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": msg["id"], "error": reply["__rpc_error__"]})
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": msg["id"], "result": reply})

    def client(self) -> Client:
        return Client("nm_live_test", "http://fake/mcp", http=httpx.Client(transport=httpx.MockTransport(self.handler)))

    def aclient(self) -> AsyncClient:
        async def ah(request: httpx.Request) -> httpx.Response:
            return self.handler(request)

        return AsyncClient("nm_live_test", "http://fake/mcp", http=httpx.AsyncClient(transport=httpx.MockTransport(ah)))

    def names(self) -> list[str]:
        return [n for n, _ in self.calls]


@pytest.fixture
def fake() -> Callable[[dict[str, list[Any]]], FakeServer]:
    return FakeServer


@pytest.fixture(scope="session")
def devserver(tmp_path_factory: pytest.TempPathFactory):  # type: ignore[no-untyped-def]
    """Build and start the real Go MCP dev server; yields (url, keys)."""
    if shutil.which("go") is None and not os.environ.get("NM_DEVSERVER_BIN"):
        pytest.skip("go toolchain not available (set NM_DEVSERVER_BIN to a built mcp-devserver)")
    binary = os.environ.get("NM_DEVSERVER_BIN")
    if not binary:
        binary = str(tmp_path_factory.mktemp("devserver") / "mcp-devserver")
        subprocess.run(["nice", "-n", "19", "go", "build", "-o", binary, "./cmd/mcp-devserver"], cwd=REPO / "server", check=True,
                       env={**os.environ})
    proc = subprocess.Popen([binary], env={**os.environ, "NM_DEV": "1"}, stdout=subprocess.PIPE, text=True)
    assert proc.stdout is not None
    line = proc.stdout.readline()
    m = re.search(r"url=(\S+) keys=(\S+)", line)
    assert m, f"unexpected devserver output: {line!r}"
    yield m.group(1), m.group(2).split(",")
    proc.terminate()
    proc.wait(timeout=10)
    _ = time
