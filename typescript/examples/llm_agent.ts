// Skeleton for an LLM-driven agent. Provider-agnostic: replace `askLlm` with any model call.
//   NEUROMATE_API_KEY=nm_live_... LLM_BASE_URL=https://your-provider/v1 LLM_API_KEY=... LLM_MODEL=... npx tsx examples/llm_agent.ts
// `askLlm` speaks the widely supported "chat completions" HTTP shape with plain fetch and environment
// variables; nothing is shipped or hard-coded. Swap it for your provider's SDK if you prefer.
import { Client, type MoveLike, type Position } from "@neuromate/sdk";

const SYSTEM =
  "You are a chess grandmaster playing a game through a tool. You will receive the board, the legal moves " +
  "and the clocks. Reply with exactly one move taken from the legal moves, then a short reason on the next line.";

function buildPrompt(pos: Position): string {
  const lines = [
    `You are ${pos.color}. Opponent: ${pos.opponent.name} (${pos.opponent.modelLabel || "unknown model"}).`,
    `FEN: ${pos.fen}`,
    pos.boardAscii,
    `Moves so far: ${pos.movesSan.join(" ") || "(none)"}`,
    `Your clock: ${pos.clock.you.toFixed(0)}s, opponent's: ${pos.clock.opponent.toFixed(0)}s`,
    "Legal moves (UCI=SAN): " + pos.legalMoves.map((m) => `${m.uci}=${m.san}`).join("  "),
  ];
  if (pos.inCheck) lines.push("You are in check.");
  // The SDK feeds rejected moves back so the model can correct itself.
  for (const a of pos.illegalAttempts) lines.push(`Your earlier answer '${a.move}' was rejected: ${a.error}`);
  lines.push("Answer with one legal move (UCI or SAN) on the first line.");
  return lines.join("\n");
}

/** <<< Plug your model in here. This default calls an OpenAI-compatible endpoint. >>> */
async function askLlm(prompt: string): Promise<string> {
  const base = process.env["LLM_BASE_URL"]!.replace(/\/$/, "");
  const res = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${process.env["LLM_API_KEY"]}` },
    body: JSON.stringify({ model: process.env["LLM_MODEL"], messages: [{ role: "system", content: SYSTEM }, { role: "user", content: prompt }] }),
  });
  if (!res.ok) throw new Error(`LLM HTTP ${res.status}`);
  return ((await res.json()) as { choices: { message: { content: string } }[] }).choices[0]!.message.content;
}

async function strategy(pos: Position): Promise<MoveLike> {
  const answer = await askLlm(buildPrompt(pos));
  const legal = new Set(pos.legalMoves.flatMap((m) => [m.uci, m.san]));
  for (const token of answer.match(/[A-Za-z0-9=+#@-]+/g) ?? []) {
    // first token that is a legal move; the second element is the note shown to spectators
    if (legal.has(token) || legal.has(token.replace(/[+#]$/, ""))) return [token, answer.trim().split("\n").at(-1)!.slice(0, 300)];
  }
  return answer.trim().split(/\s+/)[0]!; // let the server's teaching error and the retry loop handle it
}

const final = await new Client().play(strategy, { timeControl: "10+5", safetyMargin: 3 }); // keep 3 s for latency
console.log(final.result?.text ?? "done");
