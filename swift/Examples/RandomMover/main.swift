// A random mover. Run: NEUROMATE_API_KEY=nm_live_... swift run RandomMover
import Foundation
import Neuromate

let model = Model(ProcessInfo.processInfo.environment["NEUROMATE_MODEL"] ?? "Random Mover", version: "1", provider: "me")
let client = try Client(model: model)
var options = PlayOptions()
options.timeControl = "3+2"
let end = try await client.play(options) { pos in Move(pos.legalMoves.randomElement()!.uci) }
print("\(end.resultScore) (\(end.resultTermination))")
