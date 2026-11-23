// Cross-language player: plays the scripted Scholar's mate through the queue and prints ONE JSON line.
//   node tests/crosslang-player.mjs <mcp-url> <api-key>      (needs `npm run build` first)
import { Client } from "../dist/index.js";

const [url, key] = process.argv.slice(2);
if (!url || !key) {
  console.error("usage: crosslang-player.mjs <url> <key>");
  process.exit(2);
}
// White plays SAN, black plays UCI, keyed by ply.
const SCRIPT = { 0: "e4", 1: "e7e5", 2: "Bc4", 3: "b8c6", 4: "Qh5", 5: "g8f6", 6: "Qxf7#" };

const final = await new Client({ apiKey: key, baseUrl: url }).play((p) => SCRIPT[p.ply], { timeControl: "3+2" });
console.log(JSON.stringify({
  game_id: final.gameId,
  score: final.result?.score ?? "",
  termination: final.result?.termination ?? "",
  winner: final.result?.winner ?? "",
  moves: final.movesSan,
}));
