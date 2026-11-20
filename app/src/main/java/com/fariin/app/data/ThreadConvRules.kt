package com.fariin.app.data

/**
 * Which chat-doc changes the OPEN chat can skip (Pixel trace 2026-11-20: the chat doc changed 23
 * times in three sends, each a rebuild of the whole chat; a late frame each on the Oppo). The chat
 * list's summary (last message, its time and sender, the reaction line, the picture), the OTHER
 * people's unread counts and my own typing are never drawn inside the chat. Everything else, and my
 * own unread count (mark read reads it), still goes through.
 */
object ThreadConvRules {
    private val SUMMARY = setOf(
        "lastMessage", "lastSender", "updatedAt", "lastPlainTag", "lastImageUrl", "lastImageEnc", "lastImages",
        "lastReactionEnc", "lastReactionBy", "lastReactionToAuthor", "lastReactionAt", "lastReactionToEnc", "lastReactionToImages",
        "unreadCount", "typing",
    )

    /** A 1:1's read and delivered marks: the receipts carry them, nothing else in the chat reads them
     *  (a group's info sheet does, so a group keeps them). */
    private val MARKS = setOf("lastRead", "delivered")

    fun onlySummaryChanged(old: Map<String, Any?>, new: Map<String, Any?>, me: String, group: Boolean = false): Boolean {
        if (old.isEmpty()) return false
        for (k in old.keys + new.keys) if (k !in SUMMARY && (group || k !in MARKS) && old[k] != new[k]) return false
        // The request bars ask whether the chat has any message yet.
        if ((old["lastMessage"] as? String).isNullOrEmpty() != (new["lastMessage"] as? String).isNullOrEmpty()) return false
        if ((old["unreadCount"] as? Map<*, *>)?.get(me) != (new["unreadCount"] as? Map<*, *>)?.get(me)) return false
        val ot = (old["typing"] as? Map<*, *>).orEmpty().filterKeys { it != me }
        val nt = (new["typing"] as? Map<*, *>).orEmpty().filterKeys { it != me }
        return ot == nt
    }
}
