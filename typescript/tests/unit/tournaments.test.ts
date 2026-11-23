import { describe, expect, it } from "vitest";
import { ConflictError, NotAllowedError, type Position } from "../../src/index.js";
import { FakeServer, toolResult, waitResult, type Reply } from "../helpers.js";

const TID = "t_k3m9x2qa";
const TREF = { id: TID, name: "Friday Blitz Swiss", round: 1 };

const standings = (status = "running", joined = true): Reply => {
  const r = structuredClone(toolResult("tournament_standings"));
  r.structuredContent.tournament.status = status;
  if (!joined) delete r.structuredContent.tournament.me;
  return r;
};
const gameWait = (reason: string, gid: string, patch: Record<string, unknown> = {}) => waitResult(reason, "get_game_start", { game_id: gid, tournament: TREF, ...patch });
const over = (gid: string) => {
  const r = waitResult("game_over", "resign", { game_id: gid, tournament: TREF });
  r.structuredContent.events = [{ seq: 5, type: "tournament_game_over", data: { tournament_id: TID }, at: "" }];
  return r;
};
const timeout = () => toolResult("wait_timeout");
const BASE: Record<string, Reply[]> = { whoami: [toolResult("whoami_idle")] };
const W = "wait_for_turn", M = "make_move", S = "tournament_standings";

describe("tournament tools", () => {
  it("parse list / join / withdraw / standings", async () => {
    const f = new FakeServer({
      list_tournaments: [toolResult("tournaments_list")], join_tournament: [toolResult("tournament_join")],
      withdraw_tournament: [toolResult("tournament_withdraw")], tournament_standings: [toolResult("tournament_standings")],
    });
    const c = f.client();
    const ts = await c.listTournaments({ status: "registering,running", kind: "swiss" });
    expect(ts.map((t) => t.id)).toEqual([TID, "t_arena0001"]);
    expect(ts[0]!.me?.rank).toBe(3);
    expect(ts[0]!.joined && !ts[0]!.isOver).toBe(true);
    expect(ts[0]!.timeControl["initial_ms"]).toBe(180000);
    expect(ts[1]!.kind).toBe("arena");
    expect(f.calls.at(-1)).toEqual(["list_tournaments", { status: "registering,running", kind: "swiss" }]);
    const j = await c.joinTournament(TID);
    expect(j.joined).toBe(true);
    expect(j.tournament.status).toBe("registering");
    expect(j.next).toContain("wait_for_turn");
    const w = await c.withdrawTournament(TID);
    expect(w.withdrawn).toBe(true);
    expect(w.tournament.joined).toBe(false);
    const s = await c.tournamentStandings(TID, 2);
    expect(f.calls.at(-1)).toEqual([S, { id: TID, round: 2 }]);
    expect(s.total).toBe(4);
    expect(s.standings.map((r) => r.rank)).toEqual([1, 2, 3, 4]);
    expect(s.standings[0]!.tiebreaks["buchholz"]).toBe(3.5);
    expect(s.pairings[0]!.white?.name).toBe("Alice");
    expect(s.pairings[0]!.isBye).toBe(false);
    expect(s.pairings[1]!.isBye).toBe(true);
    expect(s.pairings[1]!.black).toBeNull();
    expect(s.pairings[1]!.points).toEqual([1, 0]);
  });

  it("maps a conflict to ConflictError and parses the tournament of a game", async () => {
    const f = new FakeServer({ join_tournament: [toolResult("err_tournament_conflict")] });
    await expect(f.client().joinTournament(TID)).rejects.toBeInstanceOf(ConflictError);
    const turn = await new FakeServer({ wait_for_turn: [gameWait("your_turn", "g1")] }).client().waitForTurn();
    expect(turn.position?.tournament).toEqual(TREF);
  });
});

describe("playTournament", () => {
  it("plays two rounds with a bye and a finish event", async () => {
    const f = new FakeServer({
      ...BASE, [S]: [standings("running", false), standings()], join_tournament: [toolResult("tournament_join")],
      [W]: [toolResult("wait_tournament_started"), gameWait("your_turn", "g1"), over("g1"), toolResult("wait_tournament_bye"),
        gameWait("your_turn", "g2"), over("g2"), toolResult("wait_tournament_finished")],
      [M]: [toolResult("move_ok")],
    });
    const seen: string[] = [];
    const events: string[] = [];
    const row = await f.client().playTournament(TID, (p: Position) => { seen.push(p.gameId); return "e4"; }, { onEvent: (e) => events.push(e.type) });
    expect(row).toMatchObject({ rank: 3, score: 1.5, name: "Alice" });
    expect(seen).toEqual(["g1", "g2"]);
    expect(f.names()).toEqual([S, "join_tournament", W, W, M, W, S, W, W, M, W, S, W, S, "whoami"]);
    expect(events).toEqual(["tournament_started", "tournament_game_over", "tournament_round", "tournament_game_over", "tournament_finished"]);
    expect(f.calls[1]![1]).toEqual({ id: TID });
  });

  it("does not join when already in, and survives timeouts", async () => {
    const f = new FakeServer({ ...BASE, [S]: [standings()], [W]: [timeout(), timeout(), toolResult("wait_tournament_finished")] });
    const row = await f.client().playTournament(TID, () => "e4");
    expect(row?.rank).toBe(3);
    expect(f.names()).not.toContain("join_tournament");
    expect(f.names().filter((n) => n === W)).toHaveLength(3);
  });

  it("tolerates a join conflict when already registered, raises other refusals", async () => {
    const f = new FakeServer({ ...BASE, [S]: [standings("running", false), standings()], join_tournament: [toolResult("err_tournament_conflict")], [W]: [toolResult("wait_tournament_finished")] });
    expect(await f.client().playTournament(TID, () => "e4")).not.toBeNull();
    const refused = { content: [{ type: "text", text: "ERROR (not_allowed): wrong pool" }], isError: true, structuredContent: { error: { code: "not_allowed", message: "wrong pool", hint: "pick another" } } };
    const g = new FakeServer({ ...BASE, [S]: [standings("registering", false)], join_tournament: [refused] });
    await expect(g.client().playTournament(TID, () => "e4")).rejects.toBeInstanceOf(NotAllowedError);
  });

  it("returns instead of hanging when the tournament is cancelled", async () => {
    const f = new FakeServer({ ...BASE, [S]: [standings("registering"), standings("cancelled")], [W]: [timeout()] });
    const row = await f.client().playTournament(TID, () => "e4");
    expect(row?.rank).toBe(3);
    expect(f.names().filter((n) => n === W)).toHaveLength(1);
  });

  it("cancelled in the middle of a game", async () => {
    const aborted = over("g1");
    aborted.structuredContent.game.status = "aborted";
    const f = new FakeServer({ ...BASE, [S]: [standings(), standings("cancelled")], [W]: [gameWait("your_turn", "g1"), aborted], [M]: [toolResult("move_ok")] });
    expect(await f.client().playTournament(TID, () => "e4")).not.toBeNull();
    expect(f.names().filter((n) => n === W)).toHaveLength(2);
  });

  it("an already finished tournament returns the row at once", async () => {
    const f = new FakeServer({ ...BASE, [S]: [standings("finished")] });
    expect((await f.client().playTournament(TID, () => "e4"))?.rank).toBe(3);
    expect(f.names()).not.toContain(W);
  });

  it("ignores events of other tournaments", async () => {
    const other = structuredClone(toolResult("wait_tournament_finished"));
    other.structuredContent.events[0].data.tournament_id = "t_other";
    const f = new FakeServer({ ...BASE, [S]: [standings()], [W]: [other, toolResult("wait_tournament_finished")] });
    await f.client().playTournament(TID, () => "e4");
    expect(f.names().filter((n) => n === W)).toHaveLength(2);
  });
});
