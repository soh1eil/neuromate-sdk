package com.neuromate

data class LegalMove(val uci: String, val san: String)

/** The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown). */
class Position private constructor(val raw: JsonObject, val text: String) {
    val gameId = raw["game_id"].str()
    val status = raw["status"].str()
    val variant = raw["variant"].str()
    val timeControl = raw["time_control"].str()
    val pool = raw["pool"].str()
    val rated = raw["rated"].bool()
    val turn = raw["turn"].str()
    val yourTurn = raw["your_turn"].bool()
    val ply = raw["ply"].long().toInt()
    val fen = raw["fen"].str()
    val board = raw["board"].str()
    val color = raw["you"].obj()["color"].str()
    val opponentName = raw["opponent"].obj()["name"].str()
    val legalMoves = raw["legal_moves"].arr().map { LegalMove(it.obj()["uci"].str(), it.obj()["san"].str()) }
    val movesSan = raw["moves_san"].arr().map { it.str() }
    /** "", "from_opponent" or "from_you". */
    val drawOffer = raw["draw_offer"].str()
    val takebackRequest = raw["takeback_request"].str()
    private val clocks = raw["clocks"].obj()
    val youMs = clocks["you_ms"].long()
    val opponentMs = clocks["opponent_ms"].long()
    val running = clocks["running"].str()
    private val turnLeftMs = clocks["your_turn_time_left_ms"].long()
    private val moveCapMs = clocks["move_cap_ms"].long()
    private val firstMoveLeftMs = clocks["first_move_time_left_ms"].long()
    val resultScore = raw["result"].obj()["score"].str()
    val resultWinner = raw["result"].obj()["winner"].str()
    val resultTermination = raw["result"].obj()["termination"].str()
    val tournamentId = raw["tournament"].obj()["id"].str()
    val next = raw["next"].str()
    /** "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints). */
    val ruleset = raw["ruleset"].str().ifEmpty { "casual" }
    val rulesText = raw["rules_text"].str()
    /** false in strict games: never draw legal-move dots. */
    val hintsAllowed = raw["hints_allowed"] as? Boolean ?: true
    /** Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots): write notes and chat in it. */
    val userLocale = raw["user_locale"].str()
    val opponentLocale = raw["opponent_locale"].str()
    val strict get() = ruleset == "fide_strict"
    /** Rejected moves of this turn ("move: reason"), filled by play(). */
    var illegalAttempts: List<String> = emptyList()
        internal set

    val isOver get() = status == "finished" || status == "aborted"
    val legalUci get() = legalMoves.map { it.uci }

    /** Seconds you may think about this move (the smallest relevant clock), or null when no clock applies. */
    fun secondsForThisMove(): Double? {
        val left = mutableListOf<Long>()
        if (running == "you" && turnLeftMs > 0) left += turnLeftMs
        else {
            if (turnLeftMs > 0) left += turnLeftMs
            if (firstMoveLeftMs > 0) left += firstMoveLeftMs
        }
        if (left.isEmpty()) return null
        if (moveCapMs > 0) left += moveCapMs
        return left.min() / 1000.0
    }

    override fun toString() = "Position($gameId $status ply $ply${if (yourTurn) " your turn" else ""})"

    internal fun withAttempts(a: List<String>) = of(raw, text).also { it.illegalAttempts = a }

    companion object {
        fun of(game: JsonObject, text: String) = Position(game, text)
    }
}
