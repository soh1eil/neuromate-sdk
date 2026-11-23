import Foundation

public struct LegalMove: Sendable, Equatable {
    public let uci: String
    public let san: String
}

/// The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown).
public struct Position: @unchecked Sendable {
    public let raw: [String: Any]
    /// The server's complete human-readable text of this reply.
    public let text: String
    public let gameId, status, variant, timeControl, pool, turn, fen, board, color, opponentName: String
    public let rated, yourTurn: Bool
    public let ply: Int
    public let legalMoves: [LegalMove]
    public let movesSan: [String]
    /// "", "from_opponent" or "from_you".
    public let drawOffer, takebackRequest: String
    public let youMs, opponentMs: Int
    public let running: String
    public let resultScore, resultWinner, resultTermination, tournamentId, next: String
    /// "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints).
    public let ruleset, rulesText: String
    /// false in strict games: never draw legal-move dots.
    public let hintsAllowed: Bool
    /// Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots): write notes and chat in it.
    public let userLocale, opponentLocale: String
    /// Rejected moves of this turn ("move: reason"), filled by `play`.
    public var illegalAttempts: [String] = []
    private let turnLeftMs, moveCapMs, firstMoveLeftMs: Int

    public init(_ g: [String: Any], text: String = "") {
        raw = g
        self.text = text
        func s(_ d: Any?, _ k: String) -> String { (d as? [String: Any])?[k] as? String ?? "" }
        func n(_ d: Any?, _ k: String) -> Int { ((d as? [String: Any])?[k] as? NSNumber)?.intValue ?? 0 }
        gameId = s(g, "game_id"); status = s(g, "status"); variant = s(g, "variant"); timeControl = s(g, "time_control")
        pool = s(g, "pool"); turn = s(g, "turn"); fen = s(g, "fen"); board = s(g, "board")
        rated = g["rated"] as? Bool ?? false; yourTurn = g["your_turn"] as? Bool ?? false; ply = n(g, "ply")
        color = s(g["you"], "color"); opponentName = s(g["opponent"], "name")
        legalMoves = (g["legal_moves"] as? [Any] ?? []).map { LegalMove(uci: s($0, "uci"), san: s($0, "san")) }
        movesSan = (g["moves_san"] as? [Any] ?? []).compactMap { $0 as? String }
        drawOffer = s(g, "draw_offer"); takebackRequest = s(g, "takeback_request")
        let c = g["clocks"]
        youMs = n(c, "you_ms"); opponentMs = n(c, "opponent_ms"); running = s(c, "running")
        turnLeftMs = n(c, "your_turn_time_left_ms"); moveCapMs = n(c, "move_cap_ms"); firstMoveLeftMs = n(c, "first_move_time_left_ms")
        let r = g["result"]
        resultScore = s(r, "score"); resultWinner = s(r, "winner"); resultTermination = s(r, "termination")
        tournamentId = s(g["tournament"], "id"); next = s(g, "next")
        ruleset = s(g, "ruleset").isEmpty ? "casual" : s(g, "ruleset"); rulesText = s(g, "rules_text"); hintsAllowed = g["hints_allowed"] as? Bool ?? true
        userLocale = s(g, "user_locale"); opponentLocale = s(g, "opponent_locale")
    }

    /// Whether FIDE strict rules apply.
    public var strict: Bool { ruleset == "fide_strict" }
    public var isOver: Bool { status == "finished" || status == "aborted" }
    public var legalUci: [String] { legalMoves.map(\.uci) }

    /// Seconds you may think about this move (the smallest relevant clock), or nil when no clock applies.
    public var secondsForThisMove: Double? {
        var left: [Int] = []
        if running == "you" && turnLeftMs > 0 {
            left.append(turnLeftMs)
        } else {
            if turnLeftMs > 0 { left.append(turnLeftMs) }
            if firstMoveLeftMs > 0 { left.append(firstMoveLeftMs) }
        }
        if left.isEmpty { return nil }
        if moveCapMs > 0 { left.append(moveCapMs) }
        return Double(left.min()!) / 1000
    }
}
