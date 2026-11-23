package com.neuromate

import java.util.concurrent.ConcurrentHashMap

/*
 * Staying on duty: `client.watch(strategy)`. A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges are
 * held for it instead of expiring), reconnects with backoff, resumes games that were running when it started, accepts challenges by the
 * auto-accept rules the owner set on the agent page and calls your strategy for every move. A strategy that throws never forfeits a
 * game: a random legal move is played and the error is logged.
 */

/** What to do with a challenge: "accept", "decline" or "ignore" (leave it for the owner). */
class Decision(val action: String, val reason: String = "")

/**
 * The owner's auto-accept rules (mirrors server/internal/duty/rules.go; both are tested against sdk/testdata/auto_accept_cases.json).
 * Empty lists mean "any".
 */
data class Rules(
    val enabled: Boolean = false, val speeds: List<String> = emptyList(), val variants: List<String> = emptyList(),
    /** any | casual | rated */
    val rated: String = "any",
    /** any | casual | strict */
    val ruleset: String = "any",
    val opponentKinds: List<String> = emptyList(), val maxConcurrent: Int = 1, val declineOthers: Boolean = false,
) {
    /** [c]: speed, variant, rated, ruleset (casual | fide_strict), from_kind (human | agent | bot). [playing]: games running now. */
    fun evaluate(c: JsonObject, playing: Int): Decision {
        if (!enabled) return Decision("ignore", "auto-accept is off")
        fun refuse(why: String) = Decision(if (declineOthers) "decline" else "ignore", why)
        val speed = c["speed"].str(); val variant = c["variant"].str(); val kind = c["from_kind"].str()
        if (speeds.isNotEmpty() && speed !in speeds) return refuse("time control $speed is not accepted")
        if (variants.isNotEmpty() && variant !in variants) return refuse("variant $variant is not accepted")
        val isRated = c["rated"].bool()
        if (rated == "casual" && isRated) return refuse("only casual games are accepted")
        if (rated == "rated" && !isRated) return refuse("only rated games are accepted")
        val strict = c["ruleset"].str().let { it == "fide_strict" || it == "strict" }
        if (ruleset == "casual" && strict) return refuse("strict (FIDE) games are not accepted")
        if (ruleset == "strict" && !strict) return refuse("only strict (FIDE) games are accepted")
        if (opponentKinds.isNotEmpty() && kind !in opponentKinds) return refuse("opponents of kind $kind are not accepted")
        if (playing >= maxOf(1, maxConcurrent)) return Decision("ignore", "already playing $playing game(s)")
        return Decision("accept")
    }

    companion object {
        fun fromJson(d: JsonObject): Rules = Rules(
            d["enabled"].bool(), d["speeds"].arr().map { it.str() }, d["variants"].arr().map { it.str() },
            d["rated"].str().ifEmpty { "any" }, d["ruleset"].str().ifEmpty { "any" }, d["opponent_kinds"].arr().map { it.str() },
            maxOf(1, d["max_concurrent"].long().toInt()), d["decline_others"].bool(),
        )

        private val label = Regex("""^\s*(\d+(?:\.\d+)?)\s*(?:[+|]\s*(\d+))?""")
        private val days = Regex("""\d\s*d""")

        /** bullet / blitz / rapid / classical / correspondence / untimed, from a control object (may be null) or a label like "3+2". */
        fun speedOf(control: JsonObject? = null, label: String = ""): String {
            val kind = control?.get("kind").str()
            if (kind == "correspondence" || days.containsMatchIn(label) || "day" in label) return "correspondence"
            if (kind == "untimed" || label.trim().equals("untimed", true)) return "untimed"
            val est: Long = if (!control.isNullOrEmpty()) {
                val inc = maxOf(control["increment_ms"].long(), control["delay_ms"].long(), control["bronstein_ms"].long())
                (control["initial_ms"].long() + 40 * inc) / 1000
            } else {
                val m = this.label.find(label) ?: return "classical"
                (m.groupValues[1].toDouble() * 60).toLong() + 40 * (m.groupValues[2].toLongOrNull() ?: 0L)
            }
            return if (est < 180) "bullet" else if (est < 480) "blitz" else if (est < 1500) "rapid" else "classical"
        }

        private fun info(speed: String, variant: String, rated: Boolean, ruleset: String, kind: String): JsonObject = mapOf(
            "speed" to speed, "variant" to variant.ifEmpty { "standard" }, "rated" to rated, "ruleset" to ruleset.ifEmpty { "casual" }, "from_kind" to kind.ifEmpty { "agent" })

        /** The fields [evaluate] reads, from a challenge_received event. */
        fun infoFromEvent(ev: JsonObject): JsonObject {
            val d = ev["data"].obj()
            return info(d["speed"].str().ifEmpty { speedOf(null, d["time_control"].str()) }, d["variant"].str(), d["rated"].bool(), d["ruleset"].str(), d["from_kind"].str())
        }

        /** The same, from an entry of whoami's incoming_challenges. */
        fun infoFromChallenge(c: JsonObject): JsonObject {
            val p = c["params"].obj()
            return info(speedOf(p["control"] as? JsonObject), p["variant"].str(), p["rated"].bool(), p["ruleset"].str(), c["from"].obj()["kind"].str())
        }
    }
}

/** Reply of [Client.standby]: are you on duty, how often to call it, and the owner's auto-accept rules. */
class Duty(d: JsonObject) {
    private val p = d["presence"].obj()
    val onDuty = d["on_duty"].bool()
    val state = p["state"].str()
    val mode = p["mode"].str()
    val rules = Rules.fromJson(d["rules"].obj())
    val heartbeatEverySeconds = d["heartbeat_every_seconds"].long().toInt().takeIf { it > 0 } ?: 30
    val ttlSeconds = d["ttl_seconds"].long().toInt().takeIf { it > 0 } ?: 90
    val incomingChallenges = d["incoming_challenges"].long().toInt()
}

/** Heartbeat of a watcher: marks you on duty ([off] ends the duty) and returns your owner's auto-accept rules. */
fun Client.standby(client: String? = null, off: Boolean = false): Duty =
    Duty(callTool("standby", buildMap { if (!client.isNullOrEmpty()) put("client", client); if (off) put("action", "off") }).data)

/** Options of a [Watcher]; every field has a default. */
class WatchOptions(
    /** Overrides the owner's rules from the agent page when set. */
    var rules: Rules? = null, var serverRules: Boolean = true,
    var heartbeatEveryMs: Long = 30_000, var busyPollMs: Long = 3_000, var maxBackoffMs: Long = 60_000, var waitSeconds: Double = 25.0,
    var log: ((String) -> Unit)? = null, var clientName: String? = null,
    /** Options passed to every [Client.play]; the game id is set by the watcher. */
    var play: PlayOptions = PlayOptions(),
)

/** The durable loop behind [watch]. Use [watch] unless you need [stop] from another thread. */
class Watcher(private val client: Client, handler: Strategy, private val o: WatchOptions = WatchOptions()) {
    private val log: (String) -> Unit = o.log ?: { System.err.println("${java.time.LocalTime.now().withNano(0)} $it") }
    private val clientName = o.clientName ?: "neuromate-kotlin/${Client.VERSION} watch"
    private val handler: Strategy = { pos ->
        try {
            handler(pos)
        } catch (e: Exception) {
            log("handler error in ${pos.gameId}: $e; playing a fallback move")
            Move(pos.legalUci.randomOrNull() ?: "")
        }
    }

    @Volatile var stopped = false
        private set
    @Volatile var rules: Rules = o.rules ?: Rules()
        private set
    @Volatile var accepted = 0
        private set
    @Volatile var played = 0
        private set
    @Volatile private var standbyOk = true
    private val games: MutableMap<String, Thread> = ConcurrentHashMap()
    private val cooldown: MutableMap<String, Long> = ConcurrentHashMap()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val wake = Object()

    /** Ends the loop (the duty is ended cleanly) and wakes any pause. Safe from any thread. */
    fun stop() {
        stopped = true
        synchronized(wake) { wake.notifyAll() }
    }

    private fun nap(ms: Long) {
        val end = System.currentTimeMillis() + ms
        synchronized(wake) {
            while (!stopped) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return
                try { (wake as Object).wait(left) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); stopped = true; return }
            }
        }
    }

    private fun beat() {
        if (!standbyOk) return
        val d = try {
            client.standby(clientName)
        } catch (e: ProtocolException) {
            if ("unknown tool" in (e.message ?: "")) {
                standbyOk = false
                log("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)")
                return
            }
            throw e
        }
        if (o.serverRules && o.rules == null) {
            if (d.rules != rules) log("auto-accept rules updated: ${if (d.rules.enabled) "on" else "off"}")
            rules = d.rules
        }
    }

    private fun heartbeatLoop() {
        var fails = 0
        while (!stopped) {
            nap(o.heartbeatEveryMs)
            if (stopped) return
            try {
                beat(); fails = 0
            } catch (e: AuthException) {
                log("heartbeat: the API key was rejected"); return
            } catch (e: NeuromateException) {
                fails++
                if (fails == 1 || fails == 5) log("heartbeat failed (${e.message?.take(100)}); retrying")
            }
        }
    }

    /** Runs until [stop]; throws [AuthException] when the API key is rejected. */
    fun run() {
        log("on duty as $clientName")
        var backoff = 1000L
        try { beat() } catch (e: AuthException) { throw e } catch (e: NeuromateException) { log("first heartbeat failed (${e.message?.take(100)}); will keep trying") }
        val hb = Thread(::heartbeatLoop, "neuromate-heartbeat").apply { isDaemon = true; start() }
        var first = true
        var last = 0L
        try {
            while (!stopped) {
                try {
                    if (first || System.currentTimeMillis() - last > 30_000 || games.isNotEmpty()) { reconcile(); last = System.currentTimeMillis(); first = false }
                    games.entries.removeIf { !it.value.isAlive }
                    if (games.isNotEmpty()) {
                        nap(o.busyPollMs)
                    } else {
                        val turn = client.waitForTurn("", o.waitSeconds)
                        events(turn.events)
                        val p = turn.position
                        if (p != null && !p.isOver && p.gameId !in games) spawn(p.gameId)
                    }
                    backoff = 1000
                } catch (e: AuthException) {
                    log("the API key was rejected (${e.message?.take(80)}); stopping"); throw e
                } catch (e: RateLimitedException) {
                    nap(minOf(e.retryAfterMs, 30_000))
                } catch (e: NeuromateException) {
                    log("connection problem (${e.message?.take(100)}); retrying in ${backoff / 1000}s")
                    nap((backoff * (0.5 + Math.random())).toLong())
                    backoff = minOf(backoff * 2, o.maxBackoffMs)
                }
            }
        } finally {
            stopped = true
            synchronized(wake) { wake.notifyAll() }
            hb.join(2000)
            games.values.forEach { it.join(200) }
            if (standbyOk) try { client.standby(clientName, off = true); log("off duty") } catch (_: NeuromateException) { }
        }
    }

    private fun reconcile() {
        val lobby = client.whoami()
        lobby["active_games"].arr().forEach { g -> val id = g.obj()["game_id"].str(); if (id !in games) spawn(id) }
        val evs = lobby["events"].arr().map { it.obj() }
        events(evs)
        val pending = evs.filter { it["type"].str() == "challenge_received" }.map { it["data"].obj()["challenge_id"].str() }.toSet()
        lobby["incoming_challenges"].arr().map { it.obj() }.forEach { ch ->
            val st = ch["status"].str()
            if ((st.isEmpty() || st == "pending") && ch["id"].str() !in pending) challenge(ch["id"].str(), Rules.infoFromChallenge(ch), ch["from"].obj()["name"].str())
        }
        val max = evs.maxOfOrNull { it["seq"].long() } ?: 0L
        if (max > 0) client.listChallenges(max)
    }

    private fun events(evs: List<JsonObject>) {
        for (ev in evs) {
            val d = ev["data"].obj()
            when (ev["type"].str()) {
                "challenge_received" -> challenge(d["challenge_id"].str(), Rules.infoFromEvent(ev), d["from"].str().ifEmpty { "?" })
                "game_started" -> d["game_id"].str().let { if (it.isNotEmpty() && it !in games) spawn(it) }
                "tournament_offline_warning", "tournament_withdrawn", "queue_dropped" -> log("${ev["type"].str()}: ${d["next"].str().ifEmpty { d["message"].str() }}")
                "challenge_declined" -> log("challenge declined: ${d["reason"].str()}")
            }
        }
    }

    private fun challenge(cid: String, info: JsonObject, who: String) {
        if (cid.isEmpty()) return
        val d = rules.evaluate(info, games.size)
        val first = cid !in seen
        when (d.action) {
            "accept" -> {
                val pos = try { client.acceptChallenge(cid) } catch (e: ToolException) {
                    log("could not accept $cid from $who: ${e.message?.take(120)}"); seen += cid; return
                }
                accepted++
                seen += cid
                log("accepted $cid from $who (${info["speed"]} ${info["variant"]}); game ${pos.gameId}")
                spawn(pos.gameId)
            }
            "decline" -> {
                if (first) log("declined $cid from $who: ${d.reason}")
                try { client.declineChallenge(cid, d.reason.take(200)) } catch (_: ToolException) { }
                seen += cid
            }
            else -> if (first) {
                log("challenge $cid from $who left for the owner: ${d.reason}")
                seen += cid
                if (seen.size > 500) seen.clear()
            }
        }
    }

    private fun spawn(gameId: String) {
        if (gameId.isEmpty() || gameId in games) return
        if (System.currentTimeMillis() - (cooldown[gameId] ?: -100_000L) < 5000) return
        val t = Thread({ playGame(gameId) }, "neuromate-game-$gameId").apply { isDaemon = true }
        if (games.putIfAbsent(gameId, t) != null) return
        t.start()
        log("playing game $gameId")
    }

    private fun playGame(gameId: String) {
        val s = o.play
        val po = PlayOptions(gameId, s.opponent, s.variant, s.timeControl, s.pool, s.rated, s.acceptChallenges, s.maxIllegal, s.maxRetries, s.waitSeconds, s.findTimeoutMs,
            s.noJoin, s.onPosition, s.onDrawOffer, s.fallback, s.onEvent)
        try {
            val fin = client.play(handler, po)
            log("game $gameId over: ${fin.resultScore}")
            played++
        } catch (e: AuthException) {
            log("the API key was rejected while playing; stopping"); stop()
        } catch (e: RuntimeException) {
            log("game $gameId: ${e.message?.take(120)}")
            cooldown[gameId] = System.currentTimeMillis()
        }
    }
}

/**
 * Stays on duty until [Watcher.stop] (or the JVM shuts down): heartbeats, reconnects, the owner's auto-accept rules and [strategy]
 * for every move. Blocks; returns the watcher after it stopped.
 */
fun Client.watch(strategy: Strategy, options: WatchOptions = WatchOptions()): Watcher {
    val w = Watcher(this, strategy, options)
    Runtime.getRuntime().addShutdownHook(Thread { w.stop() })
    w.run()
    return w
}
