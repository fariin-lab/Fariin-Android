package com.fariin.app.ui.debug

import com.fariin.app.data.Message
import com.fariin.app.data.Receipts
import com.fariin.app.ui.thread.ThreadUi

/**
 * Made-up messages for the chat page in debug demo mode (`--ez demo true`), so it can be looked
 * at without an account. No Firebase is touched. Sending appends to the list in memory.
 */
object DemoThread {
    private const val ME = DemoChats.ME
    private const val MIN = 60_000.0
    private const val DAY = 24 * 60 * MIN

    fun ui(cid: String, nowMs: Double = System.currentTimeMillis().toDouble()): ThreadUi? {
        val conv = DemoChats.chats(nowMs.toLong()).firstOrNull { it.id == cid } ?: return null
        val msgs = if (conv.isGroup) group(nowMs) else direct(conv.otherUid(ME), nowMs)
        return ThreadUi(
            title = conv.displayName(ME),
            subtitle = if (conv.isGroup) "${conv.users.size} members" else "",
            isGroup = conv.isGroup,
            me = ME,
            messages = msgs,
            // Read up to 30 minutes ago, delivered up to now: older ticks solid, newer faded.
            receipts = if (conv.isGroup) Receipts(otherLastReadMillis = nowMs - 2 * DAY)
            else Receipts(otherLastReadMillis = nowMs - 30 * MIN, otherDeliveredMillis = nowMs - 10 * MIN),
            names = conv.names,
        )
    }

    private fun m(id: String, author: String, text: String, at: Double, reply: Message.ReplyRef? = null,
                  state: Message.SendState? = null, edited: Boolean = false) =
        Message(id = id, authorId = author, text = text, createdAtMillis = at, clientTs = at,
            replyTo = reply, sendState = state, edited = edited, hasServerTime = state == null)

    private fun direct(other: String, now: Double) = listOf(
        m("a1", other, "Hi! Are you coming to the study group tomorrow?", now - 2 * DAY - 40 * MIN),
        m("a2", ME, "Yes, what time does it start?", now - 2 * DAY - 38 * MIN),
        m("a3", other, "At 6, in the library on the second floor", now - 2 * DAY - 37 * MIN),
        m("a4", ME, "Perfect", now - 2 * DAY - 36 * MIN),
        m("y1", other, "Do you still have my maths notes?", now - DAY - 3 * 60 * MIN),
        m("y2", ME, "I do, I will bring them with me. Sorry I kept them so long, they were really useful for the exam.", now - DAY - 2 * 60 * MIN, edited = true),
        m("t1", other, "No problem at all", now - 90 * MIN),
        m("t2", other, "Can you also bring the history book?", now - 89 * MIN),
        m("t3", ME, "Sure", now - 60 * MIN, reply = Message.ReplyRef("t2", other, "Can you also bring the history book?")),
        m("t4", ME, "Anything else?", now - 59 * MIN),
        m("t5", other, "See you at 6, I will bring the books", now - 20 * MIN),
        m("t6", ME, "Great, see you then", now - 5 * MIN),
        m("t7", ME, "On my way", now - 1 * MIN, state = Message.SendState.SENDING),
    )

    private fun group(now: Double) = listOf(
        Message(id = "s1", authorId = "hodan", text = "Hodan added Ali", type = "system", createdAtMillis = now - DAY - 5 * 60 * MIN),
        m("g1", "hodan", "Did you get the photos from Saturday?", now - DAY - 4 * 60 * MIN),
        m("g2", "hodan", "I sent them last night, there are a lot of them", now - DAY - 4 * 60 * MIN + 30_000),
        m("g3", "ali", "Yes, they came out really well", now - DAY - 3 * 60 * MIN),
        m("g4", ME, "Thank you for sending them!", now - DAY - 2 * 60 * MIN),
        m("g5", "ali", "Dinner at our place on Friday?", now - 3 * 60 * MIN),
        m("g6", ME, "I am in", now - 2 * 60 * MIN, reply = Message.ReplyRef("g5", "ali", "Dinner at our place on Friday?")),
    )
}
