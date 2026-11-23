import Foundation

// Staying on duty: `client.watch(strategy)`. A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges are
// held for it instead of expiring), reconnects with backoff, resumes games that were running when it started, accepts challenges by the
// auto-accept rules the owner set on the agent page and calls your strategy for every move, one task per game. A strategy that throws
// never forfeits a game: a random legal move is played and the error is logged.

/// What to do with a challenge.
public struct Decision: Sendable, Equatable {
    /// `accept`, `decline` or `ignore` (leave it for the owner).
    public let action: String
    public let reason: String
    public init(_ action: String, _ reason: String = "") { self.action = action; self.reason = reason }
}

/// The owner's auto-accept rules (mirrors server/internal/duty/rules.go; both are tested against sdk/testdata/auto_accept_cases.json).
/// Empty lists mean "any".
public struct Rules: Sendable, Equatable {
    public var enabled = false
    public var speeds: [String] = [], variants: [String] = []
    /// `any`, `casual` or `rated`.
    public var rated = "any"
    /// `any`, `casual` or `strict`.
    public var ruleset = "any"
    public var opponentKinds: [String] = []
    public var maxConcurrent = 1
    public var declineOthers = false

    public init(enabled: Bool = false, speeds: [String] = [], variants: [String] = [], rated: String = "any", ruleset: String = "any",
                opponentKinds: [String] = [], maxConcurrent: Int = 1, declineOthers: Bool = false) {
        self.enabled = enabled; self.speeds = speeds; self.variants = variants; self.rated = rated; self.ruleset = ruleset
        self.opponentKinds = opponentKinds; self.maxConcurrent = max(1, maxConcurrent); self.declineOthers = declineOthers
    }

    public init(json d: JSON) {
        func strs(_ k: String) -> [String] { (d[k] as? [Any])?.compactMap { $0 as? String } ?? [] }
        let rated = d["rated"] as? String ?? "", ruleset = d["ruleset"] as? String ?? ""
        self.init(enabled: d["enabled"] as? Bool ?? false, speeds: strs("speeds"), variants: strs("variants"), rated: rated.isEmpty ? "any" : rated,
                  ruleset: ruleset.isEmpty ? "any" : ruleset, opponentKinds: strs("opponent_kinds"), maxConcurrent: (d["max_concurrent"] as? NSNumber)?.intValue ?? 1,
                  declineOthers: d["decline_others"] as? Bool ?? false)
    }

    /// `c`: speed, variant, rated, ruleset (casual | fide_strict), from_kind (human | agent | bot). `playing`: games running now.
    public func evaluate(_ c: JSON, playing: Int) -> Decision {
        guard enabled else { return Decision("ignore", "auto-accept is off") }
        func refuse(_ why: String) -> Decision { Decision(declineOthers ? "decline" : "ignore", why) }
        let speed = c["speed"] as? String ?? "", variant = c["variant"] as? String ?? "", kind = c["from_kind"] as? String ?? ""
        if !speeds.isEmpty && !speeds.contains(speed) { return refuse("time control \(speed) is not accepted") }
        if !variants.isEmpty && !variants.contains(variant) { return refuse("variant \(variant) is not accepted") }
        let isRated = c["rated"] as? Bool ?? false
        if rated == "casual" && isRated { return refuse("only casual games are accepted") }
        if rated == "rated" && !isRated { return refuse("only rated games are accepted") }
        let rs = c["ruleset"] as? String ?? ""
        let strict = rs == "fide_strict" || rs == "strict"
        if ruleset == "casual" && strict { return refuse("strict (FIDE) games are not accepted") }
        if ruleset == "strict" && !strict { return refuse("only strict (FIDE) games are accepted") }
        if !opponentKinds.isEmpty && !opponentKinds.contains(kind) { return refuse("opponents of kind \(kind) are not accepted") }
        if playing >= max(1, maxConcurrent) { return Decision("ignore", "already playing \(playing) game(s)") }
        return Decision("accept")
    }

    /// bullet / blitz / rapid / classical / correspondence / untimed, from a control object (or nil) or a label like "3+2".
    public static func speedOf(control: JSON? = nil, label: String = "") -> String {
        let kind = control?["kind"] as? String ?? ""
        if kind == "correspondence" || label.range(of: #"\d\s*d"#, options: .regularExpression) != nil || label.contains("day") { return "correspondence" }
        if kind == "untimed" || label.trimmingCharacters(in: .whitespaces).lowercased() == "untimed" { return "untimed" }
        let est: Int
        if let c = control, !c.isEmpty {
            func n(_ k: String) -> Int { (c[k] as? NSNumber)?.intValue ?? 0 }
            est = (n("initial_ms") + 40 * max(n("increment_ms"), n("delay_ms"), n("bronstein_ms"))) / 1000
        } else {
            guard let r = label.range(of: #"^\s*(\d+(?:\.\d+)?)\s*(?:[+|]\s*(\d+))?"#, options: .regularExpression) else { return "classical" }
            let m = String(label[r]).trimmingCharacters(in: .whitespaces)
            let parts = m.split(whereSeparator: { $0 == "+" || $0 == "|" }).map { $0.trimmingCharacters(in: .whitespaces) }
            est = Int((Double(parts[0]) ?? 0) * 60) + 40 * (parts.count > 1 ? Int(parts[1]) ?? 0 : 0)
        }
        return est < 180 ? "bullet" : est < 480 ? "blitz" : est < 1500 ? "rapid" : "classical"
    }

    private static func info(_ speed: String, _ variant: String, _ rated: Bool, _ ruleset: String, _ kind: String) -> JSON {
        ["speed": speed, "variant": variant.isEmpty ? "standard" : variant, "rated": rated, "ruleset": ruleset.isEmpty ? "casual" : ruleset, "from_kind": kind.isEmpty ? "agent" : kind]
    }

    /// The fields `evaluate` reads, from a `challenge_received` event.
    public static func info(fromEvent ev: JSON) -> JSON {
        let d = ev["data"] as? JSON ?? [:]
        let speed = d["speed"] as? String ?? ""
        return info(speed.isEmpty ? speedOf(label: d["time_control"] as? String ?? "") : speed, d["variant"] as? String ?? "", d["rated"] as? Bool ?? false,
                    d["ruleset"] as? String ?? "", d["from_kind"] as? String ?? "")
    }

    /// The same, from an entry of whoami's `incoming_challenges`.
    public static func info(fromChallenge c: JSON) -> JSON {
        let p = c["params"] as? JSON ?? [:]
        return info(speedOf(control: p["control"] as? JSON), p["variant"] as? String ?? "", p["rated"] as? Bool ?? false, p["ruleset"] as? String ?? "",
                    (c["from"] as? JSON)?["kind"] as? String ?? "")
    }
}

/// Reply of `Client.standby`: are you on duty, how often to call it, and the owner's auto-accept rules.
public struct Duty: Sendable {
    public let onDuty: Bool
    /// `playing`, `on_duty` or `offline`.
    public let state: String
    /// `watcher`, `webhook` or `active`.
    public let mode: String
    public let rules: Rules
    public let heartbeatEverySeconds, ttlSeconds, incomingChallenges: Int

    init(_ d: JSON) {
        let p = d["presence"] as? JSON ?? [:]
        onDuty = d["on_duty"] as? Bool ?? false
        state = p["state"] as? String ?? ""
        mode = p["mode"] as? String ?? ""
        rules = Rules(json: d["rules"] as? JSON ?? [:])
        let hb = (d["heartbeat_every_seconds"] as? NSNumber)?.intValue ?? 0, ttl = (d["ttl_seconds"] as? NSNumber)?.intValue ?? 0
        heartbeatEverySeconds = hb > 0 ? hb : 30
        ttlSeconds = ttl > 0 ? ttl : 90
        incomingChallenges = (d["incoming_challenges"] as? NSNumber)?.intValue ?? 0
    }
}

extension Client {
    /// Heartbeat of a watcher: marks you on duty (`off` ends the duty) and returns your owner's auto-accept rules.
    public func standby(client name: String = "", off: Bool = false) async throws -> Duty {
        var a: JSON = [:]
        if !name.isEmpty { a["client"] = name }
        if off { a["action"] = "off" }
        return Duty(try await callTool("standby", a).data)
    }

    /// Stays on duty until the task is cancelled or `Watcher.stop()` is called; returns the watcher after it stopped.
    @discardableResult
    public func watch(_ options: WatchOptions = WatchOptions(), strategy: @escaping Strategy) async throws -> Watcher {
        let w = Watcher(self, options, strategy)
        try await withTaskCancellationHandler { try await w.run() } onCancel: { w.stop() }
        return w
    }
}

/// Options of a `Watcher`; every field has a default.
public struct WatchOptions: @unchecked Sendable {
    /// Overrides the owner's rules from the agent page when set.
    public var rules: Rules?
    public var serverRules = true
    public var heartbeatEvery: TimeInterval = 30, busyPoll: TimeInterval = 3, maxBackoff: TimeInterval = 60, waitSeconds = 25.0
    public var log: (@Sendable (String) -> Void)?
    public var clientName: String?
    /// Options passed to every `play`; the game id is set by the watcher.
    public var play = PlayOptions()
    public init() {}
}

/// The durable loop behind `Client.watch`.
public final class Watcher: @unchecked Sendable {
    private let client: Client
    private let o: WatchOptions
    private let handler: Strategy
    private let logf: @Sendable (String) -> Void
    private let name: String
    private let lock = NSLock()
    private var _stopped = false, _standbyOk = true
    private var _rules: Rules
    private var games: [String: Task<Void, Never>] = [:]
    private var cooldown: [String: Date] = [:]
    private var seen = Set<String>()
    private var _accepted = 0, _played = 0

    init(_ client: Client, _ options: WatchOptions, _ strategy: @escaping Strategy) {
        self.client = client
        o = options
        let log: @Sendable (String) -> Void = options.log ?? { m in
            let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
            FileHandle.standardError.write(Data("\(f.string(from: Date())) \(m)\n".utf8))
        }
        logf = log
        name = options.clientName ?? "neuromate-swift/\(Client.version) watch"
        _rules = options.rules ?? Rules()
        handler = { pos in
            do { return try await strategy(pos) } catch {
                log("handler error in \(pos.gameId): \(error); playing a fallback move")
                return Move(pos.legalMoves.randomElement()?.uci ?? "")
            }
        }
    }

    public convenience init(_ client: Client, options: WatchOptions = WatchOptions(), strategy: @escaping Strategy) { self.init(client, options, strategy) }

    private func locked<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }
    public var stopped: Bool { locked { _stopped } }
    public var rules: Rules { locked { _rules } }
    public var accepted: Int { locked { _accepted } }
    public var played: Int { locked { _played } }

    /// Ends the loop (the duty is ended cleanly). Safe from any thread.
    public func stop() { locked { _stopped = true } }

    private func nap(_ seconds: TimeInterval) async {
        let end = Date().addingTimeInterval(seconds)
        while !stopped && !Task.isCancelled {
            let left = end.timeIntervalSinceNow
            if left <= 0 { return }
            try? await Task.sleep(nanoseconds: UInt64(min(left, 0.05) * 1e9))
        }
    }

    private func beat() async throws {
        guard locked({ _standbyOk }) else { return }
        do {
            let d = try await client.standby(client: name)
            if o.serverRules && o.rules == nil {
                if d.rules != rules { logf("auto-accept rules updated: \(d.rules.enabled ? "on" : "off")") }
                locked { _rules = d.rules }
            }
        } catch NeuromateError.protocolError(_, let m) where m.contains("unknown tool") {
            locked { _standbyOk = false }
            logf("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)")
        }
    }

    private func heartbeatLoop() async {
        var fails = 0
        while !stopped {
            await nap(o.heartbeatEvery)
            if stopped { return }
            do { try await beat(); fails = 0 } catch NeuromateError.auth {
                logf("heartbeat: the API key was rejected"); return
            } catch {
                fails += 1
                if fails == 1 || fails == 5 { logf("heartbeat failed (\(String(String(describing: error).prefix(100)))); retrying") }
            }
        }
    }

    /// Runs until `stop()` or task cancellation; throws `NeuromateError.auth` when the API key is rejected.
    public func run() async throws {
        logf("on duty as \(name)")
        var backoff = 1.0
        do { try await beat() } catch NeuromateError.auth { throw NeuromateError.auth(message: "the API key was rejected", code: "", hint: "") } catch {
            logf("first heartbeat failed (\(String(String(describing: error).prefix(100)))); will keep trying")
        }
        let hb = Task { await self.heartbeatLoop() }
        var first = true
        var last = Date.distantPast
        var failure: Error?
        while !stopped && !Task.isCancelled {
            do {
                if first || Date().timeIntervalSince(last) > 30 || !locked({ games.isEmpty }) {
                    try await reconcile()
                    last = Date(); first = false
                }
                if !locked({ games.isEmpty }) {
                    await nap(o.busyPoll)
                } else {
                    let turn = try await client.waitForTurn("", timeoutSeconds: o.waitSeconds)
                    try await events(turn.events)
                    if let p = turn.position, !p.isOver, !locked({ games[p.gameId] != nil }) { spawn(p.gameId) }
                }
                backoff = 1
            } catch let e as NeuromateError {
                switch e {
                case .auth: logf("the API key was rejected (\(String(e.description.prefix(80)))); stopping"); failure = e; stop()
                case .rateLimited(_, let ra): await nap(min(ra, 30))
                default:
                    logf("connection problem (\(String(e.description.prefix(100)))); retrying in \(Int(backoff))s")
                    await nap(backoff * (0.5 + Double.random(in: 0..<1)))
                    backoff = min(backoff * 2, o.maxBackoff)
                }
            } catch is CancellationError { break } catch {
                logf("unexpected error (\(error)); retrying"); await nap(backoff)
            }
        }
        stop()
        _ = await hb.value
        if locked({ _standbyOk }) {
            if (try? await client.standby(client: name, off: true)) != nil { logf("off duty") }
        }
        if let f = failure { throw f }
    }

    private func reconcile() async throws {
        let lobby = try await client.whoami()
        for g in lobby["active_games"] as? [JSON] ?? [] {
            let id = g["game_id"] as? String ?? ""
            if !locked({ games[id] != nil }) { spawn(id) }
        }
        let evs = lobby["events"] as? [JSON] ?? []
        try await events(evs)
        let pending = Set(evs.filter { $0["type"] as? String == "challenge_received" }.map { ($0["data"] as? JSON)?["challenge_id"] as? String ?? "" })
        for ch in lobby["incoming_challenges"] as? [JSON] ?? [] {
            let st = ch["status"] as? String ?? "", id = ch["id"] as? String ?? ""
            if (st.isEmpty || st == "pending") && !pending.contains(id) {
                try await challenge(id, Rules.info(fromChallenge: ch), (ch["from"] as? JSON)?["name"] as? String ?? "")
            }
        }
        let maxSeq = evs.compactMap { ($0["seq"] as? NSNumber)?.intValue }.max() ?? 0
        if maxSeq > 0 { _ = try await client.listChallenges(ack: maxSeq) }
    }

    private func events(_ evs: [JSON]) async throws {
        for ev in evs {
            let d = ev["data"] as? JSON ?? [:]
            switch ev["type"] as? String ?? "" {
            case "challenge_received":
                let who = d["from"] as? String ?? ""
                try await challenge(d["challenge_id"] as? String ?? "", Rules.info(fromEvent: ev), who.isEmpty ? "?" : who)
            case "game_started":
                let id = d["game_id"] as? String ?? ""
                if !id.isEmpty && !locked({ games[id] != nil }) { spawn(id) }
            case "tournament_offline_warning", "tournament_withdrawn", "queue_dropped":
                let t = d["next"] as? String ?? ""
                logf("\(ev["type"] as? String ?? ""): \(t.isEmpty ? d["message"] as? String ?? "" : t)")
            case "challenge_declined": logf("challenge declined: \(d["reason"] as? String ?? "")")
            default: break
            }
        }
    }

    private func challenge(_ cid: String, _ info: JSON, _ who: String) async throws {
        guard !cid.isEmpty else { return }
        let d = rules.evaluate(info, playing: locked { games.count })
        let first = !locked { seen.contains(cid) }
        switch d.action {
        case "accept":
            let pos: Position
            do { pos = try await client.acceptChallenge(cid) } catch NeuromateError.tool(let e) {
                logf("could not accept \(cid) from \(who): \(String(e.description.prefix(120)))")
                locked { _ = seen.insert(cid) }
                return
            }
            locked { _accepted += 1; _ = seen.insert(cid) }
            logf("accepted \(cid) from \(who) (\(info["speed"] as? String ?? "") \(info["variant"] as? String ?? "")); game \(pos.gameId)")
            spawn(pos.gameId)
        case "decline":
            if first { logf("declined \(cid) from \(who): \(d.reason)") }
            do { _ = try await client.declineChallenge(cid, reason: String(d.reason.prefix(200))) } catch NeuromateError.tool {}
            locked { _ = seen.insert(cid) }
        default:
            if first {
                logf("challenge \(cid) from \(who) left for the owner: \(d.reason)")
                locked { if seen.count > 500 { seen.removeAll() }; _ = seen.insert(cid) }
            }
        }
    }

    private func spawn(_ gameId: String) {
        guard !gameId.isEmpty else { return }
        let started: Bool = locked {
            if games[gameId] != nil { return false }
            if let t = cooldown[gameId], Date().timeIntervalSince(t) < 5 { return false }
            games[gameId] = Task { [self] in await playGame(gameId) }
            return true
        }
        if started { logf("playing game \(gameId)") }
    }

    private func playGame(_ gameId: String) async {
        var po = o.play
        po.gameId = gameId
        do {
            let fin = try await client.play(po, strategy: handler)
            logf("game \(gameId) over: \(fin.resultScore)")
            locked { _played += 1 }
        } catch NeuromateError.auth {
            logf("the API key was rejected while playing; stopping"); stop()
        } catch {
            logf("game \(gameId): \(String(String(describing: error).prefix(120)))")
            locked { cooldown[gameId] = Date() }
        }
        locked { _ = games.removeValue(forKey: gameId) }
    }
}
