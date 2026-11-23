import Foundation

/// Chooses a move; called whenever it is your turn.
public typealias Strategy = (Position) async throws -> Move

extension Client {
    private func retry<T>(_ o: PlayOptions, _ fn: () async throws -> T) async throws -> T {
        var delay = 0.25
        var i = 0
        while true {
            do { return try await fn() } catch let e as NeuromateError {
                if !e.retryable || i >= o.maxRetries { throw e }
                var wait = delay + Double.random(in: 0..<delay)
                if case .rateLimited(_, let ra) = e { wait = ra }
                try await Task.sleep(nanoseconds: UInt64(min(wait, 30) * 1e9))
                if delay < 4 { delay *= 2 }
                i += 1
            }
        }
    }

    private func randomLegal(_ p: Position) -> Move { Move(p.legalMoves.randomElement()?.uci ?? "") }

    /// Plays one complete game and returns the final position. Without `gameId` it resumes your active game, else
    /// challenges `opponent`, else joins the matchmaking queue (leaving it again if something fails while queued).
    public func play(_ o: PlayOptions = PlayOptions(), strategy: Strategy) async throws -> Position {
        let nonce = String(UUID().uuidString.prefix(6)).lowercased()
        var gameId = o.gameId
        var queued = false
        do {
            if gameId.isEmpty {
                let lobby = try await retry(o) { try await whoami() }
                let maxSeq = (lobby["events"] as? [JSON] ?? []).compactMap { ($0["seq"] as? NSNumber)?.intValue }.max() ?? 0
                if maxSeq > 0 { _ = try await retry(o) { try await listChallenges(ack: maxSeq) } }
                let mine = (lobby["active_games"] as? [JSON] ?? []).sorted { ($0["your_turn"] as? Bool ?? false) && !($1["your_turn"] as? Bool ?? false) }
                let go = Client.game(variant: o.variant, timeControl: o.timeControl, rated: o.rated)
                if let g = mine.first {
                    gameId = g["game_id"] as? String ?? ""
                } else if !o.opponent.isEmpty {
                    _ = try await retry(o) { try await challenge(o.opponent, go) }
                } else {
                    queued = true
                    let q = try await retry(o) { try await joinQueue(go, pool: o.pool) }
                    if let p = q.position { gameId = p.gameId; queued = false }
                }
            }
            let started = Date()
            while true {
                try Task.checkCancellation()
                if gameId.isEmpty && o.findTimeout > 0 && Date().timeIntervalSince(started) > o.findTimeout {
                    throw NeuromateError.usage("neuromate: no game started within \(o.findTimeout) s")
                }
                let gid = gameId
                let turn = try await retry(o) { try await waitForTurn(gid, timeoutSeconds: o.waitSeconds) }
                var pos = turn.position
                if gameId.isEmpty && o.acceptChallenges {
                    for ev in turn.events {
                        let id = (ev["data"] as? JSON)?["challenge_id"] as? String ?? ""
                        if ev["type"] as? String == "challenge_received" && !id.isEmpty { pos = try await retry(o) { try await acceptChallenge(id) } }
                    }
                }
                guard var p = pos, !(gameId.isEmpty && p.isOver) else { continue }
                if gameId.isEmpty { gameId = p.gameId; queued = false }
                if p.gameId != gameId { continue }
                o.onPosition?(p)
                if p.isOver { return p }
                p = try await answerOffers(o, p)
                if p.isOver { return p }
                if !p.yourTurn { continue }
                if let done = try await playMove(o, strategy, p, nonce), done.isOver { return done }
            }
        } catch {
            if queued && gameId.isEmpty { _ = try? await leaveQueue() }
            throw error
        }
    }

    private func answerOffers(_ o: PlayOptions, _ start: Position) async throws -> Position {
        var p = start
        let gid = p.gameId
        if p.drawOffer == "from_opponent" {
            let accept = o.onDrawOffer?(p) ?? false
            p = try await retry(o) { try await respondDraw(accept: accept, gid) }
        }
        if !p.isOver && p.takebackRequest == "from_opponent" { p = try await retry(o) { try await respondTakeback(accept: false, gid) } }
        return p
    }

    private func playMove(_ o: PlayOptions, _ strategy: Strategy, _ start: Position, _ nonce: String) async throws -> Position? {
        var pos = start
        var attempts: [String] = []
        var n = 0
        while true {
            var view = pos
            view.illegalAttempts = attempts
            let mv = attempts.count >= o.maxIllegal ? (o.fallback?(view) ?? randomLegal(view)) : try await strategy(view)
            let gid = pos.gameId
            if mv.resign { return try await retry(o) { try await resign(gid) } }
            if mv.move.isEmpty { throw NeuromateError.usage("neuromate: your strategy returned an empty move; return Move(\"e2e4\") or Move.resign") }
            n += 1
            let cmid = "\(gid)-\(pos.ply + 1)-\(nonce)-\(n)"
            let ply = pos.ply
            do {
                return try await retry(o) { try await makeMove(mv.move, gameId: gid, clientMoveId: cmid, expectedPly: ply, note: mv.note) }.position
            } catch NeuromateError.tool(let t) {
                switch t.kind {
                case .illegalMove:
                    attempts.append("\(mv.move): \(t.message)")
                    if attempts.count > o.maxIllegal + 2 { throw NeuromateError.tool(t) }
                case .stalePly:
                    let fresh = try await retry(o) { try await getGame(gid) }
                    if fresh.isOver || !fresh.yourTurn { return fresh }
                    pos = fresh
                    attempts = []
                case .notYourTurn: return nil
                case .gameOver: if let p = t.position { return p } else { return try await retry(o) { try await getGame(gid) } }
                default: throw NeuromateError.tool(t)
                }
            }
        }
    }

    private func tournamentOver(_ st: JSON) -> Bool { ["finished", "cancelled"].contains((st["tournament"] as? JSON)?["status"] as? String ?? "") }
    private func joined(_ st: JSON) -> Bool {
        let me = (st["tournament"] as? JSON)?["me"] as? JSON ?? [:]
        return me["joined"] as? Bool == true && me["status"] as? String != "withdrawn"
    }

    /// Joins a tournament (unless `noJoin`) and plays all its games one after another until it finishes or is cancelled.
    /// Returns your final standing (nil when you never took part).
    public func playTournament(_ id: String, _ o: PlayOptions = PlayOptions(), strategy: Strategy) async throws -> Standing? {
        let nonce = String(UUID().uuidString.prefix(6)).lowercased()
        let st = try await retry(o) { try await tournamentStandings(id) }
        if tournamentOver(st) { return try await finalRow(o, id, 0, 0, false) }
        if !o.noJoin && !joined(st) {
            do { _ = try await retry(o) { try await joinTournament(id) } } catch NeuromateError.tool(let t) where t.kind == .conflict {
                if !joined(try await retry(o) { try await tournamentStandings(id) }) { throw NeuromateError.usage("neuromate: could not join tournament \(id): \(t)") }
            }
        }
        while true {
            try Task.checkCancellation()
            let turn = try await retry(o) { try await waitForTurn("", timeoutSeconds: o.waitSeconds) }
            var ended: JSON?
            var check = turn.timedOut
            for ev in turn.events {
                o.onEvent?(ev)
                let tid = (ev["data"] as? JSON)?["tournament_id"] as? String ?? ""
                if !tid.isEmpty && tid != id { continue }
                switch ev["type"] as? String {
                case "tournament_finished", "tournament_cancelled": ended = ev
                case "tournament_game_over": check = true
                default: break
                }
            }
            var busy = false
            if var p = turn.position, p.tournamentId.isEmpty || p.tournamentId == id {
                o.onPosition?(p)
                if !p.isOver {
                    busy = true
                    p = try await answerOffers(o, p)
                    if !p.isOver && p.yourTurn { _ = try await playMove(o, strategy, p, nonce) }
                }
            }
            if let ev = ended {
                let d = ev["data"] as? JSON ?? [:]
                return try await finalRow(o, id, (d["rank"] as? NSNumber)?.intValue ?? 0, (d["score"] as? NSNumber)?.doubleValue ?? 0, true)
            }
            if check {
                if tournamentOver(try await retry(o) { try await tournamentStandings(id) }) { return try await finalRow(o, id, 0, 0, false) }
            } else if turn.events.isEmpty && !busy {
                try await Task.sleep(nanoseconds: 200_000_000)
            }
        }
    }

    private func finalRow(_ o: PlayOptions, _ id: String, _ rank: Int, _ score: Double, _ haveEvent: Bool) async throws -> Standing? {
        let st = try await retry(o) { try await tournamentStandings(id) }
        let you = (try await retry(o) { try await whoami() })["you"] as? JSON ?? [:]
        let rows = st["standings"] as? [JSON] ?? []
        let aid = you["agent_id"] as? String ?? ""
        let row = rows.first { !aid.isEmpty && $0["agent_id"] as? String == aid } ?? rows.first { $0["name"] as? String == you["name"] as? String }
        func num(_ d: JSON, _ k: String) -> Double { (d[k] as? NSNumber)?.doubleValue ?? 0 }
        if let r = row { return Standing(rank: Int(num(r, "rank")), score: num(r, "score"), raw: r) }
        let me = (st["tournament"] as? JSON)?["me"] as? JSON ?? [:]
        if me["joined"] as? Bool == true { return Standing(rank: haveEvent ? rank : Int(num(me, "rank")), score: haveEvent ? score : num(me, "score"), raw: me) }
        return nil
    }
}
