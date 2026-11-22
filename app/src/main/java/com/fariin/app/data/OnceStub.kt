package com.fariin.app.data

/**
 * An opened view-once on this phone (iOS files it with body {}): what the phone keeps of it once
 * it is spent. The row stays, so the chat still draws the "Opened" bubble in its place, but the
 * photo or voice link, the keys, the caption, the thumb and any phone file are gone for good.
 *
 * Kept: id, author, time, type, viewOnce, consumed (= opened), and what places the bubble in the
 * chat (server time, client id, reply, reactions, disappearing timer). Pure, unit tested.
 */
object OnceStub {
    /** Opened: the server burned it ([Message.consumed]) or this phone opened it ([seenHere], receiver only). */
    fun isSpent(m: Message, seenHere: Boolean): Boolean = m.viewOnce && !m.deleted && (m.consumed || seenHere)

    /** The stub of [m]. A row with no type gets the one its link says, so it still draws as view-once. */
    fun stub(m: Message): Message = Message(
        id = m.id, authorId = m.authorId, text = "",
        type = m.type ?: when { m.audioUrl != null -> "audio"; m.videoUrl != null -> "video"; else -> "image" },
        createdAtMillis = m.createdAtMillis, hasServerTime = m.hasServerTime,
        clientTs = m.clientTs, clientId = m.clientId, replyTo = m.replyTo, reactions = m.reactions,
        expiresAtMillis = m.expiresAtMillis, disappearSeconds = m.disappearSeconds,
        viewOnce = true, consumed = true,
    )

    /** Already a stub: nothing left to drop. */
    fun isStub(m: Message): Boolean = m.viewOnce && m.consumed && stub(m) == m

    /** What the phone files for [m]: its stub once spent, else [m] untouched. */
    fun forStore(m: Message, seenHere: Boolean): Message = if (isSpent(m, seenHere)) stub(m) else m

    /** The links whose cached files belong to [m] alone (the chat-media cache keys files by link). */
    fun mediaUrls(m: Message): List<String> =
        listOfNotNull(m.imageUrl, m.videoUrl, m.audioUrl, m.fileUrl, m.thumbUrl).filter { it.isNotEmpty() }

    /**
     * The one-time sweep of rows filed before this rule: the stubs to write, one per spent
     * view-once that still has content. [seenHere] is asked only for rows [me] did not send.
     * Run again on its own output it returns nothing.
     */
    fun migrate(rows: List<Message>, me: String, seenHere: (String) -> Boolean): List<Message> =
        rows.filter { it.viewOnce && !it.deleted && !isStub(it) && isSpent(it, it.authorId != me && seenHere(it.id)) }
            .map(::stub)
}
