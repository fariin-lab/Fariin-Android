package com.fariin.app.data

/**
 * Why a group will not take a forward from me (iOS ForwardPicker.refusal + sendAll, parity sweep 16,
 * 2026-11-21). Android forwarded into any group: one I may not write in, one in slow mode, one with
 * that kind turned off. The server then refused or removed the copy after it seemed sent. Now the
 * pick is refused at once, in iOS's words. Pure, so it is tested without a phone.
 */
object ForwardRules {

    /** The group's reason to refuse these messages from me, or null when it takes them. [d] is the
     *  group's own doc as the chat list holds it; null (not known yet) refuses nothing, as before. */
    fun refusal(d: Map<String, Any?>?, conv: Conversation, me: String, messages: List<Message>,
                share: Boolean = false, nowMs: Double = System.currentTimeMillis().toDouble()): String? {
        if (!conv.isGroup || conv.isChannel) return null
        val name = conv.displayName(me)
        // The chat row itself already knows an ended or Fariin-locked group, doc or not.
        if (conv.ended || conv.fariinLocked(nowMs)) return "You can't send messages in $name."
        if (d == null) return null
        if (GroupMod.blocksSending(d, nowMs) || GroupAdmins.announcementBlock(d, me) != null || MemberRestrict.isMuted(d, me, nowMs)) {
            return "You can't send messages in $name."
        }
        // Each forward is its own message and the server removes a second one inside the wait.
        if (GroupAdmins.slows(d, me)) {
            if (messages.size > 1) return "Slow mode is on in $name. Forward one message at a time."
            val wait = if (conv.lastIsMine(me)) GroupAdmins.slowLeftSeconds(d, me, conv.updatedAtMillis, nowMs) else 0
            if (wait > 0) return "Slow mode is on in $name. You can send your next message in $wait seconds."
        }
        if (share) return null
        for (m in messages) {
            val kind = kindOf(m) ?: continue
            if (GroupAdmins.maySend(d, me, kind)) continue
            return "${kind.label} ${if (kind == GroupAdmins.SendKind.LOCATION) "is" else "are"} off in $name."
        }
        return null
    }

    /**
     * Why a group will not take what another app shares into it, or null when it takes it. Not a
     * forward: these are new photos, videos, files and words, so the group's switches for each kind
     * apply, as the chat's own composer applies them (heldBack, slow mode). [messages] is how many
     * messages the share makes in each chat: slow mode lets only one through.
     */
    fun shareRefusal(d: Map<String, Any?>?, conv: Conversation, me: String, kinds: Collection<GroupAdmins.SendKind>,
                     messages: Int, nowMs: Double = System.currentTimeMillis().toDouble()): String? {
        refusal(d, conv, me, emptyList(), share = true, nowMs = nowMs)?.let { return it }
        if (!conv.isGroup || conv.isChannel || d == null) return null
        val name = conv.displayName(me)
        if (messages > 1 && GroupAdmins.slows(d, me)) return "Slow mode is on in $name. Send one message at a time."
        for (k in kinds) {
            if (!GroupAdmins.maySend(d, me, k)) return "${k.label} are off in $name."
            if (GroupAdmins.heldBack(d, me, k, nowMs) != null) return "An admin has turned off ${k.words} for you in $name."
        }
        return null
    }

    /** A note is a second message: a slowed group would remove one of the two (iOS sendAll). */
    fun noteRefusal(docs: List<Pair<Conversation, Map<String, Any?>?>>, me: String, note: String): String? {
        if (note.isBlank()) return null
        val slow = docs.firstOrNull { (c, d) -> d != null && c.isGroup && !c.isChannel && GroupAdmins.slows(d, me) } ?: return null
        return "Slow mode is on in ${slow.first.displayName(me)}. Send without a comment, or send the comment later."
    }

    /** The group's doc as this phone holds it: the open-chat seed, else the saved chat list. */
    fun docOf(cid: String, me: String): Map<String, Any?>? =
        ThreadSeed.conv(cid) ?: runCatching { com.fariin.app.local.LocalCache.peekChats(me)?.firstOrNull { it.first == cid }?.second }.getOrNull()

    /** iOS ForwardPicker.sendKind. A poll, a shared place and a link are all text messages,
     *  so they are told by the words: the poll marker, the location marker, then any link. */
    fun kindOf(m: Message): GroupAdmins.SendKind? = when {
        m.poll != null -> GroupAdmins.SendKind.POLLS
        isText(m) && CardRules.location(m.text) != null -> GroupAdmins.SendKind.LOCATION
        isText(m) && GroupAdmins.hasLink(m.text) -> GroupAdmins.SendKind.LINKS
        else -> kindOfType(m)
    }

    /** A plain text row: an older one may have no type at all. A photo's caption link stays a photo. */
    private fun isText(m: Message) = m.type == null || m.type == "text"

    private fun kindOfType(m: Message): GroupAdmins.SendKind? = when (m.type) {
        "sticker" -> GroupAdmins.SendKind.STICKERS
        "gif" -> GroupAdmins.SendKind.GIFS
        "video" -> GroupAdmins.SendKind.VIDEOS
        "image", "album" -> GroupAdmins.SendKind.PHOTOS
        "audio" -> GroupAdmins.SendKind.VOICE
        "file" -> GroupAdmins.SendKind.FILES
        "location" -> GroupAdmins.SendKind.LOCATION
        else -> null
    }
}
