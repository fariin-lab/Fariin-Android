package com.fariin.app.data

/**
 * The member lists of a group (iOS GroupInfoView GroupMembers / GroupMembersSheet /
 * GroupRemovedUsersView). Pure, so the order and the search are unit-tested.
 */
object GroupMembers {
    /** The Group Info card shows six; past six, the first five and See All (iOS membersShown). */
    const val SHOWN = 6

    data class Group(val owner: String, val users: List<String>, val admins: List<String>,
                     val names: Map<String, String>, val banned: List<String>) {
        companion object {
            fun from(d: Map<String, Any?>): Group {
                fun list(k: String) = (d[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val names = (d["names"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap()
                return Group(d["createdBy"] as? String ?: "", list("users"), list("admins"), names, list("bannedUids"))
            }
        }
        fun isOwner(uid: String) = owner.isNotEmpty() && owner == uid
    }

    /** "Owner" / "Admin" / null, the word at the end of a member row. */
    fun rank(g: Group, uid: String): String? = when {
        g.isOwner(uid) -> "Owner"
        uid in g.admins -> "Admin"
        else -> null
    }

    /** Group Info's order (iOS GroupMembers.sorted): me first, then admins, then by name. */
    fun infoOrder(g: Group, me: String): List<String> {
        val admins = g.admins.toSet()
        return g.users.sortedWith(compareBy<String>({ it != me }, { it !in admins }, { (g.names[it] ?: "").lowercase() }))
    }

    /** What Group Info's card lists: all up to six, else the first five (and See All). */
    fun infoShown(order: List<String>): Pair<List<String>, Boolean> =
        if (order.size > SHOWN) order.take(SHOWN - 1) to true else order to false

    /** The name a member shows under: "You" for me. */
    fun shownName(g: Group, uid: String, me: String) = if (uid == me) "You" else g.names[uid]?.ifEmpty { null } ?: "User"

    /**
     * The Members page (iOS GroupMembersSheet.members): the search over names and, with or without
     * a leading "@", the @usernames already read; by name, or owner, admins, then the rest.
     */
    fun search(g: Group, me: String, query: String, handles: Map<String, String> = emptyMap(), adminsFirst: Boolean = false): List<String> {
        val q = query.trim().lowercase()
        val handleQ = q.removePrefix("@")
        val admins = g.admins.toSet()
        val rows = g.users.mapNotNull { uid ->
            val n = shownName(g, uid, me)
            val hit = q.isEmpty() || n.lowercase().contains(q) ||
                (handleQ.isNotEmpty() && handles[uid]?.lowercase()?.contains(handleQ) == true)
            if (!hit) null else Triple(uid, n, if (g.isOwner(uid)) 0 else if (uid in admins) 1 else 2)
        }
        val byName = compareBy<Triple<String, String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.second }
        return rows.sortedWith(if (adminsFirst) compareBy<Triple<String, String, Int>> { it.third }.then(byName) else byName).map { it.first }
    }

    /** The letter a name files under: A to Z, everything else "#" (iOS letter). */
    fun letter(name: String): String {
        val c = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).firstOrNull() ?: return "#"
        return if (c in 'a'..'z' || c in 'A'..'Z') c.uppercaseChar().toString() else "#"
    }

    /** A to Z sections, "#" last; one unnamed section when sorted admins first (iOS buildSections). */
    fun sections(list: List<String>, nameOf: (String) -> String, adminsFirst: Boolean): List<Pair<String, List<String>>> {
        if (adminsFirst) return listOf("" to list)
        val out = LinkedHashMap<String, MutableList<String>>()
        for (uid in list) out.getOrPut(letter(nameOf(uid))) { mutableListOf() }.add(uid)
        return out.entries.map { it.key to it.value.toList() }
            .sortedWith(compareBy<Pair<String, List<String>>>({ it.first == "#" }, { it.first }))
    }

    /** The Banned Users page's empty line (iOS GroupRemovedUsersView). */
    const val NOBODY_BANNED = "Nobody is banned from this group."
}
