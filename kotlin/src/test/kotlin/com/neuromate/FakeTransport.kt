package com.neuromate

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Replays recorded tool results (sdk/testdata) as a scripted MCP server. */
class FakeTransport : Transport {
    val script = LinkedHashMap<String, MutableList<Any?>>()
    val names = ArrayList<String>()
    val args = ArrayList<JsonObject>()
    var forcedStatus = 0
    var forcedBody = ""
    var contentType = "application/json"

    fun on(tool: String, vararg results: Any?) = apply { script[tool] = results.toMutableList() }

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): Transport.Response {
        if (forcedStatus != 0) return Transport.Response(forcedStatus, mapOf("content-type" to "application/json"), forcedBody)
        val req = Json.parse(body).obj()
        val method = req["method"].str()
        if (method == "notifications/initialized") return Transport.Response(202, emptyMap(), "")
        val result: Any? = if (method == "initialize") mapOf("serverInfo" to mapOf("name" to "fake", "version" to "1")) else {
            val p = req["params"].obj()
            val name = p["name"].str()
            names += name; args += p["arguments"].obj()
            val q = script[name] ?: throw IOException("no scripted reply for $name")
            if (q.size > 1) q.removeAt(0) else q[0]
        }
        var json = Json.stringify(mapOf("jsonrpc" to "2.0", "id" to req["id"], "result" to result))
        if ("event-stream" in contentType) json = "event: message\ndata: $json\n\n"
        return Transport.Response(200, mapOf("content-type" to contentType), json)
    }

    companion object {
        fun fixture(name: String): JsonObject = Json.parse(Files.readString(Path.of("..", "testdata", "$name.json"))).obj()
        fun result(name: String): JsonObject = fixture(name)["response"].obj()["result"].obj()

        /** A wait_for_turn result around the game of a recorded fixture. */
        fun waitResult(reason: String, gameFrom: String): JsonObject = mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to "WAIT")),
            "structuredContent" to mapOf("reason" to reason, "events" to emptyList<Any>(), "game" to result(gameFrom)["structuredContent"].obj()["game"]),
        )
    }
}
