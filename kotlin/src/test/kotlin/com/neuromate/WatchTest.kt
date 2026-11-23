package com.neuromate

import com.neuromate.FakeTransport.Companion.result
import com.neuromate.FakeTransport.Companion.waitResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

class WatchTest {
    private val onRules: JsonObject = mapOf("enabled" to true, "speeds" to listOf("blitz"), "variants" to listOf("standard"), "rated" to "any", "ruleset" to "any",
        "opponent_kinds" to emptyList<String>(), "max_concurrent" to 1, "decline_others" to false)

    private fun standby(patch: JsonObject = emptyMap()): JsonObject {
        val r = Json.parse(Json.stringify(result("standby_on"))).obj().toMutableMap()
        val sc = r["structuredContent"].obj().toMutableMap()
        sc["rules"] = onRules + patch
        r["structuredContent"] = sc
        return r
    }

    private val challenge = result("wait_challenge_received")
    private val timeout = result("wait_timeout")

    private fun base(standby: JsonObject, vararg waits: Any?) = FakeTransport().on("standby", standby).on("whoami", result("whoami_idle"))
        .on("list_challenges", result("list_challenges_empty")).on("wait_for_turn", *waits).on("accept_challenge", result("get_game_start"))
        .on("make_move", result("move_ok")).on("decline_challenge", result("cancel"))

    private fun run(f: FakeTransport, h: Strategy = { Move("e4") }, o: WatchOptions = WatchOptions(), logs: MutableList<String> = Collections.synchronizedList(ArrayList()), until: (Watcher) -> Boolean): Watcher {
        o.heartbeatEveryMs = 50; o.busyPollMs = 20; o.waitSeconds = 0.01; o.log = { logs += it }
        val w = Watcher(Client(apiKey = "nm_live_test", transport = f), h, o)
        val t = Thread { w.run() }.also { it.start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !until(w)) Thread.sleep(20)
        w.stop()
        t.join(5000)
        assertFalse(t.isAlive, "the watcher did not stop")
        return w
    }

    @Test
    fun rulesMatchTheSharedVectors() {
        val cases = Json.parse(Files.readString(Path.of("..", "testdata", "auto_accept_cases.json"))).arr()
        assertTrue(cases.size >= 15)
        for (c in cases.map { it.obj() }) {
            assertEquals(c["expect"].str(), Rules.fromJson(c["rules"].obj()).evaluate(c["challenge"].obj(), c["playing"].long().toInt()).action, c["name"].str())
        }
    }

    @Test
    fun speedAndChallengeInfo() {
        assertEquals(listOf("bullet", "blitz", "rapid", "classical", "correspondence", "untimed"), listOf("1+0", "3+2", "10+0", "30+0", "2d", "untimed").map { Rules.speedOf(null, it) })
        assertEquals("blitz", Rules.speedOf(mapOf("kind" to "realtime", "initial_ms" to 180000, "increment_ms" to 2000)))
        val info = Rules.infoFromEvent(challenge["structuredContent"].obj()["events"].arr()[0].obj())
        assertEquals(listOf("blitz", "agent", "standard", false), listOf(info["speed"], info["from_kind"], info["variant"], info["rated"]))
        assertEquals("rapid", Rules.infoFromChallenge(result("list_challenges_busy")["structuredContent"].obj()["incoming_challenges"].arr()[0].obj())["speed"])
    }

    @Test
    fun standbyParsesTheOwnersRules() {
        val d = Client(apiKey = "nm_live_test", transport = FakeTransport().on("standby", standby(mapOf("speeds" to listOf("bullet"))))).standby("x")
        assertTrue(d.onDuty); assertEquals(listOf("bullet"), d.rules.speeds); assertEquals(30, d.heartbeatEverySeconds)
    }

    @Test
    fun acceptsAMatchingChallengeAndPlaysTheGame() {
        val f = base(standby(), challenge, waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), timeout)
        val w = run(f) { it.played >= 1 }
        assertEquals(1, w.played); assertEquals(1, w.accepted)
        assertEquals("standby", f.names[0])
        assertTrue("accept_challenge" in f.names && "make_move" in f.names)
        assertEquals("ch_c4v3a4vr", f.args[f.names.indexOf("accept_challenge")]["id"])
        assertEquals("off", f.args.last()["action"], "a clean shutdown ends the duty")
        assertTrue((f.args[0]["client"] as String).startsWith("neuromate-kotlin/"))
    }

    @Test
    fun aChallengeOutsideTheRulesIsLeftOrDeclined() {
        var f = base(standby(mapOf("speeds" to listOf("bullet"))), challenge, timeout)
        val logs = Collections.synchronizedList(ArrayList<String>())
        run(f, logs = logs) { logs.any { "left for the owner" in it } }
        assertFalse("accept_challenge" in f.names || "decline_challenge" in f.names)
        f = base(standby(mapOf("speeds" to listOf("bullet"), "decline_others" to true)), challenge, timeout)
        val ff = f
        run(f) { "decline_challenge" in ff.names }
        assertFalse("accept_challenge" in f.names)
        assertTrue((f.args[f.names.indexOf("decline_challenge")]["reason"] as String).contains("blitz"))
    }

    @Test
    fun localRulesOverrideTheOwnersAndACrashingHandlerSurvives() {
        val f = base(standby(mapOf("enabled" to false)), challenge, waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), timeout)
        val logs = Collections.synchronizedList(ArrayList<String>())
        val w = run(f, h = { throw IllegalStateException("model crashed") }, o = WatchOptions(rules = Rules(enabled = true)), logs = logs) { it.played >= 1 }
        assertEquals(1, w.accepted)
        assertTrue("make_move" in f.names, "a fallback move was played")
        assertTrue(logs.any { "handler error" in it })
    }

    @Test
    fun resumesARunningGameAfterARestart() {
        val gid = result("get_game_start")["structuredContent"].obj()["game"].obj()["game_id"].str()
        val busy = Json.parse(Json.stringify(result("whoami_idle"))).obj().toMutableMap()
        busy["structuredContent"] = busy["structuredContent"].obj() + ("active_games" to listOf(mapOf("game_id" to gid, "opponent" to "Bob", "color" to "white", "your_turn" to true, "ply" to 0, "variant" to "standard")))
        val f = base(standby(), waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), timeout).on("whoami", busy)
        val w = run(f) { it.played >= 1 }
        assertEquals(1, w.played)
        assertFalse("accept_challenge" in f.names)
    }

    @Test
    fun aRejectedKeyStopsTheWatcher() {
        val f = FakeTransport().apply { forcedStatus = 401; forcedBody = Json.stringify(FakeTransport.fixture("http_401")["response"]) }
        assertThrows(AuthException::class.java) { Watcher(Client(apiKey = "nm_live_bad", transport = f), { Move("e4") }, WatchOptions(log = {})).run() }
    }
}
