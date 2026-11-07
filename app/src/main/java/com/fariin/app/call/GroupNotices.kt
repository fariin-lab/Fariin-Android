package com.fariin.app.call

/** One remote person, as a join/leave notice needs them (keyed by uid, so a rejoin is the same person). */
data class NoticePerson(val key: String, val name: String, val photo: String?)

/** A notice under the call's header; [avatar] is set when it is about exactly one person. */
data class GroupNotice(val text: String, val avatar: NoticePerson? = null)

/**
 * iOS GroupCallStatusBanner's join/leave bookkeeping, without the screen: who is known, who came
 * and who went since the last notice. The screen feeds it the room on every change and takes
 * [next] after a short batch window, then again each time a notice has gone.
 *
 * - The people already here when it starts, and the people a (re)connect hands me, are not "joined".
 * - Only while connected: a reconnect empties and refills the room, which is nobody coming or going.
 * - Someone who leaves and comes back (or comes and leaves) before the notice went out cancels out.
 * - Joins go before leaves, one notice at a time.
 */
class GroupNoticeQueue {
    private var known: Map<String, NoticePerson> = emptyMap()
    private var baselineTaken = false
    private var wasConnected = false
    private val pendingJoined = LinkedHashMap<String, NoticePerson>()
    private val pendingLeft = LinkedHashMap<String, NoticePerson>()

    /** The room as it is now (remote people only). True when someone came or went. */
    fun update(now: Map<String, NoticePerson>, connected: Boolean): Boolean {
        val firstConnect = connected && !wasConnected
        wasConnected = connected
        if (!baselineTaken || !connected || firstConnect) {
            baselineTaken = true
            known = now
            return false
        }
        var changed = false
        for ((key, person) in now) if (key !in known) {
            if (pendingLeft.remove(key) == null) pendingJoined[key] = person
            changed = true
        }
        for ((key, person) in known) if (key !in now) {
            if (pendingJoined.remove(key) == null) pendingLeft[key] = person
            changed = true
        }
        known = now
        return changed
    }

    /** The next notice to show: everyone who joined, else everyone who left; null when nothing waits. */
    fun next(): GroupNotice? {
        if (pendingJoined.isNotEmpty()) {
            // The freshest name and photo: they often arrive a moment after the person does.
            val people = pendingJoined.values.map { known[it.key] ?: it }
            pendingJoined.clear()
            return make(people, "joined")
        }
        if (pendingLeft.isNotEmpty()) {
            val people = pendingLeft.values.toList()
            pendingLeft.clear()
            return make(people, "left")
        }
        return null
    }

    /** The screen went away: a fresh baseline next time, so nobody is announced late. */
    fun reset() {
        known = emptyMap(); baselineTaken = false; wasConnected = false
        pendingJoined.clear(); pendingLeft.clear()
    }

    companion object {
        /** iOS holdSeconds / batchSeconds and GroupCallNoticeMotion.slideSeconds. */
        const val HOLD_MS = 2000L
        const val BATCH_MS = 500L
        const val SLIDE_MS = 350

        /** iOS showToast: the service's short notes stay four seconds. */
        const val TOAST_MS = 4000L

        /** "Alice joined" / "Alice and Bob joined" / "Alice, Bob and 3 others joined", names A to Z. */
        fun make(people: List<NoticePerson>, verb: String): GroupNotice {
            val sorted = people.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { p: NoticePerson -> shownName(p.name) }.thenBy { it.key })
            val names = sorted.map { shownName(it.name) }
            val text = when (names.size) {
                0 -> "Someone $verb"
                1 -> "${names[0]} $verb"
                2 -> "${names[0]} and ${names[1]} $verb"
                else -> {
                    val others = names.size - 2
                    "${names[0]}, ${names[1]} and $others ${if (others == 1) "other" else "others"} $verb"
                }
            }
            return GroupNotice(text, sorted.singleOrNull())
        }

        private fun shownName(name: String) = name.trim().ifEmpty { "Someone" }
    }
}
