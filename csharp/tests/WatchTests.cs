using System.Text.Json.Nodes;
using Neuromate;
using Xunit;
using static FakeTransport;

public class WatchTests
{
    private static JsonObject OnRules(JsonObject? patch = null)
    {
        var r = new JsonObject
        {
            ["enabled"] = true, ["speeds"] = new JsonArray("blitz"), ["variants"] = new JsonArray("standard"), ["rated"] = "any", ["ruleset"] = "any",
            ["opponent_kinds"] = new JsonArray(), ["max_concurrent"] = 1, ["decline_others"] = false,
        };
        if (patch != null) foreach (var kv in patch.ToList()) { patch.Remove(kv.Key); r[kv.Key] = kv.Value; }
        return r;
    }

    private static JsonNode Standby(JsonObject? patch = null)
    {
        var r = Result("standby_on");
        r["structuredContent"]!["rules"] = OnRules(patch);
        return r;
    }

    private static JsonNode Challenge => Result("wait_challenge_received");
    private static JsonNode Timeout => Result("wait_timeout");

    private static FakeTransport Base(JsonNode standby, params JsonNode[] waits) => new FakeTransport().On("standby", standby).On("whoami", Result("whoami_idle"))
        .On("list_challenges", Result("list_challenges_empty")).On("wait_for_turn", waits).On("accept_challenge", Result("get_game_start"))
        .On("make_move", Result("move_ok")).On("decline_challenge", Result("cancel"));

    private static bool Has(FakeTransport f, string tool) { lock (f) return f.Names.Contains(tool); }

    private static async Task<Watcher> Run(FakeTransport f, Func<Position, Move> h, WatchOptions o, Func<Watcher, bool> until, List<string>? logs = null)
    {
        logs ??= new List<string>();
        o.HeartbeatEvery = TimeSpan.FromMilliseconds(50); o.BusyPoll = TimeSpan.FromMilliseconds(20); o.WaitSeconds = 0.01;
        o.Log = m => { lock (logs) logs.Add(m); };
        var w = new Watcher(new Client(new ClientOptions { ApiKey = "nm_live_test", Transport = f }), h, o);
        var run = Task.Run(() => w.RunAsync());
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline && !until(w) && !run.IsCompleted) await Task.Delay(20);
        w.Stop();
        Assert.True(await Task.WhenAny(run, Task.Delay(5000)) == run, "the watcher did not stop");
        await run;
        return w;
    }

    private static readonly Func<Position, Move> E4 = _ => Move.Of("e4");

    [Fact]
    public void RulesMatchTheSharedVectors()
    {
        var cases = (JsonArray)FakeTransport.FixtureArray("auto_accept_cases");
        Assert.True(cases.Count >= 15);
        foreach (var c in cases.OfType<JsonObject>())
            Assert.Equal((string)c["expect"]!, Rules.FromJson((JsonObject)c["rules"]!).Evaluate((JsonObject)c["challenge"]!, (int)c["playing"]!).Action);
    }

    [Fact]
    public void SpeedAndChallengeInfo()
    {
        Assert.Equal(new[] { "bullet", "blitz", "rapid", "classical", "correspondence", "untimed" }, new[] { "1+0", "3+2", "10+0", "30+0", "2d", "untimed" }.Select(x => Rules.SpeedOf(null, x)));
        Assert.Equal("blitz", Rules.SpeedOf((JsonObject)JsonNode.Parse("{\"kind\":\"realtime\",\"initial_ms\":180000,\"increment_ms\":2000}")!));
        var info = Rules.InfoFromEvent((JsonObject)Challenge["structuredContent"]!["events"]![0]!);
        Assert.Equal("blitz", (string)info["speed"]!); Assert.Equal("agent", (string)info["from_kind"]!); Assert.False((bool)info["rated"]!);
        Assert.Equal("rapid", (string)Rules.InfoFromChallenge((JsonObject)Result("list_challenges_busy")["structuredContent"]!["incoming_challenges"]![0]!)["speed"]!);
    }

    [Fact]
    public async Task StandbyParsesTheOwnersRules()
    {
        var f = new FakeTransport().On("standby", Standby(new JsonObject { ["speeds"] = new JsonArray("bullet") }));
        var d = await new Client(new ClientOptions { ApiKey = "nm_live_test", Transport = f }).StandbyAsync("x");
        Assert.True(d.OnDuty); Assert.Equal(new[] { "bullet" }, d.Rules.Speeds); Assert.Equal(30, d.HeartbeatEverySeconds);
    }

    [Fact]
    public async Task AcceptsAMatchingChallengeAndPlaysTheGame()
    {
        var f = Base(Standby(), Challenge, WaitResult("your_turn", "get_game_start"), WaitResult("game_over", "resign"), Timeout);
        var w = await Run(f, E4, new WatchOptions(), x => x.Played >= 1);
        Assert.Equal(1, w.Played); Assert.Equal(1, w.Accepted);
        Assert.Equal("standby", f.Names[0]);
        Assert.True(Has(f, "accept_challenge") && Has(f, "make_move"));
        Assert.Equal("ch_c4v3a4vr", (string)f.Args[f.Names.IndexOf("accept_challenge")]["id"]!);
        Assert.Equal("off", (string)f.Args[^1]["action"]!);
        Assert.StartsWith("neuromate-dotnet/", (string)f.Args[0]["client"]!);
    }

    [Fact]
    public async Task AChallengeOutsideTheRulesIsLeftOrDeclined()
    {
        var logs = new List<string>();
        var f = Base(Standby(new JsonObject { ["speeds"] = new JsonArray("bullet") }), Challenge, Timeout);
        await Run(f, E4, new WatchOptions(), _ => { lock (logs) return logs.Any(m => m.Contains("left for the owner")); }, logs);
        Assert.False(Has(f, "accept_challenge") || Has(f, "decline_challenge"));
        f = Base(Standby(new JsonObject { ["speeds"] = new JsonArray("bullet"), ["decline_others"] = true }), Challenge, Timeout);
        var ff = f;
        await Run(f, E4, new WatchOptions(), _ => Has(ff, "decline_challenge"));
        Assert.False(Has(f, "accept_challenge"));
        Assert.Contains("blitz", (string)f.Args[f.Names.IndexOf("decline_challenge")]["reason"]!);
    }

    [Fact]
    public async Task LocalRulesOverrideTheOwnersAndACrashingHandlerSurvives()
    {
        var f = Base(Standby(new JsonObject { ["enabled"] = false }), Challenge, WaitResult("your_turn", "get_game_start"), WaitResult("game_over", "resign"), Timeout);
        var logs = new List<string>();
        var w = await Run(f, _ => throw new InvalidOperationException("model crashed"), new WatchOptions { Rules = new Rules { Enabled = true } }, x => x.Played >= 1, logs);
        Assert.Equal(1, w.Accepted);
        Assert.True(Has(f, "make_move"), "a fallback move was played");
        lock (logs) Assert.Contains(logs, m => m.Contains("handler error"));
    }

    [Fact]
    public async Task ResumesARunningGameAfterARestart()
    {
        var gid = (string)Result("get_game_start")["structuredContent"]!["game"]!["game_id"]!;
        var busy = Result("whoami_idle");
        busy["structuredContent"]!["active_games"] = new JsonArray(new JsonObject { ["game_id"] = gid, ["opponent"] = "Bob", ["color"] = "white", ["your_turn"] = true, ["ply"] = 0, ["variant"] = "standard" });
        var f = Base(Standby(), WaitResult("your_turn", "get_game_start"), WaitResult("game_over", "resign"), Timeout).On("whoami", busy);
        var w = await Run(f, E4, new WatchOptions(), x => x.Played >= 1);
        Assert.Equal(1, w.Played);
        Assert.False(Has(f, "accept_challenge"));
    }

    [Fact]
    public async Task ARejectedKeyStopsTheWatcher()
    {
        var f = new FakeTransport { ForcedStatus = 401, ForcedBody = Fixture("http_401")["response"]!.ToJsonString() };
        var w = new Watcher(new Client(new ClientOptions { ApiKey = "nm_live_bad", Transport = f }), E4, new WatchOptions { Log = _ => { } });
        await Assert.ThrowsAsync<AuthException>(() => w.RunAsync());
    }
}
