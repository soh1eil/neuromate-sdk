import XCTest
@testable import Neuromate

private func rulesJSON(_ patch: JSON = [:]) -> JSON {
    var r: JSON = ["enabled": true, "speeds": ["blitz"], "variants": ["standard"], "rated": "any", "ruleset": "any", "opponent_kinds": [] as [Any], "max_concurrent": 1, "decline_others": false]
    for (k, v) in patch { r[k] = v }
    return r
}

private func standbyResult(_ patch: JSON = [:]) -> JSON {
    var r = result("standby_on")
    var sc = r["structuredContent"] as! JSON
    sc["rules"] = rulesJSON(patch)
    r["structuredContent"] = sc
    return r
}

private final class Logs: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []
    func add(_ m: String) { lock.lock(); items.append(m); lock.unlock() }
    var all: [String] { lock.lock(); defer { lock.unlock() }; return items }
}

final class WatchTests: XCTestCase {
    private func base(_ standby: JSON, _ waits: JSON...) -> Fake {
        let f = Fake()
        f.on("standby", standby).on("whoami", result("whoami_idle")).on("list_challenges", result("list_challenges_empty"))
            .on("accept_challenge", result("get_game_start")).on("make_move", result("move_ok")).on("decline_challenge", result("cancel"))
        f.on("wait_for_turn", waits.first!, Array(waits.dropFirst()))
        return f
    }

    private func run(_ f: Fake, rules: Rules? = nil, logs: Logs = Logs(), strategy: @escaping Strategy = { _ in Move("e4") }, until: @escaping (Watcher) -> Bool) async throws -> Watcher {
        var o = WatchOptions()
        o.heartbeatEvery = 0.05; o.busyPoll = 0.02; o.waitSeconds = 0.01; o.rules = rules; o.log = { logs.add($0) }
        let w = Watcher(try f.client(), o, strategy)
        let t = Task { try await w.run() }
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline && !until(w) { try await Task.sleep(nanoseconds: 20_000_000) }
        w.stop()
        try await t.value
        return w
    }

    func testRulesMatchTheSharedVectors() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("testdata/auto_accept_cases.json")
        let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [JSON]
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let got = Rules(json: c["rules"] as! JSON).evaluate(c["challenge"] as! JSON, playing: (c["playing"] as! NSNumber).intValue).action
            XCTAssertEqual(got, c["expect"] as? String, c["name"] as? String ?? "")
        }
    }

    func testSpeedAndChallengeInfo() {
        XCTAssertEqual(["1+0", "3+2", "10+0", "30+0", "2d", "untimed"].map { Rules.speedOf(label: $0) }, ["bullet", "blitz", "rapid", "classical", "correspondence", "untimed"])
        XCTAssertEqual(Rules.speedOf(control: ["kind": "realtime", "initial_ms": 180000, "increment_ms": 2000]), "blitz")
        let ev = ((result("wait_challenge_received")["structuredContent"] as! JSON)["events"] as! [JSON])[0]
        let info = Rules.info(fromEvent: ev)
        XCTAssertEqual(info["speed"] as? String, "blitz"); XCTAssertEqual(info["from_kind"] as? String, "agent"); XCTAssertEqual(info["rated"] as? Bool, false)
        let busy = ((result("list_challenges_busy")["structuredContent"] as! JSON)["incoming_challenges"] as! [JSON])[0]
        XCTAssertEqual(Rules.info(fromChallenge: busy)["speed"] as? String, "rapid")
    }

    func testStandbyParsesTheOwnersRules() async throws {
        let d = try await Fake().on("standby", standbyResult(["speeds": ["bullet"]])).client().standby(client: "x")
        XCTAssertTrue(d.onDuty); XCTAssertEqual(d.rules.speeds, ["bullet"]); XCTAssertEqual(d.heartbeatEverySeconds, 30)
    }

    func testAcceptsAMatchingChallengeAndPlaysTheGame() async throws {
        let f = base(standbyResult(), result("wait_challenge_received"), waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), result("wait_timeout"))
        let w = try await run(f) { $0.played >= 1 }
        XCTAssertEqual(w.played, 1); XCTAssertEqual(w.accepted, 1)
        let s = f.snapshot()
        XCTAssertEqual(s.names.first, "standby")
        XCTAssertTrue(s.names.contains("accept_challenge") && s.names.contains("make_move"))
        XCTAssertEqual(s.args[s.names.firstIndex(of: "accept_challenge")!]["id"] as? String, "ch_c4v3a4vr")
        XCTAssertEqual(s.args.last?["action"] as? String, "off", "a clean shutdown ends the duty")
        XCTAssertTrue((s.args[0]["client"] as? String ?? "").hasPrefix("neuromate-swift/"))
    }

    func testAChallengeOutsideTheRulesIsLeftOrDeclined() async throws {
        var f = base(standbyResult(["speeds": ["bullet"]]), result("wait_challenge_received"), result("wait_timeout"))
        let logs = Logs()
        _ = try await run(f, logs: logs) { _ in logs.all.contains { $0.contains("left for the owner") } }
        XCTAssertFalse(f.snapshot().names.contains("accept_challenge") || f.snapshot().names.contains("decline_challenge"))
        f = base(standbyResult(["speeds": ["bullet"], "decline_others": true]), result("wait_challenge_received"), result("wait_timeout"))
        let ff = f
        _ = try await run(f) { _ in ff.snapshot().names.contains("decline_challenge") }
        let s = f.snapshot()
        XCTAssertFalse(s.names.contains("accept_challenge"))
        XCTAssertTrue((s.args[s.names.firstIndex(of: "decline_challenge")!]["reason"] as? String ?? "").contains("blitz"))
    }

    func testLocalRulesOverrideTheOwnersAndAThrowingStrategySurvives() async throws {
        struct Boom: Error {}
        let f = base(standbyResult(["enabled": false]), result("wait_challenge_received"), waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), result("wait_timeout"))
        let logs = Logs()
        let w = try await run(f, rules: Rules(enabled: true), logs: logs, strategy: { _ in throw Boom() }) { $0.played >= 1 }
        XCTAssertEqual(w.accepted, 1)
        XCTAssertTrue(f.snapshot().names.contains("make_move"), "a fallback move was played")
        XCTAssertTrue(logs.all.contains { $0.contains("handler error") })
    }

    func testResumesARunningGameAfterARestart() async throws {
        let gid = (((result("get_game_start")["structuredContent"] as! JSON)["game"]) as! JSON)["game_id"] as! String
        var busy = result("whoami_idle")
        var sc = busy["structuredContent"] as! JSON
        sc["active_games"] = [["game_id": gid, "opponent": "Bob", "color": "white", "your_turn": true, "ply": 0, "variant": "standard"] as JSON]
        busy["structuredContent"] = sc
        let f = base(standbyResult(), waitResult("your_turn", "get_game_start"), waitResult("game_over", "resign"), result("wait_timeout"))
        f.on("whoami", busy)
        let w = try await run(f) { $0.played >= 1 }
        XCTAssertEqual(w.played, 1)
        XCTAssertFalse(f.snapshot().names.contains("accept_challenge"))
    }

    func testARejectedKeyStopsTheWatcher() async throws {
        let f = Fake()
        f.forced = TransportReply(status: 401, headers: ["content-type": "application/json"], body: String(decoding: try JSONSerialization.data(withJSONObject: fixture("http_401")["response"]!), as: UTF8.self))
        var o = WatchOptions(); o.log = { _ in }
        let w = Watcher(try f.client(key: "nm_live_bad"), o, { _ in Move("e4") })
        do { try await w.run(); XCTFail("expected an auth error") } catch NeuromateError.auth {} catch { XCTFail("wrong error \(error)") }
    }
}
