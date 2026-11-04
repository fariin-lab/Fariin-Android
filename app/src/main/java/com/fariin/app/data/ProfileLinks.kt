package com.fariin.app.data

import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * A link on a profile (iOS ProfileLink): a title and a web address, up to two per account, shown
 * as small pills under the bio. The pair is the identity. Behind the `profileLinks` switch, as iOS.
 */
data class ProfileLink(val title: String, val url: String) {
    /** The address without its scheme, "www." or a trailing slash. */
    val displayUrl: String get() {
        var s = url
        for (p in listOf("https://", "http://")) if (s.startsWith(p)) s = s.removePrefix(p)
        return s.removePrefix("www.").removeSuffix("/")
    }

    /** Only http(s) with a dotted host; a bare "example.com" gets https. Null opens nothing. */
    val openUrl: Uri? get() {
        val t = url.trim()
        if (t.isEmpty()) return null
        val u = runCatching { Uri.parse(if (t.contains("://")) t else "https://$t") }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        return u.takeIf { it.host?.contains('.') == true }
    }

    companion object {
        const val MAX_PER_USER = 2
        const val MAX_TITLE = 30
        const val MAX_URL = 250

        fun list(raw: Any?): List<ProfileLink> = (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val t = (m["title"] as? String).orEmpty().trim()
            val u = (m["url"] as? String).orEmpty().trim()
            if (t.isEmpty() || u.isEmpty()) null else ProfileLink(t.take(MAX_TITLE), u.take(MAX_URL))
        }.take(MAX_PER_USER)

        fun encode(links: List<ProfileLink>) = links.take(MAX_PER_USER).map { mapOf("title" to it.title, "url" to it.url) }
    }
}

/** The bio's rules and my profile's own small writes (iOS ProfileStore, Limits). */
object ProfileWrites {
    const val BIO_CHARS = 140

    /** iOS Limits.oneParagraph: every whitespace a space, no doubles, then the ceiling. */
    fun oneParagraph(raw: String, max: Int = BIO_CHARS): String {
        var s = raw.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
        while (s.contains("  ")) s = s.replace("  ", " ")
        return s.take(max)
    }

    suspend fun saveAbout(me: String, about: String) {
        FirebaseFirestore.getInstance().collection("users").document(me)
            .set(mapOf("about" to about.trim()), SetOptions.merge()).await()
    }

    suspend fun saveLinks(me: String, links: List<ProfileLink>) {
        FirebaseFirestore.getInstance().collection("users").document(me)
            .set(mapOf("links" to ProfileLink.encode(links)), SetOptions.merge()).await()
    }
}
