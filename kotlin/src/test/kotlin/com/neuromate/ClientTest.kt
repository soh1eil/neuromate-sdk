package com.neuromate

import com.neuromate.FakeTransport.Companion.result
import com.neuromate.FakeTransport.Companion.waitResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClientTest {
    private fun client(f: FakeTransport, key: String = "nm_live_test", model: Model? = null) = Client(apiKey = key, transport = f, model = model)

    @Test
    fun queueThenPlayUntilGameOver() {
        val f = FakeTransport().on("whoami", result("whoami_idle")).on("join_queue", result("join_queue_waiting"))
            .on("wait_for_turn", waitResult("your_turn", "get_game_start"), waitResult("game_over", "get_game_finished"))
            .on("make_move", result("move_ok"))
        var seen = 0
        val end = client(f).play({ seen++; Move("e4", "centre") }, PlayOptions(timeControl = "3+0"))
        assertTrue(end.isOver)
        assertEquals(1, seen)
        assertEquals(listOf("whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"), f.names)
        val mv = f.args[3]
        assertEquals("e4", mv["move"]); assertEquals("centre", mv["note"]); assertEquals(0L, mv["expected_ply"])
        assertTrue((mv["client_move_id"] as String).startsWith(end.gameId))
        assertEquals(mapOf("time_control" to "3+0"), f.args[1])
    }

    @Test
    fun illegalMoveIsTypedAndCarriesCode() {
        val f = FakeTransport().on("make_move", result("err_illegal_move"))
        val e = assertThrows(IllegalMoveException::class.java) { client(f).makeMove("e2e5", clientMoveId = "x2") }
        assertEquals("illegal_move", e.code)
        assertEquals(20, e.legalMoves.size); assertTrue("e2e4" in e.legalMoves)
        assertNotNull(e.position)
        assertTrue(e.message!!.contains("ERROR (illegal_move)"))
    }

    @Test
    fun rejectedMovesAreFedBackThenFallbackIsUsed() {
        val f = FakeTransport().on("wait_for_turn", waitResult("your_turn", "get_game_start"), waitResult("game_over", "get_game_finished"))
            .on("make_move", result("err_illegal_move"), result("err_illegal_move"), result("move_ok"))
        val seen = ArrayList<Int>()
        client(f).play({ seen += it.illegalAttempts.size; Move("e2e5") }, PlayOptions(gameId = "g76p6aeov4", maxIllegal = 1))
        assertEquals(listOf(0), seen)
        assertEquals(3, f.names.count { it == "make_move" })
    }

    @Test
    fun modelIsReportedBeforeTheFirstCall() {
        val f = FakeTransport().on("report_model", result("report_model")).on("join_queue", result("join_queue_waiting"))
        client(f, model = Model("Claude Opus 5", "2026-06", "Anthropic")).joinQueue()
        assertEquals(listOf("report_model", "join_queue"), f.names)
        assertEquals("Claude Opus 5", f.args[0]["name"]); assertEquals("Anthropic", f.args[0]["provider"])
    }

    @Test
    fun modelNotReportedIsRepairedOnce() {
        val f = FakeTransport().on("report_model", result("report_model")).on("join_queue", result("err_model_not_reported"), result("join_queue_waiting"))
        client(f, model = Model("Claude Opus 5")).joinQueue()
        assertEquals(listOf("report_model", "join_queue", "report_model", "join_queue"), f.names)
    }

    @Test
    fun modelNotReportedWithoutModelExplainsHowToFix() {
        val f = FakeTransport().on("join_queue", result("err_model_not_reported"))
        val e = assertThrows(ToolException::class.java) { client(f).joinQueue() }
        assertEquals("model_not_reported", e.code)
        assertTrue(e.detail.contains("model = Model("))
    }

    @Test
    fun unauthorizedIsAuthException() {
        val f = FakeTransport().apply { forcedStatus = 401; forcedBody = Json.stringify(FakeTransport.fixture("http_401")["response"]) }
        val e = assertThrows(AuthException::class.java) { client(f).whoami() }
        assertEquals("unauthorized", e.code); assertFalse(e.retryable)
        assertTrue(e.message!!.contains("Authorization: Bearer"))
    }

    @Test
    fun serverErrorsAreRetryable() {
        val f = FakeTransport().apply { forcedStatus = 503; forcedBody = "down" }
        assertTrue(assertThrows(ServerException::class.java) { client(f).whoami() }.retryable)
    }

    @Test
    fun serverSentEventsReplyIsUnwrapped() {
        val f = FakeTransport().on("whoami", result("whoami_idle")).apply { contentType = "text/event-stream" }
        assertEquals("Alice", client(f).whoami()["you"].obj()["name"])
    }

    @Test
    fun keyIsNeverPrinted() {
        assertFalse(client(FakeTransport(), key = "nm_live_SECRETSECRETSECRET").toString().contains("SECRETSECRET"))
    }

    @Test
    fun tournamentsComeBackAsMaps() {
        val f = FakeTransport().on("list_tournaments", result("tournaments_list"))
        val d = client(f).listTournaments("registering,running", "swiss")
        assertEquals("t_k3m9x2qa", d["tournaments"].arr()[0].obj()["id"])
        assertEquals(mapOf("status" to "registering,running", "kind" to "swiss"), f.args[0])
    }

    @Test
    fun positionParsesClocksAndLegalMoves() {
        val p = Position.of(result("get_game_start")["structuredContent"].obj()["game"].obj(), "")
        assertEquals(20, p.legalMoves.size); assertEquals("white", p.color); assertTrue(p.yourTurn)
        assertTrue(p.secondsForThisMove()!! > 80)
    }

    @Test
    fun chatIsTypedAndUntrusted() {
        val f = FakeTransport().on("send_chat", result("send_chat")).on("read_chat", result("read_chat")).on("get_game", result("get_game_strict"))
        val c = client(f)
        val m = c.sendChat("good luck!", "gzcffjrmdb")
        assertEquals("good luck!", m.text); assertEquals("agent", m.authorKind)
        assertEquals("good luck!", f.args[0]["text"])
        val log = c.readChat("gzcffjrmdb")
        assertEquals(1L, log.lastSeq); assertTrue(log.messages[0].untrusted)
        assertFalse(f.args[1].containsKey("after"))
        val p = c.getGame()
        assertTrue(p.strict); assertFalse(p.hintsAllowed)
    }
}
