package com.fariin.app.backup

/**
 * The archive's one JSON spelling, byte for byte the same as iOS (JSONSerialization with
 * .sortedKeys + .withoutEscapingSlashes): keys sorted, "/" not escaped, no spaces, whole numbers
 * written without a fraction, null as null.
 */
object Canonical {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 9.0E15) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is String -> string(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v.entries.map { it.key.toString() to it.value }.sortedBy { it.first }) {
                    if (!first) sb.append(','); first = false
                    string(sb, k); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('['); var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            is org.json.JSONObject -> write(sb, v.keys().asSequence().associateWith { v.opt(it).let { x -> if (x == org.json.JSONObject.NULL) null else x } })
            is org.json.JSONArray -> write(sb, (0 until v.length()).map { v.opt(it).let { x -> if (x == org.json.JSONObject.NULL) null else x } })
            else -> string(sb, v.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    /** JSON text -> plain maps / lists / numbers / strings (null for JSON null). */
    fun decode(s: String): Any? = plain(org.json.JSONTokener(s).nextValue())

    private fun plain(x: Any?): Any? = when (x) {
        org.json.JSONObject.NULL, null -> null
        is org.json.JSONObject -> x.keys().asSequence().associateWith { plain(x.opt(it)) }
        is org.json.JSONArray -> (0 until x.length()).map { plain(x.opt(it)) }
        else -> x
    }
}
