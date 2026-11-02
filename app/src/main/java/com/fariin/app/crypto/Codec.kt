package com.fariin.app.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Encodings exactly as the iOS side uses them.
 *
 * iOS writes Foundation `Data.base64EncodedString()`: STANDARD alphabet (+ /), PADDED with '=',
 * no line breaks (Crypto.swift:12-14). It reads with `Data(base64Encoded:)`, which wants the same.
 */
object B64 {
    private val enc = java.util.Base64.getEncoder()          // standard, padded, no wrapping
    private val dec = java.util.Base64.getDecoder()

    fun encode(b: ByteArray): String = enc.encodeToString(b)

    /** Strict standard base64 (what `Data(base64Encoded:)` accepts). null on anything else. */
    fun decode(s: String): ByteArray? {
        if (s.length % 4 != 0) return null
        return try { dec.decode(s) } catch (_: IllegalArgumentException) { null }
    }

    /** Standard or URL-safe, padded or not: `Crypto.looseBase64` (Crypto.swift:1042). Used for `rk`. */
    fun decodeLoose(s: String): ByteArray? {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return decode(t)
    }
}

object Utf8 {
    fun encode(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** Like Swift `String(bytes:encoding:.utf8)`: null on malformed UTF-8 instead of U+FFFD. */
    fun decodeStrict(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) { null }
}

/**
 * Minimal JSON for the group envelope (`encg1:`), so the format code has no Android dependency
 * (org.json is a stub in JVM unit tests). Reads any JSON value; writes objects of strings.
 *
 * iOS writes with `JSONSerialization`, which escapes '/' as "\/" and orders keys arbitrarily;
 * the reader here handles both. The writer emits plain '/', which JSONSerialization reads fine.
 */
object MiniJson {
    fun parse(s: String): Any? = try {
        val p = Parser(s); p.ws(); val v = p.value(); p.ws()
        if (p.i != s.length) null else v
    } catch (_: RuntimeException) { null }

    fun writeObject(m: Map<String, Any?>): String = StringBuilder().also { writeValue(it, m) }.toString()

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString()); sb.append(':'); writeValue(sb, x)
                }
                sb.append('}')
            }
            is Boolean, is Int, is Long -> sb.append(v.toString())
            else -> throw IllegalArgumentException("unsupported JSON value")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) error("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) error("bad literal"); i += w.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i] != ':') error("colon"); i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("obj") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("arr") }
            }
        }
        fun str(): String {
            if (s[i] != '"') error("string"); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("escape $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            if (t.isEmpty()) error("value")
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
