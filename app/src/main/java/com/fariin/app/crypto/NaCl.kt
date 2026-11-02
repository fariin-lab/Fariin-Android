package com.fariin.app.crypto

import com.goterl.lazysodium.Sodium

/**
 * The few libsodium calls the wire format needs. The iOS app (swift-sodium) uses exactly these:
 * crypto_box_easy / crypto_box_open_easy (curve25519-xsalsa20-poly1305, MAC first) and
 * crypto_secretbox_easy / crypto_secretbox_open_easy (xsalsa20-poly1305, MAC first).
 *
 * One interface, two back ends: [SodiumNaCl] over `SodiumAndroid` in the app, over `SodiumJava`
 * in JVM unit tests. Both extend lazysodium's `Sodium`, which declares the same native methods.
 */
interface NaCl {
    class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun boxKeyPair(): KeyPair
    /** Public key for a 32-byte secret key (crypto_scalarmult_base). */
    fun boxPublicKey(secretKey: ByteArray): ByteArray
    fun randomBytes(n: Int): ByteArray

    /** crypto_box_easy: returns MAC(16) || ciphertext. */
    fun boxEasy(message: ByteArray, nonce: ByteArray, recipientPublicKey: ByteArray, senderSecretKey: ByteArray): ByteArray?
    /** crypto_box_open_easy: null when the MAC does not verify. */
    fun boxOpenEasy(box: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray?
    /** crypto_secretbox_easy: returns MAC(16) || ciphertext. */
    fun secretBoxEasy(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?
    fun secretBoxOpenEasy(box: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val SECRET_KEY_BYTES = 32
        const val NONCE_BYTES = 24        // box and secretbox nonces are both 24 bytes
        const val SECRETBOX_KEY_BYTES = 32
        const val MAC_BYTES = 16

        /** The app's back end. Call once and keep it (loads libsodium.so through JNA). */
        fun android(): NaCl = SodiumNaCl(com.goterl.lazysodium.SodiumAndroid())
    }
}

class SodiumNaCl(private val s: Sodium) : NaCl {
    init { check(s.sodium_init() >= 0) { "sodium_init failed" } }

    override fun boxKeyPair(): NaCl.KeyPair {
        val pk = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        val sk = ByteArray(NaCl.SECRET_KEY_BYTES)
        check(s.crypto_box_keypair(pk, sk) == 0) { "crypto_box_keypair failed" }
        return NaCl.KeyPair(pk, sk)
    }

    override fun boxPublicKey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == NaCl.SECRET_KEY_BYTES)
        val pk = ByteArray(NaCl.PUBLIC_KEY_BYTES)
        check(s.crypto_scalarmult_base(pk, secretKey) == 0)
        return pk
    }

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { s.randombytes_buf(it, n) }

    override fun boxEasy(message: ByteArray, nonce: ByteArray, recipientPublicKey: ByteArray, senderSecretKey: ByteArray): ByteArray? {
        if (nonce.size != NaCl.NONCE_BYTES || recipientPublicKey.size != NaCl.PUBLIC_KEY_BYTES ||
            senderSecretKey.size != NaCl.SECRET_KEY_BYTES) return null
        val out = ByteArray(message.size + NaCl.MAC_BYTES)
        return if (s.crypto_box_easy(out, message, message.size.toLong(), nonce, recipientPublicKey, senderSecretKey) == 0) out else null
    }

    override fun boxOpenEasy(box: ByteArray, nonce: ByteArray, senderPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray? {
        if (box.size < NaCl.MAC_BYTES || nonce.size != NaCl.NONCE_BYTES ||
            senderPublicKey.size != NaCl.PUBLIC_KEY_BYTES || recipientSecretKey.size != NaCl.SECRET_KEY_BYTES) return null
        val out = ByteArray(box.size - NaCl.MAC_BYTES)
        return if (s.crypto_box_open_easy(out, box, box.size.toLong(), nonce, senderPublicKey, recipientSecretKey) == 0) out else null
    }

    override fun secretBoxEasy(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (nonce.size != NaCl.NONCE_BYTES || key.size != NaCl.SECRETBOX_KEY_BYTES) return null
        val out = ByteArray(message.size + NaCl.MAC_BYTES)
        return if (s.crypto_secretbox_easy(out, message, message.size.toLong(), nonce, key) == 0) out else null
    }

    override fun secretBoxOpenEasy(box: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        if (box.size < NaCl.MAC_BYTES || nonce.size != NaCl.NONCE_BYTES || key.size != NaCl.SECRETBOX_KEY_BYTES) return null
        val out = ByteArray(box.size - NaCl.MAC_BYTES)
        return if (s.crypto_secretbox_open_easy(out, box, box.size.toLong(), nonce, key) == 0) out else null
    }
}
