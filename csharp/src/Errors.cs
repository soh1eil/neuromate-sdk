using System.Text.Json.Nodes;

namespace Neuromate;

/// <summary>Base of every error the SDK throws. <see cref="Retryable"/> says whether repeating the call may help.</summary>
public class NeuromateException : Exception
{
    public NeuromateException(string message, Exception? inner = null) : base(message, inner) { }
    public virtual bool Retryable => false;
}

/// <summary>The request never got an answer (connection refused, timeout, DNS).</summary>
public sealed class TransportException : NeuromateException
{
    public TransportException(string m, Exception? inner = null) : base(m, inner) { }
    public override bool Retryable => true;
}

/// <summary>An unexpected HTTP status.</summary>
public sealed class HttpStatusException : NeuromateException
{
    public int Status { get; }
    public HttpStatusException(int status, string body) : base($"HTTP {status}: {body}") => Status = status;
}

/// <summary>HTTP 401: the API key is missing, wrong or revoked.</summary>
public sealed class AuthException : NeuromateException
{
    public string Code { get; }
    public string Hint { get; }
    public AuthException(string m, string code = "", string hint = "") : base(m) { Code = code; Hint = hint; }
}

/// <summary>HTTP 5xx or a platform INTERNAL ERROR.</summary>
public sealed class ServerException : NeuromateException
{
    public ServerException(string m) : base(m) { }
    public override bool Retryable => true;
}

/// <summary>Too many requests; wait <see cref="RetryAfter"/> before retrying.</summary>
public sealed class RateLimitedException : NeuromateException
{
    public TimeSpan RetryAfter { get; }
    public RateLimitedException(string m, TimeSpan retryAfter) : base(m) => RetryAfter = retryAfter;
    public override bool Retryable => true;
}

/// <summary>A JSON-RPC level error (unknown tool, invalid params, unreadable reply).</summary>
public sealed class ProtocolException : NeuromateException
{
    public int Code { get; }
    public JsonNode? Payload { get; }
    public ProtocolException(string m, int code = 0, JsonNode? data = null) : base(m) { Code = code; Payload = data; }
}

/// <summary>
/// A tool ran and said no. <c>Message</c> is the server's complete teaching text; <see cref="Code"/> is the stable machine
/// code (illegal_move, not_your_turn, stale_ply, game_over, model_not_reported, invalid_params, ...).
/// </summary>
public class ToolException : NeuromateException
{
    public string Tool { get; }
    public string Code { get; }
    public string Detail { get; }
    public string Hint { get; }
    public IReadOnlyList<string> LegalMoves { get; }
    public Position? Position { get; }
    public JsonObject Context { get; }

    public ToolException(string tool, string code, string detail, string hint, IReadOnlyList<string> legal, Position? pos, string text, JsonObject ctx)
        : base(text.Length > 0 ? text : detail)
    {
        Tool = tool; Code = code; Detail = detail; Hint = hint; LegalMoves = legal; Position = pos; Context = ctx;
    }

    internal ToolException(ToolException t) : this(t.Tool, t.Code, t.Detail, t.Hint, t.LegalMoves, t.Position, t.Message, t.Context) { }

    internal static ToolException From(string tool, string text, JsonObject structured)
    {
        var err = structured.Obj("error");
        var code = err.Str("code");
        var msg = err.Str("message");
        if (msg.Length == 0) msg = text.Trim();
        var hint = err.Str("hint");
        if (hint.Length == 0) hint = err.Str("what_to_do");
        var legal = err.Arr("legal_moves").Select(x => x?.GetValue<string>() ?? "").ToList();
        var ctx = new JsonObject();
        foreach (var kv in err)
            if (kv.Key is not ("code" or "message" or "hint" or "what_to_do" or "legal_moves")) ctx[kv.Key] = kv.Value?.DeepClone();
        var pos = structured["game"] is JsonObject g ? new Position(g, text) : null;
        var t = new ToolException(tool, code, msg, hint, legal, pos, text, ctx);
        return code switch
        {
            "illegal_move" => new IllegalMoveException(t),
            "not_your_turn" => new NotYourTurnException(t),
            "stale_ply" => new StalePlyException(t),
            "game_over" => new GameOverException(t),
            "model_not_reported" => new ModelNotReportedException(t),
            "conflict" => new ConflictException(t),
            _ => t,
        };
    }
}

public sealed class IllegalMoveException : ToolException { internal IllegalMoveException(ToolException t) : base(t) { } }
public sealed class NotYourTurnException : ToolException { internal NotYourTurnException(ToolException t) : base(t) { } }
/// <summary>expected_ply no longer matches: re-read the game.</summary>
public sealed class StalePlyException : ToolException { internal StalePlyException(ToolException t) : base(t) { } }
public sealed class GameOverException : ToolException { internal GameOverException(ToolException t) : base(t) { } }
/// <summary>report_model has not been called in this session (the client does it for you when you give it a model).</summary>
public sealed class ModelNotReportedException : ToolException { internal ModelNotReportedException(ToolException t) : base(t) { } }
public sealed class ConflictException : ToolException { internal ConflictException(ToolException t) : base(t) { } }

internal static class JsonExt
{
    public static JsonObject Obj(this JsonNode? n, string key = "") => (key.Length == 0 ? n : n?[key]) as JsonObject ?? new JsonObject();
    public static JsonArray Arr(this JsonNode? n, string key) => n?[key] as JsonArray ?? new JsonArray();
    public static string Str(this JsonNode? n, string key) => n?[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";
    public static long Long(this JsonNode? n, string key) => n?[key] is JsonValue v && v.TryGetValue<double>(out var d) ? (long)d : 0;
    public static double Dbl(this JsonNode? n, string key) => n?[key] is JsonValue v && v.TryGetValue<double>(out var d) ? d : 0;
    public static bool Bool(this JsonNode? n, string key) => n?[key] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
}
