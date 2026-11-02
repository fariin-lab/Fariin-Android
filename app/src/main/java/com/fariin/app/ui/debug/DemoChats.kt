package com.fariin.app.ui.debug

import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.MessageDecryptor

/**
 * Made-up chats for eyeballing the list without signing in. Used only by debug builds, when
 * MainActivity is started with `--ez demo true`. No Firebase is touched in that mode.
 */
object DemoChats {
    const val ME = "demo-me"

    private val previews = mapOf(
        "d1" to "See you at 6, I will bring the books",
        "d2" to "Thanks! That works for me",
        "d3" to "Did you get the photos from Saturday? I sent them last night, there are a lot of them",
        "d4" to "Okay",
        "d6" to "Happy birthday!",
    )

    /** Shows the made-up preview lines; real chats use the crypto port. */
    val decryptor = MessageDecryptor { conv, _ -> previews[conv.id] }

    fun chats(nowMs: Long = System.currentTimeMillis()): List<Conversation> {
        val min = 60_000.0
        val day = 24 * 60 * min
        val now = nowMs.toDouble()
        val all = listOf(
            Conversation(
                id = "d1", users = listOf(ME, "amina"), names = mapOf("amina" to "Amina Yusuf"),
                lastMessageCipher = "x", lastSender = "amina", updatedAtMillis = now - 3 * min,
                unreadCount = mapOf(ME to 2), pinnedBy = mapOf(ME to true),
            ),
            Conversation(
                id = "d2", users = listOf(ME, "omar"), names = mapOf("omar" to "Omar"),
                lastMessageCipher = "x", lastSender = ME, updatedAtMillis = now - 40 * min,
                unreadCount = mapOf("omar" to 0),
            ),
            Conversation(
                id = "d3", convType = "group", title = "Family", users = listOf(ME, "hodan", "ali"),
                names = mapOf("hodan" to "Hodan", "ali" to "Ali"),
                lastMessageCipher = "x", lastSender = "hodan", updatedAtMillis = now - 1 * day - 2 * 60 * min,
                unreadCount = mapOf(ME to 14), replyUnread = mapOf(ME to 1),
            ),
            Conversation(
                id = "d4", users = listOf(ME, "sahra"), names = mapOf("sahra" to "Sahra"),
                lastMessageCipher = "x", lastSender = ME, updatedAtMillis = now - 3 * day,
                unreadCount = mapOf("sahra" to 1), delivered = mapOf("sahra" to now - 3 * day),
                mutedBy = mapOf(ME to now + day),
            ),
            Conversation(
                id = "d5", users = listOf(ME, "farah"), names = mapOf("farah" to "Farah"),
                lastMessageCipher = "", lastImageUrl = "x", lastSender = "farah",
                updatedAtMillis = now - 20 * day, markedUnread = mapOf(ME to true),
            ),
            Conversation(
                id = "d6", users = listOf(ME, "yasmin"), names = mapOf("yasmin" to "Yasmin"),
                lastMessageCipher = "x", lastSender = "yasmin", updatedAtMillis = now - 400 * day,
            ),
            // Cleared by me and nothing newer: must not show.
            Conversation(
                id = "d7", users = listOf(ME, "hidden"), names = mapOf("hidden" to "Hidden"),
                lastMessageCipher = "x", updatedAtMillis = now - 5 * day, clearedAt = mapOf(ME to now - 5 * day),
            ),
            // A group I am in that I cleared earlier: still shows (owner 2026-11-02).
            Conversation(
                id = "d8", convType = "group", title = "Work", users = listOf(ME, "k"),
                lastMessageCipher = "", updatedAtMillis = now - 6 * day, clearedAt = mapOf(ME to now),
            ),
        )
        return ChatList.visible(all, ME)
    }
}
