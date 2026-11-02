package com.fariin.app.call

import com.fariin.app.data.AuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * One call link's root key, as iOS CallLinkKey (Services/CallLinkService.swift), byte for byte:
 * 16 random bytes that live only in the link and in the creator's own list. The server only ever
 * sees what is derived from them one way:
 *  - roomId   = lowercase hex of HKDF-SHA256(key, salt empty, info "kulan-calllink-roomid-v1", 32)
 *  - name key = HKDF-SHA256(key, salt empty, info "kulan-calllink-name-v1", 32), AES-GCM, the
 *    stored encName is base64(nonce 12 + ciphertext + tag 16) (CryptoKit's `combined`).
 * Two spellings: [text] "bcdf-ghkm-..." (32 letters, the old link and the saved list's `key`) and
 * [compact] (22 base62 digits, the path of https://call.fariin.com/video/<id>).
 */
class CallLinkKey private constructor(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is CallLinkKey && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()

    val text: String
        get() {
            val letters = StringBuilder()
            for (b in bytes) {
                val v = b.toInt() and 0xff
                letters.append(ALPHABET[v shr 4]).append(ALPHABET[v and 0x0f])
            }
            return letters.chunked(4).joinToString("-")
        }

    val compact: String
        get() {
            val num = bytes.map { it.toInt() and 0xff }.toIntArray()
            val digits = CharArray(COMPACT_LENGTH) { '0' }
            for (pos in COMPACT_LENGTH - 1 downTo 0) {
                var rem = 0
                for (i in num.indices) {
                    val cur = (rem shl 8) or num[i]
                    num[i] = cur / 62
                    rem = cur % 62
                }
                digits[pos] = BASE62[rem]
            }
            return String(digits)
        }

    val roomId: String get() = derive("kulan-calllink-roomid-v1").joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun url(video: Boolean): String = "https://$LINK_HOST/${if (video) "video" else "voice"}/$compact"

    /** "" for an empty name, as the server stores an unnamed link. */
    fun encryptName(name: String, nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }): String {
        if (name.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("kulan-calllink-name-v1"), "AES"), GCMParameterSpec(128, nonce))
        val sealed = c.doFinal(name.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(nonce + sealed)
    }

    /** null when it does not open (a wrong key or a damaged name). */
    fun decryptName(enc: String): String? {
        if (enc.isEmpty()) return ""
        return try {
            val raw = java.util.Base64.getDecoder().decode(enc.trim())
            if (raw.size < 12 + 16) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("kulan-calllink-name-v1"), "AES"), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun derive(info: String): ByteArray = hkdf32(bytes, info.toByteArray(Charsets.UTF_8))

    companion object {
        private const val ALPHABET = "bcdfghkmnpqrstxz"
        private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val COMPACT_LENGTH = 22
        const val LINK_HOST = "call.fariin.com"

        /** HKDF-SHA256 (RFC 5869) with an empty salt, one 32-byte block: what CryptoKit's HKDF gives. */
        internal fun hkdf32(ikm: ByteArray, info: ByteArray): ByteArray {
            val ext = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
            val prk = ext.doFinal(ikm)
            val exp = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            return exp.doFinal(info + byteArrayOf(1))
        }

        /** iOS init?(raw:): refused when any 2-byte group is one nibble four times ("bbbb"). */
        fun ofBytes(raw: ByteArray): CallLinkKey? {
            if (raw.size != 16) return null
            for (i in 0 until 16 step 2) {
                val a = raw[i].toInt() and 0xff
                val b = raw[i + 1].toInt() and 0xff
                val n = intArrayOf(a shr 4, a and 0x0f, b shr 4, b and 0x0f)
                if (n.all { it == n[0] }) return null
            }
            return CallLinkKey(raw.copyOf())
        }

        fun generate(rng: SecureRandom = SecureRandom()): CallLinkKey {
            while (true) { ofBytes(ByteArray(16).also { rng.nextBytes(it) })?.let { return it } }
        }

        /** The text form; dashes, spaces and case forgiven, anything else refused. */
        fun ofText(text: String): CallLinkKey? {
            val clean = text.lowercase().filter { it != '-' && !it.isWhitespace() }
            if (clean.length != 32) return null
            val out = ByteArray(16)
            for (i in 0 until 16) {
                val hi = ALPHABET.indexOf(clean[2 * i]); val lo = ALPHABET.indexOf(clean[2 * i + 1])
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return ofBytes(out)
        }

        /** The path form: exactly 22 base62 digits, nothing forgiven. */
        fun ofCompact(s: String): CallLinkKey? {
            if (s.length != COMPACT_LENGTH) return null
            val num = IntArray(16)
            for (ch in s) {
                var carry = BASE62.indexOf(ch)
                if (carry < 0) return null
                for (i in 15 downTo 0) {
                    val cur = num[i] * 62 + carry
                    num[i] = cur and 0xff
                    carry = cur shr 8
                }
                if (carry != 0) return null
            }
            return ofBytes(ByteArray(16) { num[it].toByte() })
        }

        /**
         * A tapped link, as iOS KulanApp's URL reader: https://call.fariin.com/video/<id> or /voice/<id>;
         * the same two paths on fariin.com (one site file serves every host); the old
         * https://fariin.com/call/#key=bcdf-... with the key in the fragment; and the web page's
         * kulan://video/<id>, kulan://voice/<id>, kulan://call/<text>. Anything else is not a link.
         */
        fun fromLink(url: String): CallLinkKey? {
            val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase() ?: ""
            val parts = (u.rawPath ?: "").split("/").filter { it.isNotEmpty() }
            if (scheme == "kulan" || scheme == "fariin") {
                val v = parts.firstOrNull() ?: return null
                return when (host) {
                    "video", "voice" -> if (parts.size == 1) ofCompact(v) else null
                    "call" -> if (parts.size == 1) ofText(v) else null
                    else -> null
                }
            }
            if (scheme != "https") return null
            if (host == LINK_HOST || host == "fariin.com" || host == "www.fariin.com") {
                if (parts.size == 2 && (parts[0] == "video" || parts[0] == "voice")) return ofCompact(parts[1])
            }
            if (host == "fariin.com" || host == "www.fariin.com") {
                if (parts == listOf("call")) {
                    val raw = (u.rawFragment ?: "").split("&").firstOrNull { it.startsWith("key=") }?.removePrefix("key=") ?: return null
                    return ofText(raw)
                }
            }
            return null
        }

        /** The type a link says in its path (true = video); the old form says nothing, so video. */
        fun linkSaysVideo(url: String): Boolean = !url.contains("/voice/") && !url.startsWith("kulan://voice") && !url.startsWith("fariin://voice")
    }
}

/** A tapped call link waiting for the signed-in app to open its join screen: key text + type. */
object CallLinkRouter {
    val pending = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** Reads an opened link (App Links or the web page's button); true when it was one. */
    fun take(data: String?): Boolean {
        val url = data ?: return false
        val key = CallLinkKey.fromLink(url) ?: return false
        pending.value = key.text to CallLinkKey.linkSaysVideo(url)
        return true
    }
}

/** iOS CallLinkDefaults. The shown name of an unnamed link. */
object CallLinkDefaults {
    const val NAME = "Fariin Call"
    const val MAX_NAME_LENGTH = 32
    /** The rules cap the stored name at 64 code points. */
    const val MAX_NAME_SCALARS = 64

    fun clamp(name: String): String {
        val chars = name.codePoints().toArray()
        var out = String(chars, 0, minOf(chars.size, MAX_NAME_LENGTH))
        while (out.codePointCount(0, out.length) > MAX_NAME_SCALARS) out = out.dropLast(1)
        return out
    }
}

/** One row of my list, users/{me}/callLinks/{roomId} {key, name, createdAt, admin}. */
data class SavedCallLink(val roomId: String, val key: String, val name: String, val createdAtMs: Long, val admin: Boolean) {
    val title: String get() = name.ifEmpty { CallLinkDefaults.NAME }
    val linkKey: CallLinkKey? get() = CallLinkKey.ofText(key)
}

/** A link made on the server but not yet in my list (it goes in on Done, Join, Copy or Share). */
data class CallLinkDraft(val roomId: String, val key: String, val name: String = "", val approval: Boolean = true, val video: Boolean = true) {
    val title: String get() = name.ifEmpty { CallLinkDefaults.NAME }
    val linkKey: CallLinkKey? get() = CallLinkKey.ofText(key)
}

/** peekCallLink's answer, for the join screen. */
data class LinkPeek(val title: String, val count: Int, val names: List<String>, val approval: Boolean, val video: Boolean,
                    val gone: Boolean, val full: Boolean, val iAmCreator: Boolean)

/** The wire shapes, kept pure for the tests. */
object CallLinkWire {
    fun createBody(roomId: String): Map<String, Any> =
        mapOf("roomId" to roomId, "encName" to "", "restrictions" to "adminApproval", "video" to true)

    fun listFields(draft: CallLinkDraft, first: Boolean, stamp: Any): Map<String, Any> = buildMap {
        put("key", draft.key); put("name", draft.name); put("admin", true)
        if (first) put("createdAt", stamp)
    }

    fun tokenRequest(roomId: String): Map<String, Any> = mapOf("roomId" to roomId, "link" to true, "servers" to listOf("own"))

    fun parseSaved(id: String, d: Map<String, Any?>): SavedCallLink? {
        val key = d["key"] as? String ?: return null
        if (CallLinkKey.ofText(key) == null) return null
        return SavedCallLink(id, key, d["name"] as? String ?: "",
            (d["createdAt"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis(), d["admin"] as? Boolean ?: false)
    }

    fun parsePeek(key: CallLinkKey, d: Map<*, *>): LinkPeek {
        val enc = d["encName"] as? String ?: ""
        val name = if (enc.isNotEmpty()) key.decryptName(enc) else null
        return LinkPeek(
            title = name?.takeIf { it.isNotEmpty() } ?: CallLinkDefaults.NAME,
            count = (d["count"] as? Number)?.toInt() ?: 0,
            names = (d["names"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            approval = d["approval"] == true, video = d["video"] != false, gone = d["gone"] == true,
            full = d["full"] == true, iAmCreator = d["creator"] == true,
        )
    }

    /** The join screen's line under the title. */
    fun peopleLine(p: LinkPeek): String = when {
        p.gone -> "This call link is no longer valid"
        p.full -> "This call is full."
        p.count == 0 -> "No one is in the call yet"
        p.names.isEmpty() -> if (p.count == 1) "1 person is in the call" else "${p.count} people are in the call"
        p.count <= p.names.size -> p.names.joinToString(", ") + if (p.count == 1) " is in the call" else " are in the call"
        else -> p.names.joinToString(", ") + " and ${p.count - p.names.size} more are in the call"
    }
}

/**
 * My call links (iOS CallLinkService): the saved list, making a link, naming, approval, call
 * type, delete. Every server step is a callable in me-central1 (createCallLink, updateCallLink,
 * deleteCallLink, peekCallLink); they cannot run on the local backend, so this is built to the
 * iOS contract and marked untested in PROGRESS.md.
 */
object CallLinks {
    private val _links = MutableStateFlow<List<SavedCallLink>>(emptyList())
    val links: StateFlow<List<SavedCallLink>> = _links
    private var loadedFor: String? = null
    private var reg: com.google.firebase.firestore.ListenerRegistration? = null

    private val db get() = FirebaseFirestore.getInstance()
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
    private val me get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun list(uid: String) = db.collection("users").document(uid).collection("callLinks")

    /** Live list, newest first (sorted here, as iOS: no index for a tiny subcollection). */
    fun watch(uid: String) {
        if (loadedFor == uid && reg != null) return
        reg?.remove()
        loadedFor = uid
        _links.value = emptyList()
        reg = list(uid).addSnapshotListener { s, _ ->
            if (s == null || loadedFor != uid) return@addSnapshotListener
            _links.value = s.documents.mapNotNull {
                CallLinkWire.parseSaved(it.id, it.getData(com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
            }.sortedByDescending { it.createdAtMs }
        }
    }

    fun reset() { reg?.remove(); reg = null; loadedFor = null; _links.value = emptyList() }

    /** Makes a link on the server (me as admin, approval on, video). Not in my list yet. */
    suspend fun create(): CallLinkDraft {
        var last: Exception? = null
        repeat(3) {
            val key = CallLinkKey.generate()
            try {
                fn.getHttpsCallable("createCallLink").call(CallLinkWire.createBody(key.roomId)).await()
                return CallLinkDraft(key.roomId, key.text)
            } catch (e: Exception) {
                last = e
                if ((e as? FirebaseFunctionsException)?.code != FirebaseFunctionsException.Code.ALREADY_EXISTS) throw e
            }
        }
        throw last ?: IllegalStateException("createCallLink")
    }

    /** Into my list; safe more than once. createdAt only on the first save. */
    fun persist(d: CallLinkDraft) {
        val uid = me ?: return
        val first = _links.value.none { it.roomId == d.roomId }
        if (first) _links.value = listOf(SavedCallLink(d.roomId, d.key, d.name, System.currentTimeMillis(), true)) + _links.value
        list(uid).document(d.roomId).set(CallLinkWire.listFields(d, first, FieldValue.serverTimestamp()), SetOptions.merge())
    }

    suspend fun rename(roomId: String, key: CallLinkKey, name: String): String {
        val clean = CallLinkDefaults.clamp(name.trim())
        fn.getHttpsCallable("updateCallLink").call(mapOf("roomId" to roomId, "encName" to key.encryptName(clean))).await()
        val uid = me
        if (uid != null && _links.value.any { it.roomId == roomId }) {
            list(uid).document(roomId).set(mapOf("name" to clean), SetOptions.merge())
        }
        return clean
    }

    suspend fun setApproval(roomId: String, on: Boolean) {
        fn.getHttpsCallable("updateCallLink").call(mapOf("roomId" to roomId, "restrictions" to if (on) "adminApproval" else "none")).await()
    }

    suspend fun setVideo(roomId: String, on: Boolean) {
        fn.getHttpsCallable("updateCallLink").call(mapOf("roomId" to roomId, "video" to on)).await()
    }

    /** The link's own doc (anyone signed in may get it): approval and type for the details page. */
    suspend fun readDoc(roomId: String): Map<String, Any?>? =
        try { db.collection("callLinks").document(roomId).get().await().data } catch (_: Exception) { null }

    /** The creator deletes it for everyone; anyone else only drops it from their list. */
    suspend fun delete(link: SavedCallLink) {
        if (link.admin) {
            try {
                fn.getHttpsCallable("deleteCallLink").call(mapOf("roomId" to link.roomId)).await()
            } catch (e: Exception) {
                if ((e as? FirebaseFunctionsException)?.code != FirebaseFunctionsException.Code.NOT_FOUND) throw e
            }
        }
        _links.value = _links.value.filter { it.roomId != link.roomId }
        me?.let { list(it).document(link.roomId).delete() }
    }

    /** null = the server could not be asked; the join screen then shows the link plainly. */
    suspend fun peek(key: CallLinkKey): LinkPeek? = try {
        val r = fn.getHttpsCallable("peekCallLink").call(mapOf("roomId" to key.roomId)).await()
        (r.data as? Map<*, *>)?.let { CallLinkWire.parsePeek(key, it) }
    } catch (_: Exception) { null }
}
