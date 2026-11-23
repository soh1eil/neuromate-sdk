import { describe, expect, it } from "vitest";
import { FakeServer, toolResult } from "../helpers.js";

const reply = (structuredContent: Record<string, unknown>, text = "OK") => ({ content: [{ type: "text", text }], structuredContent });

describe("coach mode and user_locale", () => {
  it("carries the human's language on positions, challenges and the lobby", async () => {
    const g = { ...toolResult("get_game_start").structuredContent };
    g.game = { ...g.game, user_locale: "fa", opponent_locale: "de" };
    const pos = await new FakeServer({ get_game: [reply(g)] }).client().getGame();
    expect(pos.userLocale).toBe("fa");
    expect(pos.opponentLocale).toBe("de");
    const plain = await new FakeServer({ get_game: [toolResult("get_game_start")] }).client().getGame();
    expect(plain.userLocale).toBe("");
    expect(plain.opponentLocale).toBe("");

    const lobby = await new FakeServer({
      list_challenges: [reply({
        user_locale: "ja", incoming_challenges: [{ id: "ch_1", from: { name: "Hanna", kind: "human" }, to: { name: "me", kind: "agent" }, params: {}, message: "", status: "pending", game_id: "", expires_at: "", from_locale: "de" }],
      })],
    }).client().listChallenges();
    expect(lobby.userLocale).toBe("ja");
    expect(lobby.incomingChallenges[0]!.fromLocale).toBe("de");
  });

  it("coach sessions, wait, context, say and progress", async () => {
    const srv = new FakeServer({
      coach_sessions: [reply({ sessions: [{ id: "cs_1", kind: "guided", status: "active", hint_mode: "ask", locale: "fa", game_id: "g1", topic: "" }] })],
      coach_wait: [
        reply({ user_locale: "fa", next: "coach_get_context", events: [{ type: "student_message", session_id: "cs_1", kind: "chat", text: "help", untrusted_text: true }] }),
        reply({ timeout: true, events: [], user_locale: "fa", next: "call coach_wait again" }),
      ],
      coach_get_context: [reply({ session: { id: "cs_1" }, user_locale: "fa", policy: "ASK mode", level: { label: "beginner" } })],
      coach_say: [reply({ message: { id: 1 } }, "SENT")],
      coach_mark_progress: [reply({ topic: "fork", mastery: 50 }, "RECORDED")],
    });
    const c = srv.client();
    const l = await c.coachSessions();
    expect(l.sessions[0]).toMatchObject({ id: "cs_1", kind: "guided", hintMode: "ask", locale: "fa", gameId: "g1" });
    const w = await c.coachWait({ timeoutSeconds: 5 });
    expect(w.timedOut).toBe(false);
    expect(w.events[0]).toMatchObject({ type: "student_message", sessionId: "cs_1" });
    expect(w.events[0]!.data["text"]).toBe("help");
    expect(w.events[0]!.data["untrusted_text"]).toBe(true);
    expect((await c.coachWait()).timedOut).toBe(true);
    const cx = await c.coachContext("cs_1");
    expect(cx).toMatchObject({ sessionId: "cs_1", userLocale: "fa", policy: "ASK mode" });
    await c.coachSay("نگاه کن", { sessionId: "cs_1", kind: "suggestion", arrows: [{ from: "e2", to: "e4", color: "primary" }], circles: [{ square: "f7" }] });
    await c.coachMarkProgress("fork", 50, { note: "ok" });
    const say = srv.calls.find(([t]) => t === "coach_say")![1];
    expect(say).toMatchObject({ text: "نگاه کن", kind: "suggestion", session_id: "cs_1", arrows: [{ from: "e2", to: "e4", color: "primary" }] });
    expect(srv.calls.find(([t]) => t === "coach_wait")![1]).toEqual({ timeout_seconds: 5 });
    expect(srv.calls.find(([t]) => t === "coach_mark_progress")![1]).toEqual({ topic: "fork", mastery: 50, note: "ok" });
  });
});
