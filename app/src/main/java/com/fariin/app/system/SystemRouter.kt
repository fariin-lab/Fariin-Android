package com.fariin.app.system

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The launcher's long-press shortcuts (res/xml/shortcuts.xml: New Chat, New Story) arrive at
 * MainActivity with [EXTRA_ACTION]; the signed-in shell opens the page they name.
 */
object SystemRouter {
    const val EXTRA_ACTION = "fariin.launcher"
    const val NEW_CHAT = "newChat"
    const val NEW_STORY = "newStory"

    /** Waiting for the signed-in shell: [NEW_CHAT] or [NEW_STORY]. */
    val pending = MutableStateFlow<String?>(null)

    /** Bumped for each New Story request; the shell's Stories tab opens its picker once per bump. */
    val storyRequests = MutableStateFlow(0)

    fun take(intent: Intent?) {
        val a = intent?.getStringExtra(EXTRA_ACTION) ?: return
        intent.removeExtra(EXTRA_ACTION)
        if (a == NEW_CHAT || a == NEW_STORY) pending.value = a
    }
}

/** What arrived through the share sheet, sorted the way the send path takes it. Pure. */
object ShareIntake {
    enum class Kind { MEDIA, FILE }

    /** Photos and videos go as a photo/video message (albums when several); anything else as a file. */
    fun kindOf(mime: String?): Kind {
        val m = mime?.lowercase() ?: return Kind.FILE
        return if ((m.startsWith("image/") && m != "image/svg+xml") || m.startsWith("video/")) Kind.MEDIA else Kind.FILE
    }

    /** The words to send: the shared text, with the subject in front when a page gave one
     *  that the text does not already hold (a shared link often comes as subject + URL). */
    fun textOf(subject: String?, text: String?): String? {
        val t = text?.trim().orEmpty()
        val s = subject?.trim().orEmpty()
        return when {
            t.isEmpty() && s.isEmpty() -> null
            t.isEmpty() -> s
            s.isEmpty() || t.contains(s) -> t
            else -> "$s\n$t"
        }
    }

    /** A file name extension for a copied stream, from its type (the copy keeps its type). */
    fun extensionFor(mime: String?, name: String?): String {
        name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }?.let { return it.lowercase() }
        return when (mime?.lowercase()) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
            "image/heic" -> "heic"; "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "video/webm" -> "webm"
            "video/3gpp" -> "3gp"; "application/pdf" -> "pdf"; "text/plain" -> "txt"
            else -> "bin"
        }
    }

    /** The share sheet's summary line for what is about to be sent. */
    fun summary(media: Int, files: Int, hasText: Boolean): String {
        val parts = mutableListOf<String>()
        if (media == 1) parts += "1 photo or video" else if (media > 1) parts += "$media photos and videos"
        if (files == 1) parts += "1 file" else if (files > 1) parts += "$files files"
        if (parts.isEmpty() && hasText) return "Text"
        return parts.joinToString(", ")
    }
}
