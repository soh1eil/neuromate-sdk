import Foundation

/// A tool ran and said no. `text` is the server's complete teaching text; `code` is the stable machine code
/// (`illegal_move`, `not_your_turn`, `stale_ply`, `game_over`, `model_not_reported`, `invalid_params`, ...).
public struct ToolError: Error, CustomStringConvertible, @unchecked Sendable {
    public enum Kind: Sendable { case illegalMove, notYourTurn, stalePly, gameOver, modelNotReported, conflict, other }
    public let tool: String
    public let code: String
    public var message: String
    public let hint: String
    public let legalMoves: [String]
    public let position: Position?
    public let text: String
    /// Every other field of the server's error object.
    public let context: [String: Any]

    public var kind: Kind {
        switch code {
        case "illegal_move": return .illegalMove
        case "not_your_turn": return .notYourTurn
        case "stale_ply": return .stalePly
        case "game_over": return .gameOver
        case "model_not_reported": return .modelNotReported
        case "conflict": return .conflict
        default: return .other
        }
    }

    public var description: String { text.isEmpty ? message : text }

    static func from(tool: String, text: String, structured: [String: Any]) -> ToolError {
        let err = structured["error"] as? [String: Any] ?? [:]
        var msg = err["message"] as? String ?? ""
        if msg.isEmpty { msg = text.trimmingCharacters(in: .whitespacesAndNewlines) }
        var hint = err["hint"] as? String ?? ""
        if hint.isEmpty { hint = err["what_to_do"] as? String ?? "" }
        let legal = (err["legal_moves"] as? [Any] ?? []).compactMap { $0 as? String }
        let ctx = err.filter { !["code", "message", "hint", "what_to_do", "legal_moves"].contains($0.key) }
        let pos = (structured["game"] as? [String: Any]).map { Position($0, text: text) }
        return ToolError(tool: tool, code: err["code"] as? String ?? "", message: msg, hint: hint, legalMoves: legal, position: pos, text: text, context: ctx)
    }
}

/// Every error the SDK throws. `retryable` says whether repeating the call may help.
public enum NeuromateError: Error, CustomStringConvertible, @unchecked Sendable {
    /// The request never got an answer (connection refused, timeout, DNS).
    case transport(String)
    /// An unexpected HTTP status.
    case http(status: Int, body: String)
    /// HTTP 401: the API key is missing, wrong or revoked.
    case auth(message: String, code: String, hint: String)
    /// HTTP 5xx or a platform INTERNAL ERROR.
    case server(String)
    /// Too many requests; wait `retryAfter` seconds before retrying.
    case rateLimited(message: String, retryAfter: TimeInterval)
    /// A JSON-RPC level error (unknown tool, invalid params, unreadable reply).
    case protocolError(code: Int, message: String)
    /// A tool ran and said no.
    case tool(ToolError)
    /// Misuse of the SDK (no API key, empty move, ...).
    case usage(String)

    public var retryable: Bool {
        switch self {
        case .transport, .server, .rateLimited: return true
        default: return false
        }
    }

    /// The stable server code of a tool error (`illegal_move`, `stale_ply`, ...), if this is one.
    public var code: String? {
        switch self {
        case .tool(let t): return t.code
        case .auth(_, let c, _): return c.isEmpty ? nil : c
        default: return nil
        }
    }

    public var toolError: ToolError? { if case .tool(let t) = self { return t } else { return nil } }

    public var description: String {
        switch self {
        case .transport(let m), .server(let m), .usage(let m): return m
        case .http(let s, let b): return "HTTP \(s): \(b)"
        case .auth(let m, _, _), .rateLimited(let m, _), .protocolError(_, let m): return m
        case .tool(let t): return t.description
        }
    }
}
