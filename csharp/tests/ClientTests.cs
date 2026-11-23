using System.Text.Json.Nodes;
using Neuromate;
using Xunit;
using static FakeTransport;

public class ClientTests
{
    private static Client C(FakeTransport f, string key = "nm_live_test", Model? model = null) =>
        new(new ClientOptions { ApiKey = key, Transport = f, Model = model });

    [Fact]
    public async Task QueueThenPlayUntilGameOver()
    {
        var f = new FakeTransport().On("whoami", Result("whoami_idle")).On("join_queue", Result("join_queue_waiting"))
            .On("wait_for_turn", WaitResult("your_turn", "get_game_start"), WaitResult("game_over", "get_game_finished"))
            .On("make_move", Result("move_ok"));
        var seen = 0;
        var end = await C(f).PlayAsync(_ => { seen++; return Move.Of("e4", "centre"); }, new PlayOptions { TimeControl = "3+0" });
        Assert.True(end.IsOver);
        Assert.Equal(1, seen);
        Assert.Equal(new[] { "whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn" }, f.Names);
        var mv = f.Args[3];
        Assert.Equal("e4", (string)mv["move"]!); Assert.Equal("centre", (string)mv["note"]!); Assert.Equal(0, (int)mv["expected_ply"]!);
        Assert.StartsWith(end.GameId, (string)mv["client_move_id"]!);
        Assert.Equal("3+0", (string)f.Args[1]["time_control"]!); Assert.Single(f.Args[1]);
    }

    [Fact]
    public async Task IllegalMoveIsTypedAndCarriesCode()
    {
        var f = new FakeTransport().On("make_move", Result("err_illegal_move"));
        var e = await Assert.ThrowsAsync<IllegalMoveException>(() => C(f).MakeMoveAsync("e2e5", clientMoveId: "x2"));
        Assert.Equal("illegal_move", e.Code);
        Assert.Equal(20, e.LegalMoves.Count); Assert.Contains("e2e4", e.LegalMoves);
        Assert.NotNull(e.Position);
        Assert.Contains("ERROR (illegal_move)", e.Message);
    }

    [Fact]
    public async Task RejectedMovesAreFedBackThenFallbackIsUsed()
    {
        var f = new FakeTransport().On("wait_for_turn", WaitResult("your_turn", "get_game_start"), WaitResult("game_over", "get_game_finished"))
            .On("make_move", Result("err_illegal_move"), Result("err_illegal_move"), Result("move_ok"));
        var seen = new List<int>();
        await C(f).PlayAsync(p => { seen.Add(p.IllegalAttempts.Count); return Move.Of("e2e5"); }, new PlayOptions { GameId = "g76p6aeov4", MaxIllegal = 1 });
        Assert.Equal(new[] { 0 }, seen);
        Assert.Equal(3, f.Names.Count(n => n == "make_move"));
    }

    [Fact]
    public async Task ModelIsReportedBeforeTheFirstCall()
    {
        var f = new FakeTransport().On("report_model", Result("report_model")).On("join_queue", Result("join_queue_waiting"));
        await C(f, model: new Model("Claude Opus 5", "2026-06", "Anthropic")).JoinQueueAsync();
        Assert.Equal(new[] { "report_model", "join_queue" }, f.Names);
        Assert.Equal("Claude Opus 5", (string)f.Args[0]["name"]!); Assert.Equal("Anthropic", (string)f.Args[0]["provider"]!);
    }

    [Fact]
    public async Task ModelNotReportedIsRepairedOnce()
    {
        var f = new FakeTransport().On("report_model", Result("report_model")).On("join_queue", Result("err_model_not_reported"), Result("join_queue_waiting"));
        await C(f, model: new Model("Claude Opus 5")).JoinQueueAsync();
        Assert.Equal(new[] { "report_model", "join_queue", "report_model", "join_queue" }, f.Names);
    }

    [Fact]
    public async Task ModelNotReportedWithoutModelExplainsHowToFix()
    {
        var f = new FakeTransport().On("join_queue", Result("err_model_not_reported"));
        var e = await Assert.ThrowsAsync<ToolException>(() => C(f).JoinQueueAsync());
        Assert.Equal("model_not_reported", e.Code);
        Assert.Contains("new Model(", e.Detail);
    }

    [Fact]
    public async Task UnauthorizedIsAuthException()
    {
        var f = new FakeTransport { ForcedStatus = 401, ForcedBody = Fixture("http_401")["response"]!.ToJsonString() };
        var e = await Assert.ThrowsAsync<AuthException>(() => C(f).WhoamiAsync());
        Assert.Equal("unauthorized", e.Code); Assert.False(e.Retryable);
        Assert.Contains("Authorization: Bearer", e.Message);
    }

    [Fact]
    public async Task ServerErrorsAreRetryable()
    {
        var f = new FakeTransport { ForcedStatus = 503, ForcedBody = "down" };
        Assert.True((await Assert.ThrowsAsync<ServerException>(() => C(f).WhoamiAsync())).Retryable);
    }

    [Fact]
    public async Task ServerSentEventsReplyIsUnwrapped()
    {
        var f = new FakeTransport { ContentType = "text/event-stream" }.On("whoami", Result("whoami_idle"));
        var w = await C(f).WhoamiAsync();
        Assert.Equal("Alice", (string)w["you"]!["name"]!);
    }

    [Fact]
    public void KeyIsNeverPrinted() => Assert.DoesNotContain("SECRETSECRET", C(new FakeTransport(), "nm_live_SECRETSECRETSECRET").ToString());

    [Fact]
    public async Task TournamentsComeBackAsObjects()
    {
        var f = new FakeTransport().On("list_tournaments", Result("tournaments_list"));
        var d = await C(f).ListTournamentsAsync("registering,running", "swiss");
        Assert.Equal("t_k3m9x2qa", (string)d["tournaments"]![0]!["id"]!);
        Assert.Equal("registering,running", (string)f.Args[0]["status"]!); Assert.Equal("swiss", (string)f.Args[0]["kind"]!);
    }

    [Fact]
    public void PositionParsesClocksAndLegalMoves()
    {
        var p = new Position((JsonObject)Result("get_game_start")["structuredContent"]!["game"]!);
        Assert.Equal(20, p.LegalMoves.Count); Assert.Equal("white", p.Color); Assert.True(p.YourTurn);
        Assert.True(p.TimeForThisMove()!.Value.TotalSeconds > 80);
    }

    [Fact]
    public async Task ChatIsTypedAndUntrusted()
    {
        var f = new FakeTransport().On("send_chat", Result("send_chat")).On("read_chat", Result("read_chat")).On("get_game", Result("get_game_strict"));
        var c = C(f);
        var m = await c.SendChatAsync("good luck!", "gzcffjrmdb");
        Assert.Equal("good luck!", m.Text); Assert.Equal("agent", m.AuthorKind);
        Assert.Equal("good luck!", (string)f.Args[0]["text"]!);
        var log = await c.ReadChatAsync("gzcffjrmdb");
        Assert.Equal(1, log.LastSeq); Assert.True(log.Messages[0].Untrusted);
        Assert.False(f.Args[1].ContainsKey("after"));
        var p = await c.GetGameAsync();
        Assert.True(p.Strict); Assert.False(p.HintsAllowed);
    }
}
