package com.neuromate

/** A tiny JSON reader/writer so the SDK has no dependencies. Objects are Map, arrays List, numbers Long or Double. */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun expect(c: Char) { require(i < s.length && s[i] == c) { "expected '$c' at $i" }; i++ }
        fun value(): Any? {
            require(i < s.length) { "unexpected end of JSON" }
            when (val c = s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        ws(); val k = string(); ws(); expect(':'); ws()
                        m[k] = value(); ws()
                        if (s[i] == ',') { i++; continue }
                        expect('}'); return m
                    }
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        ws(); l.add(value()); ws()
                        if (s[i] == ',') { i++; continue }
                        expect(']'); return l
                    }
                }
                '"' -> return string()
                't' -> { lit("true"); return true }
                'f' -> { lit("false"); return false }
                'n' -> { lit("null"); return null }
                else -> {
                    val st = i
                    while (i < s.length && s[i] in "+-0123456789.eE") i++
                    val n = s.substring(st, i)
                    require(n.isNotEmpty()) { "unexpected '$c' at $st" }
                    return if (n.any { it in ".eE" }) n.toDouble() else n.toLong()
                }
            }
        }
        fun lit(w: String) { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length }
        fun string(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') return b.toString()
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
        }
    }

    fun stringify(o: Any?): String = StringBuilder().also { write(it, o) }.toString()

    private fun write(b: StringBuilder, o: Any?) {
        when (o) {
            null -> b.append("null")
            is String -> {
                b.append('"')
                for (c in o) when {
                    c == '"' -> b.append("\\\"")
                    c == '\\' -> b.append("\\\\")
                    c == '\n' -> b.append("\\n")
                    c == '\r' -> b.append("\\r")
                    c == '\t' -> b.append("\\t")
                    c.code < 0x20 -> b.append(String.format("\\u%04x", c.code))
                    else -> b.append(c)
                }
                b.append('"')
            }
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, v) in o) { if (!first) b.append(','); first = false; write(b, k.toString()); b.append(':'); write(b, v) }
                b.append('}')
            }
            is Iterable<*> -> {
                b.append('[')
                var first = true
                for (x in o) { if (!first) b.append(','); first = false; write(b, x) }
                b.append(']')
            }
            else -> b.append(o.toString())
        }
    }
}

typealias JsonObject = Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): JsonObject = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.arr(): List<Any?> = (this as? List<Any?>) ?: emptyList()
fun Any?.str(): String = this as? String ?: ""
fun Any?.long(): Long = (this as? Number)?.toLong() ?: 0L
fun Any?.dbl(): Double = (this as? Number)?.toDouble() ?: 0.0
fun Any?.bool(): Boolean = this as? Boolean ?: false
