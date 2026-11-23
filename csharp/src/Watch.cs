using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Neuromate;

// Staying on duty: client.WatchAsync(strategy). A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges are
// held for it instead of expiring), reconnects with backoff, resumes games that were running when it started, accepts challenges by the
// auto-accept rules the owner set on the agent page and calls your strategy for every move. A strategy that throws never forfeits a
// game: a random legal move is played and the error is logged.

/// <summary>What to do with a challenge: "accept", "decline" or "ignore" (leave it for the owner).</summary>
public sealed record Decision(string Action, string Reason = "");

/// <summary>
/// The owner's auto-accept rules (mirrors server/internal/duty/rules.go; both are tested against sdk/testdata/auto_accept_cases.json).
/// Empty lists mean "any".
/// </summary>
public sealed record Rules
{
    public bool Enabled { get; init; }
    public IReadOnlyList<string> Speeds { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> Variants { get; init; } = Array.Empty<string>();
    /// <summary>any | casual | rated</summary>
    public string Rated { get; init; } = "any";
    /// <summary>any | casual | strict</summary>
    public string Ruleset { get; init; } = "any";
    public IReadOnlyList<string> OpponentKinds { get; init; } = Array.Empty<string>();
    public int MaxConcurrent { get; init; } = 1;
    public bool DeclineOthers { get; init; }

    public static Rules FromJson(JsonObject? d)
    {
        d ??= new JsonObject();
        static List<string> Strs(JsonNode? n, string k) => n.Arr(k).Select(x => x is JsonValue v && v.TryGetValue<string>(out var s) ? s : "").ToList();
        return new Rules
        {
            Enabled = d.Bool("enabled"), Speeds = Strs(d, "speeds"), Variants = Strs(d, "variants"),
            Rated = d.Str("rated") is { Length: > 0 } r ? r : "any", Ruleset = d.Str("ruleset") is { Length: > 0 } s ? s : "any",
            OpponentKinds = Strs(d, "opponent_kinds"), MaxConcurrent = (int)Math.Max(1, d.Long("max_concurrent")), DeclineOthers = d.Bool("decline_others"),
        };
    }

    public bool SameAs(Rules o) => Enabled == o.Enabled && Speeds.SequenceEqual(o.Speeds) && Variants.SequenceEqual(o.Variants) && Rated == o.Rated &&
        Ruleset == o.Ruleset && OpponentKinds.SequenceEqual(o.OpponentKinds) && MaxConcurrent == o.MaxConcurrent && DeclineOthers == o.DeclineOthers;

    /// <summary>c: speed, variant, rated, ruleset (casual | fide_strict), from_kind (human | agent | bot). playing: games running now.</summary>
    public Decision Evaluate(JsonObject c, int playing)
    {
        if (!Enabled) return new Decision("ignore", "auto-accept is off");
        Decision Refuse(string why) => new(DeclineOthers ? "decline" : "ignore", why);
        string speed = c.Str("speed"), variant = c.Str("variant"), kind = c.Str("from_kind");
        if (Speeds.Count > 0 && !Speeds.Contains(speed)) return Refuse($"time control {speed} is not accepted");
        if (Variants.Count > 0 && !Variants.Contains(variant)) return Refuse($"variant {variant} is not accepted");
        var rated = c.Bool("rated");
        if (Rated == "casual" && rated) return Refuse("only casual games are accepted");
        if (Rated == "rated" && !rated) return Refuse("only rated games are accepted");
        var strict = c.Str("ruleset") is "fide_strict" or "strict";
        if (Ruleset == "casual" && strict) return Refuse("strict (FIDE) games are not accepted");
        if (Ruleset == "strict" && !strict) return Refuse("only strict (FIDE) games are accepted");
        if (OpponentKinds.Count > 0 && !OpponentKinds.Contains(kind)) return Refuse($"opponents of kind {kind} are not accepted");
        if (playing >= Math.Max(1, MaxConcurrent)) return new Decision("ignore", $"already playing {playing} game(s)");
        return new Decision("accept");
    }

    private static readonly Regex LabelRx = new(@"^\s*(\d+(?:\.\d+)?)\s*(?:[+|]\s*(\d+))?", RegexOptions.Compiled);
    private static readonly Regex DaysRx = new(@"\d\s*d", RegexOptions.Compiled);

    /// <summary>bullet / blitz / rapid / classical / correspondence / untimed, from a control object (may be null) or a label like "3+2".</summary>
    public static string SpeedOf(JsonObject? control = null, string label = "")
    {
        var kind = control.Str("kind");
        if (kind == "correspondence" || DaysRx.IsMatch(label) || label.Contains("day")) return "correspondence";
        if (kind == "untimed" || label.Trim().Equals("untimed", StringComparison.OrdinalIgnoreCase)) return "untimed";
        long est;
        if (control is { Count: > 0 })
        {
            var inc = Math.Max(control.Long("increment_ms"), Math.Max(control.Long("delay_ms"), control.Long("bronstein_ms")));
            est = (control.Long("initial_ms") + 40 * inc) / 1000;
        }
        else
        {
            var m = LabelRx.Match(label);
            if (!m.Success) return "classical";
            est = (long)(double.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) * 60) + 40 * (m.Groups[2].Success ? long.Parse(m.Groups[2].Value) : 0);
        }
        return est < 180 ? "bullet" : est < 480 ? "blitz" : est < 1500 ? "rapid" : "classical";
    }

    private static JsonObject Info(string speed, string variant, bool rated, string ruleset, string kind) => new()
    {
        ["speed"] = speed, ["variant"] = variant.Length == 0 ? "standard" : variant, ["rated"] = rated,
        ["ruleset"] = ruleset.Length == 0 ? "casual" : ruleset, ["from_kind"] = kind.Length == 0 ? "agent" : kind,
    };

    /// <summary>The fields <see cref="Evaluate"/> reads, from a challenge_received event.</summary>
    public static JsonObject InfoFromEvent(JsonObject ev)
    {
        var d = ev.Obj("data");
        return Info(d.Str("speed") is { Length: > 0 } s ? s : SpeedOf(null, d.Str("time_control")), d.Str("variant"), d.Bool("rated"), d.Str("ruleset"), d.Str("from_kind"));
    }

    /// <summary>The same, from an entry of whoami's incoming_challenges.</summary>
    public static JsonObject InfoFromChallenge(JsonObject c)
    {
        var p = c.Obj("params");
        return Info(SpeedOf(p["control"] as JsonObject), p.Str("variant"), p.Bool("rated"), p.Str("ruleset"), c.Obj("from").Str("kind"));
    }
}

/// <summary>Reply of <see cref="Client.StandbyAsync"/>: are you on duty, how often to call it, and the owner's auto-accept rules.</summary>
public sealed record Duty(bool OnDuty, string State, string Mode, Rules Rules, int HeartbeatEverySeconds, int TtlSeconds, int IncomingChallenges)
{
    internal static Duty From(JsonObject d)
    {
        var p = d.Obj("presence");
        var hb = (int)d.Long("heartbeat_every_seconds"); var ttl = (int)d.Long("ttl_seconds");
        return new Duty(d.Bool("on_duty"), p.Str("state"), p.Str("mode"), Rules.FromJson(d.Obj("rules")), hb > 0 ? hb : 30, ttl > 0 ? ttl : 90, (int)d.Long("incoming_challenges"));
    }
}

/// <summary>Options of a <see cref="Watcher"/>; every field has a default.</summary>
public sealed class WatchOptions
{
    /// <summary>Overrides the owner's rules from the agent page when set.</summary>
    public Rules? Rules { get; set; }
    public bool ServerRules { get; set; } = true;
    public TimeSpan HeartbeatEvery { get; set; } = TimeSpan.FromSeconds(30);
    public TimeSpan BusyPoll { get; set; } = TimeSpan.FromSeconds(3);
    public TimeSpan MaxBackoff { get; set; } = TimeSpan.FromSeconds(60);
    public double WaitSeconds { get; set; } = 25;
    public Action<string>? Log { get; set; }
    public string? ClientName { get; set; }
    /// <summary>Options passed to every <c>PlayAsync</c>; the game id is set by the watcher.</summary>
    public PlayOptions Play { get; set; } = new();
}

/// <summary>The durable loop behind <see cref="Client.WatchAsync"/>.</summary>
public sealed class Watcher
{
    private readonly Client client;
    private readonly Func<Position, CancellationToken, Task<Move>> handler;
    private readonly WatchOptions o;
    private readonly Action<string> log;
    private readonly string clientName;
    private readonly CancellationTokenSource stop = new();
    private readonly ConcurrentDictionary<string, Task> games = new();
    private readonly ConcurrentDictionary<string, long> cooldown = new();
    private readonly ConcurrentDictionary<string, bool> seen = new();
    private volatile bool standbyOk = true;
    private volatile Rules rules;
    private int accepted, played;

    public int Accepted => accepted;
    public int Played => played;
    public Rules Rules => rules;

    public Watcher(Client client, Func<Position, Move> strategy, WatchOptions? options = null) : this(client, (p, _) => Task.FromResult(strategy(p)), options) { }

    public Watcher(Client client, Func<Position, CancellationToken, Task<Move>> strategy, WatchOptions? options = null)
    {
        this.client = client;
        o = options ?? new WatchOptions();
        log = o.Log ?? (m => Console.Error.WriteLine($"{DateTime.Now:HH:mm:ss} {m}"));
        clientName = o.ClientName ?? $"neuromate-dotnet/{Client.Version} watch";
        rules = o.Rules ?? new Rules();
        handler = async (pos, ct) =>
        {
            try { return await strategy(pos, ct).ConfigureAwait(false); }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log($"handler error in {pos.GameId}: {e.Message}; playing a fallback move");
                return pos.LegalMoves.Count == 0 ? Move.Of("") : Move.Of(pos.LegalMoves[Random.Shared.Next(pos.LegalMoves.Count)].Uci);
            }
        };
    }

    /// <summary>Ends the loop; the duty is ended cleanly. Safe from any thread.</summary>
    public void Stop() => stop.Cancel();

    private async Task Nap(TimeSpan t)
    {
        try { await Task.Delay(t, stop.Token).ConfigureAwait(false); } catch (OperationCanceledException) { }
    }

    private async Task BeatAsync()
    {
        if (!standbyOk) return;
        Duty d;
        try { d = await client.StandbyAsync(clientName, false, CancellationToken.None).ConfigureAwait(false); }
        catch (ProtocolException e) when (e.Message.Contains("unknown tool"))
        {
            standbyOk = false;
            log("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)");
            return;
        }
        if (o.ServerRules && o.Rules is null)
        {
            if (!d.Rules.SameAs(rules)) log("auto-accept rules updated: " + (d.Rules.Enabled ? "on" : "off"));
            rules = d.Rules;
        }
    }

    private async Task HeartbeatLoop()
    {
        var fails = 0;
        while (!stop.IsCancellationRequested)
        {
            await Nap(o.HeartbeatEvery).ConfigureAwait(false);
            if (stop.IsCancellationRequested) return;
            try { await BeatAsync().ConfigureAwait(false); fails = 0; }
            catch (AuthException) { log("heartbeat: the API key was rejected"); return; }
            catch (NeuromateException e) { fails++; if (fails is 1 or 5) log($"heartbeat failed ({Cut(e.Message, 100)}); retrying"); }
        }
    }

    private static string Cut(string s, int n) => s.Length <= n ? s : s[..n];

    /// <summary>Runs until <see cref="Stop"/> or cancellation of <paramref name="ct"/>; throws <see cref="AuthException"/> when the API key is rejected.</summary>
    public async Task RunAsync(CancellationToken ct = default)
    {
        using var link = CancellationTokenSource.CreateLinkedTokenSource(ct);
        using var reg = link.Token.Register(() => stop.Cancel());
        log($"on duty as {clientName}");
        var backoff = TimeSpan.FromSeconds(1);
        try { await BeatAsync().ConfigureAwait(false); }
        catch (AuthException) { throw; }
        catch (NeuromateException e) { log($"first heartbeat failed ({Cut(e.Message, 100)}); will keep trying"); }
        var hb = Task.Run(HeartbeatLoop);
        var first = true;
        var last = DateTime.MinValue;
        try
        {
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    if (first || DateTime.UtcNow - last > TimeSpan.FromSeconds(30) || !games.IsEmpty) { await ReconcileAsync().ConfigureAwait(false); last = DateTime.UtcNow; first = false; }
                    foreach (var kv in games) if (kv.Value.IsCompleted) games.TryRemove(kv.Key, out _);
                    if (!games.IsEmpty) await Nap(o.BusyPoll).ConfigureAwait(false);
                    else
                    {
                        var turn = await client.WaitForTurnAsync("", o.WaitSeconds, stop.Token).ConfigureAwait(false);
                        await EventsAsync(turn.Events).ConfigureAwait(false);
                        if (turn.Position is { IsOver: false } p && !games.ContainsKey(p.GameId)) Spawn(p.GameId);
                    }
                    backoff = TimeSpan.FromSeconds(1);
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
                catch (AuthException e) { log($"the API key was rejected ({Cut(e.Message, 80)}); stopping"); throw; }
                catch (RateLimitedException e) { await Nap(e.RetryAfter > TimeSpan.FromSeconds(30) ? TimeSpan.FromSeconds(30) : e.RetryAfter).ConfigureAwait(false); }
                catch (NeuromateException e)
                {
                    log($"connection problem ({Cut(e.Message, 100)}); retrying in {backoff.TotalSeconds:0}s");
                    await Nap(backoff * (0.5 + Random.Shared.NextDouble())).ConfigureAwait(false);
                    backoff = TimeSpan.FromTicks(Math.Min(backoff.Ticks * 2, o.MaxBackoff.Ticks));
                }
            }
        }
        finally
        {
            stop.Cancel();
            await Task.WhenAny(hb, Task.Delay(2000)).ConfigureAwait(false);
            await Task.WhenAny(Task.WhenAll(games.Values), Task.Delay(500)).ConfigureAwait(false);
            if (standbyOk)
            {
                try { await client.StandbyAsync(clientName, true, CancellationToken.None).ConfigureAwait(false); log("off duty"); }
                catch (NeuromateException) { }
            }
        }
    }

    private async Task ReconcileAsync()
    {
        var lobby = await client.WhoamiAsync(stop.Token).ConfigureAwait(false);
        foreach (var g in lobby.Arr("active_games")) { var id = g.Str("game_id"); if (!games.ContainsKey(id)) Spawn(id); }
        var evs = lobby.Arr("events").OfType<JsonObject>().ToList();
        await EventsAsync(evs).ConfigureAwait(false);
        var pending = evs.Where(e => e.Str("type") == "challenge_received").Select(e => e.Obj("data").Str("challenge_id")).ToHashSet();
        foreach (var ch in lobby.Arr("incoming_challenges").OfType<JsonObject>())
        {
            var st = ch.Str("status");
            if ((st.Length == 0 || st == "pending") && !pending.Contains(ch.Str("id"))) await ChallengeAsync(ch.Str("id"), Rules.InfoFromChallenge(ch), ch.Obj("from").Str("name")).ConfigureAwait(false);
        }
        if (evs.Count > 0) await client.ListChallengesAsync(evs.Max(e => e.Long("seq")), stop.Token).ConfigureAwait(false);
    }

    private async Task EventsAsync(IEnumerable<JsonObject> evs)
    {
        foreach (var ev in evs)
        {
            var d = ev.Obj("data");
            switch (ev.Str("type"))
            {
                case "challenge_received": await ChallengeAsync(d.Str("challenge_id"), Rules.InfoFromEvent(ev), d.Str("from") is { Length: > 0 } f ? f : "?").ConfigureAwait(false); break;
                case "game_started": if (d.Str("game_id") is { Length: > 0 } gid && !games.ContainsKey(gid)) Spawn(gid); break;
                case "tournament_offline_warning" or "tournament_withdrawn" or "queue_dropped": log($"{ev.Str("type")}: {(d.Str("next").Length > 0 ? d.Str("next") : d.Str("message"))}"); break;
                case "challenge_declined": log("challenge declined: " + d.Str("reason")); break;
            }
        }
    }

    private async Task ChallengeAsync(string cid, JsonObject info, string who)
    {
        if (cid.Length == 0) return;
        var d = rules.Evaluate(info, games.Count);
        var first = !seen.ContainsKey(cid);
        switch (d.Action)
        {
            case "accept":
                Position pos;
                try { pos = await client.AcceptChallengeAsync(cid, stop.Token).ConfigureAwait(false); }
                catch (ToolException e) { log($"could not accept {cid} from {who}: {Cut(e.Message, 120)}"); seen[cid] = true; return; }
                Interlocked.Increment(ref accepted);
                seen[cid] = true;
                log($"accepted {cid} from {who} ({info.Str("speed")} {info.Str("variant")}); game {pos.GameId}");
                Spawn(pos.GameId);
                break;
            case "decline":
                if (first) log($"declined {cid} from {who}: {d.Reason}");
                try { await client.DeclineChallengeAsync(cid, Cut(d.Reason, 200), stop.Token).ConfigureAwait(false); } catch (ToolException) { }
                seen[cid] = true;
                break;
            default:
                if (first)
                {
                    log($"challenge {cid} from {who} left for the owner: {d.Reason}");
                    seen[cid] = true;
                    if (seen.Count > 500) seen.Clear();
                }
                break;
        }
    }

    private void Spawn(string gameId)
    {
        if (gameId.Length == 0 || games.ContainsKey(gameId)) return;
        if (Environment.TickCount64 - cooldown.GetValueOrDefault(gameId, -100_000) < 5000) return;
        var gate = new TaskCompletionSource();
        var t = Task.Run(async () => { await gate.Task.ConfigureAwait(false); await PlayGameAsync(gameId).ConfigureAwait(false); });
        if (!games.TryAdd(gameId, t)) return;
        gate.SetResult();
        log($"playing game {gameId}");
    }

    private async Task PlayGameAsync(string gameId)
    {
        var s = o.Play;
        var po = new PlayOptions
        {
            GameId = gameId, Opponent = s.Opponent, Variant = s.Variant, TimeControl = s.TimeControl, Pool = s.Pool, Rated = s.Rated, AcceptChallenges = s.AcceptChallenges,
            NoJoin = s.NoJoin, MaxIllegal = s.MaxIllegal, MaxRetries = s.MaxRetries, WaitSeconds = s.WaitSeconds, FindTimeout = s.FindTimeout, OnPosition = s.OnPosition,
            OnDrawOffer = s.OnDrawOffer, Fallback = s.Fallback, OnEvent = s.OnEvent,
        };
        try
        {
            var fin = await client.PlayAsync(handler, po, stop.Token).ConfigureAwait(false);
            log($"game {gameId} over: {fin.ResultScore}");
            Interlocked.Increment(ref played);
        }
        catch (OperationCanceledException) { }
        catch (AuthException) { log("the API key was rejected while playing; stopping"); Stop(); }
        catch (Exception e)
        {
            log($"game {gameId}: {Cut(e.Message, 120)}");
            cooldown[gameId] = Environment.TickCount64;
        }
    }
}

public sealed partial class Client
{
    /// <summary>Heartbeat of a watcher: marks you on duty (<paramref name="off"/> ends the duty) and returns your owner's auto-accept rules.</summary>
    public async Task<Duty> StandbyAsync(string? client = null, bool off = false, CancellationToken ct = default) =>
        Duty.From(await DataAsync("standby", Args(("client", client), ("action", off ? "off" : null)), ct).ConfigureAwait(false));

    /// <summary>
    /// Stays on duty until <paramref name="ct"/> is cancelled or <see cref="Watcher.Stop"/> is called: heartbeats, reconnects, the owner's
    /// auto-accept rules and <paramref name="strategy"/> for every move. Returns the watcher after it stopped.
    /// </summary>
    public async Task<Watcher> WatchAsync(Func<Position, Move> strategy, WatchOptions? options = null, CancellationToken ct = default)
    {
        var w = new Watcher(this, strategy, options);
        await w.RunAsync(ct).ConfigureAwait(false);
        return w;
    }
}
