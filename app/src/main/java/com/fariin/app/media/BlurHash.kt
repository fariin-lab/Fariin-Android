package com.fariin.app.media

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.withSign

/**
 * BlurHash, the standard public algorithm, as Kulan-iOS BlurHash.swift uses it: 4 x 3 components
 * from a picture of at most 32 px, sealed into the message like the caption, so the other side
 * draws a blur of the real photo before any bytes arrive. Pixels are ARGB ints (Android's).
 */
object BlurHash {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun encode(pixels: IntArray, w: Int, h: Int, cx: Int = 4, cy: Int = 3): String? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val factors = ArrayList<FloatArray>()
        for (j in 0 until cy) for (i in 0 until cx) {
            val norm = if (i == 0 && j == 0) 1f else 2f
            var r = 0f; var g = 0f; var b = 0f
            for (y in 0 until h) for (x in 0 until w) {
                val basis = norm * cos(Math.PI * i * x / w).toFloat() * cos(Math.PI * j * y / h).toFloat()
                val p = pixels[y * w + x]
                r += basis * toLinear((p shr 16) and 0xFF)
                g += basis * toLinear((p shr 8) and 0xFF)
                b += basis * toLinear(p and 0xFF)
            }
            val s = 1f / (w * h)
            factors.add(floatArrayOf(r * s, g * s, b * s))
        }
        val dc = factors[0]
        val ac = factors.drop(1)
        val sb = StringBuilder()
        sb.append(encode83((cx - 1) + (cy - 1) * 9, 1))
        var maxValue = 1f
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { maxOf(abs(it[0]), abs(it[1]), abs(it[2])) }
            val q = floor(actualMax * 166 - 0.5).toInt().coerceIn(0, 82)
            maxValue = (q + 1) / 166f
            sb.append(encode83(q, 1))
        } else sb.append(encode83(0, 1))
        sb.append(encode83((toSrgb(dc[0]) shl 16) + (toSrgb(dc[1]) shl 8) + toSrgb(dc[2]), 4))
        for (f in ac) {
            fun q(v: Float) = floor(signPow(v / maxValue, 0.5f) * 9 + 9.5f).toInt().coerceIn(0, 18)
            sb.append(encode83(q(f[0]) * 19 * 19 + q(f[1]) * 19 + q(f[2]), 2))
        }
        return sb.toString()
    }

    /** ARGB pixels of a [w] x [h] picture, or null for a string that is not a BlurHash. */
    fun decode(hash: String, w: Int = 32, h: Int = 32, punch: Float = 1f): IntArray? {
        if (hash.length < 6 || w <= 0 || h <= 0) return null
        val size = decode83(hash, 0, 1) ?: return null
        val cy = size / 9 + 1
        val cx = size % 9 + 1
        if (hash.length != 4 + 2 * cx * cy) return null
        val qMax = decode83(hash, 1, 2) ?: return null
        val maxValue = (qMax + 1) / 166f * punch
        val colors = Array(cx * cy) { FloatArray(3) }
        val dc = decode83(hash, 2, 6) ?: return null
        colors[0] = floatArrayOf(toLinear(dc shr 16), toLinear((dc shr 8) and 0xFF), toLinear(dc and 0xFF))
        for (k in 1 until cx * cy) {
            val v = decode83(hash, 4 + k * 2, 6 + k * 2) ?: return null
            fun d(q: Int) = signPow((q - 9) / 9f, 2f) * maxValue
            colors[k] = floatArrayOf(d(v / (19 * 19)), d((v / 19) % 19), d(v % 19))
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until cy) for (i in 0 until cx) {
                val basis = (cos(Math.PI * x * i / w) * cos(Math.PI * y * j / h)).toFloat()
                val c = colors[i + j * cx]
                r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
            }
            out[y * w + x] = (0xFF shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
        }
        return out
    }

    private fun signPow(v: Float, e: Float) = abs(v).pow(e).withSign(v)

    private fun toLinear(v: Int): Float {
        val x = v / 255f
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun toSrgb(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) (x * 12.92f * 255 + 0.5f).toInt()
        else ((1.055f * x.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }

    private fun encode83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            sb.append(ALPHABET[digit])
        }
        return sb.toString()
    }

    private fun decode83(s: String, from: Int, to: Int): Int? {
        var v = 0
        for (i in from until to) {
            val d = ALPHABET.indexOf(s[i])
            if (d < 0) return null
            v = v * 83 + d
        }
        return v
    }
}
