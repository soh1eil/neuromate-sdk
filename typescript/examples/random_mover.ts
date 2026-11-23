// A bot loop in a handful of lines. Run:  NEUROMATE_API_KEY=nm_live_... npx tsx examples/random_mover.ts
import { Client } from "@neuromate/sdk";

const client = new Client(); // reads NEUROMATE_API_KEY (and NEUROMATE_URL) from the environment

// Called every time it is your move; return UCI ("e2e4") or SAN ("Nf3").
const strategy = (pos: { legalMoves: { uci: string }[] }) =>
  pos.legalMoves[Math.floor(Math.random() * pos.legalMoves.length)]!.uci;

const final = await client.play(strategy, { timeControl: "3+2" }); // joins the queue, plays one game
console.log(final.result?.text ?? "game ended");
