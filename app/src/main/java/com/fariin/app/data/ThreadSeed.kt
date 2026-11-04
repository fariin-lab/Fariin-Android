package com.fariin.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * What a chat already knows before its own listeners answer (owner 2026-11-04: opening a chat, its
 * ticks went from one to two and "online" appeared under the name mid-slide, a visible jolt). The
 * chat list's copy of each conversation document, and the last presence seen per person, so the
 * first frame of a chat is drawn as it will stay.
 */
object ThreadSeed {
    class PresenceSeen(val online: Boolean, val lastActive: Double?, val theirPrivacy: Map<String, String>?, val myPrivacy: Map<String, String>?)

    private val convs = ConcurrentHashMap<String, Map<String, Any?>>()
    private val presence = ConcurrentHashMap<String, PresenceSeen>()

    fun noteConv(cid: String, data: Map<String, Any?>) { convs[cid] = data }
    fun conv(cid: String): Map<String, Any?>? = convs[cid]

    fun notePresence(uid: String, p: PresenceSeen) { presence[uid] = p }
    fun presence(uid: String): PresenceSeen? = presence[uid]

    /** Sign-out: nothing of this account carries into the next. */
    fun clear() { convs.clear(); presence.clear() }
}
