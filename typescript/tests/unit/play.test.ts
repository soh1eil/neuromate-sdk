import { describe, expect, it } from "vitest";
import { NeuromateError, Resign, TransportError, type Position } from "../../src/index.js";
import { FakeServer, toolResult, waitResult, type Reply } from "../helpers.js";

const YOUR_TURN = waitResult("your_turn", "get_game_start");
const GID: string = YOUR_TURN["structuredContent"].game.game_id;
const GAME_OVER = waitResult("game_over", "resign", { game_id: GID });
const BASE: Record<string, Reply[]> = { whoami: [toolResult("whoami_idle")], join_queue: [toolResult("join_queue_waiting")] };
const base = (more: Record<string, Reply[]>) => ({ ...BASE, ...more });
const moves = (f: FakeServer) => f.calls.filter(([n]) => n === "make_move").map(([, a]) => a);

describe("play", () => {
  it("queues then plays until game over", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [toolResult("move_ok")] }));
    const seen: Position[] = [];
    const final = await f.client().play((p) => { seen.push(p); return { move: "e4", note: "centre" }; }, { timeControl: "3+0" });
    expect(final.isOver).toBe(true);
    expect(seen).toHaveLength(1);
    expect(f.names()).toEqual(["whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"]);
    const mv = f.calls[3]![1];
    expect(mv).toMatchObject({ move: "e4", note: "centre", expected_ply: 0 });
    expect(String(mv["client_move_id"]).startsWith(seen[0]!.gameId)).toBe(true);
    expect(f.calls[1]![1]).toEqual({ time_control: "3+0" });
  });

  it("resumes an active game without queueing", async () => {
    const busy = toolResult("whoami_idle");
    busy.structuredContent = { ...busy.structuredContent, active_games: [{ game_id: GID, opponent: "Bob", color: "white", your_turn: true, ply: 0, variant: "standard" }] };
    const f = new FakeServer({ whoami: [busy], wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [toolResult("move_ok")] });
    await f.client().play(() => "e4");
    expect(f.names()).not.toContain("join_queue");
    expect(f.calls[1]![1]["game_id"]).toBe(GID);
  });

  it("drains stale lobby events before looking for a game", async () => {
    const stale = toolResult("whoami_idle");
    stale.structuredContent = { ...stale.structuredContent, events: [{ seq: 4, type: "game_over", data: {}, at: "" }] };
    const f = new FakeServer(base({ whoami: [stale], list_challenges: [toolResult("list_challenges_empty")], wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [toolResult("move_ok")] }));
    await f.client().play(() => "e4");
    expect(f.calls[1]).toEqual(["list_challenges", { ack: 4 }]);
  });

  it("feeds illegal moves back and retries with a new id", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [toolResult("err_illegal_move"), toolResult("move_ok")] }));
    const tries: string[][] = [];
    await f.client().play((p) => { tries.push(p.illegalAttempts.map((a) => a.error)); return p.illegalAttempts.length ? "e2e4" : "e2e5"; });
    expect(tries[0]).toEqual([]);
    expect(tries[1]![0]).toContain("pawn on e2 cannot move to e5");
    expect(new Set(moves(f).map((a) => a["client_move_id"])).size).toBe(2);
  });

  it("falls back to a legal move once the illegal budget is used", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [...Array(3).fill(toolResult("err_illegal_move")), toolResult("move_ok")] }));
    await f.client().play(() => "zzzz", { maxIllegal: 3 });
    const sent = moves(f).map((a) => a["move"]);
    expect(sent.slice(0, 3)).toEqual(["zzzz", "zzzz", "zzzz"]);
    expect(YOUR_TURN["structuredContent"].game.legal_moves.map((m: { uci: string }) => m.uci)).toContain(sent[3]);
  });

  it("retries transient errors with the same clientMoveId", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [new TypeError("fetch failed"), [503, "unavailable"], toolResult("move_ok")] }));
    const t0 = Date.now();
    await f.client().play(() => "e4");
    expect(moves(f)).toHaveLength(3);
    expect(new Set(moves(f).map((a) => a["client_move_id"])).size).toBe(1);
    expect(Date.now() - t0).toBeLessThan(5000);
  });

  it("gives up after maxRetries", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN], make_move: [new TypeError("down")] }));
    await expect(f.client().play(() => "e4", { maxRetries: 1 })).rejects.toBeInstanceOf(TransportError);
  });

  it("re-reads the game after stale_ply", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN, GAME_OVER], make_move: [toolResult("err_stale_ply"), toolResult("move_ok")], get_game: [toolResult("get_game_start")] }));
    let n = 0;
    await f.client().play(() => { n++; return "e4"; });
    expect(f.names()).toContain("get_game");
    expect(n).toBe(2);
  });

  it("a slow strategy hits the clock and the fallback move is sent", async () => {
    const g = { clocks: { you_ms: 2500, opponent_ms: 9000, running: "you", your_turn_time_left_ms: 1500 } };
    const f = new FakeServer(base({ wait_for_turn: [waitResult("your_turn", "get_game_start", g), GAME_OVER], make_move: [toolResult("move_ok")] }));
    const t0 = Date.now();
    await f.client().play(() => new Promise<string>((r) => setTimeout(() => r("e4"), 5000)), { safetyMargin: 1, fallback: () => "d2d4" });
    expect(Date.now() - t0).toBeLessThan(3000);
    expect(moves(f).map((a) => a["move"])).toEqual(["d2d4"]);
  });

  it("no time left skips the strategy", async () => {
    const g = { clocks: { you_ms: 400, running: "you", your_turn_time_left_ms: 400 } };
    const f = new FakeServer(base({ wait_for_turn: [waitResult("your_turn", "get_game_start", g), GAME_OVER], make_move: [toolResult("move_ok")] }));
    let called = 0;
    await f.client().play(() => { called++; return "e4"; }, { fallback: () => "c2c4" });
    expect(called).toBe(0);
    expect(moves(f).map((a) => a["move"])).toEqual(["c2c4"]);
  });

  it("resigns when the strategy throws Resign", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN], resign: [toolResult("resign")] }));
    const final = await f.client().play(() => { throw new Resign(); });
    expect(final.isOver).toBe(true);
    expect(f.names()).toContain("resign");
  });

  it("declines a draw offer by default and can accept", async () => {
    const offer = toolResult("wait_draw_offer");
    const f = new FakeServer(base({ wait_for_turn: [offer, GAME_OVER], respond_draw: [toolResult("respond_draw_decline")] }));
    await f.client().play(() => "e4");
    expect(f.calls[3]![0]).toBe("respond_draw");
    expect(f.calls[3]![1]["accept"]).toBe(false);
    const f2 = new FakeServer(base({ wait_for_turn: [offer, GAME_OVER], respond_draw: [toolResult("respond_draw_decline")] }));
    await f2.client().play(() => "e4", { onDrawOffer: () => true });
    expect(f2.calls[3]![1]["accept"]).toBe(true);
  });

  it("explains a bad strategy return value", async () => {
    const f = new FakeServer(base({ wait_for_turn: [YOUR_TURN] }));
    await expect(f.client().play((() => undefined) as never)).rejects.toThrow(/your strategy returned/);
    await expect(f.client().play((() => undefined) as never)).rejects.toBeInstanceOf(NeuromateError);
  });

  it("aborting while queued leaves the queue", async () => {
    const ctl = new AbortController();
    const f = new FakeServer(base({ wait_for_turn: [toolResult("wait_timeout")], leave_queue: [toolResult("leave_queue")] }));
    const cl = f.client();
    const orig = cl.waitForTurn.bind(cl);
    cl.waitForTurn = async (...a) => { ctl.abort(); return orig(...a); };
    await expect(cl.play(() => "e4", { signal: ctl.signal })).rejects.toThrow(/aborted/);
    expect(f.names()).toContain("leave_queue");
  });
});
