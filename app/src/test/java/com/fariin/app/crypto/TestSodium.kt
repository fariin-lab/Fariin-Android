package com.fariin.app.crypto

import com.goterl.lazysodium.SodiumJava

/** JVM back end for unit tests (bundled desktop libsodium through lazysodium-java). */
object TestSodium {
    val raw: SodiumJava by lazy { SodiumJava() }
    val nacl: NaCl by lazy { SodiumNaCl(raw) }

    fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    // RFC 7748 section 6.1 X25519 test keys (the same pair the NaCl box test uses).
    val aliceSk = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    val alicePk = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    val bobSk = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    val bobPk = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    // The NaCl box test nonce.
    val nonce = hex("69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37")
}
