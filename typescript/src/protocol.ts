/** Transport-independent MCP plumbing: request building, reply parsing, typed results. */
import {
  AuthError, HTTPError, ProtocolError, RateLimitedError, ServerError, toolErrorClass,
} from "./errors.js";
import {
  type Analysis, type Created, type Handbook, type LegalMoves, type Lobby, type MoveResult, type Position, type Queued, type Turn,
  type JoinResult, type Standings, type Tournament, type WithdrawResult,
  parseEvent, parseLobby, parseMoveInfo, parsePairing, parsePosition, parseStanding, parseTournament,
} from "./types.js";

type Rec = Record<string, unknown>;

export const DEFAULT_URL = "https://api.neuromate.larsima.com/mcp";
export const PROTOCOL_VERSION = "2025-06-18";
export const SDK_VERSION = "0.1.0";

export function requestHeaders(apiKey: string, sessionId?: string | null): Record<string, string> {
  const h: Record<string, string> = {
    Authorization: `Bearer ${apiKey}`,
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    "MCP-Protocol-Version": PROTOCOL_VERSION,
  };
  // Browsers forbid setting User-Agent; Node's fetch accepts it.
  if (typeof window === "undefined") h["User-Agent"] = `neuromate-typescript/${SDK_VERSION}`;
  if (sessionId) h["Mcp-Session-Id"] = sessionId;
  return h;
}

export function rpcBody(id: number | null, method: string, params?: Rec): string {
  const msg: Rec = { jsonrpc: "2.0", method };
  if (id !== null) msg["id"] = id;
  if (params !== undefined) msg["params"] = params;
  return JSON.stringify(msg);
}

export function initializeParams(): Rec {
  return { protocolVersion: PROTOCOL_VERSION, capabilities: {}, clientInfo: { name: "neuromate-typescript", version: SDK_VERSION } };
}

function ssePayload(body: string, id: number | null): string {
  let last = "";
  for (const block of body.split(/\r?\n\r?\n/)) {
    const data = block.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trimStart()).join("\n");
    if (!data) continue;
    last = data;
    try {
      if ((JSON.parse(data) as Rec)["id"] === id) return data;
    } catch { /* keep looking */ }
  }
  return last;
}

/** Throw the right typed error for a non-2xx reply. */
export function checkHttp(status: number, headers: Headers, body: string): void {
  if (status === 200 || status === 202 || status === 204) return;
  const snippet = body.trim().slice(0, 500);
  if (status === 401) {
    const auth = new AuthError(snippet || "invalid or missing API key: send it as Authorization: Bearer nm_live_...", status, body);
    try {
      const j = JSON.parse(body) as Rec;
      const e = j["error"] as Rec | undefined;
      const d = ((e && typeof e === "object" ? e["data"] : undefined) as Rec | undefined) ?? j;
      if (d && typeof d === "object" && d["message"]) {
        auth.hint = String(d["hint"] ?? "");
        auth.message = String(d["message"]) + (auth.hint ? `. ${auth.hint}` : "");
      }
    } catch { /* plain-text body from an older server */ }
    throw auth;
  }
  if (status === 429) {
    const ra = Number(headers.get("retry-after") ?? "1");
    throw new RateLimitedError(snippet || "rate limited", Number.isFinite(ra) ? ra : 1);
  }
  if (status >= 500) throw new ServerError(snippet || `server error ${status}`, status, body);
  let err: unknown;
  try { err = (JSON.parse(body) as Rec)["error"]; } catch { err = undefined; }
  if (err && typeof err === "object") {
    const e = err as Rec;
    throw new ProtocolError(String(e["message"] ?? snippet), Number(e["code"] ?? 0), e["data"]);
  }
  throw new HTTPError(snippet || `HTTP ${status}`, status, body);
}

/** Return the `result` of a JSON-RPC reply or throw ProtocolError. */
export function parseRpc(contentType: string, body: string, id: number | null): unknown {
  if (contentType.includes("text/event-stream")) body = ssePayload(body, id);
  let msg: unknown;
  try { msg = JSON.parse(body); } catch { throw new ProtocolError(`the server sent something that is not JSON: ${JSON.stringify(body.slice(0, 200))}`); }
  if (!msg || typeof msg !== "object" || Array.isArray(msg)) throw new ProtocolError("unexpected JSON-RPC reply shape");
  const m = msg as Rec;
  if (m["error"]) {
    const e = m["error"] as Rec;
    throw new ProtocolError(String(e["message"] ?? "error"), Number(e["code"] ?? 0), e["data"]);
  }
  return m["result"];
}

/** Decoded `tools/call` result. */
export class ToolOut {
  text: string;
  data: Rec;
  isError: boolean;
  constructor(public tool: string, result: Rec) {
    const content = Array.isArray(result["content"]) ? (result["content"] as Rec[]) : [];
    this.text = content.filter((p) => p["type"] === "text").map((p) => String(p["text"] ?? "")).join("");
    this.data = (result["structuredContent"] as Rec | undefined) ?? {};
    this.isError = !!result["isError"];
  }

  raiseForError(): void {
    if (!this.isError) return;
    const t = this.text;
    const err = (this.data["error"] as Rec | undefined) ?? {};
    const code = (err["code"] as string | undefined) ?? null;
    // Servers with unified error codes: decide by code. The text prefixes
    // below are only for older servers.
    if (code === "rate_limited" || t.startsWith("RATE LIMITED")) {
      const m = /Wait (\d+) seconds/.exec(t);
      const ra = typeof err["retry_after_seconds"] === "number" ? (err["retry_after_seconds"] as number) : m ? Number(m[1]) : 1;
      throw new RateLimitedError(t.trim(), ra);
    }
    const msg = String(err["message"] ?? t.trim());
    if (code === "internal" || t.startsWith("INTERNAL ERROR") || msg === "internal error") throw new ServerError(t.trim(), 500, t);
    const g = this.data["game"];
    const position = g && typeof g === "object" ? parsePosition(g as Rec) : null;
    const Cls = toolErrorClass(code);
    const known = new Set(["code", "message", "hint", "what_to_do", "legal_moves"]);
    throw new Cls(msg, {
      code, hint: String(err["hint"] ?? err["what_to_do"] ?? ""),
      legalMoves: Array.isArray(err["legal_moves"]) ? (err["legal_moves"] as unknown[]).map(String) : [],
      position, text: t, tool: this.tool,
      context: Object.fromEntries(Object.entries(err).filter(([k]) => !known.has(k))),
    });
  }
}

export function decodeTool(tool: string, result: unknown): ToolOut {
  if (!result || typeof result !== "object") throw new ProtocolError(`tools/call ${tool} returned no result`);
  const out = new ToolOut(tool, result as Rec);
  out.raiseForError();
  return out;
}

/** Drop undefined/null entries. */
export function clean(o: Record<string, unknown>): Rec {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null));
}

// ---- parsers ----

export function toPosition(out: ToolOut): Position {
  const g = out.data["game"];
  if (!g || typeof g !== "object") throw new ProtocolError(`${out.tool}: the reply has no game state:\n${out.text.slice(0, 300)}`);
  return parsePosition(g as Rec, out.text);
}

export const toLobby = (out: ToolOut): Lobby => parseLobby(out.data, out.text);

export function toTurn(out: ToolOut): Turn {
  const d = out.data;
  const g = d["game"];
  const reason = String(d["reason"] ?? "");
  return {
    reason, position: g && typeof g === "object" ? parsePosition(g as Rec, out.text) : null,
    events: (Array.isArray(d["events"]) ? (d["events"] as Rec[]) : []).map(parseEvent), next: String(d["next"] ?? ""), text: out.text,
    timedOut: reason === "timeout",
  };
}

export function toMove(out: ToolOut): MoveResult {
  return { move: parseMoveInfo(out.data["move"]), replayed: !!out.data["replayed"], position: toPosition(out), text: out.text };
}

export function toQueued(out: ToolOut): Queued {
  if (out.data["game"] && typeof out.data["game"] === "object") {
    return { queued: false, position: toPosition(out), pool: "", variant: "", timeControl: "", text: out.text };
  }
  const d = out.data;
  return { queued: !!d["queued"], position: null, pool: String(d["pool"] ?? ""), variant: String(d["variant"] ?? ""), timeControl: String(d["time_control"] ?? ""), text: out.text };
}

export const toCreated = (kind: string) => (out: ToolOut): Created => {
  const raw = (out.data[kind] as Rec | undefined) ?? {};
  return { id: String(raw["id"] ?? ""), kind, raw, text: out.text };
};

export function toLegal(out: ToolOut): LegalMoves {
  const d = out.data;
  const moves = (Array.isArray(d["legal_moves"]) ? (d["legal_moves"] as Rec[]) : []).map((m) => ({ uci: String(m["uci"] ?? ""), san: String(m["san"] ?? "") }));
  return { gameId: String(d["game_id"] ?? ""), ply: Number(d["ply"] ?? 0), moves, text: out.text };
}

export function toAnalysis(out: ToolOut): Analysis {
  const available = out.data["available"] !== false;
  return { text: out.text, data: out.data, available, status: available ? "done" : String(out.data["status"] ?? "") };
}

export function toHandbook(out: ToolOut): Handbook {
  const d = out.data;
  return { text: out.text, sections: Array.isArray(d["sections"]) ? (d["sections"] as unknown[]).map(String) : [], section: String(d["section"] ?? "") };
}

export const toText = (out: ToolOut): string => out.text;

// ---- tournaments ----

function tournamentOf(out: ToolOut): Tournament {
  const t = out.data["tournament"];
  if (!t || typeof t !== "object") throw new ProtocolError(`${out.tool}: the reply has no tournament:\n${out.text.slice(0, 300)}`);
  return parseTournament(t as Rec);
}

export const toTournaments = (out: ToolOut): Tournament[] =>
  (Array.isArray(out.data["tournaments"]) ? (out.data["tournaments"] as Rec[]) : []).map(parseTournament);

export const toJoin = (out: ToolOut): JoinResult =>
  ({ tournament: tournamentOf(out), joined: out.data["joined"] !== false, next: String(out.data["next"] ?? ""), text: out.text });

export const toWithdraw = (out: ToolOut): WithdrawResult =>
  ({ tournament: tournamentOf(out), withdrawn: out.data["withdrawn"] !== false, text: out.text });

export function toStandings(out: ToolOut): Standings {
  const d = out.data;
  const standings = (Array.isArray(d["standings"]) ? (d["standings"] as Rec[]) : []).map(parseStanding);
  const pairings = (Array.isArray(d["pairings"]) ? (d["pairings"] as Rec[]) : []).map(parsePairing);
  return { tournament: tournamentOf(out), standings, pairings, total: typeof d["total"] === "number" ? d["total"] : standings.length, text: out.text };
}
