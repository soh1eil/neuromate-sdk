import XCTest
@testable import Neuromate

/// Replays recorded tool results (sdk/testdata) as a scripted MCP server.
final class Fake: Transport, @unchecked Sendable {
    private let lock = NSLock()
    private var script: [String: [JSON]] = [:]
    private(set) var names: [String] = []
    private(set) var args: [JSON] = []
    var forced: TransportReply?
    var contentType = "application/json"

    @discardableResult func on(_ tool: String, _ results: JSON...) -> Fake { script[tool] = results; return self }

    func client(key: String = "nm_live_test", model: Model? = nil) throws -> Client { try Client(apiKey: key, model: model, transport: self) }

    func post(url: String, headers: [String: String], body: String, timeout: TimeInterval) async throws -> TransportReply {
        lock.lock(); defer { lock.unlock() }
        if let f = forced { return f }
        let req = try JSONSerialization.jsonObject(with: Data(body.utf8)) as! JSON
        let method = req["method"] as! String
        if method == "notifications/initialized" { return TransportReply(status: 202, headers: [:], body: "") }
        let result: JSON
        if method == "initialize" {
            result = ["serverInfo": ["name": "fake", "version": "1"]]
        } else {
            let p = req["params"] as! JSON
            let name = p["name"] as! String
            names.append(name); args.append(p["arguments"] as? JSON ?? [:])
            guard var q = script[name] else { fatalError("no scripted reply for \(name)") }
            result = q.count > 1 ? q.removeFirst() : q[0]
            script[name] = q
        }
        var json = String(decoding: try JSONSerialization.data(withJSONObject: ["jsonrpc": "2.0", "id": req["id"]!, "result": result]), as: UTF8.self)
        if contentType.contains("event-stream") { json = "event: message\ndata: \(json)\n\n" }
        return TransportReply(status: 200, headers: ["content-type": contentType], body: json)
    }
}

func fixture(_ name: String) -> JSON {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("testdata/\(name).json")
    return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! JSON
}
func result(_ name: String) -> JSON { (fixture(name)["response"] as! JSON)["result"] as! JSON }
func waitResult(_ reason: String, _ gameFrom: String) -> JSON {
    ["content": [["type": "text", "text": "WAIT"]], "structuredContent": ["reason": reason, "events": [] as [Any], "game": (result(gameFrom)["structuredContent"] as! JSON)["game"]!]]
}
extension Dictionary: @retroactive Equatable where Key == String, Value == Any {
    public static func == (a: [String: Any], b: [String: Any]) -> Bool { NSDictionary(dictionary: a).isEqual(to: b) }
}

final class ClientTests: XCTestCase {
    func testQueueThenPlayUntilGameOver() async throws {
        let f = Fake().on("whoami", result("whoami_idle")).on("join_queue", result("join_queue_waiting"))
            .on("wait_for_turn", waitResult("your_turn", "get_game_start"), waitResult("game_over", "get_game_finished"))
            .on("make_move", result("move_ok"))
        var o = PlayOptions(); o.timeControl = "3+0"
        var seen = 0
        let end = try await f.client().play(o) { _ in seen += 1; return Move("e4", note: "centre") }
        XCTAssertTrue(end.isOver)
        XCTAssertEqual(seen, 1)
        XCTAssertEqual(f.names, ["whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"])
        let mv = f.args[3]
        XCTAssertEqual(mv["move"] as? String, "e4"); XCTAssertEqual(mv["note"] as? String, "centre"); XCTAssertEqual(mv["expected_ply"] as? Int, 0)
        XCTAssertTrue((mv["client_move_id"] as! String).hasPrefix(end.gameId))
        XCTAssertEqual(f.args[1].count, 1); XCTAssertEqual(f.args[1]["time_control"] as? String, "3+0")
    }

    func testIllegalMoveIsTypedAndCarriesCode() async throws {
        let f = Fake().on("make_move", result("err_illegal_move"))
        do { _ = try await f.client().makeMove("e2e5", clientMoveId: "x2"); XCTFail() } catch NeuromateError.tool(let t) {
            XCTAssertEqual(t.kind, .illegalMove); XCTAssertEqual(t.code, "illegal_move")
            XCTAssertEqual(t.legalMoves.count, 20); XCTAssertTrue(t.legalMoves.contains("e2e4"))
            XCTAssertNotNil(t.position)
            XCTAssertTrue(t.description.contains("ERROR (illegal_move)"))
        }
    }

    func testRejectedMovesAreFedBackThenFallbackIsUsed() async throws {
        let f = Fake().on("wait_for_turn", waitResult("your_turn", "get_game_start"), waitResult("game_over", "get_game_finished"))
            .on("make_move", result("err_illegal_move"), result("err_illegal_move"), result("move_ok"))
        var o = PlayOptions(); o.gameId = "g76p6aeov4"; o.maxIllegal = 1
        var seen: [Int] = []
        _ = try await f.client().play(o) { p in seen.append(p.illegalAttempts.count); return Move("e2e5") }
        XCTAssertEqual(seen, [0])
        XCTAssertEqual(f.names.filter { $0 == "make_move" }.count, 3)
    }

    func testModelIsReportedBeforeTheFirstCall() async throws {
        let f = Fake().on("report_model", result("report_model")).on("join_queue", result("join_queue_waiting"))
        _ = try await f.client(model: Model("Claude Opus 5", version: "2026-06", provider: "Anthropic")).joinQueue()
        XCTAssertEqual(f.names, ["report_model", "join_queue"])
        XCTAssertEqual(f.args[0]["name"] as? String, "Claude Opus 5"); XCTAssertEqual(f.args[0]["provider"] as? String, "Anthropic")
    }

    func testModelNotReportedIsRepairedOnce() async throws {
        let f = Fake().on("report_model", result("report_model")).on("join_queue", result("err_model_not_reported"), result("join_queue_waiting"))
        _ = try await f.client(model: Model("Claude Opus 5")).joinQueue()
        XCTAssertEqual(f.names, ["report_model", "join_queue", "report_model", "join_queue"])
    }

    func testModelNotReportedWithoutModelExplainsHowToFix() async throws {
        let f = Fake().on("join_queue", result("err_model_not_reported"))
        do { _ = try await f.client().joinQueue(); XCTFail() } catch NeuromateError.tool(let t) {
            XCTAssertEqual(t.kind, .modelNotReported)
            XCTAssertTrue(t.message.contains("Model("))
        }
    }

    func testUnauthorizedIsAuthError() async throws {
        let f = Fake()
        f.forced = TransportReply(status: 401, headers: [:], body: String(decoding: try JSONSerialization.data(withJSONObject: fixture("http_401")["response"]!), as: UTF8.self))
        do { _ = try await f.client().whoami(); XCTFail() } catch let e as NeuromateError {
            guard case .auth = e else { return XCTFail("\(e)") }
            XCTAssertEqual(e.code, "unauthorized"); XCTAssertFalse(e.retryable)
            XCTAssertTrue(e.description.contains("Authorization: Bearer"))
        }
    }

    func testServerErrorsAreRetryable() async throws {
        let f = Fake(); f.forced = TransportReply(status: 503, headers: [:], body: "down")
        do { _ = try await f.client().whoami(); XCTFail() } catch let e as NeuromateError { XCTAssertTrue(e.retryable) }
    }

    func testServerSentEventsReplyIsUnwrapped() async throws {
        let f = Fake().on("whoami", result("whoami_idle")); f.contentType = "text/event-stream"
        let w = try await f.client().whoami()
        XCTAssertEqual((w["you"] as? JSON)?["name"] as? String, "Alice")
    }

    func testKeyIsNeverPrinted() throws {
        XCTAssertFalse(try Fake().client(key: "nm_live_SECRETSECRETSECRET").description.contains("SECRETSECRET"))
    }

    func testTournamentsComeBackAsDictionaries() async throws {
        let f = Fake().on("list_tournaments", result("tournaments_list"))
        let d = try await f.client().listTournaments(status: "registering,running", kind: "swiss")
        XCTAssertEqual(((d["tournaments"] as! [JSON])[0])["id"] as? String, "t_k3m9x2qa")
        XCTAssertEqual(f.args[0]["status"] as? String, "registering,running"); XCTAssertEqual(f.args[0]["kind"] as? String, "swiss")
    }

    func testPositionParsesClocksAndLegalMoves() {
        let p = Position(((result("get_game_start")["structuredContent"]) as! JSON)["game"] as! JSON)
        XCTAssertEqual(p.legalMoves.count, 20); XCTAssertEqual(p.color, "white"); XCTAssertTrue(p.yourTurn)
        XCTAssertGreaterThan(p.secondsForThisMove ?? 0, 80)
    }

    func testChatIsTypedAndUntrusted() async throws {
        let f = Fake().on("send_chat", result("send_chat")).on("read_chat", result("read_chat")).on("get_game", result("get_game_strict"))
        let c = try f.client()
        let m = try await c.sendChat("good luck!", gameId: "gzcffjrmdb")
        XCTAssertEqual(m.text, "good luck!"); XCTAssertEqual(m.authorKind, "agent")
        XCTAssertEqual(f.args[0]["text"] as? String, "good luck!")
        let log = try await c.readChat("gzcffjrmdb")
        XCTAssertEqual(log.lastSeq, 1); XCTAssertTrue(log.messages[0].untrusted)
        XCTAssertNil(f.args[1]["after"])
        let p = try await c.getGame()
        XCTAssertTrue(p.strict); XCTAssertFalse(p.hintsAllowed)
    }
}
