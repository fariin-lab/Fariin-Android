package com.fariin.app.ui.debug

import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.MessageDecryptor

/**
 * The store demo (owner 2026-11-13: the Google Play pictures): the same made-up people and words as
 * the iPhone's App Store pictures (Kulan-iOS DemoMode.swift), so both stores show one Fariin. Opened
 * with `am start -n com.fariin.app/.MainActivity --ez demo true` on debug and test builds only, never
 * a store build. No Firebase is touched; nothing is sent.
 *
 * THIS IS MARKETING MATERIAL: every word ends up in a public picture. Pictures are the owner's own
 * AI-made images, pushed to the phone (adb) under [DIR], so they never ride inside the app.
 */
object DemoChats {
    const val ME = "demo-me"

    /** The store demo is on (set by MainActivity): pages with their own server lists show the demo's. */
    @Volatile var active = false

    /** The demo pictures on the phone: adb push Documents/Fariin-AppStore/demo/<name>.jpg here. */
    const val DIR = "/storage/emulated/0/Android/data/com.fariin.app/files/demo/"
    fun pic(name: String) = "$DIR$name.jpg"

    private val previews = mapOf(
        "demo-family" to "Dinner is ready, come home 🍲",
        "demo-hooyo" to "Did you get home safe?",
        "demo-ayaan" to "I will call you tonight",
        "demo-news" to "Channels are here 📣",
        "demo-cabdi" to "🎤 Voice message · 0:14",
        "demo-khadra" to "📷 Photo",
        "demo-linnea" to "See you at the library at 4?",
        "demo-ilhan" to "Thank you so much ❤️",
        "demo-faarax" to "Are we still meeting tomorrow?",
        "demo-trip" to "Can't wait!! 🙌",
    )

    /** Shows the made-up preview lines; real chats use the crypto port. */
    val decryptor = MessageDecryptor { conv, _ -> previews[conv.id] }

    val NAMES = mapOf(
        "demo-hooyo" to "Maya", "demo-ayaan" to "Ayaan Warsame", "demo-cabdi" to "Daniel", "demo-khadra" to "Sofia",
        "demo-linnea" to "Linnea", "demo-ilhan" to "Leo", "demo-faarax" to "Lucas", ME to "You",
    )
    val PHOTOS = mapOf(
        "demo-hooyo" to pic("demo-face-hooyo"), "demo-ayaan" to pic("demo-face-ayaan"), "demo-cabdi" to pic("demo-face-cabdi"),
        "demo-khadra" to pic("demo-face-khadra"), "demo-linnea" to pic("demo-face-linnea"), "demo-ilhan" to pic("demo-face-ilhan"),
        ME to pic("demo-face-me"),
    )

    /** The status bar's 9:41 on the screenshot day: every time on screen sits before it. */
    fun shotNow(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 41); set(java.util.Calendar.SECOND, 0)
    }.timeInMillis

    private fun direct(id: String, other: String, at: Double, mine: Boolean = false, unread: Int = 0, pinned: Boolean = false) =
        Conversation(id = id, users = listOf(ME, other), names = NAMES.filterKeys { it == other || it == ME },
            photos = PHOTOS.filterKeys { it == other }, lastMessageCipher = "x", lastSender = if (mine) ME else other,
            updatedAtMillis = at, unreadCount = if (unread > 0) mapOf(ME to unread) else emptyMap(),
            pinnedBy = if (pinned) mapOf(ME to true) else emptyMap(),
            delivered = if (mine) mapOf(other to at + 5_000) else emptyMap())

    fun chats(nowMs: Long = shotNow()): List<Conversation> {
        val now = nowMs.toDouble()
        val s = 1000.0
        val all = listOf(
            Conversation(id = "demo-family", convType = "group", title = "Family 🏡", avatarUrl = pic("demo-photo-dinner"),
                users = listOf(ME, "demo-hooyo", "demo-cabdi", "demo-khadra"),
                names = mapOf(ME to "You", "demo-hooyo" to "Mom", "demo-cabdi" to "Daniel", "demo-khadra" to "Sofia"),
                photos = PHOTOS, lastMessageCipher = "x", lastSender = "demo-hooyo", updatedAtMillis = now - 90 * s,
                unreadCount = mapOf(ME to 3), pinnedBy = mapOf(ME to true)),
            direct("demo-hooyo", "demo-hooyo", now - 260 * s, unread = 2, pinned = true),
            direct("demo-ayaan", "demo-ayaan", now - 2100 * s, mine = true),
            Conversation(id = "demo-news", convType = "group", isChannel = true, verified = true, title = "Fariin News", avatarUrl = pic("demo-official"),
                users = listOf(ME, "demo-fariin"), names = mapOf(ME to "You", "demo-fariin" to "Fariin News"),
                lastMessageCipher = "x", lastSender = "demo-fariin", updatedAtMillis = now - 4200 * s),
            direct("demo-cabdi", "demo-cabdi", now - 8000 * s, unread = 1),
            direct("demo-khadra", "demo-khadra", now - 12000 * s),
            direct("demo-linnea", "demo-linnea", now - 26000 * s, mine = true),
            direct("demo-ilhan", "demo-ilhan", now - 93000 * s),
            direct("demo-faarax", "demo-faarax", now - 176400 * s),
            Conversation(id = "demo-trip", convType = "group", title = "Beach Trip 🌴", avatarUrl = pic("demo-photo-trip"),
                users = listOf(ME, "demo-hooyo", "demo-cabdi", "demo-khadra", "demo-linnea"),
                names = NAMES, photos = PHOTOS, lastMessageCipher = "x", lastSender = ME, updatedAtMillis = now - 180000 * s),
        )
        return ChatList.visible(all, ME)
    }
}
