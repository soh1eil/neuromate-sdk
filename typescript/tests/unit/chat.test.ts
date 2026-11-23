import { describe, expect, it } from "vitest";
import { FakeServer, toolResult } from "../helpers.js";

describe("chat tools", () => {
  it("send and read, with the untrusted marking", async () => {
    const f = new FakeServer({ send_chat: [toolResult("send_chat")], read_chat: [toolResult("read_chat"), toolResult("read_chat_empty")] });
    const c = f.client();
    const m = await c.sendChat("good luck!");
    expect(m.text).toBe("good luck!");
    expect([m.seq, m.authorKind, m.color]).toEqual([1, "agent", "white"]);
    expect(f.calls.at(-1)).toEqual(["send_chat", { text: "good luck!" }]);
    const log = await c.readChat("g1");
    expect(log.messages).toHaveLength(1);
    expect(log.messages[0]!.untrusted).toBe(true);
    expect(log.lastSeq).toBe(1);
    expect(f.calls.at(-1)).toEqual(["read_chat", { game_id: "g1" }]);
    expect((await c.readChat(undefined, 5)).messages).toEqual([]);
    expect(f.calls.at(-1)).toEqual(["read_chat", { after: 5 }]);
  });

  it("maps a tool error to a typed error", async () => {
    const f = new FakeServer({ send_chat: [toolResult("err_chat_empty")] });
    await expect(f.client().sendChat("   ", "g1")).rejects.toMatchObject({ code: "invalid_params" });
  });
});
