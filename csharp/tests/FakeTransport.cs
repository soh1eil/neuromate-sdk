using System.Text.Json.Nodes;
using Neuromate;

/// <summary>Replays recorded tool results (sdk/testdata) as a scripted MCP server.</summary>
public sealed class FakeTransport : ITransport
{
    public Dictionary<string, List<JsonNode>> Script { get; } = new();
    public List<string> Names { get; } = new();
    public List<JsonObject> Args { get; } = new();
    public int ForcedStatus { get; set; }
    public string ForcedBody { get; set; } = "";
    public string ContentType { get; set; } = "application/json";

    public FakeTransport On(string tool, params JsonNode[] results) { Script[tool] = results.ToList(); return this; }

    public static JsonObject Fixture(string name)
    {
        var dir = AppContext.BaseDirectory;
        while (dir != null && !Directory.Exists(Path.Combine(dir, "testdata"))) dir = Path.GetDirectoryName(dir);
        return (JsonObject)JsonNode.Parse(File.ReadAllText(Path.Combine(dir!, "testdata", name + ".json")))!;
    }

    public static JsonNode Result(string name) => Fixture(name)["response"]!["result"]!.DeepClone();

    /// <summary>A wait_for_turn result around the game of a recorded fixture.</summary>
    public static JsonNode WaitResult(string reason, string gameFrom) => new JsonObject
    {
        ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = "WAIT" }),
        ["structuredContent"] = new JsonObject { ["reason"] = reason, ["events"] = new JsonArray(), ["game"] = Result(gameFrom)["structuredContent"]!["game"]!.DeepClone() },
    };

    public Task<TransportReply> PostAsync(string url, IReadOnlyDictionary<string, string> headers, string body, TimeSpan timeout, CancellationToken ct)
    {
        if (ForcedStatus != 0) return Task.FromResult(new TransportReply(ForcedStatus, new Dictionary<string, string>(), ForcedBody));
        var req = JsonNode.Parse(body)!;
        var method = (string)req["method"]!;
        if (method == "notifications/initialized") return Task.FromResult(new TransportReply(202, new Dictionary<string, string>(), ""));
        JsonNode result;
        if (method == "initialize") result = new JsonObject { ["serverInfo"] = new JsonObject { ["name"] = "fake", ["version"] = "1" } };
        else
        {
            var p = req["params"]!;
            var name = (string)p["name"]!;
            Names.Add(name); Args.Add((JsonObject)p["arguments"]!.DeepClone());
            if (!Script.TryGetValue(name, out var q)) throw new IOException("no scripted reply for " + name);
            result = q.Count > 1 ? q[0] : q[0];
            if (q.Count > 1) q.RemoveAt(0);
            result = result.DeepClone();
        }
        var json = new JsonObject { ["jsonrpc"] = "2.0", ["id"] = req["id"]!.DeepClone(), ["result"] = result }.ToJsonString();
        if (ContentType.Contains("event-stream")) json = "event: message\ndata: " + json + "\n\n";
        return Task.FromResult(new TransportReply(200, new Dictionary<string, string> { ["content-type"] = ContentType }, json));
    }
}
