package com.fariin.app.data

import android.content.Context
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.Wire
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FARIIN PLUS `sharedWallpaper` (1:1 only): `conversations/{cid}.sharedWallpaper = {w, by, at, n?, k?, kn?}`,
 * written only by the server (`plusSharedWallpaper`). w = "g:<id>", "c:RRGGBB", "b:<id>", "a:<id>" or
 * "e:wp<ms>", a photo sealed with the chat's own file encryption at chat/<cid>/wp<ms>.enc.
 *
 * While present it beats this phone's own pick, unless this phone opted out of that very `at` (Use My
 * Own Wallpaper, applying an own wallpaper, Reset); a newer `at` applies again. The other person sees
 * once per new `at` a note naming who set it. Nothing of Plus is said to anyone who did not set it.
 */
object SharedWallpapers {
    data class Value(val w: String, val by: String, val at: Long, val n: String?, val k: String?, val kn: String?) {
        val isPhoto: Boolean get() = w.startsWith("e:")
        fun encode(): String = org.json.JSONObject().apply {
            put("w", w); put("by", by); put("at", at); n?.let { put("n", it) }; k?.let { put("k", it) }; kn?.let { put("kn", it) }
        }.toString()

        companion object {
            fun parse(raw: Any?): Value? {
                val d = raw as? Map<*, *> ?: return null
                // As iOS PairWallpaper: w and by are needed, at reads 0 when missing.
                val w = (d["w"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
                val by = (d["by"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
                val at = (d["at"] as? Number)?.toLong() ?: 0L
                val n = d["n"] as? String; val k = d["k"] as? String; val kn = d["kn"] as? String
                // A photo only in the shape the server writes: "e:wp" + 13 digits, with the three seal fields.
                if (w.startsWith("e:")) {
                    val f = w.removePrefix("e:")
                    if (f.length != 15 || !f.startsWith("wp") || !f.drop(2).all { it in '0'..'9' }) return null
                    if (n.isNullOrEmpty() || k.isNullOrEmpty() || kn.isNullOrEmpty()) return null
                }
                return Value(w, by, at, n, k, kn)
            }
            fun decode(s: String?): Value? = s?.let { runCatching {
                val o = org.json.JSONObject(it)
                Value(o.getString("w"), o.optString("by"), o.getLong("at"),
                    o.optString("n").ifEmpty { null }, o.optString("k").ifEmpty { null }, o.optString("kn").ifEmpty { null })
            }.getOrNull() }
        }
    }

    private const val PREFS = "fariin_shared_wallpaper"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(cid: String) = "v.$cid"
    private fun outKey(cid: String) = "out.$cid"
    private fun noteKey(cid: String) = "note.$cid"
    private val synced = HashMap<String, String>()
    private val fetching = HashSet<String>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** The chat's value as last read, or null. */
    fun value(c: Context, cid: String): Value? = Value.decode(prefs(c).getString(key(cid), null))

    /** Present and not opted out on this phone: it is what the chat shows. */
    fun active(c: Context, cid: String): Value? =
        value(c, cid)?.takeIf { prefs(c).getLong(outKey(cid), 0L) != it.at }

    /** From every snapshot of the conversation (the chat list's and the open chat's). Unchanged costs nothing. */
    fun sync(c: Context, cid: String, raw: Any?) {
        if (Wire.isGroupCid(cid)) return
        val v = Value.parse(raw)
        val enc = v?.encode() ?: ""
        if (synchronized(synced) { synced.put(cid, enc) } == enc) return
        val p = prefs(c)
        if (p.getString(key(cid), null).orEmpty() != enc) {
            if (v == null) p.edit().remove(key(cid)).apply() else p.edit().putString(key(cid), enc).apply()
            ChatLookStore.changed()
        }
        if (v != null && v.isPhoto) fetchPhoto(c.applicationContext, cid, v)
    }

    /** What the chat should draw from it, or null for this phone's own pick. */
    fun resolve(c: Context, cid: String): ChatWallpaper? {
        val v = active(c, cid) ?: return null
        if (!v.isPhoto) return ChatWallpaper.parse(v.w).takeIf { it != ChatWallpaper.None }
        val id = photoId(cid, v.w)
        if (ChatLookStore.libraryFile(c, id).exists()) return ChatWallpaper.Photo(id)
        fetchPhoto(c.applicationContext, cid, v)
        return null
    }

    /** This phone goes back to its own wallpaper for this `at` (a newer one applies again). */
    fun optOut(c: Context, cid: String) {
        val v = value(c, cid) ?: return
        if (prefs(c).getLong(outKey(cid), 0L) == v.at) return
        prefs(c).edit().putLong(outKey(cid), v.at).putLong(noteKey(cid), v.at).apply()
        ChatLookStore.changed()
    }

    /** The receiver's note: shown once per new `at`, for a value someone else set. */
    fun noteDue(c: Context, cid: String, me: String): Value? =
        active(c, cid)?.takeIf { it.by.isNotEmpty() && it.by != me && prefs(c).getLong(noteKey(cid), 0L) != it.at }

    fun noteSeen(c: Context, cid: String, at: Long) { prefs(c).edit().putLong(noteKey(cid), at).apply() }

    /** Set by me and showing here: Reset offers "Reset for Me" / "Remove for Both". */
    fun mineActive(c: Context, cid: String, me: String): Boolean = active(c, cid)?.by == me

    fun photoId(cid: String, w: String): String = "shw-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest("$cid|$w".toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun storagePath(cid: String, w: String) = "chat/$cid/${w.removePrefix("e:")}.enc"

    private fun fetchPhoto(app: Context, cid: String, v: Value) {
        val id = photoId(cid, v.w)
        if (ChatLookStore.libraryFile(app, id).exists()) return
        val n = v.n ?: return; val k = v.k ?: return; val kn = v.kn ?: return
        if (!synchronized(fetching) { fetching.add(id) }) return
        io.launch {
            try {
                val cipher = FirebaseStorage.getInstance().reference.child(storagePath(cid, v.w)).getBytes(16L * 1024 * 1024).await()
                val plain = CryptoHub.get(app).decryptBytes(cid, cipher, EncMeta(1, n, k, kn)) ?: return@launch
                ChatLookStore.libraryFile(app, id).writeBytes(plain)
                ChatLookStore.changed()
            } catch (_: Exception) {
            } finally { synchronized(fetching) { fetching.remove(id) } }
        }
    }

    // ---- Setting (callable plusSharedWallpaper, me-central1) ----

    /** Why it was refused, said plainly; PLUS_REQUIRED opens the Plus page instead (the caller checks [plusRequired]). */
    class Refused(message: String, val plusRequired: Boolean) : Exception(message)

    private suspend fun call(data: Map<String, Any>): Map<*, *>? {
        try {
            return FirebaseFunctions.getInstance(PlusStore.REGION).getHttpsCallable("plusSharedWallpaper").call(data).await().getData() as? Map<*, *>
        } catch (e: FirebaseFunctionsException) {
            val code = PlusStore.detailsCode(e)
            throw when {
                code == "PLUS_REQUIRED" -> Refused("", plusRequired = true)
                e.code == FirebaseFunctionsException.Code.NOT_FOUND -> Refused("This isn't available right now.", false)
                e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> Refused("Wait a few seconds and try again.", false)
                else -> Refused(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't set the wallpaper. Try again.", false)
            }
        }
    }

    /** A theme, colour or moving wallpaper for both of us. */
    suspend fun setForBoth(c: Context, cid: String, w: ChatWallpaper) {
        val before = value(c, cid)?.w
        if (w is ChatWallpaper.Photo) { setPhotoForBoth(c, cid, ChatLookStore.libraryFile(c, w.id)); notice(cid, SET_SUFFIX, "Wallpaper changed"); return }
        call(mapOf("cid" to cid, "wallpaper" to w.stored))
        if (before != w.stored) notice(cid, SET_SUFFIX, "Wallpaper changed")
    }

    // ---- The notice in the chat (iOS wallpaper-note-1120 4df24f5a5) ----

    const val SET_SUFFIX = " set a new wallpaper for this chat"
    const val REMOVED_SUFFIX = " removed the chat wallpaper"

    /** "<my name> set a new wallpaper for this chat" as a system line, and the chat list's own words. */
    private suspend fun notice(cid: String, suffix: String, line: String) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        val db = com.fariin.app.Fb.db
        runCatching {
            val u = db.collection("users").document(me)
            val name = (runCatching { u.get(com.google.firebase.firestore.Source.CACHE).await().getString("name") }.getOrNull()
                ?: runCatching { u.get().await().getString("name") }.getOrNull()).orEmpty().ifEmpty { "Someone" }
            val ref = db.collection("conversations").document(cid)
            val b = db.batch()
            b.set(ref.collection("messages").document(), mapOf("text" to name + suffix, "authorId" to me, "type" to "system",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            b.update(ref, mapOf("lastMessage" to line, "lastSender" to me, "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            b.commit().await()
        }
    }

    /** A photo: about 2000 px JPEG, sealed like a chat photo, then named to the server. */
    private suspend fun setPhotoForBoth(c: Context, cid: String, file: File) {
        val app = c.applicationContext
        val jpeg = withContext(Dispatchers.IO) {
            val src = android.graphics.BitmapFactory.decodeFile(file.path) ?: throw Refused("Couldn't read the photo.", false)
            val scale = minOf(1f, 2000f / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
        val (cipher, meta) = CryptoHub.get(app).encryptBytes(cid, jpeg)
        val w = "e:wp" + System.currentTimeMillis()
        FirebaseStorage.getInstance().reference.child(storagePath(cid, w))
            .putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build()).await()
        // My own copy, so this phone never downloads what it just sent.
        withContext(Dispatchers.IO) { ChatLookStore.libraryFile(app, photoId(cid, w)).writeBytes(jpeg) }
        call(mapOf("cid" to cid, "wallpaper" to w, "enc" to mapOf("n" to meta.n, "k" to meta.k, "kn" to meta.kn)))
    }

    /** Remove for Both (the setter only; the server checks). */
    suspend fun removeForBoth(cid: String) {
        val d = call(mapOf("cid" to cid, "remove" to true))
        if (d?.get("removed") == true) notice(cid, REMOVED_SUFFIX, "Wallpaper removed")
    }

    fun wipe(c: Context) { prefs(c).edit().clear().apply(); synchronized(synced) { synced.clear() }; ChatLookStore.changed() }
}
