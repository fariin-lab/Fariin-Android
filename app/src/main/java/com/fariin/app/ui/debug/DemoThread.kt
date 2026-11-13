package com.fariin.app.ui.debug

import com.fariin.app.data.Message
import com.fariin.app.data.Receipts
import com.fariin.app.ui.thread.ThreadUi

/**
 * The store demo's chats (owner 2026-11-13), the same words as the iPhone's App Store pictures
 * (Kulan-iOS DemoMode.messages). Marketing material: read it out loud before changing a word.
 * No Firebase is touched; sending appends to the list in memory.
 */
object DemoThread {
    private const val ME = DemoChats.ME
    private const val S = 1000.0

    fun ui(cid: String, nowMs: Double = DemoChats.shotNow().toDouble()): ThreadUi? {
        val conv = DemoChats.chats(nowMs.toLong()).firstOrNull { it.id == cid } ?: return null
        return ThreadUi(
            title = conv.displayName(ME),
            subtitle = if (conv.isGroup) conv.users.filter { it != ME }.joinToString(", ") { DemoChats.NAMES[it] ?: "" }.ifEmpty { "${conv.users.size} members" }
                else "online",
            isGroup = conv.isGroup,
            me = ME,
            messages = messages(cid, nowMs),
            receipts = Receipts(otherLastReadMillis = nowMs, otherDeliveredMillis = nowMs),
            names = conv.names,
            photos = conv.photos + DemoChats.PHOTOS,
            photoUrl = conv.avatarUrl ?: DemoChats.PHOTOS[conv.otherUid(ME)],
            cid = cid,
        )
    }

    private val WAVE = listOf(8, 14, 30, 52, 61, 44, 25, 12, 30, 58, 72, 66, 40, 18, 9, 22, 47, 63, 55, 31,
        14, 8, 26, 49, 70, 64, 38, 20, 11, 28, 51, 60, 42, 24, 12, 7, 18, 35, 22, 10)

    private fun m(id: String, author: String, text: String, at: Double, reply: Message.ReplyRef? = null,
                  reactions: Map<String, String> = emptyMap()) =
        Message(id = id, authorId = author, text = text, createdAtMillis = at, clientTs = at, replyTo = reply,
            reactions = reactions, hasServerTime = true)

    private fun photo(id: String, author: String, at: Double, name: String, w: Double, h: Double,
                      reactions: Map<String, String> = emptyMap()) =
        Message(id = id, authorId = author, text = "", type = "image", createdAtMillis = at, clientTs = at,
            imageUrl = DemoChats.pic(name), width = w, height = h, reactions = reactions, hasServerTime = true)

    private fun voice(id: String, author: String, at: Double, seconds: Double) =
        Message(id = id, authorId = author, text = "", type = "audio", createdAtMillis = at, clientTs = at,
            audioUrl = DemoChats.DIR + "voice.m4a", duration = seconds, waveform = WAVE, hasServerTime = true)

    private fun messages(cid: String, n: Double): List<Message> {
        fun t(sec: Double) = n + sec * S
        return when (cid) {
            // Store picture 2: a photo with a heart, a voice note, a reply, read ticks and emoji.
            "demo-hooyo" -> {
                val note = voice("h4", ME, t(-1100.0), 14.0)
                listOf(
                    m("h0", "demo-hooyo", "Are you coming this weekend? 🌞", t(-1900.0)),
                    m("h1", ME, "Yes! Booked my flight ✈️", t(-1800.0)),
                    photo("h2", "demo-hooyo", t(-1500.0), "demo-photo-beach", 1900.0, 900.0, mapOf(ME to "❤️")),
                    m("h3", "demo-hooyo", "This is where we're going 😍", t(-1450.0)),
                    note,
                    m("h5", "demo-hooyo", "Haha can't wait to see you 🫶", t(-900.0),
                        reply = Message.ReplyRef(note.id, ME, "Voice message")),
                    m("h6", ME, "Me too. See you Friday! 🎉", t(-600.0)),
                    m("h7", "demo-hooyo", "Did you get home safe?", t(-260.0)),
                )
            }
            // Store picture 3: the Beach Trip group, a car photo with reactions and a celebration.
            "demo-trip" -> listOf(
                m("t0", "demo-linnea", "Who's driving on Saturday? 🚗", t(-2600.0)),
                m("t1", "demo-cabdi", "I can, my car fits five", t(-2400.0)),
                photo("t3", "demo-hooyo", t(-1500.0), "demo-photo-car", 2200.0, 1000.0,
                    mapOf(ME to "❤️", "demo-cabdi" to "❤️", "demo-khadra" to "😍", "demo-linnea" to "❤️")),
                m("t4", "demo-khadra", "Road trip! I'll bring snacks 🍉", t(-1200.0)),
                m("t5", "demo-cabdi", "🎉🎉", t(-900.0)),
                m("t6", ME, "Can't wait!! 🙌", t(-600.0)),
            )
            "demo-family" -> listOf(
                m("f0", "demo-khadra", "Who is home tonight?", t(-3000.0)),
                m("f1", ME, "Me, after work", t(-2800.0)),
                photo("f2", "demo-hooyo", t(-400.0), "demo-photo-dinner", 1200.0, 900.0),
                m("f3", "demo-hooyo", "Dinner is ready, come home 🍲", t(-90.0)),
            )
            "demo-ayaan" -> listOf(
                m("a0", "demo-ayaan", "Subax wanaagsan", t(-33000.0)),
                m("a1", ME, "Subax wanaagsan walaal", t(-32800.0)),
                m("a2", "demo-ayaan", "Sawirkii aroostii ma i soo dirtay?", t(-9000.0)),
                photo("a3", ME, t(-8800.0), "demo-photo-wedding", 1080.0, 1350.0),
                m("a4", "demo-ayaan", "Mashallah, aad ayay u quruxsan tahay", t(-8600.0)),
                m("a5", ME, "Mahadsanid", t(-8400.0)),
                m("a6", "demo-ayaan", "I will call you tonight", t(-2100.0)),
            )
            "demo-cabdi" -> listOf(
                m("c0", ME, "Did you hear the news?", t(-12000.0)),
                m("c1", "demo-cabdi", "Yes! Wait, I'll send you a voice note", t(-8200.0)),
                voice("c3", "demo-cabdi", t(-8000.0), 14.0),
            )
            "demo-khadra" -> listOf(
                m("k0", "demo-khadra", "Dinner tonight was so good", t(-12400.0)),
                photo("k1", "demo-khadra", t(-12000.0), "demo-photo-dinner", 1200.0, 900.0),
            )
            "demo-linnea" -> listOf(
                m("l0", "demo-linnea", "Did you finish the reading?", t(-28000.0)),
                m("l1", ME, "Almost. Two chapters left", t(-27600.0)),
                m("l2", "demo-linnea", "Same. Coffee first though", t(-27200.0)),
                m("l3", ME, "Always", t(-26800.0)),
                m("l4", "demo-linnea", "See you at the library at 4?", t(-26000.0)),
            )
            "demo-ilhan" -> listOf(
                m("i0", ME, "I brought your book back", t(-94000.0)),
                m("i1", "demo-ilhan", "Thank you so much ❤️", t(-93000.0)),
            )
            "demo-faarax" -> listOf(
                m("x0", "demo-faarax", "Football at five?", t(-178000.0)),
                m("x1", ME, "I'm in", t(-177600.0)),
                m("x2", "demo-faarax", "Are we still meeting tomorrow?", t(-176400.0)),
            )
            else -> emptyList()
        }
    }
}
