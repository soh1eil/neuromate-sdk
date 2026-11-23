import Foundation

/// Board helpers computed locally from a FEN: where the pieces are. Pure geometry, no evaluation.
/// Models read a FEN badly; the server sends the same facts (describe_position, square_info).
public enum Board {
    private static let files = Array("abcdefgh")
    private static let order = Array("kqrbnp")

    /// One piece: color "white"/"black", kind "king".."pawn", square "e4", symbol the FEN letter.
    public struct Piece: Equatable, Sendable {
        public let color: String
        public let kind: String
        public let square: String
        public let symbol: Character
    }

    private static func kind(_ c: Character) -> String {
        switch Character(c.lowercased()) {
        case "k": return "king"
        case "q": return "queen"
        case "r": return "rook"
        case "b": return "bishop"
        case "n": return "knight"
        default: return "pawn"
        }
    }

    private static func piece(_ c: Character, _ square: String) -> Piece {
        Piece(color: c.isUppercase ? "white" : "black", kind: kind(c), square: square, symbol: c)
    }

    /// square to FEN letter (Crazyhouse pockets and "~" marks are ignored). Empty when the FEN is malformed.
    public static func parse(_ fen: String) -> [String: Character] {
        var field = fen.split(separator: " ").first.map(String.init) ?? ""
        if let br = field.firstIndex(of: "[") { field = String(field[..<br]) }
        field = field.replacingOccurrences(of: "~", with: "")
        let ranks = field.split(separator: "/", omittingEmptySubsequences: false)
        guard ranks.count == 8 else { return [:] }
        var out: [String: Character] = [:]
        for (i, row) in ranks.enumerated() {
            var f = 0
            for c in row {
                if let d = c.wholeNumberValue { f += d } else if f < 8 { out["\(files[f])\(8 - i)"] = c; f += 1 }
            }
        }
        return out
    }

    /// Eight strings, rank 8 first, "." for empty (the server's board_rows).
    public static func rows(_ fen: String) -> [String] {
        let b = parse(fen)
        return (1...8).reversed().map { r in String((0..<8).map { f in b["\(files[f])\(r)"] ?? "." }) }
    }

    /// ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, "." = empty; fromBlack turns it around.
    public static func diagram(_ fen: String, fromBlack: Bool = false) -> String {
        let rs = rows(fen)
        var lines: [String] = []
        for i in 0..<8 {
            let idx = fromBlack ? 7 - i : i
            let row = fromBlack ? String(rs[idx].reversed()) : rs[idx]
            lines.append("\(8 - idx) " + row.map(String.init).joined(separator: " "))
        }
        lines.append("  " + (fromBlack ? "h g f e d c b a" : "a b c d e f g h"))
        return lines.joined(separator: "\n")
    }

    public static func pieceAt(_ fen: String, _ square: String) -> Piece? {
        let sq = square.lowercased()
        return parse(fen)[sq].map { piece($0, sq) }
    }

    /// Pieces of one color (nil = both): king, queen, rooks, bishops, knights, pawns, then by file and rank.
    public static func pieces(_ fen: String, color: String? = nil) -> [Piece] {
        parse(fen).map { piece($0.value, $0.key) }.filter { color == nil || $0.color == color }.sorted { a, b in
            if a.color != b.color { return a.color == "white" }
            let oa = order.firstIndex(of: Character(a.symbol.lowercased())) ?? 9, ob = order.firstIndex(of: Character(b.symbol.lowercased())) ?? 9
            if oa != ob { return oa < ob }
            return a.square < b.square
        }
    }

    /// "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it.
    public static func piecesText(_ fen: String, color: String) -> String {
        let ps = pieces(fen, color: color)
        var parts: [String] = []
        for k in order {
            let sqs = ps.filter { Character($0.symbol.lowercased()) == k }.map { $0.square }
            if sqs.isEmpty { continue }
            let name = kind(k)
            parts.append(name.prefix(1).uppercased() + name.dropFirst() + (sqs.count > 1 ? "s" : "") + " " + sqs.joined(separator: " "))
        }
        return parts.isEmpty ? "none" : parts.joined(separator: ", ")
    }
}

extension Position {
    /// ASCII diagram, rank 8 on top, UPPER CASE = White. fromYourSide turns it for Black.
    public func boardDiagram(fromYourSide: Bool = false) -> String { Board.diagram(fen, fromBlack: fromYourSide && color == "black") }
    /// Eight strings, rank 8 first, "." for empty (the server's board_rows).
    public func boardRows() -> [String] { Board.rows(fen) }
    public func pieceAt(_ square: String) -> Board.Piece? { Board.pieceAt(fen, square) }
    public func pieces(color: String? = nil) -> [Board.Piece] { Board.pieces(fen, color: color) }
    /// "King g1, Queen d1, Rooks a1 f1, ..."
    public func piecesText(color: String) -> String { Board.piecesText(fen, color: color) }
}

extension Client {
    /// The strategy guide. An empty topic returns the index and the six-step think-before-every-move checklist (read it before your first game).
    /// Topics: checklist, opening, openings, middlegame, pawns, tactics, attack, endgame, blunders.
    public func strategyGuide(_ topic: String = "") async throws -> String { try await callTool("get_strategy_guide", Client.clean(["topic": topic])).text }
    /// Every piece of both sides with its square, side to move, castling, en passant and the last move in words.
    public func describePosition(gameId: String = "") async throws -> String { try await callTool("describe_position", Client.clean(["game_id": gameId])).text }
    /// What stands on a square and which pieces attack or defend it (geometry; occupancy only in fide_strict games).
    public func squareInfo(_ square: String, gameId: String = "") async throws -> String { try await callTool("square_info", Client.clean(["square": square, "game_id": gameId])).text }
}
