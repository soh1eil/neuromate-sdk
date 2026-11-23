import { describe, expect, it } from "vitest";
import {
  AuthError, Client, GameOverError, IllegalMoveError, InvalidParamsError, NoGameError, NotYourTurnError, ProtocolError, RateLimitedError, ServerError, StalePlyError, ToolError,
} from "../../src/index.js";
import { FakeServer, fixture, toolResult } from "../helpers.js";

const c = (script: Record<string, any[]>) => new FakeServer(script).client();

describe("parsing", () => {
  it("position fields from a real reply", async () => {
    const pos = await c({ get_game: [toolResult("get_game_start")] }).getGame();
    expect(pos.status).toBe("created");
    expect(pos.variant).toBe("standard");
    expect(pos.ply).toBe(0);
    expect(pos.fen.startsWith("rnbqkbnr/pppppppp")).toBe(true);
    expect(pos.yourTurn && pos.color === "white").toBe(true);
    expect(pos.legalMoves).toHaveLength(20);
    expect(pos.legalUci).toContain("e2e4");
    expect(pos.legalSan).toContain("Nf3");
    expect(pos.boardAscii).toContain("a b c d e f g h");
    expect(pos.clock.youMs).toBe(180000);
    expect(["Alice", "Bob"]).toContain(pos.opponent.name);
    expect(pos.opponent.name).not.toBe(pos.you.name);
    expect(pos.opponent.modelLabel.startsWith("Dev Model")).toBe(true);
    expect(pos.isOver).toBe(false);
    expect(pos.clock.secondsForThisMove()).not.toBeNull();
    expect(pos.deadline).not.toBeNull();
    expect(pos.text).toContain("YOUR TURN");
    expect(pos.next.startsWith("It is your turn")).toBe(true);
  });

  it("move result and replay", async () => {
    const ok = await c({ make_move: [toolResult("move_ok")] }).makeMove("e4", { clientMoveId: "w1" });
    expect(ok.move?.uci).toBe("e2e4");
    expect(ok.move?.note).toBe("centre");
    expect(ok.replayed).toBe(false);
    expect(ok.position.yourTurn).toBe(false);
    const again = await c({ make_move: [toolResult("move_replayed")] }).makeMove("e4", { clientMoveId: "w1" });
    expect(again.replayed).toBe(true);
    expect(again.move?.san).toBe("e4");
  });

  it("wait variants", async () => {
    let t = await c({ wait_for_turn: [toolResult("wait_timeout")] }).waitForTurn("g", 1);
    expect(t.timedOut).toBe(true);
    expect(t.position).toBeNull();
    t = await c({ wait_for_turn: [toolResult("wait_your_turn")] }).waitForTurn();
    expect(t.reason).toBe("your_turn");
    expect(t.position?.legalMoves.length).toBeGreaterThan(0);
    t = await c({ wait_for_turn: [toolResult("wait_draw_offer")] }).waitForTurn();
    expect(t.reason).toBe("draw_offer");
    expect(t.position?.drawOffer).toBe("from_opponent");
    t = await c({ wait_for_turn: [toolResult("wait_game_started")] }).waitForTurn();
    expect(t.events[0]?.type).toBe("game_started");
    expect(t.events[0]?.data["game_id"]).toBeTruthy();
  });

  it("lobby, queue, seeks", async () => {
    const lobby = await c({ start_here: [toolResult("start_here_idle")] }).startHere();
    expect(lobby.activeGames).toEqual([]);
    expect(lobby.next).toContain("join_queue");
    expect(lobby.tools).toContain("make_move");
    expect(lobby.you.name).toBe("Alice");
    let q = await c({ join_queue: [toolResult("join_queue_waiting")] }).joinQueue({ timeControl: "3+0" });
    expect(q.queued && q.position === null).toBe(true);
    q = await c({ join_queue: [toolResult("join_queue_matched")] }).joinQueue();
    expect(!q.queued && q.position !== null).toBe(true);
    const busy = await c({ list_challenges: [toolResult("list_challenges_busy")] }).listChallenges();
    expect(busy.incomingChallenges[0]?.sender.name).toBe("Bob");
    expect(busy.mySeeks.length).toBeGreaterThan(0);
    const s = await c({ create_seek: [toolResult("create_seek")] }).createSeek({ variant: "chess960", color: "white" });
    expect(s.id.startsWith("sk_") && s.kind === "seek").toBe(true);
    const ch = await c({ challenge: [toolResult("challenge")] }).challenge("carol", { message: "hi" });
    expect(ch.id.startsWith("ch_")).toBe(true);
  });

  it("handbook and analysis", async () => {
    const hb = await c({ get_handbook: [toolResult("handbook_index")] }).getHandbook();
    expect(hb.sections).toContain("quickstart");
    const a = await c({ get_analysis: [toolResult("analysis_unavailable")] }).getAnalysis();
    expect(a.text).toBeTruthy();
    expect(typeof a.available).toBe("boolean");
  });

  it("finished game from resign", async () => {
    const pos = await c({ resign: [toolResult("resign")] }).resign();
    expect(pos.isOver).toBe(true);
    expect(pos.result?.termination).toBe("resignation");
    expect(pos.result?.winner).toBeTruthy();
  });

  it("typed tool errors carry the teaching message", async () => {
    const e1 = await c({ make_move: [toolResult("err_illegal_move")] }).makeMove("e2e5").catch((e) => e);
    expect(e1).toBeInstanceOf(IllegalMoveError);
    expect(String(e1.message)).toContain("pawn on e2 cannot move to e5");
    expect(e1.code).toBe("illegal_move");
    expect(e1.detail).toContain("e2e4");
    expect(e1.hint).toBeTruthy();
    expect(e1.position?.yourTurn).toBe(true);
    expect([...e1.legalMoves, ...e1.position.legalUci]).toContain("e2e4");
    // the remaining illegal-move budget is in the error context
    expect(e1.context.illegal_attempts_remaining).toBe(24);
    expect(e1.context.illegal_attempts_budget).toBe(25);
    expect(e1.hint).toContain("24 remaining");
    const e2 = await c({ make_move: [toolResult("err_not_your_turn")] }).makeMove("e5").catch((e) => e);
    expect(e2).toBeInstanceOf(NotYourTurnError);
    expect(e2.hint).toContain("wait_for_turn");
    expect(await c({ make_move: [toolResult("err_stale_ply")] }).makeMove("e4", { expectedPly: 5 }).catch((e) => e)).toBeInstanceOf(StalePlyError);
    const e3 = await c({ make_move: [toolResult("err_game_over")] }).makeMove("Nf3").catch((e) => e);
    expect(e3).toBeInstanceOf(GameOverError);
    expect(e3.position.isOver).toBe(true);
    const e4 = await c({ make_move: [toolResult("err_unknown_param")] }).makeMove("e4").catch((e) => e);
    expect(e4.constructor).toBe(InvalidParamsError);
    expect(e4.hint).toContain("allowed parameters");
    expect(e4.code).toBe("invalid_params");
    const e5 = await c({ challenge: [toolResult("err_unknown_player")] }).challenge("nobody").catch((e) => e);
    expect(e5).toBeInstanceOf(ToolError);
    expect(e5.code).toBe("unknown_player");
    const e6 = await c({ get_game: [toolResult("err_no_game")] }).getGame().catch((e) => e);
    expect(e6).toBeInstanceOf(NoGameError);
    expect(e6.hint).toContain("join_queue");
    // no game_id after the game ended: the server resolves to the most recent game
    const pos = await c({ get_game: [toolResult("get_game_default_finished")] }).getGame();
    expect(pos.isOver).toBe(true);
    expect(pos.text).toContain("most recent game");
    const e7 = await c({ make_move: [toolResult("err_game_over_no_id")] }).makeMove("Nf3").catch((e) => e);
    expect(e7).toBeInstanceOf(GameOverError);
    expect(e7.context.game_id).toBeTruthy();
    expect(e7.hint).toBeTruthy();
  });

  it("protocol, http and limits", async () => {
    const err = fixture("rpc_unknown_tool").response.error;
    const e = await c({ nope: [{ __rpc_error__: err }] }).callTool("nope").catch((x) => x);
    expect(e).toBeInstanceOf(ProtocolError);
    expect(e.code).toBe(-32602);
    expect(e.message).toContain("Did you mean make_move");
    const a = await c({ whoami: [[401, fixture("http_401").response]] }).whoami().catch((x) => x);
    expect(a).toBeInstanceOf(AuthError);
    expect(a.status).toBe(401);
    expect(a.code).toBe("unauthorized");
    expect(a.hint).toContain("Authorization: Bearer");
    expect(a.message).toContain("missing or invalid agent API key");
    const old = await c({ whoami: [[401, "missing or invalid agent API key\n"]] }).whoami().catch((x) => x);
    expect(old).toBeInstanceOf(AuthError);
    expect(old.message).toContain("missing or invalid");
    expect(e.errorCode).toBe("unknown_tool");
    expect(e.hint).toBeTruthy();
    const text = { content: [{ type: "text", text: "RATE LIMITED: too many requests. Wait 7 seconds, then retry." }], isError: true };
    const r = await c({ whoami: [text] }).whoami().catch((x) => x);
    expect(r).toBeInstanceOf(RateLimitedError);
    expect(r.retryAfter).toBe(7);
    const structured = { content: [{ type: "text", text: "ERROR (rate_limited): too many requests\n" }], isError: true,
      structuredContent: { error: { code: "rate_limited", message: "too many requests", hint: "wait", retry_after_seconds: 4 } } };
    expect((await c({ whoami: [structured] }).whoami().catch((x) => x)).retryAfter).toBe(4);
    expect((await c({ whoami: [[429, "slow down"]] }).whoami().catch((x) => x)).retryAfter).toBeGreaterThanOrEqual(1);
    const internal = { content: [{ type: "text", text: "INTERNAL ERROR: something went wrong on our side" }], isError: true, structuredContent: { error: { message: "internal error" } } };
    expect(await c({ whoami: [internal] }).whoami().catch((x) => x)).toBeInstanceOf(ServerError);
    const coded = { content: [{ type: "text", text: "ERROR (internal): something went wrong" }], isError: true,
      structuredContent: { error: { code: "internal", message: "something went wrong on our side", hint: "retry" } } };
    expect(await c({ whoami: [coded] }).whoami().catch((x) => x)).toBeInstanceOf(ServerError);
    expect(await c({ whoami: [[503, "down"]] }).whoami().catch((x) => x)).toBeInstanceOf(ServerError);
  });

  it("unknown game option is a clear TypeError", async () => {
    await expect(Promise.resolve().then(() => c({}).createSeek({ vairant: "chess960" } as never))).rejects.toThrow(/unknown game option/);
  });

  it("requests are well formed", async () => {
    const f = new FakeServer({ make_move: [toolResult("move_ok")], join_queue: [toolResult("join_queue_waiting")] });
    const cl = f.client();
    await cl.makeMove("e4", { gameId: "g1", note: "n", expectedPly: 0 });
    await cl.joinQueue({ variant: "chess960", timeControl: "3+2", rated: true, pool: "agents", incrementSeconds: 1 });
    const [[n1, a1], [n2, a2]] = f.calls as [[string, any], [string, any]];
    expect(n1).toBe("make_move");
    expect(a1.game_id).toBe("g1");
    expect(a1.note).toBe("n");
    expect(a1.expected_ply).toBe(0);
    expect(a1.client_move_id.length).toBeGreaterThanOrEqual(8);
    expect(n2).toBe("join_queue");
    expect(a2).toEqual({ variant: "chess960", time_control: "3+2", rated: true, increment_seconds: 1, pool: "agents" });
  });

  it("handles text/event-stream replies and sessions", async () => {
    const seen: Array<Record<string, string>> = [];
    const fetchFn = async (_u: unknown, init?: RequestInit) => {
      const msg = JSON.parse(String(init?.body));
      seen.push(init?.headers as Record<string, string>);
      if (msg.id === undefined) return new Response(null, { status: 202 });
      const result = msg.method === "initialize" ? { serverInfo: { name: "x" } } : toolResult("whoami_idle");
      const body = `event: message\ndata: {"jsonrpc":"2.0","method":"notifications/progress"}\n\nevent: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", id: msg.id, result })}\n\n`;
      return new Response(body, { headers: { "content-type": "text/event-stream", "mcp-session-id": "sess-1" } });
    };
    const cl = new Client({ apiKey: "nm_live_x", baseUrl: "http://x/mcp", fetch: fetchFn as typeof fetch });
    expect((await cl.whoami()).you.name).toBe("Alice");
    expect(seen.at(-1)?.["Mcp-Session-Id"]).toBe("sess-1");
  });

  it("api key from env, missing key, key never in toString", () => {
    const old = process.env["NEUROMATE_API_KEY"];
    delete process.env["NEUROMATE_API_KEY"];
    expect(() => new Client()).toThrow(/NEUROMATE_API_KEY/);
    process.env["NEUROMATE_API_KEY"] = "nm_live_abc";
    process.env["NEUROMATE_URL"] = "http://x/mcp";
    const cl = new Client();
    expect(cl.apiKey).toBe("nm_live_abc");
    expect(cl.baseUrl).toBe("http://x/mcp");
    expect(String(cl)).not.toContain("nm_live_abc");
    delete process.env["NEUROMATE_URL"];
    if (old === undefined) delete process.env["NEUROMATE_API_KEY"]; else process.env["NEUROMATE_API_KEY"] = old;
  });
});
