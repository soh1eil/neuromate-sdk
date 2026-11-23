package com.neuromate

/**
 * Board helpers computed locally from a FEN: where the pieces are. Pure geometry, no evaluation.
 * Models read a FEN badly; the server sends the same facts (describe_position, square_info).
 */
object Board {
    private const val FILES = "abcdefgh"
    private const val ORDER = "kqrbnp"

    /** One piece: color "white"/"black", kind "king".."pawn", square "e4", symbol the FEN letter. */
    data class Piece(val color: String, val kind: String, val square: String, val symbol: Char)

    private fun kind(c: Char): String = when (c.lowercaseChar()) {
        'k' -> "king"; 'q' -> "queen"; 'r' -> "rook"; 'b' -> "bishop"; 'n' -> "knight"; else -> "pawn"
    }

    /** square to FEN letter (Crazyhouse pockets and "~" marks are ignored). */
    fun parse(fen: String): Map<String, Char> {
        val field = fen.trim().split(" ")[0].substringBefore('[').replace("~", "")
        val ranks = field.split("/")
        require(ranks.size == 8) { "not a FEN: $fen" }
        val out = HashMap<String, Char>()
        for ((i, row) in ranks.withIndex()) {
            var f = 0
            for (c in row) if (c.isDigit()) f += c - '0' else out["" + FILES[f++] + (8 - i)] = c
        }
        return out
    }

    /** Eight strings, rank 8 first, '.' for empty (the server's board_rows). */
    fun rows(fen: String): List<String> {
        val b = parse(fen)
        return (8 downTo 1).map { r -> (0 until 8).map { f -> b["" + FILES[f] + r] ?: '.' }.joinToString("") }
    }

    /** ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, '.' = empty; fromBlack turns it around. */
    fun diagram(fen: String, fromBlack: Boolean = false): String {
        val rs = rows(fen)
        val lines = (0 until 8).map { i ->
            val idx = if (fromBlack) 7 - i else i
            val row = if (fromBlack) rs[idx].reversed() else rs[idx]
            "${8 - idx} " + row.toList().joinToString(" ")
        }
        return (lines + ("  " + (if (fromBlack) "h g f e d c b a" else "a b c d e f g h"))).joinToString("\n")
    }

    fun pieceAt(fen: String, square: String): Piece? {
        val sq = square.lowercase()
        return parse(fen)[sq]?.let { Piece(if (it.isUpperCase()) "white" else "black", kind(it), sq, it) }
    }

    /** Pieces of one color (null = both): king, queen, rooks, bishops, knights, pawns, then by file and rank. */
    fun pieces(fen: String, color: String? = null): List<Piece> =
        parse(fen).map { (sq, c) -> Piece(if (c.isUpperCase()) "white" else "black", kind(c), sq, c) }
            .filter { color == null || it.color == color }
            .sortedWith(compareBy({ it.color != "white" }, { ORDER.indexOf(it.symbol.lowercaseChar()) }, { it.square[0] }, { it.square[1] }))

    /** "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it. */
    fun piecesText(fen: String, color: String): String {
        val ps = pieces(fen, color)
        val parts = ORDER.mapNotNull { k ->
            val sqs = ps.filter { it.symbol.lowercaseChar() == k }.map { it.square }
            if (sqs.isEmpty()) null else kind(k).replaceFirstChar { it.uppercase() } + (if (sqs.size > 1) "s" else "") + " " + sqs.joinToString(" ")
        }
        return if (parts.isEmpty()) "none" else parts.joinToString(", ")
    }
}

/** ASCII diagram, rank 8 on top, UPPER CASE = White. fromYourSide turns it for Black. */
fun Position.boardDiagram(fromYourSide: Boolean = false): String = Board.diagram(fen, fromYourSide && color == "black")
/** Eight strings, rank 8 first, '.' for empty (the server's board_rows). */
fun Position.boardRows(): List<String> = Board.rows(fen)
fun Position.pieceAt(square: String): Board.Piece? = Board.pieceAt(fen, square)
fun Position.pieces(color: String? = null): List<Board.Piece> = Board.pieces(fen, color)
/** "King g1, Queen d1, Rooks a1 f1, ..." */
fun Position.piecesText(color: String): String = Board.piecesText(fen, color)
