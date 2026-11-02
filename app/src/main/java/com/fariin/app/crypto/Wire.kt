package com.fariin.app.crypto

/**
 * The message formats, byte for byte as the iOS app writes and reads them. Stateless: every key
 * comes in as an argument, so the formats can be tested without accounts, storage or network.
 * See WIRE_FORMAT.md in this folder for the formats with references into the Swift source.
 */
class Wire(private val nacl: NaCl) {

    companion object {
        const val ENC1 = "enc1:"          // 1:1 text
        const val ENCG1 = "encg1:"        // group text (sealed groups only)
        const val LEGACY = "enc:"         // an old, fake format; never opened

        // What the iOS `decrypt` returns instead of text (Crypto.swift:718-721). The UI tests for
        // these exact strings, so they are kept identical.
        const val LOCKED = "🔒"   // the lock emoji: tampered or wrong key
        const val NOT_READY = "…"      // horizontal ellipsis: keys not loaded yet
        const val OLD_MESSAGE = "[old message]"

        /** 1:1 chat ids are "uidA_uidB" (sorted); group ids never contain '_' (Crypto.swift:1036). */
        fun isGroupCid(cid: String): Boolean = cid.isNotEmpty() && !cid.contains('_')

        /** The 1:1 chat id two users share: sorted, joined with '_' (CallService.swift:406). */
        fun directCid(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")

        /** The other participant of a 1:1 chat id (Crypto.swift:661). */
        fun otherUid(cid: String, me: String): String {
            val p = cid.split('_')
            if (p.size != 2) return ""
            return if (p[0] == me) p[1] else p[0]
        }
    }

    fun randomNonce(): ByteArray = nacl.randomBytes(NaCl.NONCE_BYTES)
    fun randomKey(): ByteArray = nacl.randomBytes(NaCl.SECRETBOX_KEY_BYTES)

    /** Opens a box with the sender's current key first, then earlier ones (Crypto.swift:121).
     *  Returns the plaintext and which key opened it (0 = the first in the list). */
    fun openBoxAny(box: ByteArray, nonce: ByteArray, senderKeys: List<ByteArray>, mySk: ByteArray): Pair<ByteArray, Int>? {
        val tried = ArrayList<ByteArray>()
        senderKeys.forEachIndexed { i, k ->
            if (tried.any { it.contentEquals(k) }) return@forEachIndexed
            tried.add(k)
            nacl.boxOpenEasy(box, nonce, k, mySk)?.let { return it to i }
        }
        return null
    }

    // ---------------------------------------------------------------- 1:1 text

    /** "enc1:" + b64(nonce) + ":" + b64(MAC||ct), crypto_box_easy (Crypto.swift:672-688). */
    fun sealText(text: String, recipientPub: ByteArray, mySk: ByteArray, nonce: ByteArray = randomNonce()): String {
        val box = nacl.boxEasy(Utf8.encode(text), nonce, recipientPub, mySk) ?: throw CryptoException("seal failed")
        return ENC1 + B64.encode(nonce) + ":" + B64.encode(box)
    }

    class Enc1(val nonce: ByteArray, val box: ByteArray, val nonceB64: String)

    /** Splits "enc1:n:b" exactly as Swift does (split on ':', empties kept, exactly 3 parts). */
    fun parseEnc1(raw: String): Enc1? {
        if (!raw.startsWith(ENC1)) return null
        val parts = raw.split(':')
        if (parts.size != 3) return null
        val n = B64.decode(parts[1]) ?: return null
        val b = B64.decode(parts[2]) ?: return null
        return Enc1(n, b, parts[1])
    }

    /** Plaintext and the index of the sender key that opened it; null when nothing opens it or
     *  the bytes are not UTF-8 (both show [LOCKED] on iOS). */
    fun openText(raw: String, senderKeys: List<ByteArray>, mySk: ByteArray): Pair<String, Int>? {
        val e = parseEnc1(raw) ?: return null
        val (plain, idx) = openBoxAny(e.box, e.nonce, senderKeys, mySk) ?: return null
        return (Utf8.decodeStrict(plain) ?: return null) to idx
    }

    // ---------------------------------------------------------------- group text

    /** Decoded "encg1:" envelope: base64 of JSON { n, c, w: { uid: "wrapB64.nonceB64" } }. */
    class GroupEnvelope(val n: String, val c: String, val w: Map<String, String>) {
        val dataNonce: ByteArray? get() = B64.decode(n)
        val cipher: ByteArray? get() = B64.decode(c)
    }

    fun parseEncg1(raw: String): GroupEnvelope? {
        if (!raw.startsWith(ENCG1)) return null
        val json = B64.decode(raw.substring(ENCG1.length)) ?: return null
        val obj = MiniJson.parse(Utf8.decodeStrict(json) ?: return null) as? Map<*, *> ?: return null
        val n = obj["n"] as? String ?: return null
        val c = obj["c"] as? String ?: return null
        val wRaw = obj["w"] as? Map<*, *> ?: return null
        // Swift casts `w` to [String: String]; one non-string value fails the whole cast.
        val w = LinkedHashMap<String, String>()
        for ((k, v) in wRaw) w[k as String] = v as? String ?: return null
        if (B64.decode(n) == null || B64.decode(c) == null) return null
        return GroupEnvelope(n, c, w)
    }

    /** A per-member wrap: b64(box of the 32-byte item key) + "." + b64(box nonce). */
    fun makeWrap(itemKey: ByteArray, memberPub: ByteArray, mySk: ByteArray, nonce: ByteArray = randomNonce()): String? {
        val w = nacl.boxEasy(itemKey, nonce, memberPub, mySk) ?: return null
        return B64.encode(w) + "." + B64.encode(nonce)
    }

    /** Opens my wrap with the AUTHOR's keys. Returns the item key and the key index. */
    fun openWrap(wrap: String, authorKeys: List<ByteArray>, mySk: ByteArray): Pair<ByteArray, Int>? {
        val wp = wrap.split('.')
        if (wp.size != 2) return null
        val wrapped = B64.decode(wp[0]) ?: return null
        val nonce = B64.decode(wp[1]) ?: return null
        return openBoxAny(wrapped, nonce, authorKeys, mySk)
    }

    /**
     * Crypto.swift:931-965. The body is sealed once with a random secretbox key; that key is boxed
     * to every member with a key (author included). [memberKeys] = uid -> public key of the members
     * that have one. Throws [MissingRecipientKeyException] when nobody but me could read it.
     */
    fun sealGroupText(
        text: String, me: String, members: Collection<String>, memberKeys: Map<String, ByteArray>, mySk: ByteArray,
        msgKey: ByteArray = randomKey(), dataNonce: ByteArray = randomNonce(),
        wrapNonce: (String) -> ByteArray = { randomNonce() },
    ): String {
        val ct = nacl.secretBoxEasy(Utf8.encode(text), dataNonce, msgKey) ?: throw CryptoException("group seal failed")
        val wraps = wrapAll(msgKey, me, members, memberKeys, mySk, wrapNonce)
        val payload = linkedMapOf<String, Any?>("n" to B64.encode(dataNonce), "c" to B64.encode(ct), "w" to wraps)
        return ENCG1 + B64.encode(Utf8.encode(MiniJson.writeObject(payload)))
    }

    private fun wrapAll(
        key: ByteArray, me: String, members: Collection<String>, memberKeys: Map<String, ByteArray>, mySk: ByteArray,
        wrapNonce: (String) -> ByteArray,
    ): Map<String, String> {
        val wraps = LinkedHashMap<String, String>()
        for (uid in LinkedHashSet(members)) {
            val pub = memberKeys[uid] ?: continue            // keyless members are skipped
            wraps[uid] = makeWrap(key, pub, mySk, wrapNonce(uid)) ?: continue
        }
        if (wraps.keys.none { it != me }) throw MissingRecipientKeyException(members.firstOrNull { it != me } ?: "")
        return wraps
    }

    /** Opens a group text with my wrap (falls back to a released key, groups only). */
    fun openGroupText(raw: String, me: String, authorKeys: List<ByteArray>, mySk: ByteArray,
                      releasedKey: ((String) -> ByteArray?)? = null): Pair<String, Int>? {
        val env = parseEncg1(raw) ?: return null
        val ct = env.cipher!!; val dn = env.dataNonce!!
        val viaWrap = env.w[me]?.let { openWrap(it, authorKeys, mySk) }?.let { (k, idx) ->
            nacl.secretBoxOpenEasy(ct, dn, k)?.let { Utf8.decodeStrict(it) }?.let { it to idx }
        }
        if (viaWrap != null) return viaWrap
        val rk = releasedKey?.invoke(env.n) ?: return null
        return nacl.secretBoxOpenEasy(ct, dn, rk)?.let { Utf8.decodeStrict(it) }?.let { it to -1 }
    }

    // ---------------------------------------------------------------- files

    /** 1:1 file (Crypto.swift:807-832): secretbox with a fresh key, key boxed to the recipient. */
    fun sealFile(data: ByteArray, recipientPub: ByteArray, mySk: ByteArray,
                 fileKey: ByteArray = randomKey(), dataNonce: ByteArray = randomNonce(),
                 keyNonce: ByteArray = randomNonce()): Pair<ByteArray, EncMeta> {
        val ct = nacl.secretBoxEasy(data, dataNonce, fileKey) ?: throw CryptoException("secretbox seal failed")
        val wrapped = nacl.boxEasy(fileKey, keyNonce, recipientPub, mySk) ?: throw CryptoException("key wrap failed")
        return ct to EncMeta(v = 1, n = B64.encode(dataNonce), k = B64.encode(wrapped), kn = B64.encode(keyNonce))
    }

    /** Group file (Crypto.swift:837-861): wraps in `w`, author in `a`, `k`/`kn` empty. */
    fun sealGroupFile(data: ByteArray, me: String, members: Collection<String>, memberKeys: Map<String, ByteArray>,
                      mySk: ByteArray, fileKey: ByteArray = randomKey(), dataNonce: ByteArray = randomNonce(),
                      wrapNonce: (String) -> ByteArray = { randomNonce() }): Pair<ByteArray, EncMeta> {
        val ct = nacl.secretBoxEasy(data, dataNonce, fileKey) ?: throw CryptoException("group file seal failed")
        val wraps = wrapAll(fileKey, me, members, memberKeys, mySk, wrapNonce)
        return ct to EncMeta(v = 1, n = B64.encode(dataNonce), k = "", kn = "", w = wraps, a = me)
    }

    /** 1:1 file open (Crypto.swift:894-917). [senderKeys] = the other participant's keys. */
    fun openFile(cipher: ByteArray, meta: EncMeta, senderKeys: List<ByteArray>, mySk: ByteArray): Pair<ByteArray, Int>? {
        if (meta.v != 1) return null
        val wrapped = B64.decode(meta.k) ?: return null
        val kn = B64.decode(meta.kn) ?: return null
        val dn = B64.decode(meta.n) ?: return null
        val (fileKey, idx) = openBoxAny(wrapped, kn, senderKeys, mySk) ?: return null
        return (nacl.secretBoxOpenEasy(cipher, dn, fileKey) ?: return null) to idx
    }

    /** Group file open with my wrap in meta.w, sealed by meta.a (Crypto.swift:870-892). */
    fun openGroupFile(cipher: ByteArray, meta: EncMeta, me: String, authorKeys: List<ByteArray>, mySk: ByteArray,
                      releasedKey: ((String) -> ByteArray?)? = null): ByteArray? {
        val dn = B64.decode(meta.n)
        val viaWrap = meta.w?.get(me)?.let { openWrap(it, authorKeys, mySk) }?.first?.let { k ->
            dn?.let { nacl.secretBoxOpenEasy(cipher, it, k) }
        }
        if (viaWrap != null) return viaWrap
        val rk = releasedKey?.invoke(meta.n) ?: return null
        return dn?.let { nacl.secretBoxOpenEasy(cipher, it, rk) }
    }

    /** Small sealed text fields that are not messages (blurhash, thumb, captions, reply copies)
     *  use the same text seals; nothing else to port. */
}

/** Per-attachment envelope stored with an encrypted file, as `enc` / `imageEnc` / `lastImageEnc`
 *  on the message (Crypto.swift:40-60, ChatService.swift:2010,1236,2033). */
data class EncMeta(
    val v: Int,                  // 1 = sealed; 0 = plain (groups no longer end-to-end only)
    val n: String,               // b64 secretbox nonce of the file data
    val k: String,               // b64 wrapped file key (1:1)
    val kn: String,              // b64 nonce of the wrap (1:1)
    val w: Map<String, String>? = null,   // group: uid -> "wrapB64.nonceB64"
    val a: String? = null,                // group: author uid
) {
    val isPlain: Boolean get() = v == 0

    /** The dictionary written to Firestore (EncMeta.asDict). */
    fun asMap(): Map<String, Any> = buildMap {
        put("v", v); put("n", n); put("k", k); put("kn", kn)
        w?.let { put("w", it) }
        a?.let { put("a", it) }
    }

    companion object {
        val PLAIN = EncMeta(v = 0, n = "", k = "", kn = "")

        /** From a Firestore map. Numbers come back as Long (or Double); Codable on iOS needs all
         *  four of v, n, k, kn present, so a map missing one is not an EncMeta. */
        fun fromMap(m: Map<*, *>?): EncMeta? {
            m ?: return null
            val v = (m["v"] as? Number)?.toInt() ?: return null
            val n = m["n"] as? String ?: return null
            val k = m["k"] as? String ?: return null
            val kn = m["kn"] as? String ?: return null
            val w = (m["w"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                (key as? String)?.let { kk -> (value as? String)?.let { kk to it } }
            }?.toMap()
            return EncMeta(v, n, k, kn, w, m["a"] as? String)
        }
    }
}

open class CryptoException(msg: String) : Exception(msg)

/** The recipient has no published key. Queue the send; never fall back to plaintext. */
class MissingRecipientKeyException(val uid: String) : CryptoException("no public key for $uid")

/** A group member's key could not be read just now (not the same as "has none"). */
class GroupKeyReadException : CryptoException("group member key read failed")
