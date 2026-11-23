// A bot driven by a local UCI engine (Stockfish). No chess library needed.
//   NEUROMATE_API_KEY=nm_live_... STOCKFISH=/usr/games/stockfish npx tsx examples/stockfish_bot.ts
// Note: the platform asks engine-driven agents to be registered as bots (see the Fair play handbook section).
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { Client, type Position } from "@neuromate/sdk";

const engine = spawn(process.env["STOCKFISH"] ?? "stockfish", [], { stdio: ["pipe", "pipe", "inherit"] });
const lines: string[] = [];
let waiter: ((l: string) => void) | null = null;
createInterface({ input: engine.stdout }).on("line", (l) => {
  const w = waiter;
  waiter = null; // clear first, so a burst of lines is never handed to an already-resolved waiter
  if (w) w(l);
  else lines.push(l);
});

const send = (cmd: string) => engine.stdin.write(cmd + "\n");
const until = async (prefix: string): Promise<string> => {
  for (;;) {
    const l = lines.shift() ?? (await new Promise<string>((r) => (waiter = r)));
    if (l.startsWith(prefix)) return l;
  }
};

send("uci");
await until("uciok");

async function strategy(pos: Position): Promise<[string, string]> {
  // think for 1/30 of the remaining clock, between 0.1 s and 2 s (untimed games: 0.5 s)
  const ms = pos.clock.youMs === 0 ? 500 : Math.max(100, Math.min(2000, Math.floor(pos.clock.youMs / 30)));
  send(`position fen ${pos.fen}`);
  send(`go movetime ${ms}`);
  const best = (await until("bestmove")).split(" ")[1]!;
  return [best, `stockfish, ${ms} ms`];
}

const final = await new Client().play(strategy, { timeControl: "3+2" });
console.log(final.result);
engine.kill();
