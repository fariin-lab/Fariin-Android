package com.fariin.app.data

/**
 * Export Chat (iOS DataExport.chatExport / groupChatExport): one conversation's messages as a plain
 * text file. Only the words: photos, videos, voice notes, files, calls, GIFs and stickers are a tag
 * in brackets, a caption after it. Left out, as iOS: everything before my Clear, messages deleted
 * for everyone, and messages I hid for myself. A disappearing message whose time is up is left out too.
 *
 * A 1:1 export has every message; a group's has the newest [GROUP_CAP], and its header says so when
 * the cut was made. This file only builds the text; ChatExportRun reads and opens the messages.
 */
object ChatExport {
    const val GROUP_CAP = 10_000

    /** "Fariin-Chat-Amina.txt": letters, digits and spaces of the name, "Export" when nothing is left. */
    fun chatFileName(otherName: String) = "Fariin-Chat-${safe(otherName)}.txt"
    fun groupFileName(title: String) = "Fariin-Group-${safe(title)}.txt"

    private fun safe(name: String): String =
        name.filter { it.isLetterOrDigit() || it == ' ' }.ifEmpty { "Export" }

    /** Whether [m] belongs in the file: after my Clear, not deleted, not hidden, not expired. */
    fun keeps(m: Message, clearedMs: Double, hidden: (String) -> Boolean, nowMs: Double): Boolean =
        m.createdAtMillis > clearedMs && !m.deleted && !hidden(m.id) && !Disappearing.isExpired(m, nowMs)

    /** One 1:1 chat, [messages] oldest first and already filtered by [keeps]. */
    fun chatText(otherName: String, me: String, messages: List<Message>, now: String, fmt: (Double) -> String): String {
        val out = StringBuilder("Fariin, chat with $otherName\n")
        out.append("Exported $now\n\n")
        for (m in messages) {
            val who = if (m.authorId == me) "You" else otherName
            out.append("[${fmt(m.createdAtMillis)}] $who: ${describe(m)}\n")
        }
        return out.toString()
    }

    /**
     * One group, [messages] oldest first and filtered. [cut] = the read came back full, so older
     * messages were left behind. A notice line has no author; a member's name comes from [names].
     */
    fun groupText(title: String, me: String, names: Map<String, String>, messages: List<Message>, cut: Boolean,
                  now: String, fmt: (Double) -> String): String {
        val out = StringBuilder("Fariin, group $title\n")
        out.append("Exported $now\n")
        if (cut) out.append("The newest $GROUP_CAP messages\n")
        out.append("\n")
        for (m in messages) {
            if (m.isSystem) { out.append("[${fmt(m.createdAtMillis)}] ${m.text}\n"); continue }
            val who = if (m.authorId == me) "You" else names[m.authorId]?.ifEmpty { null } ?: "Member"
            out.append("[${fmt(m.createdAtMillis)}] $who: ${describe(m)}\n")
        }
        return out.toString()
    }

    /** The words of one line: a media message is its tag, with its caption when it has one. */
    fun describe(m: Message): String = when {
        m.isCall -> if (m.callVideo) "[Video call]" else "[Voice call]"
        m.type == "audio" -> "[Voice message]"
        m.type == "video" -> captioned("[Video]", m.text)
        m.type == "image" || m.album.isNotEmpty() -> captioned("[Photo]", m.text)
        m.type == "file" -> "[File: ${m.fileName?.ifEmpty { null } ?: "document"}]"
        m.type == "gif" -> "[GIF]"
        m.type == "sticker" -> "[${StickerGifRules.stickerLabel(m.stickerEmoji)}]"
        // A poll and a shared card travel as markers in the text: never write the raw marker.
        m.poll != null -> PollRules.safeText(m.poll!!)
        CardRules.previewWord(m.text) != null -> "[${CardRules.previewWord(m.text)}]"
        else -> m.text
    }

    private fun captioned(tag: String, text: String) = if (text.isEmpty()) tag else "$tag $text"
}
