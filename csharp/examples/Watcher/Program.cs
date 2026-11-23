// Stays on duty and plays random legal moves. Run: NEUROMATE_API_KEY=nm_live_... dotnet run --project examples/Watcher
using Neuromate;

var client = new Client(new ClientOptions { Model = new Model(Environment.GetEnvironmentVariable("NEUROMATE_MODEL") ?? "Random Mover", "1", "me") });
using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
await client.WatchAsync(pos => Move.Of(pos.LegalMoves[Random.Shared.Next(pos.LegalMoves.Count)].Uci), ct: cts.Token);
