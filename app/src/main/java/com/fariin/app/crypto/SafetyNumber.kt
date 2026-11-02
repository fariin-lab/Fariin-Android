package com.fariin.app.crypto

import java.security.MessageDigest

/**
 * The 60-digit safety number of a 1:1 chat, the same digits as Kulan/Services/SafetyNumber.swift
 * so an iPhone and an Android phone show (and scan) the same number.
 */
object SafetyNumber {
    private const val ITERATIONS = 5200
    private val VERSION = byteArrayOf(0x00, 0x00)
    const val QR_PREFIX = "fariinverify:v1:"

    /** Symmetric: compute(me, them) on my phone == compute(them, me) on theirs. */
    fun compute(myUid: String, myPub: ByteArray, theirUid: String, theirPub: ByteArray): String {
        val a = fingerprint(myPub, myUid)
        val b = fingerprint(theirPub, theirUid)
        // Swift compares the two digit strings with `<=` (ASCII digits, same length), as here.
        return if (a <= b) a + b else b + a
    }

    /** 12 groups of 5 digits. */
    fun grouped(number: String): List<String> = number.chunked(5)

    fun qrPayload(number: String): String = QR_PREFIX + number

    fun numberFromQR(code: String): String? {
        if (!code.startsWith(QR_PREFIX)) return null
        val n = code.substring(QR_PREFIX.length)
        // Swift's Character.isNumber is wider than ASCII; a real number only ever holds 0-9.
        return if (n.length == 60 && n.all { it in '0'..'9' }) n else null
    }

    /** Iterated SHA-512(hash || pub), starting from version || pub || utf8(uid); 30 digits. */
    internal fun fingerprint(pub: ByteArray, id: String): String {
        var hash = VERSION + pub + id.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-512")
        repeat(ITERATIONS) {
            md.reset(); md.update(hash); md.update(pub)
            hash = md.digest()
        }
        val sb = StringBuilder()
        for (chunk in 0 until 6) {
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (hash[chunk * 5 + j].toLong() and 0xff)
            sb.append(String.format(java.util.Locale.ROOT, "%05d", v % 100_000))
        }
        return sb.toString()
    }
}
