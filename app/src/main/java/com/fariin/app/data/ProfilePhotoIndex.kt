package com.fariin.app.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf

/**
 * Each person's OWN photo, as their users document says the last time this phone read it (iOS
 * ProfilePhotoIndex, the part a chat row needs). A chat keeps a MIRROR of its members' photos
 * (`photos` on the conversation) that only the photo's owner keeps right; someone on an old build,
 * or who rarely opens the app, stayed stale or blank there. [Conversation.photoUrl] takes whichever
 * of the two carries the newer `?v=` stamp.
 *
 * The photo's audience (privacy "photo") is kept with it: a photo I may not see is never handed out
 * here, so the mirror (which the owner republishes per audience) stays the answer then.
 */
object ProfilePhotoIndex {
    data class Facts(val photo: String, val audience: String)

    /** Read in composition: a row redraws when a fresher record lands. */
    private val facts = mutableStateMapOf<String, Facts>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Main thread here (or no Android looper at all, as in JVM tests). */
    private fun onMain(): Boolean = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(true)

    /** From any thread: a whole users document just read (it carries `name`; a partial read is skipped). */
    fun record(uid: String, d: Map<String, Any?>) {
        if (uid.isEmpty() || !d.containsKey("name")) return
        val f = Facts(d["photoUrl"] as? String ?: "",
            ((d["privacy"] as? Map<*, *>)?.get("photo") as? String).orEmpty())
        val apply = { if (facts[uid] != f) facts[uid] = f }
        if (onMain()) apply() else main.post(apply)
    }

    /** Their own photo when known, non-empty and visible to me ([contact]: I am one of their contacts). */
    fun photo(uid: String, contact: Boolean): String? {
        val f = facts[uid] ?: return null
        if (f.photo.isEmpty() || !Person.allows(mapOf("photo" to f.audience), "photo", contact)) return null
        return f.photo
    }

    /** The `v=` stamp a published photo url carries (ms of the upload); 0 when absent. */
    fun stamp(url: String): Double {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return 0.0
        for (part in q.split('&')) {
            val k = part.substringBefore('=')
            if (k == "v") return part.substringAfter('=', "").toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    /** Whichever of the chat's [mirror] and their own [known] photo is newer (iOS Conversation.photoUrl). */
    fun newer(mirror: String?, known: String?): String? = when {
        known.isNullOrEmpty() -> mirror
        mirror.isNullOrEmpty() -> known
        stamp(known) > stamp(mirror) -> known
        else -> mirror
    }

    /** Sign-out: the next account starts empty. */
    fun clear() { if (onMain()) facts.clear() else main.post { facts.clear() } }
}
