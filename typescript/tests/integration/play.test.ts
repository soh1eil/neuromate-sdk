import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Client, GameOverError, IllegalMoveError, playMany, type Position } from "../../src/index.js";
import { haveGo, startDevServer, type Dev } from "./devserver.js";

const EX = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "examples");
// Scholar's mate keyed by ply. White plays SAN, black plays UCI: both notations are exercised.
const SCRIPT: Record<number, string> = { 0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#" };
const scripted = (p: Position) => SCRIPT[p.ply]!;

describe.skipIf(!haveGo())("integration against the real Go MCP handler", () => {
  let dev: Dev;
  beforeAll(async () => { dev = await startDevServer(); });
  afterAll(() => dev?.stop());
  const cl = (i: number) => new Client({ apiKey: dev.keys[i]!, baseUrl: dev.url });

  it("two TS clients play Scholar's mate to checkmate", async () => {
    const [a, b] = await Promise.all([cl(0).play(scripted, { timeControl: "3+2" }), cl(1).play(scripted, { timeControl: "3+2" })]);
    for (const p of [a, b]) {
      expect(p.isOver).toBe(true);
      expect(p.result?.termination).toBe("checkmate");
      expect(p.result?.winner).toBe("white");
      expect(p.movesSan).toEqual(["e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"]);
    }
  });

  it("two games in a row ignore leftover events", async () => {
    const [a, b] = await Promise.all([playMany(cl(0), scripted, 2, { timeControl: "untimed" }), playMany(cl(1), scripted, 2, { timeControl: "untimed" })]);
    const ids = new Set([...a, ...b].map((p) => p.gameId));
    expect(ids.size).toBe(2);
    expect([...a, ...b].every((p) => p.result?.termination === "checkmate")).toBe(true);
  });

  it("manual calls and typed errors against the real server", async () => {
    const a = cl(0), b = cl(2); // alice and carol
    const lobby = await a.startHere();
    expect(lobby.you.name).toBe("Alice");
    expect(lobby.tools).toContain("make_move");
    expect((await a.listTools())[0]!["name"]).toBe("start_here");
    const sk = await a.createSeek({ timeControl: "5+0", color: "white" });
    const pos = await b.acceptChallenge(sk.id);
    expect(pos.opponent.name).toBe("Alice");
    const gid = pos.gameId;
    const t = await a.waitForTurn(gid, 2);
    expect(t.reason).toBe("your_turn");
    expect(t.position?.legalMoves).toHaveLength(20);
    const e = await a.makeMove("e2e5", { gameId: gid }).catch((x) => x);
    expect(e).toBeInstanceOf(IllegalMoveError);
    expect(e.message).toContain("legal pawn moves from e2");
    expect(e.legalMoves).toContain("e2e4");
    const first = await a.makeMove("e4", { gameId: gid, clientMoveId: "same" });
    const again = await a.makeMove("e4", { gameId: gid, clientMoveId: "same" });
    expect(first.replayed).toBe(false);
    expect(again.replayed).toBe(true);
    expect((await b.waitForTurn(gid, 1)).reason).toBe("your_turn");
    expect((await a.waitForTurn(gid, 1)).timedOut).toBe(true);
    expect((await b.makeMove("e5", { gameId: gid })).move).toBeTruthy();
    await a.offerDraw(gid);
    expect((await b.waitForTurn(gid, 2)).reason).toBe("draw_offer");
    await b.respondDraw(false, gid);
    const res = await b.resign(gid);
    expect(res.isOver).toBe(true);
    expect(res.result?.termination).toBe("resignation");
    expect(res.result?.winner).toBe("white");
    expect(await a.makeMove("Nf3", { gameId: gid }).catch((x) => x)).toBeInstanceOf(GameOverError);
    expect((await a.getAnalysis(gid)).text).toBeTruthy();
    expect((await a.getHandbook()).sections).toContain("quickstart");
  });

  function run(script: string, key: string, extra: Record<string, string> = {}): Promise<{ code: number | null; out: string }> {
    return new Promise((resolve) => {
      const p = spawn("npx", ["tsx", join(EX, script)], { env: { ...process.env, NEUROMATE_URL: dev.url, NEUROMATE_API_KEY: key, ...extra } });
      let out = "";
      p.stdout.on("data", (d) => (out += d));
      p.stderr.on("data", (d) => (out += d));
      p.on("close", (code) => resolve({ code, out }));
    });
  }

  it("random_mover example runs against the real server", async () => {
    const [a, b] = await Promise.all([run("random_mover.ts", dev.keys[0]!), run("random_mover.ts", dev.keys[1]!)]);
    expect([a.code, b.code], a.out + b.out).toEqual([0, 0]);
    expect(a.out.trim()).toBeTruthy();
  }, 120000);

  const sf = process.env["STOCKFISH"] ?? "/data2/neuromate-build/engines/bin/stockfish";
  it.skipIf(!existsSync(sf))("stockfish_bot example beats random_mover", async () => {
    const [a, b] = await Promise.all([run("stockfish_bot.ts", dev.keys[0]!, { STOCKFISH: sf }), run("random_mover.ts", dev.keys[1]!)]);
    expect([a.code, b.code], a.out + b.out).toEqual([0, 0]);
    expect(a.out).toContain("checkmate");
  }, 120000);
});
