import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "../src/index.js";

type Rec = Record<string, any>;
const here = dirname(fileURLToPath(import.meta.url));
export const TESTDATA = join(here, "..", "..", "testdata");

export const fixture = (name: string): Rec => JSON.parse(readFileSync(join(TESTDATA, `${name}.json`), "utf8"));
export const toolResult = (name: string): Rec => fixture(name).response.result;

/** A recorded game, wrapped as a wait_for_turn result. */
export function waitResult(reason: string, gameFrom: string, patch: Rec = {}): Rec {
  const g = { ...toolResult(gameFrom).structuredContent.game, ...patch };
  return { content: [{ type: "text", text: "WAIT" }], structuredContent: { reason, events: [], game: g } };
}

export type Reply = Rec | Error | [number, string];

/** Scripted MCP server behind a mock fetch. Each tool's replies are consumed in order; the last one repeats. */
export class FakeServer {
  calls: Array<[string, Rec]> = [];
  script: Record<string, Reply[]>;
  constructor(script: Record<string, Reply[]>) {
    this.script = Object.fromEntries(Object.entries(script).map(([k, v]) => [k, [...v]]));
  }

  fetch = async (_url: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const msg = JSON.parse(String(init?.body));
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    if (msg.method === "initialize") return json({ jsonrpc: "2.0", id: msg.id, result: fixture("initialize").response.result });
    if (msg.id === undefined) return new Response(null, { status: 202 });
    if (msg.method === "tools/list") return json({ jsonrpc: "2.0", id: msg.id, result: fixture("tools_list").response.result });
    const { name, arguments: args } = msg.params;
    this.calls.push([name, args]);
    const q = this.script[name];
    if (!q) throw new Error(`unexpected tool call ${name} ${JSON.stringify(args)}`);
    const reply = q.length > 1 ? q.shift()! : q[0]!;
    if (reply instanceof Error) throw reply;
    if (Array.isArray(reply)) return new Response(typeof reply[1] === "string" ? reply[1] : JSON.stringify(reply[1]), { status: reply[0] });
    if ("__rpc_error__" in reply) return json({ jsonrpc: "2.0", id: msg.id, error: reply["__rpc_error__"] });
    return json({ jsonrpc: "2.0", id: msg.id, result: reply });
  };

  client(): Client {
    return new Client({ apiKey: "nm_live_test", baseUrl: "http://fake/mcp", fetch: this.fetch as typeof fetch });
  }
  names(): string[] { return this.calls.map(([n]) => n); }
}
