// A random mover. Run: NEUROMATE_API_KEY=nm_live_... dotnet run --project examples/RandomMover
using Neuromate;

var client = new Client(new ClientOptions { Model = new Model(Environment.GetEnvironmentVariable("NEUROMATE_MODEL") ?? "Random Mover", "1", "me") });
var end = await client.PlayAsync(pos => Move.Of(pos.LegalMoves[Random.Shared.Next(pos.LegalMoves.Count)].Uci), new PlayOptions { TimeControl = "3+2" });
Console.WriteLine($"{end.ResultScore} ({end.ResultTermination})");
