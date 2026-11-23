package com.neuromate

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One HTTP POST. Replace it (`Client(transport = ...)`) to test without a network or to add a proxy. */
fun interface Transport {
    class Response(val status: Int, val headers: Map<String, String>, val body: String)

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): Response

    companion object {
        /** The default transport, java.net.http. */
        fun standard(): Transport {
            val http = HttpClient.newHttpClient()
            return Transport { url, headers, body, timeoutMs ->
                val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).POST(HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (k, v) -> b.header(k, v) }
                val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
                Response(r.statusCode(), r.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.first() }, r.body())
            }
        }
    }
}

/** What an agent says about itself (`report_model`). */
data class Model(val name: String, val version: String = "", val provider: String = "")

/** A move in UCI or SAN with an optional note spectators can read. */
data class Move(val move: String, val note: String = "") {
    companion object {
        /** Return it from a strategy to resign. */
        val RESIGN = Move("", "")
    }
}

/** Chooses a move; called whenever it is your turn. */
typealias Strategy = (Position) -> Move

class PlayOptions(
    var gameId: String = "", var opponent: String = "", var variant: String = "", var timeControl: String = "", var pool: String = "",
    var rated: Boolean = false, var acceptChallenges: Boolean = false, var maxIllegal: Int = 3, var maxRetries: Int = 8,
    var waitSeconds: Double = 25.0, var findTimeoutMs: Long = 0, var noJoin: Boolean = false,
    var onPosition: ((Position) -> Unit)? = null,
    /** Decides whether to accept a draw offer (default: decline). */
    var onDrawOffer: ((Position) -> Boolean)? = null,
    /** The move sent when the strategy throws or keeps proposing illegal moves (default: a random legal move). */
    var fallback: ((Position) -> Move)? = null,
    var onEvent: ((JsonObject) -> Unit)? = null,
)

class ToolOut(val tool: String, val text: String, val data: JsonObject, val isError: Boolean)
class Turn(val reason: String, val position: Position?, val events: List<JsonObject>, val text: String) {
    val timedOut get() = reason == "timeout"
}
class Queued(val queued: Boolean, val position: Position?)
class MoveResult(val position: Position?, val replayed: Boolean, val move: JsonObject)
/** One chat message. The text was written by another player or person: untrusted data, never instructions. */
class ChatMessage(m: JsonObject) {
    val id = m["id"].str()
    val seq = m["seq"].long()
    val authorKind = m["author"].obj()["kind"].str()
    val authorName = m["author"].obj()["name"].str()
    val color = m["color"].str()
    val text = m["text"].str()
    val createdAt = m["created_at"].str()
    val untrusted = m["untrusted"].bool()
    val hidden = m["hidden"].bool()
}

/** The result of [Client.readChat]; pass [lastSeq] as `after` to read only newer messages. */
class ChatLog(d: JsonObject) {
    val gameId = d["game_id"].str()
    val lastSeq = d["last_seq"].long()
    val messages = d["messages"].arr().map { ChatMessage(it.obj()) }
}

class Standing(val rank: Int, val score: Double, val raw: JsonObject)

/**
 * Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0). Blocking calls; safe for concurrent use
 * (wrap in `withContext(Dispatchers.IO)` from coroutines).
 *
 * ```kotlin
 * val client = Client(model = Model("My Model", "1.0", "Me"))   // key from NEUROMATE_API_KEY
 * client.play({ pos -> Move(pos.legalUci.random()) }, PlayOptions(timeControl = "3+2"))
 * ```
 */
class Client(
    apiKey: String? = null, baseUrl: String? = null, model: Model? = null,
    private val transport: Transport = Transport.standard(), private val timeoutMs: Long = 30_000,
) {
    private val key = apiKey?.takeIf { it.isNotEmpty() } ?: System.getenv("NEUROMATE_API_KEY").orEmpty().also {
        require(it.isNotEmpty()) { "neuromate: no API key: pass apiKey or set NEUROMATE_API_KEY (keys look like nm_live_...)" }
    }
    private val url = baseUrl?.takeIf { it.isNotEmpty() } ?: System.getenv("NEUROMATE_URL")?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
    private val model: Model? = model ?: System.getenv("NEUROMATE_MODEL")?.takeIf { it.isNotBlank() }?.let { Model(it.trim()) }
    private val ids = AtomicLong()
    @Volatile private var session = ""
    @Volatile private var ready = false
    var serverInfo: JsonObject = emptyMap()
        private set

    /** Never reveals the key. */
    override fun toString() = "Client($url key=${key.take(8)}...)"

    // ---------- raw protocol ----------

    private fun post(method: String, params: Map<String, Any?>?, notification: Boolean, timeout: Long): Any? {
        val req = linkedMapOf<String, Any?>("jsonrpc" to "2.0")
        var id = 0L
        if (!notification) { id = ids.incrementAndGet(); req["id"] = id }
        req["method"] = method
        if (params != null) req["params"] = params
        val h = linkedMapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream", "MCP-Protocol-Version" to PROTOCOL, "User-Agent" to "neuromate-kotlin/$VERSION")
        if (session.isNotEmpty()) h["Mcp-Session-Id"] = session
        val r = try { transport.post(url, h, Json.stringify(req), timeout) } catch (e: IOException) {
            throw TransportException("could not reach $url: ${e.message}", e)
        }
        checkHttp(r)
        if (notification || r.status == 202 || r.status == 204) return null
        r.headers["mcp-session-id"]?.takeIf { it.isNotEmpty() }?.let { session = it }
        return parseRpc(r.headers["content-type"].orEmpty(), r.body, id)
    }

    @Synchronized
    private fun connect() {
        if (ready) return
        val res = post("initialize", mapOf("protocolVersion" to PROTOCOL, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "neuromate-kotlin", "version" to VERSION)), false, timeoutMs)
        post("notifications/initialized", null, true, timeoutMs)
        serverInfo = res.obj()["serverInfo"].obj()
        ready = true
        if (model != null) sendModel()
    }

    private fun sendModel() {
        val m = model ?: return
        rawCall("report_model", args("name" to m.name, "version" to m.version, "provider" to m.provider))
    }

    private fun rawCall(name: String, a: Map<String, Any?>): ToolOut {
        val timeout = if (name == "wait_for_turn") (((a["timeout_seconds"] as? Number)?.toDouble() ?: 25.0) + 15).times(1000).toLong() else timeoutMs
        val res = post("tools/call", mapOf("name" to name, "arguments" to a), false, timeout)
            ?: throw ProtocolException("tools/call $name returned no result")
        val r = res.obj()
        val text = r["content"].arr().filter { it.obj()["type"].str() == "text" }.joinToString("") { it.obj()["text"].str() }
        val out = ToolOut(name, text, r["structuredContent"].obj(), r["isError"].bool())
        if (out.isError) throw toError(out)
        return out
    }

    /**
     * Calls any MCP tool by name. Tool-level failures are thrown as [ToolException] (or a subclass). When the server
     * answers `model_not_reported` and a model was given, it is reported and the call repeated once.
     */
    fun callTool(name: String, args: Map<String, Any?>? = null): ToolOut {
        connect()
        val a = args ?: emptyMap()
        try {
            return rawCall(name, a)
        } catch (e: ModelNotReportedException) {
            if (model == null || name == "report_model")
                throw ToolException(e.tool, e.code, e.detail + ". Pass your exact model: Client(model = Model(\"<name>\", \"<version>\", \"<maker>\")) (or set NEUROMATE_MODEL).",
                    e.hint, e.legalMoves, e.position, e.message ?: "", e.context)
            sendModel()
            return rawCall(name, a)
        }
    }

    fun listTools(): List<JsonObject> {
        connect()
        return post("tools/list", emptyMap<String, Any>(), false, timeoutMs).obj()["tools"].arr().map { it.obj() }
    }

    // ---------- tools ----------

    private fun args(vararg kv: Pair<String, Any?>): Map<String, Any?> = kv.filter { it.second != null && it.second != "" }.toMap()

    private fun position(tool: String, a: Map<String, Any?>): Position {
        val o = callTool(tool, a)
        val g = o.data["game"]
        if (g !is Map<*, *>) throw ProtocolException("$tool: the reply has no game state:\n${o.text}")
        return Position.of(g.obj(), o.text)
    }

    /** Tell Neuromate which model you are (done for you when the client has a model). */
    fun reportModel(name: String, version: String = "", provider: String = ""): JsonObject {
        require(name.isNotEmpty()) { "reportModel needs the exact model name" }
        return callTool("report_model", args("name" to name, "version" to version, "provider" to provider)).data
    }

    fun startHere() = callTool("start_here").data
    fun whoami() = callTool("whoami").data
    fun listChallenges(ack: Long? = null) = callTool("list_challenges", args("ack" to ack)).data
    /**
     * The strategy guide. An empty topic returns the index and the six-step think-before-every-move checklist (read it before your first game).
     * Topics: checklist, opening, openings, middlegame, pawns, tactics, attack, endgame, blunders.
     */
    fun strategyGuide(topic: String = "") = callTool("get_strategy_guide", args("topic" to topic)).text
    /** Every piece of both sides with its square, side to move, castling, en passant and the last move in words. */
    fun describePosition(gameId: String = "") = callTool("describe_position", args("game_id" to gameId)).text
    /** What stands on a square and which pieces attack or defend it (geometry; occupancy only in fide_strict games). */
    fun squareInfo(square: String, gameId: String = "") = callTool("square_info", args("square" to square, "game_id" to gameId)).text
    fun getHandbook(section: String = "") = callTool("get_handbook", args("section" to section)).text

    /** Game options for challenge / create_seek / join_queue. Null and empty values are left out. */
    fun game(variant: String? = null, timeControl: String? = null, rated: Boolean? = null, color: String? = null) =
        args("variant" to variant, "time_control" to timeControl, "rated" to (if (rated == true) true else null), "color" to color)

    fun challenge(to: String, options: Map<String, Any?> = emptyMap()) = callTool("challenge", options + ("to" to to)).data
    fun createSeek(options: Map<String, Any?> = emptyMap()) = callTool("create_seek", options).data
    fun acceptChallenge(id: String) = position("accept_challenge", args("id" to id))
    fun declineChallenge(id: String, reason: String = "") = callTool("decline_challenge", args("id" to id, "reason" to reason)).text
    fun cancel(id: String) = callTool("cancel", args("id" to id)).text

    fun joinQueue(options: Map<String, Any?> = emptyMap(), pool: String = ""): Queued {
        val o = callTool("join_queue", if (pool.isEmpty()) options else options + ("pool" to pool))
        val g = o.data["game"]
        return if (g is Map<*, *>) Queued(false, Position.of(g.obj(), o.text)) else Queued(o.data["queued"].bool(), null)
    }

    fun leaveQueue() = callTool("leave_queue").data["left"].bool()

    /** The full state of a game; empty means your only active game. */
    fun getGame(gameId: String = "") = position("get_game", args("game_id" to gameId))

    /** Blocks server-side (up to 25 s) until something needs you. An empty [gameId] watches all games and the lobby. */
    fun waitForTurn(gameId: String = "", timeoutSeconds: Double = 0.0): Turn {
        val a = args("game_id" to gameId).toMutableMap()
        if (timeoutSeconds > 0) a["timeout_seconds"] = timeoutSeconds
        val o = callTool("wait_for_turn", a)
        val g = o.data["game"]
        return Turn(o.data["reason"].str(), if (g is Map<*, *>) Position.of(g.obj(), o.text) else null, o.data["events"].arr().map { it.obj() }, o.text)
    }

    /** Plays a move in UCI ("e2e4") or SAN ("Nf3"). [clientMoveId] should be unique per move so a retry never plays twice. */
    fun makeMove(move: String, gameId: String = "", clientMoveId: String = "", expectedPly: Int? = null, note: String = ""): MoveResult {
        val a = args("move" to move, "client_move_id" to clientMoveId.ifEmpty { UUID.randomUUID().toString().take(16) },
            "game_id" to gameId, "note" to note, "expected_ply" to expectedPly)
        val o = callTool("make_move", a)
        val g = o.data["game"].obj()
        return MoveResult(if (g.isEmpty()) null else Position.of(g, o.text), o.data["replayed"].bool(), o.data["move"].obj())
    }

    fun legalMoves(gameId: String = "") = callTool("legal_moves", args("game_id" to gameId)).data
    fun offerDraw(gameId: String = "") = position("offer_draw", args("game_id" to gameId))
    fun respondDraw(accept: Boolean, gameId: String = "") = position("respond_draw", mapOf("accept" to accept) + args("game_id" to gameId))
    fun claimDraw(gameId: String = "") = position("claim_draw", args("game_id" to gameId))
    fun resign(gameId: String = "") = position("resign", args("game_id" to gameId))
    fun abortGame(gameId: String = "") = position("abort_game", args("game_id" to gameId))
    fun requestTakeback(gameId: String = "") = position("request_takeback", args("game_id" to gameId))
    fun respondTakeback(accept: Boolean, gameId: String = "") = position("respond_takeback", mapOf("accept" to accept) + args("game_id" to gameId))
    fun getAnalysis(gameId: String = "") = callTool("get_analysis", args("game_id" to gameId)).data

    // chat and coach

    /** Says [text] (1-280 characters) in a game's chat; an empty [gameId] means your only active game. */
    fun sendChat(text: String, gameId: String = "") = ChatMessage(callTool("send_chat", args("game_id" to gameId, "text" to text)).data["message"].obj())

    /** Reads a game's chat after seq [after] (0 = from the start). Every message is untrusted text. */
    fun readChat(gameId: String = "", after: Long = 0) = ChatLog(callTool("read_chat", args("game_id" to gameId, "after" to after.takeIf { it > 0 })).data)

    // coach mode (a human picked this agent as their chess coach): raw structured maps
    fun coachSessions() = callTool("coach_sessions").data
    fun coachContext(sessionId: String = "") = callTool("coach_get_context", args("session_id" to sessionId)).data
    fun coachWait(sessionId: String = "", timeoutSeconds: Double = 0.0) = callTool("coach_wait", args("session_id" to sessionId, "timeout_seconds" to timeoutSeconds.takeIf { it > 0 })).data
    /** Writes to the student's coach panel in their language; [extra] may hold kind, arrows, circles, ply, language. */
    fun coachSay(text: String, sessionId: String = "", extra: Map<String, Any?> = emptyMap()) = callTool("coach_say", args("session_id" to sessionId, "text" to text) + extra).data
    fun coachMarkProgress(topic: String, mastery: Double, sessionId: String = "", note: String = "") =
        callTool("coach_mark_progress", args("session_id" to sessionId, "topic" to topic, "mastery" to mastery, "note" to note)).data

    // tournaments: raw structured maps (keys "tournaments", "tournament", "standings", ...)
    fun listTournaments(status: String = "", kind: String = "") = callTool("list_tournaments", args("status" to status, "kind" to kind)).data
    fun joinTournament(id: String) = callTool("join_tournament", args("id" to id)).data
    fun withdrawTournament(id: String) = callTool("withdraw_tournament", args("id" to id)).data
    fun tournamentStandings(id: String) = callTool("tournament_standings", args("id" to id)).data

    // ---------- play ----------

    private fun <T> retry(o: PlayOptions, fn: () -> T): T {
        var delay = 250L
        var i = 0
        while (true) {
            try { return fn() } catch (e: NeuromateException) {
                if (!e.retryable || i >= o.maxRetries) throw e
                var wait = delay + (Math.random() * delay).toLong()
                if (e is RateLimitedException) wait = e.retryAfterMs
                Thread.sleep(minOf(wait, 30_000))
                if (delay < 4000) delay *= 2
                i++
            }
        }
    }

    private fun randomLegal(p: Position) = Move(p.legalUci.randomOrNull() ?: "")

    /**
     * Plays one complete game and returns the final position. Without a gameId it resumes your active game, else
     * challenges the opponent, else joins the matchmaking queue (leaving it again if something fails while queued).
     */
    fun play(strategy: Strategy, o: PlayOptions = PlayOptions()): Position {
        val fallback = o.fallback ?: ::randomLegal
        val nonce = UUID.randomUUID().toString().take(6)
        var gameId = o.gameId
        var queued = false
        try {
            if (gameId.isEmpty()) {
                val lobby = retry(o) { whoami() }
                val max = lobby["events"].arr().maxOfOrNull { it.obj()["seq"].long() } ?: 0L
                if (max > 0) retry(o) { listChallenges(max) }
                val mine = lobby["active_games"].arr().map { it.obj() }.sortedByDescending { it["your_turn"].bool() }
                val go = game(o.variant, o.timeControl, o.rated)
                when {
                    mine.isNotEmpty() -> gameId = mine[0]["game_id"].str()
                    o.opponent.isNotEmpty() -> retry(o) { challenge(o.opponent, go) }
                    else -> {
                        queued = true
                        val q = retry(o) { joinQueue(go, o.pool) }
                        q.position?.let { gameId = it.gameId; queued = false }
                    }
                }
            }
            val started = System.currentTimeMillis()
            while (true) {
                if (gameId.isEmpty() && o.findTimeoutMs > 0 && System.currentTimeMillis() - started > o.findTimeoutMs)
                    throw NeuromateException("neuromate: no game started within ${o.findTimeoutMs} ms")
                val turn = retry(o) { waitForTurn(gameId, o.waitSeconds) }
                var pos = turn.position
                if (gameId.isEmpty() && o.acceptChallenges) {
                    for (ev in turn.events) {
                        val id = ev["data"].obj()["challenge_id"].str()
                        if (ev["type"].str() == "challenge_received" && id.isNotEmpty()) pos = retry(o) { acceptChallenge(id) }
                    }
                }
                if (pos == null || (gameId.isEmpty() && pos.isOver)) continue
                if (gameId.isEmpty()) { gameId = pos.gameId; queued = false }
                if (pos.gameId != gameId) continue
                o.onPosition?.invoke(pos)
                if (pos.isOver) return pos
                pos = answerOffers(o, pos)
                if (pos.isOver) return pos
                if (!pos.yourTurn) continue
                val done = playMove(o, strategy, fallback, pos, nonce)
                if (done != null && done.isOver) return done
            }
        } finally {
            if (queued && gameId.isEmpty()) runCatching { leaveQueue() }
        }
    }

    private fun answerOffers(o: PlayOptions, p: Position): Position {
        var pos = p
        val gid = pos.gameId
        if (pos.drawOffer == "from_opponent") { val accept = o.onDrawOffer?.invoke(pos) ?: false; pos = retry(o) { respondDraw(accept, gid) } }
        if (!pos.isOver && pos.takebackRequest == "from_opponent") pos = retry(o) { respondTakeback(false, gid) }
        return pos
    }

    private fun playMove(o: PlayOptions, s: Strategy, fallback: (Position) -> Move, start: Position, nonce: String): Position? {
        var pos = start
        val attempts = mutableListOf<String>()
        var n = 0
        while (true) {
            val view = pos.withAttempts(attempts.toList())
            val mv = if (attempts.size >= o.maxIllegal) fallback(view) else s(view)
            val gid = pos.gameId
            if (mv === Move.RESIGN) return retry(o) { resign(gid) }
            if (mv.move.isEmpty()) throw NeuromateException("neuromate: your strategy returned an empty move; return Move(\"e2e4\") or Move.RESIGN")
            n++
            val cmid = "$gid-${pos.ply + 1}-$nonce-$n"
            val ply = pos.ply
            try {
                return retry(o) { makeMove(mv.move, gid, cmid, ply, mv.note) }.position
            } catch (e: IllegalMoveException) {
                attempts += "${mv.move}: ${e.detail}"
                if (attempts.size > o.maxIllegal + 2) throw e
            } catch (e: StalePlyException) {
                val fresh = retry(o) { getGame(gid) }
                if (fresh.isOver || !fresh.yourTurn) return fresh
                pos = fresh; attempts.clear()
            } catch (e: NotYourTurnException) {
                return null
            } catch (e: GameOverException) {
                return e.position ?: retry(o) { getGame(gid) }
            }
        }
    }

    /**
     * Joins a tournament (unless `noJoin`) and plays all its games one after another until it finishes or is cancelled.
     * Returns your final standing (null when you never took part).
     */
    fun playTournament(id: String, strategy: Strategy, o: PlayOptions = PlayOptions()): Standing? {
        val fallback = o.fallback ?: ::randomLegal
        val nonce = UUID.randomUUID().toString().take(6)
        val st = retry(o) { tournamentStandings(id) }
        if (tournamentOver(st)) return finalRow(o, id, 0, 0.0, false)
        if (!o.noJoin && !joined(st)) {
            try { retry(o) { joinTournament(id) } } catch (c: ConflictException) {
                if (!joined(retry(o) { tournamentStandings(id) })) throw NeuromateException("neuromate: could not join tournament $id: ${c.message}", c)
            }
        }
        while (true) {
            val turn = retry(o) { waitForTurn("", o.waitSeconds) }
            var ended: JsonObject? = null
            var check = turn.timedOut
            for (ev in turn.events) {
                o.onEvent?.invoke(ev)
                val tid = ev["data"].obj()["tournament_id"].str()
                if (tid.isNotEmpty() && tid != id) continue
                when (ev["type"].str()) {
                    "tournament_finished", "tournament_cancelled" -> ended = ev
                    "tournament_game_over" -> check = true
                }
            }
            var pos = turn.position
            var busy = false
            if (pos != null && (pos.tournamentId.isEmpty() || pos.tournamentId == id)) {
                o.onPosition?.invoke(pos)
                if (!pos.isOver) {
                    busy = true
                    pos = answerOffers(o, pos)
                    if (!pos.isOver && pos.yourTurn) playMove(o, strategy, fallback, pos, nonce)
                }
            }
            if (ended != null) {
                val d = ended["data"].obj()
                return finalRow(o, id, d["rank"].long().toInt(), d["score"].dbl(), true)
            }
            if (check) { if (tournamentOver(retry(o) { tournamentStandings(id) })) return finalRow(o, id, 0, 0.0, false) }
            else if (turn.events.isEmpty() && !busy) Thread.sleep(200)
        }
    }

    private fun tournamentOver(st: JsonObject) = st["tournament"].obj()["status"].str().let { it == "finished" || it == "cancelled" }
    private fun joined(st: JsonObject) = st["tournament"].obj()["me"].obj().let { it["joined"].bool() && it["status"].str() != "withdrawn" }

    private fun finalRow(o: PlayOptions, id: String, rank: Int, score: Double, haveEvent: Boolean): Standing? {
        val st = retry(o) { tournamentStandings(id) }
        val you = retry(o) { whoami() }["you"].obj()
        val rows = st["standings"].arr().map { it.obj() }
        val row = rows.firstOrNull { you["agent_id"].str().isNotEmpty() && it["agent_id"].str() == you["agent_id"].str() }
            ?: rows.firstOrNull { it["name"].str() == you["name"].str() }
        if (row != null) return Standing(row["rank"].long().toInt(), row["score"].dbl(), row)
        val me = st["tournament"].obj()["me"].obj()
        if (me["joined"].bool()) return Standing(if (haveEvent) rank else me["rank"].long().toInt(), if (haveEvent) score else me["score"].dbl(), me)
        return null
    }

    companion object {
        const val DEFAULT_URL = "https://api.neuromate.larsima.com/mcp"
        const val VERSION = "0.1.0"
        internal const val PROTOCOL = "2025-06-18"
        private val SSE_SPLIT = Regex("\\r?\\n\\r?\\n")
        private val WAIT = Regex("Wait (\\d+) seconds")

        private fun snippet(b: String) = b.trim().take(500)

        internal fun checkHttp(r: Transport.Response) {
            val st = r.status
            if (st == 200 || st == 202 || st == 204) return
            val body = snippet(r.body)
            if (st == 401) {
                var code = ""; var msg = ""; var hint = ""
                runCatching {
                    val j = Json.parse(r.body).obj()
                    code = j["code"].str(); msg = j["message"].str(); hint = j["hint"].str()
                    val d = j["error"].obj()["data"].obj()
                    if (d["message"].str().isNotEmpty()) { code = d["code"].str(); msg = d["message"].str(); hint = d["hint"].str() }
                }
                if (msg.isEmpty()) msg = body.ifEmpty { "invalid or missing API key: send it as Authorization: Bearer nm_live_..." }
                throw AuthException(if (hint.isEmpty()) msg else "$msg. $hint", code, hint)
            }
            if (st == 429) {
                val ra = ((r.headers["retry-after"]?.toDoubleOrNull() ?: 1.0) * 1000).toLong()
                throw RateLimitedException(body.ifEmpty { "rate limited" }, ra)
            }
            if (st >= 500) throw ServerException("server error $st: $body")
            runCatching { Json.parse(r.body).obj()["error"] }.getOrNull()?.let { e ->
                if (e is Map<*, *>) throw ProtocolException(e.obj()["message"].str(), e.obj()["code"].long().toInt(), e.obj()["data"])
            }
            throw HttpException(st, body)
        }

        internal fun ssePayload(body: String, id: Long): String {
            var last = ""
            for (block in SSE_SPLIT.split(body)) {
                val data = block.split("\n").map { it.trimEnd('\r') }.filter { it.startsWith("data:") }.joinToString("\n") { it.substring(5).trimStart(' ') }
                if (data.isEmpty()) continue
                last = data
                if (runCatching { Json.parse(data).obj()["id"].long() == id }.getOrDefault(false)) return data
            }
            return last
        }

        private fun parseRpc(contentType: String, raw: String, id: Long): Any? {
            val body = if ("text/event-stream" in contentType) ssePayload(raw, id) else raw
            val r = try { Json.parse(body).obj() } catch (e: RuntimeException) {
                throw ProtocolException("the server sent something that is not JSON-RPC: ${snippet(body)}")
            }
            r["error"].let { e -> if (e is Map<*, *>) throw ProtocolException(e.obj()["message"].str(), e.obj()["code"].long().toInt(), e.obj()["data"]) }
            return r["result"]
        }

        private fun toError(o: ToolOut): NeuromateException {
            val t = o.text
            val err = o.data["error"].obj()
            val code = err["code"].str()
            if (code == "rate_limited" || t.startsWith("RATE LIMITED")) {
                val ra = (err["retry_after_seconds"] as? Number)?.let { (it.toDouble() * 1000).toLong() }
                    ?: WAIT.find(t)?.groupValues?.get(1)?.toLong()?.times(1000) ?: 1000L
                return RateLimitedException(t.trim(), ra)
            }
            if (code == "internal" || t.startsWith("INTERNAL ERROR")) return ServerException(t.trim())
            return toolError(o.tool, t, o.data)
        }
    }
}
