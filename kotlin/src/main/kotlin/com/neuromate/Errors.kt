package com.neuromate

/** Base of every error the SDK throws. [retryable] says whether repeating the call may help. */
open class NeuromateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    open val retryable: Boolean get() = false
}

/** The request never got an answer (connection refused, timeout, DNS). */
class TransportException(message: String, cause: Throwable? = null) : NeuromateException(message, cause) {
    override val retryable get() = true
}

/** An unexpected HTTP status. */
class HttpException(val status: Int, body: String) : NeuromateException("HTTP $status: $body")

/** HTTP 401: the API key is missing, wrong or revoked. */
class AuthException(message: String, val code: String = "", val hint: String = "") : NeuromateException(message)

/** HTTP 5xx or a platform INTERNAL ERROR. */
class ServerException(message: String) : NeuromateException(message) {
    override val retryable get() = true
}

/** Too many requests; wait [retryAfterMs] before retrying. */
class RateLimitedException(message: String, val retryAfterMs: Long) : NeuromateException(message) {
    override val retryable get() = true
}

/** A JSON-RPC level error (unknown tool, invalid params, unreadable reply). */
class ProtocolException(message: String, val code: Int = 0, val data: Any? = null) : NeuromateException(message)

/**
 * A tool ran and said no. `message` is the server's complete teaching text; [code] is the stable machine code
 * (`illegal_move`, `not_your_turn`, `stale_ply`, `game_over`, `model_not_reported`, `invalid_params`, ...).
 */
open class ToolException(
    val tool: String, val code: String, val detail: String, val hint: String, val legalMoves: List<String>,
    val position: Position?, text: String, val context: JsonObject,
) : NeuromateException(text.ifEmpty { detail })

class IllegalMoveException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)
class NotYourTurnException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)
/** expected_ply no longer matches: re-read the game. */
class StalePlyException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)
class GameOverException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)
/** report_model has not been called in this session (the client does it for you when you give it a model). */
class ModelNotReportedException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)
class ConflictException(t: ToolException) : ToolException(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.message ?: "", t.context)

internal fun toolError(tool: String, text: String, structured: JsonObject): ToolException {
    val err = structured["error"].obj()
    val code = err["code"].str()
    val msg = err["message"].str().ifEmpty { text.trim() }
    val hint = err["hint"].str().ifEmpty { err["what_to_do"].str() }
    val legal = err["legal_moves"].arr().filterIsInstance<String>()
    val ctx = err.filterKeys { it !in setOf("code", "message", "hint", "what_to_do", "legal_moves") }
    val game = structured["game"]
    val pos = if (game is Map<*, *>) Position.of(game.obj(), text) else null
    val t = ToolException(tool, code, msg, hint, legal, pos, text, ctx)
    return when (code) {
        "illegal_move" -> IllegalMoveException(t)
        "not_your_turn" -> NotYourTurnException(t)
        "stale_ply" -> StalePlyException(t)
        "game_over" -> GameOverException(t)
        "model_not_reported" -> ModelNotReportedException(t)
        "conflict" -> ConflictException(t)
        else -> t
    }
}
