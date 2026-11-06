package com.fariin.app.data

/**
 * A group's per-member restrictions, to the iOS model (Models.swift Conversation.Restrict,
 * activeRestrictions / isRestricted / isMutedMember / muteAllFlags) and the admin delete sheet's
 * "What can this member do?" (AdminDeleteSheet groupLets / seed / setAllowed / flags(for:)).
 * The doc holds `restrictedFlags.<uid>` (a list of slugs) and `restrictedUntil.<uid>` (epoch ms);
 * firestore.rules judges a restriction live while `restrictedUntil` is in the future, never for the
 * owner or an admin. Pure: unit-tested in MemberRestrictTest.
 */
object MemberRestrict {
    /** Conversation.Restrict, in the iOS order (the order flags are written in). */
    enum class R(val slug: String) {
        SEND_TEXT("sendText"), SEND_MEDIA("sendMedia"), SEND_VOICE("sendVoice"), SEND_STICKERS("sendStickers"),
        SEND_POLLS("sendPolls"), SEND_REACTIONS("sendReactions"), PIN_MESSAGES("pinMessages"),
        ADD_MEMBERS("addMembers"), CHANGE_INFO("changeInfo"),
        SEND_PHOTOS("sendPhotos"), SEND_FILES("sendFiles"), SEND_LINKS("sendLinks");
    }

    /** The "mute everything" set the simple Mute writes. */
    val MUTE_ALL: List<String> = listOf(R.SEND_TEXT, R.SEND_MEDIA, R.SEND_VOICE, R.SEND_STICKERS, R.SEND_POLLS, R.SEND_REACTIONS).map { it.slug }

    /** The sheet's five kinds of media, under one Send Media row. */
    val MEDIA: List<R> = listOf(R.SEND_PHOTOS, R.SEND_FILES, R.SEND_VOICE, R.SEND_STICKERS, R.SEND_LINKS)

    /** The sheet's switches: Send Messages, the media, Add Members, Pin Messages, Change Group Info. */
    val SWITCHES: List<R> = listOf(R.SEND_TEXT) + MEDIA + listOf(R.ADD_MEMBERS, R.PIN_MESSAGES, R.CHANGE_INFO)

    /** ChatService.muteMember's "forever": about 50 years on. */
    const val FOREVER_SECONDS = 60.0 * 60 * 24 * 365 * 50

    /** The member mute durations (iOS muteRow); 0 = "Until I lift it". */
    val MUTE_CHOICES: List<Pair<String, Double>> =
        listOf("1 hour" to 3600.0, "1 day" to 86_400.0, "1 week" to 604_800.0, "Until I lift it" to 0.0)

    const val MUTE_ROW = "Mute in This Group"
    const val LIFT_ROW = "Lift Restrictions"
    const val MUTED_BAR = "You're muted in this group"

    private fun list(d: Map<String, Any?>, k: String) = (d[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    fun until(d: Map<String, Any?>, uid: String): Double =
        ((d["restrictedUntil"] as? Map<*, *>)?.get(uid) as? Number)?.toDouble() ?: 0.0

    private fun flags(d: Map<String, Any?>, uid: String): List<String> =
        ((d["restrictedFlags"] as? Map<*, *>)?.get(uid) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun exempt(d: Map<String, Any?>, uid: String): Boolean {
        val owner = d["createdBy"] as? String ?: ""
        return (owner.isNotEmpty() && owner == uid) || uid in list(d, "admins")
    }

    /** activeRestrictions: the flags holding [uid] back now; none once `until` passes, none for staff. */
    fun active(d: Map<String, Any?>, uid: String, nowMs: Double): Set<String> {
        if (exempt(d, uid) || until(d, uid) <= nowMs) return emptySet()
        return flags(d, uid).toSet()
    }

    /** isRestricted: the old all-media flag still covers the three it was split into. */
    fun isRestricted(d: Map<String, Any?>, uid: String, r: R, nowMs: Double): Boolean {
        val a = active(d, uid, nowMs)
        if (r.slug in a) return true
        return r in setOf(R.SEND_PHOTOS, R.SEND_FILES, R.SEND_LINKS) && R.SEND_MEDIA.slug in a
    }

    /** isMutedMember: may send nothing at all. Groups only. */
    fun isMuted(d: Map<String, Any?>, uid: String, nowMs: Double): Boolean =
        (d["type"] as? String) == "group" && R.SEND_TEXT.slug in active(d, uid, nowMs)

    /** The member card's test (iOS isMuted there): a plain member with any restriction still running. */
    fun hasRestriction(d: Map<String, Any?>, uid: String, nowMs: Double): Boolean = !exempt(d, uid) && until(d, uid) > nowMs

    /** ChatService.muteMember: the end for a mute of [seconds] (0 = about 50 years). */
    fun muteEnd(seconds: Double, nowMs: Double): Double = nowMs + (if (seconds > 0) seconds else FOREVER_SECONDS) * 1000

    /**
     * The sheet's restriction end (ThreadView applyAdminDelete): forever, but a restriction that
     * already has a timed end keeps it (a 1 hour mute changed here stays a 1 hour one).
     */
    fun sheetEnd(d: Map<String, Any?>, uid: String, nowMs: Double): Double {
        val forever = muteEnd(0.0, nowMs)
        val t = until(d, uid)
        return if (t > nowMs && t < forever - 86_400_000) t else forever
    }

    // ---- The admin delete sheet's switches ------------------------------------------------------

    /** groupLets: does the GROUP let members do this at all? A switch it has off cannot be moved. */
    fun groupLets(d: Map<String, Any?>, r: R): Boolean = when (r) {
        R.ADD_MEMBERS -> d["membersCanAdd"] == true
        R.CHANGE_INFO -> d["membersCanEditInfo"] == true
        R.PIN_MESSAGES -> false
        else -> d["onlyAdminsSend"] != true
    }

    /** seed: a switch starts on when the group lets it and no picked sender is held back from it. */
    fun seed(d: Map<String, Any?>, authors: List<String>, nowMs: Double): Set<R> =
        SWITCHES.filter { r -> groupLets(d, r) && authors.none { isRestricted(d, it, r, nowMs) } }.toSet()

    /** setAllowed: Send Messages off takes the media with it; a media switch on brings it back. */
    fun setAllowed(d: Map<String, Any?>, allowed: Set<R>, r: R, on: Boolean): Set<R> {
        val next = allowed.toMutableSet()
        if (on) next += r else next -= r
        if (r == R.SEND_TEXT && !on) next -= MEDIA.toSet()
        if (r in MEDIA && on && groupLets(d, R.SEND_TEXT)) next += R.SEND_TEXT
        return next
    }

    /** The Send Media row: all five on or off together. */
    fun setAllMedia(d: Map<String, Any?>, allowed: Set<R>, on: Boolean): Set<R> =
        if (on) allowed + MEDIA + (if (groupLets(d, R.SEND_TEXT)) setOf(R.SEND_TEXT) else emptySet()) else allowed - MEDIA.toSet()

    /**
     * flags(for:): what to write for one sender. A switch the group has locked keeps the sender's own
     * value; no messages = the whole mute set plus the finer media; all three finer media off = the
     * old all-media flag too. In the model's order.
     */
    fun flagsFor(d: Map<String, Any?>, uid: String, allowed: Set<R>, nowMs: Double): List<String> {
        val f = mutableSetOf<String>()
        for (r in SWITCHES) {
            if (groupLets(d, r)) { if (r !in allowed) f += r.slug }
            else if (isRestricted(d, uid, r, nowMs)) f += r.slug
        }
        if (R.SEND_TEXT.slug in f) {
            f += MUTE_ALL
            f += listOf(R.SEND_PHOTOS, R.SEND_FILES, R.SEND_LINKS).map { it.slug }
        }
        if (listOf(R.SEND_PHOTOS, R.SEND_FILES, R.SEND_LINKS).all { it.slug in f }) f += R.SEND_MEDIA.slug
        return R.entries.map { it.slug }.filter { it in f }
    }

    fun label(r: R): String = when (r) {
        R.SEND_TEXT -> "Send Messages"
        R.SEND_PHOTOS -> "Photos and Videos"
        R.SEND_FILES -> "Files"
        R.SEND_VOICE -> "Voice Messages"
        R.SEND_STICKERS -> "Stickers and GIFs"
        R.SEND_LINKS -> "Links"
        R.ADD_MEMBERS -> "Add Members"
        R.PIN_MESSAGES -> "Pin Messages"
        R.CHANGE_INFO -> "Change Group Info"
        else -> r.slug
    }
}
