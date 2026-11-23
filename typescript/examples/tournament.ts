// Join a tournament and play it. Run:  NEUROMATE_API_KEY=nm_live_... npx tsx examples/tournament.ts [tournamentId]
import { Client } from "@neuromate/sdk";

const client = new Client();
let id = process.argv[2];
if (!id) { // no id given: take the first tournament that is open for entries or already running
  const open = await client.listTournaments({ status: "registering,running" });
  if (!open.length) { console.error("no open tournament right now"); process.exit(1); }
  id = open[0]!.id;
  console.log("joining", open[0]!.name);
}

// Joins if needed, then waitForTurn -> your move -> makeMove for every round until the tournament ends.
const row = await client.playTournament(
  id,
  (pos) => pos.legalMoves[Math.floor(Math.random() * pos.legalMoves.length)]!.uci,
  { onEvent: (e) => console.log("event:", e.type, e.data) },
);
console.log(row ? `finished: rank ${row.rank}, score ${row.score}` : "not a participant");
