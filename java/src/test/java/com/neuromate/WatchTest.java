package com.neuromate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class WatchTest {
    static final Map<String, Object> ON_RULES = new LinkedHashMap<>(Map.of("enabled", true, "speeds", List.of("blitz"), "variants", List.of("standard"),
            "rated", "any", "ruleset", "any", "opponent_kinds", List.of(), "max_concurrent", 1, "decline_others", false));

    static Map<String, Object> standby(Map<String, Object> patch) {
        Map<String, Object> r = Json.object(Json.parse(Json.stringify(FakeTransport.result("standby_on"))));
        Map<String, Object> rules = new LinkedHashMap<>(ON_RULES);
        rules.putAll(patch);
        Json.object(r.get("structuredContent")).put("rules", rules);
        return r;
    }

    static Map<String, Object> standby() { return standby(Map.of()); }

    static final Map<String, Object> CHALLENGE = FakeTransport.result("wait_challenge_received");
    static final Map<String, Object> TIMEOUT = FakeTransport.result("wait_timeout");

    static FakeTransport base(Map<String, Object> standby, Object... waits) {
        return new FakeTransport().on("standby", standby).on("whoami", FakeTransport.result("whoami_idle")).on("list_challenges", FakeTransport.result("list_challenges_empty"))
                .on("wait_for_turn", waits).on("accept_challenge", FakeTransport.result("get_game_start")).on("make_move", FakeTransport.result("move_ok"))
                .on("decline_challenge", FakeTransport.result("cancel"));
    }

    static Watcher run(FakeTransport f, Client.Strategy h, Watcher.Options o, java.util.function.Predicate<Watcher> until, List<String> logs) throws Exception {
        o.heartbeatEveryMs = 50;
        o.busyPollMs = 20;
        o.waitSeconds = 0.01;
        o.log = m -> logs.add(m);
        Client c = Client.builder().apiKey("nm_live_test").transport(f).build();
        Watcher w = new Watcher(c, h, o);
        Thread t = new Thread(w::run);
        t.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !until.test(w)) Thread.sleep(20);
        w.stop();
        t.join(5000);
        assertFalse(t.isAlive(), "the watcher did not stop");
        return w;
    }

    static Client.Strategy e4 = p -> Client.Move.of("e4");

    @Test
    @SuppressWarnings("unchecked")
    void rulesMatchTheSharedVectors() throws Exception {
        List<Object> cases = Json.array(Json.parse(Files.readString(Path.of("..", "testdata", "auto_accept_cases.json"))));
        assertTrue(cases.size() >= 15);
        for (Object o : cases) {
            Map<String, Object> c = Json.object(o);
            String got = Rules.fromMap(Json.object(c.get("rules"))).evaluate(Json.object(c.get("challenge")), (int) Json.num(c.get("playing"))).action;
            assertEquals(Json.str(c.get("expect")), got, Json.str(c.get("name")));
        }
    }

    @Test
    void speedAndChallengeInfo() {
        List<String> got = new ArrayList<>();
        for (String x : new String[] {"1+0", "3+2", "10+0", "30+0", "2d", "untimed"}) got.add(Rules.speedOf(null, x));
        assertEquals(List.of("bullet", "blitz", "rapid", "classical", "correspondence", "untimed"), got);
        assertEquals("blitz", Rules.speedOf(Map.of("kind", "realtime", "initial_ms", 180000, "increment_ms", 2000), ""));
        Map<String, Object> ev = Json.object(Json.array(Json.object(CHALLENGE.get("structuredContent")).get("events")).get(0));
        Map<String, Object> info = Rules.infoFromEvent(ev);
        assertEquals("blitz", info.get("speed"));
        assertEquals("agent", info.get("from_kind"));
        assertEquals(false, info.get("rated"));
        Map<String, Object> busy = Json.object(FakeTransport.result("list_challenges_busy").get("structuredContent"));
        assertEquals("rapid", Rules.infoFromChallenge(Json.object(Json.array(busy.get("incoming_challenges")).get(0))).get("speed"));
    }

    @Test
    void standbyParsesTheOwnersRules() {
        FakeTransport f = new FakeTransport().on("standby", standby(Map.of("speeds", List.of("bullet"))));
        Duty d = Client.builder().apiKey("nm_live_test").transport(f).build().standby("x", false);
        assertTrue(d.onDuty);
        assertEquals(List.of("bullet"), d.rules.speeds);
        assertEquals(30, d.heartbeatEverySeconds);
    }

    @Test
    void acceptsAMatchingChallengeAndPlaysTheGame() throws Exception {
        FakeTransport f = base(standby(), CHALLENGE, FakeTransport.waitResult("your_turn", "get_game_start"), FakeTransport.waitResult("game_over", "resign"), TIMEOUT);
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        Watcher w = run(f, e4, new Watcher.Options(), x -> x.played() >= 1, logs);
        assertEquals(1, w.played());
        assertEquals(1, w.accepted());
        assertEquals("standby", f.names.get(0));
        assertTrue(f.names.contains("accept_challenge") && f.names.contains("make_move"));
        assertEquals("ch_c4v3a4vr", f.args.get(f.names.indexOf("accept_challenge")).get("id"));
        assertEquals("off", f.args.get(f.args.size() - 1).get("action"), "a clean shutdown ends the duty");
        assertTrue(String.valueOf(f.args.get(0).get("client")).startsWith("neuromate-java/"));
    }

    @Test
    void aChallengeOutsideTheRulesIsLeftOrDeclined() throws Exception {
        FakeTransport f = base(standby(Map.of("speeds", List.of("bullet"))), CHALLENGE, TIMEOUT);
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        run(f, e4, new Watcher.Options(), x -> logs.stream().anyMatch(m -> m.contains("left for the owner")), logs);
        assertFalse(f.names.contains("accept_challenge") || f.names.contains("decline_challenge"));
        f = base(standby(Map.of("speeds", List.of("bullet"), "decline_others", true)), CHALLENGE, TIMEOUT);
        FakeTransport ff = f;
        run(f, e4, new Watcher.Options(), x -> ff.names.contains("decline_challenge"), logs);
        assertFalse(f.names.contains("accept_challenge"));
        assertTrue(String.valueOf(f.args.get(f.names.indexOf("decline_challenge")).get("reason")).contains("blitz"));
    }

    @Test
    void localRulesOverrideTheOwnersAndACrashingHandlerSurvives() throws Exception {
        FakeTransport f = base(standby(Map.of("enabled", false)), CHALLENGE, FakeTransport.waitResult("your_turn", "get_game_start"), FakeTransport.waitResult("game_over", "resign"), TIMEOUT);
        Watcher.Options o = new Watcher.Options();
        o.rules = Rules.enabled();
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        Watcher w = run(f, p -> { throw new IllegalStateException("model crashed"); }, o, x -> x.played() >= 1, logs);
        assertEquals(1, w.accepted());
        assertTrue(f.names.contains("make_move"), "a fallback move was played");
        assertTrue(logs.stream().anyMatch(m -> m.contains("handler error")));
    }

    @Test
    void resumesARunningGameAfterARestart() throws Exception {
        String gid = Json.str(Json.object(Json.object(FakeTransport.result("get_game_start").get("structuredContent")).get("game")).get("game_id"));
        Map<String, Object> busy = Json.object(Json.parse(Json.stringify(FakeTransport.result("whoami_idle"))));
        Map<String, Object> game = new LinkedHashMap<>(Map.of("game_id", gid, "opponent", "Bob", "color", "white", "your_turn", true, "ply", 0, "variant", "standard"));
        Json.object(busy.get("structuredContent")).put("active_games", List.of(game));
        FakeTransport f = base(standby(), FakeTransport.waitResult("your_turn", "get_game_start"), FakeTransport.waitResult("game_over", "resign"), TIMEOUT).on("whoami", busy);
        Watcher w = run(f, e4, new Watcher.Options(), x -> x.played() >= 1, Collections.synchronizedList(new ArrayList<>()));
        assertEquals(1, w.played());
        assertFalse(f.names.contains("accept_challenge"));
    }

    @Test
    void aRejectedKeyStopsTheWatcher() {
        FakeTransport f = new FakeTransport();
        f.forcedStatus = 401;
        f.forcedBody = Json.stringify(FakeTransport.fixture("http_401").get("response"));
        Watcher.Options o = new Watcher.Options();
        o.log = m -> { };
        Client c = Client.builder().apiKey("nm_live_bad").transport(f).build();
        assertThrows(NeuromateException.Auth.class, () -> new Watcher(c, e4, o).run());
    }
}
