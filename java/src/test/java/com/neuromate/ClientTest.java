package com.neuromate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientTest {
    private static Client.Builder b(FakeTransport f) {
        return Client.builder().apiKey("nm_live_test").transport(f);
    }

    @Test
    void queueThenPlayUntilGameOver() {
        FakeTransport f = new FakeTransport()
                .on("whoami", FakeTransport.result("whoami_idle"))
                .on("join_queue", FakeTransport.result("join_queue_waiting"))
                .on("wait_for_turn", FakeTransport.waitResult("your_turn", "get_game_start"), FakeTransport.waitResult("game_over", "get_game_finished"))
                .on("make_move", FakeTransport.result("move_ok"));
        Client.PlayOptions o = new Client.PlayOptions();
        o.timeControl = "3+0";
        int[] seen = {0};
        Position end = b(f).build().play(p -> {
            seen[0]++;
            return new Client.Move("e4", "centre");
        }, o);
        assertTrue(end.isOver());
        assertEquals(1, seen[0]);
        assertEquals(List.of("whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"), f.names);
        Map<String, Object> mv = f.args.get(3);
        assertEquals("e4", mv.get("move"));
        assertEquals("centre", mv.get("note"));
        assertEquals(0L, mv.get("expected_ply"));
        assertTrue(((String) mv.get("client_move_id")).startsWith(end.gameId));
        assertEquals(Map.of("time_control", "3+0"), f.args.get(1));
    }

    @Test
    void illegalMoveIsTypedAndCarriesCode() {
        FakeTransport f = new FakeTransport().on("make_move", FakeTransport.result("err_illegal_move"));
        NeuromateException.IllegalMove e = assertThrows(NeuromateException.IllegalMove.class, () -> b(f).build().makeMove(null, "e2e5", "x2", null, null));
        assertEquals("illegal_move", e.code);
        assertTrue(e.legalMoves.contains("e2e4"));
        assertEquals(20, e.legalMoves.size());
        assertNotNull(e.position);
        assertTrue(e.getMessage().contains("ERROR (illegal_move)"));
    }

    @Test
    void playFeedsRejectedMovesBackThenUsesFallback() {
        FakeTransport f = new FakeTransport()
                .on("wait_for_turn", FakeTransport.waitResult("your_turn", "get_game_start"), FakeTransport.waitResult("game_over", "get_game_finished"))
                .on("make_move", FakeTransport.result("err_illegal_move"), FakeTransport.result("err_illegal_move"), FakeTransport.result("move_ok"));
        Client.PlayOptions o = new Client.PlayOptions();
        o.gameId = "g76p6aeov4";
        o.maxIllegal = 1;
        int[] calls = {0};
        List<Integer> attempts = new java.util.ArrayList<>();
        b(f).build().play(p -> {
            calls[0]++;
            attempts.add(p.illegalAttempts.size());
            return Client.Move.of("e2e5");
        }, o);
        assertEquals(1, calls[0]);
        assertEquals(List.of(0), attempts);
        assertEquals(3, f.names.stream().filter("make_move"::equals).count());
    }

    @Test
    void modelIsReportedBeforeTheFirstCall() {
        FakeTransport f = new FakeTransport().on("report_model", FakeTransport.result("report_model")).on("join_queue", FakeTransport.result("join_queue_waiting"));
        b(f).model("Claude Opus 5", "2026-06", "Anthropic").build().joinQueue(Client.game(null, "3+0", null), null);
        assertEquals(List.of("report_model", "join_queue"), f.names);
        assertEquals("Claude Opus 5", f.args.get(0).get("name"));
        assertEquals("Anthropic", f.args.get(0).get("provider"));
    }

    @Test
    void modelNotReportedIsRepairedOnce() {
        FakeTransport f = new FakeTransport().on("report_model", FakeTransport.result("report_model"))
                .on("join_queue", FakeTransport.result("err_model_not_reported"), FakeTransport.result("join_queue_waiting"));
        // The client reports at connect time; make the first join fail anyway (a server restart) and expect a second report.
        Client c = b(f).model("Claude Opus 5", null, null).build();
        c.joinQueue(null, null);
        assertEquals(List.of("report_model", "join_queue", "report_model", "join_queue"), f.names);
    }

    @Test
    void modelNotReportedWithoutModelExplainsHowToFix() {
        FakeTransport f = new FakeTransport().on("join_queue", FakeTransport.result("err_model_not_reported"));
        try {
            b(f).build().joinQueue(null, null);
            fail();
        } catch (NeuromateException.Tool t) {
            assertEquals("model_not_reported", t.code);
            assertTrue(t.detail.contains("model("));
        }
    }

    @Test
    void unauthorizedIs401AuthError() {
        FakeTransport f = new FakeTransport();
        f.forcedStatus = 401;
        f.forcedBody = Json.stringify(FakeTransport.fixture("http_401").get("response"));
        NeuromateException.Auth e = assertThrows(NeuromateException.Auth.class, () -> b(f).build().whoami());
        assertEquals("unauthorized", e.code);
        assertFalse(e.retryable());
        assertTrue(e.getMessage().contains("Authorization: Bearer"));
    }

    @Test
    void rateLimitedAndServerErrorsAreRetryable() {
        FakeTransport f = new FakeTransport();
        f.forcedStatus = 503;
        f.forcedBody = "down";
        NeuromateException e = assertThrows(NeuromateException.Server.class, () -> b(f).build().whoami());
        assertTrue(e.retryable());
    }

    @Test
    void serverSentEventsReplyIsUnwrapped() {
        FakeTransport f = new FakeTransport().on("whoami", FakeTransport.result("whoami_idle"));
        f.contentType = "text/event-stream";
        Map<String, Object> w = b(f).build().whoami();
        assertEquals("Alice", Json.object(w.get("you")).get("name"));
    }

    @Test
    void keyIsNeverPrinted() {
        Client c = b(new FakeTransport()).apiKey("nm_live_SECRETSECRETSECRET").build();
        assertFalse(c.toString().contains("SECRETSECRET"));
    }

    @Test
    void tournamentsComeBackAsMaps() {
        FakeTransport f = new FakeTransport().on("list_tournaments", FakeTransport.result("tournaments_list"));
        Map<String, Object> d = b(f).build().listTournaments("registering,running", "swiss");
        assertEquals("t_k3m9x2qa", Json.object(Json.array(d.get("tournaments")).get(0)).get("id"));
        assertEquals(Map.of("status", "registering,running", "kind", "swiss"), f.args.get(0));
    }

    @Test
    void positionParsesClocksAndLegalMoves() {
        Position p = Position.of(Json.object(Json.object(FakeTransport.result("get_game_start").get("structuredContent")).get("game")), "");
        assertEquals(20, p.legalMoves.size());
        assertEquals("white", p.color);
        assertTrue(p.yourTurn);
        assertTrue(p.secondsForThisMove() > 80);
    }

    @Test
    void chatIsTypedAndUntrusted() {
        FakeTransport f = new FakeTransport().on("send_chat", FakeTransport.result("send_chat")).on("read_chat", FakeTransport.result("read_chat"))
                .on("get_game", FakeTransport.result("get_game_strict"));
        Client c = b(f).build();
        Client.ChatMessage m = c.sendChat("gzcffjrmdb", "good luck!");
        assertEquals("good luck!", m.text);
        assertEquals("agent", m.authorKind);
        assertEquals("good luck!", f.args.get(0).get("text"));
        Client.ChatLog log = c.readChat("gzcffjrmdb", 0);
        assertEquals(1, log.lastSeq);
        assertTrue(log.messages.get(0).untrusted);
        assertFalse(f.args.get(1).containsKey("after"));
        Position p = c.getGame("");
        assertTrue(p.strict());
        assertFalse(p.hintsAllowed);
    }
}
