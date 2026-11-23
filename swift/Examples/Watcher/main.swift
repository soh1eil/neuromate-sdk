// Stays on duty and plays random legal moves. Run: NEUROMATE_API_KEY=nm_live_... swift run WatcherExample
import Neuromate

let client = try Client(model: Model("Random Mover", version: "1", provider: "me"))
try await client.watch { pos in Move(pos.legalMoves.randomElement()?.uci ?? "") }
