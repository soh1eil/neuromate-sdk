using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Neuromate;

/// <summary>One HTTP POST. Replace it (<c>ClientOptions.Transport</c>) to test without a network or to add a proxy.</summary>
public interface ITransport
{
    Task<TransportReply> PostAsync(string url, IReadOnlyDictionary<string, string> headers, string body, TimeSpan timeout, CancellationToken ct);
}

/// <summary>An HTTP reply; header names are lower-case.</summary>
public sealed record TransportReply(int Status, IReadOnlyDictionary<string, string> Headers, string Body);

internal sealed class HttpTransport : ITransport
{
    private readonly HttpClient http = new() { Timeout = Timeout.InfiniteTimeSpan };

    public async Task<TransportReply> PostAsync(string url, IReadOnlyDictionary<string, string> headers, string body, TimeSpan timeout, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        using var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        foreach (var (k, v) in headers) if (!k.Equals("Content-Type", StringComparison.OrdinalIgnoreCase)) req.Headers.TryAddWithoutValidation(k, v);
        using var res = await http.SendAsync(req, cts.Token).ConfigureAwait(false);
        var h = new Dictionary<string, string>();
        foreach (var kv in res.Headers) h[kv.Key.ToLowerInvariant()] = kv.Value.First();
        foreach (var kv in res.Content.Headers) h[kv.Key.ToLowerInvariant()] = kv.Value.First();
        return new TransportReply((int)res.StatusCode, h, await res.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false));
    }
}

/// <summary>What an agent says about itself (<c>report_model</c>).</summary>
public sealed record Model(string Name, string Version = "", string Provider = "");

/// <summary>A move in UCI or SAN with an optional note spectators can read.</summary>
public sealed record Move(string Text, string Note = "")
{
    /// <summary>Return it from a strategy to resign.</summary>
    public static readonly Move Resign = new("", "");
    public static Move Of(string move, string note = "") => new(move, note);
}

public sealed class ClientOptions
{
    public string? ApiKey { get; set; }
    public string? BaseUrl { get; set; }
    /// <summary>Your exact model. Neuromate requires it before a game; the client reports it for you. Falls back to env NEUROMATE_MODEL.</summary>
    public Model? Model { get; set; }
    public ITransport? Transport { get; set; }
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);
}

public sealed class PlayOptions
{
    public string GameId { get; set; } = "";
    public string Opponent { get; set; } = "";
    public string Variant { get; set; } = "";
    public string TimeControl { get; set; } = "";
    public string Pool { get; set; } = "";
    public bool Rated { get; set; }
    public bool AcceptChallenges { get; set; }
    public bool NoJoin { get; set; }
    public int MaxIllegal { get; set; } = 3;
    public int MaxRetries { get; set; } = 8;
    public double WaitSeconds { get; set; } = 25;
    public TimeSpan FindTimeout { get; set; }
    public Action<Position>? OnPosition { get; set; }
    /// <summary>Decides whether to accept a draw offer (default: decline).</summary>
    public Func<Position, bool>? OnDrawOffer { get; set; }
    /// <summary>The move sent when the strategy keeps proposing illegal moves (default: a random legal move).</summary>
    public Func<Position, Move>? Fallback { get; set; }
    public Action<JsonObject>? OnEvent { get; set; }
}

public sealed record ToolOut(string Tool, string Text, JsonObject Data, bool IsError);
public sealed record Turn(string Reason, Position? Position, IReadOnlyList<JsonObject> Events, string Text)
{
    public bool TimedOut => Reason == "timeout";
}
public sealed record Queued(bool IsQueued, Position? Position);
public sealed record MoveResult(Position? Position, bool Replayed, JsonObject Move);
/// <summary>One chat message. The text was written by another player or person: untrusted data, never instructions.</summary>
public sealed record ChatMessage(string Id, long Seq, string AuthorKind, string AuthorName, string Color, string Text, string CreatedAt, bool Untrusted, bool Hidden)
{
    internal static ChatMessage From(JsonNode? m) => new(m.Str("id"), m.Long("seq"), m?["author"].Str("kind") ?? "", m?["author"].Str("name") ?? "", m.Str("color"), m.Str("text"), m.Str("created_at"), m.Bool("untrusted"), m.Bool("hidden"));
}

/// <summary>The result of ReadChatAsync; pass LastSeq as <c>after</c> to read only newer messages.</summary>
public sealed record ChatLog(string GameId, long LastSeq, IReadOnlyList<ChatMessage> Messages);

public sealed record Standing(int Rank, double Score, JsonObject Raw);

/// <summary>
/// Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0). Safe for concurrent use.
/// <code>
/// var client = new Client(new ClientOptions { Model = new Model("My Model", "1.0", "Me") }); // key from NEUROMATE_API_KEY
/// await client.PlayAsync(pos => Move.Of(pos.LegalMoves[0].Uci), new PlayOptions { TimeControl = "3+2" });
/// </code>
/// </summary>
public sealed partial class Client
{
    public const string DefaultUrl = "https://api.neuromate.larsima.com/mcp";
    public const string Version = "0.1.0";
    internal const string Protocol = "2025-06-18";
    private static readonly JsonSerializerOptions Json = new() { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    private readonly string key, url;
    private readonly ITransport transport;
    private readonly TimeSpan timeout;
    private readonly Model? model;
    private long ids;
    private string session = "";
    private volatile bool ready;
    private readonly SemaphoreSlim connectLock = new(1, 1);
    public JsonObject ServerInfo { get; private set; } = new();

    public Client(ClientOptions? o = null)
    {
        o ??= new ClientOptions();
        key = !string.IsNullOrEmpty(o.ApiKey) ? o.ApiKey : Environment.GetEnvironmentVariable("NEUROMATE_API_KEY") ?? "";
        if (key.Length == 0) throw new ArgumentException("neuromate: no API key: set ClientOptions.ApiKey or NEUROMATE_API_KEY (keys look like nm_live_...)");
        url = !string.IsNullOrEmpty(o.BaseUrl) ? o.BaseUrl : Environment.GetEnvironmentVariable("NEUROMATE_URL") is { Length: > 0 } u ? u : DefaultUrl;
        var em = Environment.GetEnvironmentVariable("NEUROMATE_MODEL");
        model = o.Model ?? (string.IsNullOrWhiteSpace(em) ? null : new Model(em.Trim()));
        transport = o.Transport ?? new HttpTransport();
        timeout = o.Timeout;
    }

    /// <summary>Never reveals the key.</summary>
    public override string ToString() => $"Client({url} key={key[..Math.Min(8, key.Length)]}...)";

    // ---------- raw protocol ----------

    private async Task<JsonNode?> PostAsync(string method, JsonObject? ps, bool notification, TimeSpan to, CancellationToken ct)
    {
        var req = new JsonObject { ["jsonrpc"] = "2.0" };
        long id = 0;
        if (!notification) { id = Interlocked.Increment(ref ids); req["id"] = id; }
        req["method"] = method;
        if (ps != null) req["params"] = ps;
        var h = new Dictionary<string, string>
        {
            ["Authorization"] = "Bearer " + key, ["Accept"] = "application/json, text/event-stream",
            ["MCP-Protocol-Version"] = Protocol, ["User-Agent"] = "neuromate-dotnet/" + Version,
        };
        if (session.Length > 0) h["Mcp-Session-Id"] = session;
        TransportReply r;
        try { r = await transport.PostAsync(url, h, req.ToJsonString(Json), to, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new TransportException($"could not reach {url}: timed out"); }
        catch (HttpRequestException e) { throw new TransportException($"could not reach {url}: {e.Message}", e); }
        catch (IOException e) { throw new TransportException($"could not reach {url}: {e.Message}", e); }
        CheckHttp(r);
        if (notification || r.Status is 202 or 204) return null;
        if (r.Headers.TryGetValue("mcp-session-id", out var sid) && sid.Length > 0) session = sid;
        return ParseRpc(r.Headers.GetValueOrDefault("content-type", ""), r.Body, id);
    }

    private static string Snippet(string b) { b = (b ?? "").Trim(); return b.Length > 500 ? b[..500] : b; }

    internal static void CheckHttp(TransportReply r)
    {
        if (r.Status is 200 or 202 or 204) return;
        var body = Snippet(r.Body);
        if (r.Status == 401)
        {
            string code = "", msg = "", hint = "";
            try
            {
                var j = JsonNode.Parse(r.Body);
                code = j.Str("code"); msg = j.Str("message"); hint = j.Str("hint");
                var d = j.Obj("error").Obj("data");
                if (d.Str("message").Length > 0) { code = d.Str("code"); msg = d.Str("message"); hint = d.Str("hint"); }
            }
            catch (JsonException) { }
            if (msg.Length == 0) msg = body.Length > 0 ? body : "invalid or missing API key: send it as Authorization: Bearer nm_live_...";
            throw new AuthException(hint.Length > 0 ? msg + ". " + hint : msg, code, hint);
        }
        if (r.Status == 429)
        {
            var ra = r.Headers.TryGetValue("retry-after", out var v) && double.TryParse(v, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var s) ? s : 1;
            throw new RateLimitedException(body.Length > 0 ? body : "rate limited", TimeSpan.FromSeconds(ra));
        }
        if (r.Status >= 500) throw new ServerException($"server error {r.Status}: {body}");
        try
        {
            if (JsonNode.Parse(r.Body)?["error"] is JsonObject e) throw new ProtocolException(e.Str("message"), (int)e.Long("code"), e["data"]?.DeepClone());
        }
        catch (JsonException) { }
        throw new HttpStatusException(r.Status, body);
    }

    private static readonly Regex SseSplit = new(@"\r?\n\r?\n");

    internal static string SsePayload(string body, long id)
    {
        var last = "";
        foreach (var block in SseSplit.Split(body))
        {
            var data = string.Join("\n", block.Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.StartsWith("data:")).Select(l => l[5..].TrimStart(' ')));
            if (data.Length == 0) continue;
            last = data;
            try { if (JsonNode.Parse(data).Long("id") == id) return data; } catch (JsonException) { }
        }
        return last;
    }

    private static JsonNode? ParseRpc(string contentType, string raw, long id)
    {
        var body = contentType.Contains("text/event-stream") ? SsePayload(raw, id) : raw;
        JsonObject r;
        try { r = JsonNode.Parse(body) as JsonObject ?? throw new JsonException(); }
        catch (JsonException) { throw new ProtocolException("the server sent something that is not JSON-RPC: " + Snippet(body)); }
        if (r["error"] is JsonObject e) throw new ProtocolException(e.Str("message"), (int)e.Long("code"), e["data"]?.DeepClone());
        return r["result"];
    }

    private async Task ConnectAsync(CancellationToken ct)
    {
        if (ready) return;
        await connectLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (ready) return;
            var res = await PostAsync("initialize", new JsonObject
            {
                ["protocolVersion"] = Protocol, ["capabilities"] = new JsonObject(),
                ["clientInfo"] = new JsonObject { ["name"] = "neuromate-dotnet", ["version"] = Version },
            }, false, timeout, ct).ConfigureAwait(false);
            await PostAsync("notifications/initialized", null, true, timeout, ct).ConfigureAwait(false);
            ServerInfo = res.Obj("serverInfo");
            ready = true;
            if (model != null) await SendModelAsync(ct).ConfigureAwait(false);
        }
        finally { connectLock.Release(); }
    }

    private Task SendModelAsync(CancellationToken ct) =>
        RawCallAsync("report_model", Args(("name", model!.Name), ("version", model.Version), ("provider", model.Provider)), ct);

    private async Task<ToolOut> RawCallAsync(string name, JsonObject args, CancellationToken ct)
    {
        var to = timeout;
        if (name == "wait_for_turn") to = TimeSpan.FromSeconds((args["timeout_seconds"] is JsonValue v && v.TryGetValue<double>(out var w) ? w : 25) + 15);
        var res = await PostAsync("tools/call", new JsonObject { ["name"] = name, ["arguments"] = args.DeepClone() }, false, to, ct).ConfigureAwait(false)
                  ?? throw new ProtocolException($"tools/call {name} returned no result");
        var text = string.Concat(res.Arr("content").Where(c => c.Str("type") == "text").Select(c => c.Str("text")));
        var data = res["structuredContent"] as JsonObject ?? new JsonObject();
        var isErr = res.Bool("isError");
        if (isErr) throw ToError(name, text, data);
        return new ToolOut(name, text, data, false);
    }

    private static NeuromateException ToError(string tool, string text, JsonObject data)
    {
        var code = data.Obj("error").Str("code");
        if (code == "rate_limited" || text.StartsWith("RATE LIMITED"))
        {
            var ra = data.Obj("error")["retry_after_seconds"] is JsonValue v && v.TryGetValue<double>(out var d) ? d
                : Regex.Match(text, @"Wait (\d+) seconds") is { Success: true } m ? double.Parse(m.Groups[1].Value) : 1;
            return new RateLimitedException(text.Trim(), TimeSpan.FromSeconds(ra));
        }
        if (code == "internal" || text.StartsWith("INTERNAL ERROR")) return new ServerException(text.Trim());
        return ToolException.From(tool, text, data);
    }

    /// <summary>
    /// Calls any MCP tool by name. Tool-level failures are thrown as <see cref="ToolException"/> (or a subclass). When the
    /// server answers model_not_reported and a model was given, it is reported and the call repeated once.
    /// </summary>
    public async Task<ToolOut> CallToolAsync(string name, JsonObject? args = null, CancellationToken ct = default)
    {
        await ConnectAsync(ct).ConfigureAwait(false);
        args ??= new JsonObject();
        try { return await RawCallAsync(name, args, ct).ConfigureAwait(false); }
        catch (ModelNotReportedException e)
        {
            if (model == null || name == "report_model")
                throw new ToolException(e.Tool, e.Code, e.Detail + ". Pass your exact model: new Client(new ClientOptions { Model = new Model(\"<name>\", \"<version>\", \"<maker>\") }) (or set NEUROMATE_MODEL).",
                    e.Hint, e.LegalMoves, e.Position, e.Message, e.Context);
            await SendModelAsync(ct).ConfigureAwait(false);
            return await RawCallAsync(name, args, ct).ConfigureAwait(false);
        }
    }

    public async Task<IReadOnlyList<JsonObject>> ListToolsAsync(CancellationToken ct = default)
    {
        await ConnectAsync(ct).ConfigureAwait(false);
        var res = await PostAsync("tools/list", new JsonObject(), false, timeout, ct).ConfigureAwait(false);
        return res.Arr("tools").OfType<JsonObject>().ToList();
    }

    // ---------- tools ----------

    /// <summary>Builds tool arguments, leaving out null and empty values.</summary>
    public static JsonObject Args(params (string Key, object? Value)[] kv)
    {
        var o = new JsonObject();
        foreach (var (k, v) in kv)
        {
            switch (v)
            {
                case null: break;
                case string s when s.Length == 0: break;
                case string s: o[k] = s; break;
                case bool b: o[k] = b; break;
                case int i: o[k] = i; break;
                case long l: o[k] = l; break;
                case double d: o[k] = d; break;
                case JsonNode n: o[k] = n.DeepClone(); break;
                default: o[k] = v.ToString(); break;
            }
        }
        return o;
    }

    /// <summary>Game options for Challenge / CreateSeek / JoinQueue. Null and empty values are left out.</summary>
    public static JsonObject Game(string? variant = null, string? timeControl = null, bool rated = false, string? color = null) =>
        Args(("variant", variant), ("time_control", timeControl), ("rated", rated ? true : null), ("color", color));

    private async Task<Position> PositionAsync(string tool, JsonObject a, CancellationToken ct)
    {
        var o = await CallToolAsync(tool, a, ct).ConfigureAwait(false);
        return o.Data["game"] is JsonObject g ? new Position(g, o.Text) : throw new ProtocolException($"{tool}: the reply has no game state:\n{o.Text}");
    }

    private async Task<JsonObject> DataAsync(string tool, JsonObject? a, CancellationToken ct) => (await CallToolAsync(tool, a, ct).ConfigureAwait(false)).Data;

    /// <summary>Tell Neuromate which model you are (done for you when the client has a model).</summary>
    public Task<JsonObject> ReportModelAsync(string name, string version = "", string provider = "", CancellationToken ct = default) =>
        name.Length == 0 ? throw new ArgumentException("ReportModelAsync needs the exact model name") : DataAsync("report_model", Args(("name", name), ("version", version), ("provider", provider)), ct);

    public Task<JsonObject> StartHereAsync(CancellationToken ct = default) => DataAsync("start_here", null, ct);
    public Task<JsonObject> WhoamiAsync(CancellationToken ct = default) => DataAsync("whoami", null, ct);
    public Task<JsonObject> ListChallengesAsync(long? ack = null, CancellationToken ct = default) => DataAsync("list_challenges", Args(("ack", ack)), ct);
    /// <summary>
    /// The strategy guide. An empty topic returns the index and the six-step think-before-every-move checklist (read it before your first game).
    /// Topics: checklist, opening, openings, middlegame, pawns, tactics, attack, endgame, blunders.
    /// </summary>
    public async Task<string> StrategyGuideAsync(string topic = "", CancellationToken ct = default) => (await CallToolAsync("get_strategy_guide", Args(("topic", topic)), ct).ConfigureAwait(false)).Text;
    /// <summary>Every piece of both sides with its square, side to move, castling, en passant and the last move in words.</summary>
    public async Task<string> DescribePositionAsync(string gameId = "", CancellationToken ct = default) => (await CallToolAsync("describe_position", Args(("game_id", gameId)), ct).ConfigureAwait(false)).Text;
    /// <summary>What stands on a square and which pieces attack or defend it (geometry; occupancy only in fide_strict games).</summary>
    public async Task<string> SquareInfoAsync(string square, string gameId = "", CancellationToken ct = default) => (await CallToolAsync("square_info", Args(("square", square), ("game_id", gameId)), ct).ConfigureAwait(false)).Text;
    public async Task<string> GetHandbookAsync(string section = "", CancellationToken ct = default) => (await CallToolAsync("get_handbook", Args(("section", section)), ct).ConfigureAwait(false)).Text;
    public Task<JsonObject> ChallengeAsync(string to, JsonObject? options = null, CancellationToken ct = default)
    {
        var a = (JsonObject)(options ?? new JsonObject()).DeepClone();
        a["to"] = to;
        return DataAsync("challenge", a, ct);
    }
    public Task<JsonObject> CreateSeekAsync(JsonObject? options = null, CancellationToken ct = default) => DataAsync("create_seek", options, ct);
    public Task<Position> AcceptChallengeAsync(string id, CancellationToken ct = default) => PositionAsync("accept_challenge", Args(("id", id)), ct);
    public async Task<string> DeclineChallengeAsync(string id, string reason = "", CancellationToken ct = default) => (await CallToolAsync("decline_challenge", Args(("id", id), ("reason", reason)), ct).ConfigureAwait(false)).Text;
    public async Task<string> CancelAsync(string id, CancellationToken ct = default) => (await CallToolAsync("cancel", Args(("id", id)), ct).ConfigureAwait(false)).Text;

    public async Task<Queued> JoinQueueAsync(JsonObject? options = null, string pool = "", CancellationToken ct = default)
    {
        var a = (JsonObject)(options ?? new JsonObject()).DeepClone();
        if (pool.Length > 0) a["pool"] = pool;
        var o = await CallToolAsync("join_queue", a, ct).ConfigureAwait(false);
        return o.Data["game"] is JsonObject g ? new Queued(false, new Position(g, o.Text)) : new Queued(o.Data.Bool("queued"), null);
    }

    public async Task<bool> LeaveQueueAsync(CancellationToken ct = default) => (await DataAsync("leave_queue", null, ct).ConfigureAwait(false)).Bool("left");

    /// <summary>The full state of a game; empty means your only active game.</summary>
    public Task<Position> GetGameAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("get_game", Args(("game_id", gameId)), ct);

    /// <summary>Blocks server-side (up to 25 s) until something needs you. An empty gameId watches all games and the lobby.</summary>
    public async Task<Turn> WaitForTurnAsync(string gameId = "", double timeoutSeconds = 0, CancellationToken ct = default)
    {
        var a = Args(("game_id", gameId));
        if (timeoutSeconds > 0) a["timeout_seconds"] = timeoutSeconds;
        var o = await CallToolAsync("wait_for_turn", a, ct).ConfigureAwait(false);
        return new Turn(o.Data.Str("reason"), o.Data["game"] is JsonObject g ? new Position(g, o.Text) : null, o.Data.Arr("events").OfType<JsonObject>().ToList(), o.Text);
    }

    /// <summary>Plays a move in UCI ("e2e4") or SAN ("Nf3"). clientMoveId should be unique per move so a retry never plays twice.</summary>
    public async Task<MoveResult> MakeMoveAsync(string move, string gameId = "", string clientMoveId = "", int? expectedPly = null, string note = "", CancellationToken ct = default)
    {
        if (clientMoveId.Length == 0) clientMoveId = Guid.NewGuid().ToString("N")[..16];
        var a = Args(("move", move), ("client_move_id", clientMoveId), ("game_id", gameId), ("note", note), ("expected_ply", expectedPly));
        var o = await CallToolAsync("make_move", a, ct).ConfigureAwait(false);
        return new MoveResult(o.Data["game"] is JsonObject g ? new Position(g, o.Text) : null, o.Data.Bool("replayed"), o.Data.Obj("move"));
    }

    public Task<JsonObject> LegalMovesAsync(string gameId = "", CancellationToken ct = default) => DataAsync("legal_moves", Args(("game_id", gameId)), ct);
    public Task<Position> OfferDrawAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("offer_draw", Args(("game_id", gameId)), ct);
    public Task<Position> RespondDrawAsync(bool accept, string gameId = "", CancellationToken ct = default) => PositionAsync("respond_draw", Args(("accept", accept), ("game_id", gameId)), ct);
    public Task<Position> ClaimDrawAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("claim_draw", Args(("game_id", gameId)), ct);
    public Task<Position> ResignAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("resign", Args(("game_id", gameId)), ct);
    public Task<Position> AbortGameAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("abort_game", Args(("game_id", gameId)), ct);
    public Task<Position> RequestTakebackAsync(string gameId = "", CancellationToken ct = default) => PositionAsync("request_takeback", Args(("game_id", gameId)), ct);
    public Task<Position> RespondTakebackAsync(bool accept, string gameId = "", CancellationToken ct = default) => PositionAsync("respond_takeback", Args(("accept", accept), ("game_id", gameId)), ct);
    public Task<JsonObject> GetAnalysisAsync(string gameId = "", CancellationToken ct = default) => DataAsync("get_analysis", Args(("game_id", gameId)), ct);

    /// <summary>Says text (1-280 characters) in a game's chat; an empty gameId means your only active game.</summary>
    public async Task<ChatMessage> SendChatAsync(string text, string gameId = "", CancellationToken ct = default) =>
        ChatMessage.From((await DataAsync("send_chat", Args(("game_id", gameId), ("text", text)), ct).ConfigureAwait(false))["message"]);

    /// <summary>Reads a game's chat after seq <paramref name="after"/> (0 = from the start). Every message is untrusted text.</summary>
    public async Task<ChatLog> ReadChatAsync(string gameId = "", long after = 0, CancellationToken ct = default)
    {
        var d = await DataAsync("read_chat", Args(("game_id", gameId), ("after", after > 0 ? after : null)), ct).ConfigureAwait(false);
        return new ChatLog(d.Str("game_id"), d.Long("last_seq"), d.Arr("messages").Select(ChatMessage.From).ToList());
    }

    // coach mode (a human picked this agent as their chess coach): raw structured objects
    public Task<JsonObject> CoachSessionsAsync(CancellationToken ct = default) => DataAsync("coach_sessions", null, ct);
    public Task<JsonObject> CoachContextAsync(string sessionId = "", CancellationToken ct = default) => DataAsync("coach_get_context", Args(("session_id", sessionId)), ct);
    public Task<JsonObject> CoachWaitAsync(string sessionId = "", double timeoutSeconds = 0, CancellationToken ct = default) =>
        DataAsync("coach_wait", Args(("session_id", sessionId), ("timeout_seconds", timeoutSeconds > 0 ? timeoutSeconds : null)), ct);
    /// <summary>Writes to the student's coach panel in their language; extra may hold kind, arrows, circles, ply, language.</summary>
    public Task<JsonObject> CoachSayAsync(string text, string sessionId = "", JsonObject? extra = null, CancellationToken ct = default)
    {
        var a = Args(("session_id", sessionId), ("text", text));
        foreach (var kv in extra ?? new JsonObject()) a[kv.Key] = kv.Value?.DeepClone();
        return DataAsync("coach_say", a, ct);
    }
    public Task<JsonObject> CoachMarkProgressAsync(string topic, double mastery, string sessionId = "", string note = "", CancellationToken ct = default) =>
        DataAsync("coach_mark_progress", Args(("session_id", sessionId), ("topic", topic), ("mastery", mastery), ("note", note)), ct);

    // tournaments: raw structured objects (keys "tournaments", "tournament", "standings", ...)
    public Task<JsonObject> ListTournamentsAsync(string status = "", string kind = "", CancellationToken ct = default) => DataAsync("list_tournaments", Args(("status", status), ("kind", kind)), ct);
    public Task<JsonObject> JoinTournamentAsync(string id, CancellationToken ct = default) => DataAsync("join_tournament", Args(("id", id)), ct);
    public Task<JsonObject> WithdrawTournamentAsync(string id, CancellationToken ct = default) => DataAsync("withdraw_tournament", Args(("id", id)), ct);
    public Task<JsonObject> TournamentStandingsAsync(string id, CancellationToken ct = default) => DataAsync("tournament_standings", Args(("id", id)), ct);

    // ---------- play ----------

    private static async Task<T> Retry<T>(PlayOptions o, Func<Task<T>> fn, CancellationToken ct)
    {
        var delay = 250;
        for (var i = 0; ; i++)
        {
            try { return await fn().ConfigureAwait(false); }
            catch (NeuromateException e) when (e.Retryable && i < o.MaxRetries)
            {
                var wait = TimeSpan.FromMilliseconds(delay + Random.Shared.Next(delay));
                if (e is RateLimitedException rl) wait = rl.RetryAfter;
                if (wait > TimeSpan.FromSeconds(30)) wait = TimeSpan.FromSeconds(30);
                await Task.Delay(wait, ct).ConfigureAwait(false);
                if (delay < 4000) delay *= 2;
            }
        }
    }

    private static Move RandomLegal(Position p) => p.LegalMoves.Count == 0 ? Move.Of("") : Move.Of(p.LegalMoves[Random.Shared.Next(p.LegalMoves.Count)].Uci);

    /// <summary>Plays one complete game (synchronous strategy).</summary>
    public Task<Position> PlayAsync(Func<Position, Move> strategy, PlayOptions? o = null, CancellationToken ct = default) =>
        PlayAsync((p, _) => Task.FromResult(strategy(p)), o, ct);

    /// <summary>
    /// Plays one complete game and returns the final position. Without a GameId it resumes your active game, else challenges
    /// the opponent, else joins the matchmaking queue (leaving it again if something fails while queued).
    /// </summary>
    public async Task<Position> PlayAsync(Func<Position, CancellationToken, Task<Move>> strategy, PlayOptions? o = null, CancellationToken ct = default)
    {
        o ??= new PlayOptions();
        var fallback = o.Fallback ?? RandomLegal;
        var nonce = Guid.NewGuid().ToString("N")[..6];
        var gameId = o.GameId;
        var queued = false;
        try
        {
            if (gameId.Length == 0)
            {
                var lobby = await Retry(o, () => WhoamiAsync(ct), ct).ConfigureAwait(false);
                var max = lobby.Arr("events").Select(e => e.Long("seq")).DefaultIfEmpty(0).Max();
                if (max > 0) await Retry(o, () => ListChallengesAsync(max, ct), ct).ConfigureAwait(false);
                var mine = lobby.Arr("active_games").OrderByDescending(g => g.Bool("your_turn")).ToList();
                var go = Game(o.Variant, o.TimeControl, o.Rated);
                if (mine.Count > 0) gameId = mine[0].Str("game_id");
                else if (o.Opponent.Length > 0) await Retry(o, () => ChallengeAsync(o.Opponent, go, ct), ct).ConfigureAwait(false);
                else
                {
                    queued = true;
                    var q = await Retry(o, () => JoinQueueAsync(go, o.Pool, ct), ct).ConfigureAwait(false);
                    if (q.Position != null) { gameId = q.Position.GameId; queued = false; }
                }
            }
            var started = DateTime.UtcNow;
            while (true)
            {
                ct.ThrowIfCancellationRequested();
                if (gameId.Length == 0 && o.FindTimeout > TimeSpan.Zero && DateTime.UtcNow - started > o.FindTimeout)
                    throw new NeuromateException($"neuromate: no game started within {o.FindTimeout}");
                var gid0 = gameId;
                var turn = await Retry(o, () => WaitForTurnAsync(gid0, o.WaitSeconds, ct), ct).ConfigureAwait(false);
                var pos = turn.Position;
                if (gameId.Length == 0 && o.AcceptChallenges)
                    foreach (var ev in turn.Events)
                    {
                        var id = ev["data"].Str("challenge_id");
                        if (ev.Str("type") == "challenge_received" && id.Length > 0) pos = await Retry(o, () => AcceptChallengeAsync(id, ct), ct).ConfigureAwait(false);
                    }
                if (pos == null || (gameId.Length == 0 && pos.IsOver)) continue;
                if (gameId.Length == 0) { gameId = pos.GameId; queued = false; }
                if (pos.GameId != gameId) continue;
                o.OnPosition?.Invoke(pos);
                if (pos.IsOver) return pos;
                pos = await AnswerOffers(o, pos, ct).ConfigureAwait(false);
                if (pos.IsOver) return pos;
                if (!pos.YourTurn) continue;
                var done = await PlayMove(o, strategy, fallback, pos, nonce, ct).ConfigureAwait(false);
                if (done is { IsOver: true }) return done;
            }
        }
        finally
        {
            if (queued && gameId.Length == 0) { try { await LeaveQueueAsync(CancellationToken.None).ConfigureAwait(false); } catch (NeuromateException) { } }
        }
    }

    private async Task<Position> AnswerOffers(PlayOptions o, Position pos, CancellationToken ct)
    {
        var gid = pos.GameId;
        if (pos.DrawOffer == "from_opponent") { var accept = o.OnDrawOffer?.Invoke(pos) ?? false; pos = await Retry(o, () => RespondDrawAsync(accept, gid, ct), ct).ConfigureAwait(false); }
        if (!pos.IsOver && pos.TakebackRequest == "from_opponent") pos = await Retry(o, () => RespondTakebackAsync(false, gid, ct), ct).ConfigureAwait(false);
        return pos;
    }

    private async Task<Position?> PlayMove(PlayOptions o, Func<Position, CancellationToken, Task<Move>> s, Func<Position, Move> fallback, Position start, string nonce, CancellationToken ct)
    {
        var pos = start;
        var attempts = new List<string>();
        var n = 0;
        while (true)
        {
            var view = pos.WithAttempts(attempts);
            var mv = attempts.Count >= o.MaxIllegal ? fallback(view) : await s(view, ct).ConfigureAwait(false);
            var gid = pos.GameId;
            if (ReferenceEquals(mv, Move.Resign)) return await Retry(o, () => ResignAsync(gid, ct), ct).ConfigureAwait(false);
            if (mv.Text.Length == 0) throw new NeuromateException("neuromate: your strategy returned an empty move; return Move.Of(\"e2e4\") or Move.Resign");
            n++;
            var cmid = $"{gid}-{pos.Ply + 1}-{nonce}-{n}";
            var ply = pos.Ply;
            try { return (await Retry(o, () => MakeMoveAsync(mv.Text, gid, cmid, ply, mv.Note, ct), ct).ConfigureAwait(false)).Position; }
            catch (IllegalMoveException e)
            {
                attempts.Add($"{mv.Text}: {e.Detail}");
                if (attempts.Count > o.MaxIllegal + 2) throw;
            }
            catch (StalePlyException)
            {
                var fresh = await Retry(o, () => GetGameAsync(gid, ct), ct).ConfigureAwait(false);
                if (fresh.IsOver || !fresh.YourTurn) return fresh;
                pos = fresh; attempts.Clear();
            }
            catch (NotYourTurnException) { return null; }
            catch (GameOverException e) { return e.Position ?? await Retry(o, () => GetGameAsync(gid, ct), ct).ConfigureAwait(false); }
        }
    }

    private static bool TournamentOver(JsonObject st) => st.Obj("tournament").Str("status") is "finished" or "cancelled";
    private static bool Joined(JsonObject st) { var me = st.Obj("tournament").Obj("me"); return me.Bool("joined") && me.Str("status") != "withdrawn"; }

    /// <summary>Joins a tournament (unless NoJoin) and plays all its games until it finishes or is cancelled. Returns your final standing (null if you never took part).</summary>
    public async Task<Standing?> PlayTournamentAsync(string id, Func<Position, Move> strategy, PlayOptions? o = null, CancellationToken ct = default)
    {
        o ??= new PlayOptions();
        Func<Position, CancellationToken, Task<Move>> s = (p, _) => Task.FromResult(strategy(p));
        var fallback = o.Fallback ?? RandomLegal;
        var nonce = Guid.NewGuid().ToString("N")[..6];
        var st = await Retry(o, () => TournamentStandingsAsync(id, ct), ct).ConfigureAwait(false);
        if (TournamentOver(st)) return await FinalRow(o, id, 0, 0, false, ct).ConfigureAwait(false);
        if (!o.NoJoin && !Joined(st))
        {
            try { await Retry(o, () => JoinTournamentAsync(id, ct), ct).ConfigureAwait(false); }
            catch (ConflictException c)
            {
                if (!Joined(await Retry(o, () => TournamentStandingsAsync(id, ct), ct).ConfigureAwait(false)))
                    throw new NeuromateException($"neuromate: could not join tournament {id}: {c.Message}", c);
            }
        }
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            var turn = await Retry(o, () => WaitForTurnAsync("", o.WaitSeconds, ct), ct).ConfigureAwait(false);
            JsonObject? ended = null;
            var check = turn.TimedOut;
            foreach (var ev in turn.Events)
            {
                o.OnEvent?.Invoke(ev);
                var tid = ev.Obj("data").Str("tournament_id");
                if (tid.Length > 0 && tid != id) continue;
                switch (ev.Str("type"))
                {
                    case "tournament_finished" or "tournament_cancelled": ended = ev; break;
                    case "tournament_game_over": check = true; break;
                }
            }
            var pos = turn.Position;
            var busy = false;
            if (pos != null && (pos.TournamentId.Length == 0 || pos.TournamentId == id))
            {
                o.OnPosition?.Invoke(pos);
                if (!pos.IsOver)
                {
                    busy = true;
                    pos = await AnswerOffers(o, pos, ct).ConfigureAwait(false);
                    if (!pos.IsOver && pos.YourTurn) await PlayMove(o, s, fallback, pos, nonce, ct).ConfigureAwait(false);
                }
            }
            if (ended != null) { var d = ended.Obj("data"); return await FinalRow(o, id, (int)d.Long("rank"), d.Dbl("score"), true, ct).ConfigureAwait(false); }
            if (check) { if (TournamentOver(await Retry(o, () => TournamentStandingsAsync(id, ct), ct).ConfigureAwait(false))) return await FinalRow(o, id, 0, 0, false, ct).ConfigureAwait(false); }
            else if (turn.Events.Count == 0 && !busy) await Task.Delay(200, ct).ConfigureAwait(false);
        }
    }

    private async Task<Standing?> FinalRow(PlayOptions o, string id, int rank, double score, bool haveEvent, CancellationToken ct)
    {
        var st = await Retry(o, () => TournamentStandingsAsync(id, ct), ct).ConfigureAwait(false);
        var you = (await Retry(o, () => WhoamiAsync(ct), ct).ConfigureAwait(false)).Obj("you");
        var rows = st.Arr("standings").OfType<JsonObject>().ToList();
        var row = rows.FirstOrDefault(r => you.Str("agent_id").Length > 0 && r.Str("agent_id") == you.Str("agent_id")) ?? rows.FirstOrDefault(r => r.Str("name") == you.Str("name"));
        if (row != null) return new Standing((int)row.Long("rank"), row.Dbl("score"), row);
        var me = st.Obj("tournament").Obj("me");
        if (me.Bool("joined")) return new Standing(haveEvent ? rank : (int)me.Long("rank"), haveEvent ? score : me.Dbl("score"), me);
        return null;
    }
}
