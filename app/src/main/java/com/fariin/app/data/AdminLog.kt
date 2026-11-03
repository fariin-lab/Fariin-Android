package com.fariin.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recent Actions (iOS GroupRecentActionsView): the group's admin log from the server's
 * `groupAdminLog` callable, 50 at a time, newest first. Only what the log stores is shown; the
 * current photo and description go on the NEWEST change of each, labelled as what the group has now.
 * Pure logic here; the page is ui/people/RecentActions.kt.
 */
object AdminLog {

    data class Event(val id: String, val atMs: Double, val type: String, val by: String, val target: String, val detail: Map<String, Any?>) {
        val fields: List<String> get() = strings(detail["fields"])

        companion object {
            fun from(d: Map<*, *>?): Event? {
                d ?: return null
                val id = d["id"] as? String
                if (id.isNullOrEmpty()) return null
                @Suppress("UNCHECKED_CAST")
                val detail = (d["detail"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap() ?: emptyMap()
                return Event(id, (d["at"] as? Number)?.toDouble() ?: 0.0, d["type"] as? String ?: "",
                    d["by"] as? String ?: "", d["target"] as? String ?: "", detail)
            }
        }
    }

    /** One page of the callable's answer. [next] is the cursor for the following page, null at the end. */
    data class Page(val events: List<Event>, val next: Map<String, Any?>?, val names: Map<String, String>) {
        companion object {
            fun from(d: Map<*, *>?): Page {
                val events = (d?.get("events") as? List<*>)?.mapNotNull { Event.from(it as? Map<*, *>) } ?: emptyList()
                val next = (d?.get("next") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap()
                val names = (d?.get("names") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    val key = k as? String; val n = v as? String
                    if (key != null && n != null) key to n else null
                }?.toMap() ?: emptyMap()
                return Page(events, next, names)
            }
        }
    }

    /** The filter's kinds of action, as the reference app groups them (iOS RecentActionKind). */
    enum class Kind(val title: String) {
        NEW_MEMBERS("New Members"),
        JOIN_REQUESTS("Join Requests"),
        RESTRICTIONS("Restrictions and Removals"),
        ADMINS("Admins and Owner"),
        INFO("Group Info and Settings"),
        LINKS("Invite Links"),
        PINS("Pinned Messages"),
        FARIIN("Fariin");

        companion object {
            fun of(type: String): Kind = when (type) {
                "member_added" -> NEW_MEMBERS
                "join_approved", "join_denied" -> JOIN_REQUESTS
                "member_removed", "member_unbanned", "restricted", "unrestricted" -> RESTRICTIONS
                "admin_added", "admin_removed", "admin_rights", "owner_changed", "roles" -> ADMINS
                "link_created", "link_edited", "link_revoked", "link_deleted", "links_reset" -> LINKS
                "pinned", "unpinned" -> PINS
                "fariin_action" -> FARIIN
                else -> INFO   // info, settings, username_set, made_private, group_ended
            }
        }
    }

    /** null = everything (the reference app's "All Actions" / "All Admins"). */
    fun shown(events: List<Event>, kinds: Set<Kind>?, actors: Set<String>?): List<Event> =
        events.filter { (kinds?.contains(Kind.of(it.type)) ?: true) && (actors?.contains(it.by) ?: true) }

    /** Days, newest first; each day's actions newest first (the order the server pages them in). */
    fun days(events: List<Event>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<Event>>> {
        val out = mutableListOf<Pair<LocalDate, MutableList<Event>>>()
        for (e in events) {
            val d = Instant.ofEpochMilli(e.atMs.toLong()).atZone(zone).toLocalDate()
            if (out.isNotEmpty() && out.last().first == d) out.last().second.add(e) else out.add(d to mutableListOf(e))
        }
        return out
    }

    /** "Someone" / "Fariin" / "You" / the log's name / the group's name (iOS name(_:)). */
    fun name(uid: String, me: String, logNames: Map<String, String>, groupNames: Map<String, String>): String {
        if (uid.isEmpty()) return "Someone"
        if (uid == "fariin") return "Fariin"
        if (uid == me) return "You"
        val n = logNames[uid] ?: groupNames[uid] ?: ""
        return n.ifEmpty { "Someone" }
    }

    /** Who the admin filter lists: the owner and today's admins, then anyone else who acted. */
    fun filterPeople(owner: String, admins: List<String>, events: List<Event>): List<String> {
        val out = mutableListOf<String>()
        if (owner.isNotEmpty()) out.add(owner)
        out += admins.filter { it != owner }
        for (e in events) if (e.by.isNotEmpty() && e.by !in out) out.add(e.by)
        return out
    }

    /** A piece of the action line; [bold] pieces are the person or value it touched. */
    data class Part(val text: String, val bold: Boolean = false)

    private fun t(s: String) = Part(s)
    private fun b(s: String) = Part(s, true)

    /** The action, without the actor (their name is the row's first line). */
    fun action(e: Event, them: String): List<Part> {
        val d = e.detail
        return when (e.type) {
            "member_added" -> listOf(t("added "), b(them))
            "member_removed" -> if (d["banned"] == true) listOf(t("removed and banned "), b(them)) else listOf(t("removed "), b(them))
            "member_unbanned" -> listOf(t("unbanned "), b(them))
            "admin_added" -> listOf(t("made "), b(them), t(" an admin"))
            "admin_rights" -> listOf(t("changed the admin rights of "), b(them))
            "admin_removed" -> listOf(t("removed "), b(them), t(" as admin"))
            "owner_changed" -> listOf(t("made "), b(them), t(" the owner"))
            "restricted" -> listOf(t("restricted "), b(them))
            "unrestricted" -> listOf(t("lifted the restrictions on "), b(them))
            "roles" -> if (e.target.isEmpty()) listOf(t("changed member roles")) else listOf(t("changed the role of "), b(them))
            "info" -> {
                val words = e.fields.filter { it == "name" || it == "description" || it == "photo" }
                if (words.isEmpty()) listOf(t("changed the group info")) else listOf(t("changed the group "), b(list(words)))
            }
            "settings" -> {
                val keys = strings(d["keys"])
                if (keys.size == 1) listOf(t("changed "), b(settingName(keys[0]))) else listOf(t("changed the group settings"))
            }
            "pinned", "unpinned" -> {
                val n = strings(d["messages"]).size
                val verb = if (e.type == "pinned") "pinned " else "unpinned "
                if (n > 1) listOf(t(verb), b("$n messages")) else listOf(t(verb + "a message"))
            }
            "join_approved" -> listOf(t("let "), b(them), t(" in"))
            "join_denied" -> listOf(t("turned down "), b(them))
            "link_created", "link_edited", "link_revoked" -> {
                val verb = when (e.type) { "link_created" -> "created "; "link_edited" -> "edited "; else -> "turned off " }
                listOf(t(verb), b(linkName(d)))
            }
            "link_deleted" -> {
                val n = (d["count"] as? Number)?.toInt() ?: 0
                if (n > 1) listOf(t("deleted "), b("$n invite links")) else listOf(t("deleted an invite link"))
            }
            "links_reset" -> listOf(t("reset the invite links"))
            "group_ended" -> listOf(t("ended the group"))
            "username_set" -> {
                val u = d["username"] as? String ?: ""
                if (u.isEmpty()) listOf(t("removed the group's public link")) else listOf(t("set the public link to "), b("@$u"))
            }
            "made_private" -> listOf(t("made the group private"))
            "fariin_action" -> listOf(t(fariinWords(d["op"] as? String ?: "")))
            else -> listOf(t("changed the group"))
        }
    }

    /** The richer part under the action line (iOS EventDetail). */
    data class Detail(
        val quoteLabel: String? = null,
        val quote: String? = null,
        val photoUrl: String? = null,
        val note: String? = null,
        val bullets: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = quote == null && photoUrl == null && note == null && bullets.isEmpty()
    }

    /**
     * From what the event stores and, for the newest photo or description change among [events],
     * what the group has now ([photoNow], [descriptionNow]). [untilText] formats a restriction's end.
     */
    fun detail(e: Event, events: List<Event>, photoNow: String?, descriptionNow: String?,
               nameOf: (String) -> String, untilText: (Double) -> String): Detail {
        val d = e.detail
        return when (e.type) {
            "admin_added", "admin_rights" -> {
                val rights = strings(d["rights"])
                // No list = a full admin (no adminRights entry means every right).
                Detail(note = if (rights.isEmpty()) "All admin rights" else "Can:",
                    bullets = rights.map { GroupAdmins.Right.of(it)?.label ?: it })
            }
            "restricted" -> {
                val flags = strings(d["flags"])
                val until = (d["until"] as? Number)?.toDouble() ?: 0.0
                // The app's "no end date" is a date ~50 years on (the restrict sheet).
                val note = if (until <= 0 || (until - e.atMs) / 1000 > 10.0 * 365 * 86_400) "No end date. Held back from:"
                    else "Until ${untilText(until)}. Held back from:"
                Detail(note = note, bullets = flags.map(::restrictName))
            }
            "settings" -> {
                val keys = strings(d["keys"])
                Detail(bullets = if (keys.size > 1) keys.map(::settingName) else emptyList())
            }
            "info" -> {
                var label: String? = null
                var quote: String? = null
                var photo: String? = null
                val n = d["name"] as? String
                if (!n.isNullOrEmpty()) { label = "New name"; quote = n }
                if ("photo" in e.fields && newest(events, "photo") == e.id && !photoNow.isNullOrEmpty()) photo = photoNow
                if (quote == null && "description" in e.fields && newest(events, "description") == e.id && !descriptionNow.isNullOrEmpty()) {
                    label = "Description now"; quote = descriptionNow
                }
                Detail(quoteLabel = label, quote = quote, photoUrl = photo)
            }
            "owner_changed" -> {
                val from = d["from"] as? String ?: ""
                if (from.isNotEmpty() && from != e.by) Detail(note = "Before: ${nameOf(from)}") else Detail()
            }
            else -> Detail()
        }
    }

    /** The newest loaded change of one info field (events come newest first). */
    fun newest(events: List<Event>, field: String): String? = events.firstOrNull { it.type == "info" && field in it.fields }?.id

    fun settingName(key: String): String = when (key) {
        "disappearSeconds" -> "Auto-Delete Messages"
        "onlyAdminsSend" -> "Who Can Send Messages"
        "membersCanAdd" -> "Who Can Add Members"
        "membersCanEditInfo" -> "Who Can Edit Group Info"
        "onlyAdminsCall" -> "Who Can Start Calls"
        "wallpaper" -> "Wallpaper"
        "membersCanShareLink" -> "Sharing the Invite Link"
        "restrictedBy", "restrictRequest" -> "Restricted Chat"
        "noScreenshots" -> "Screenshots"
        "noForwarding" -> "Forwarding"
        "noSaving" -> "Saving Media"
        "memberSend" -> "What Members Can Send"
        "slowMode" -> "Slow Mode"
        "hideHistoryAt" -> "Chat History for New Members"
        else -> "Group Settings"
    }

    fun restrictName(flag: String): String = when (flag) {
        "sendText" -> "Send Messages"
        "sendMedia" -> "Photos, Videos, Files and Links"
        "sendPhotos" -> "Photos and Videos"
        "sendFiles" -> "Files"
        "sendLinks" -> "Links"
        "sendVoice" -> "Voice Messages"
        "sendStickers" -> "Stickers and GIFs"
        "sendPolls" -> "Polls"
        "sendReactions" -> "Reactions"
        "pinMessages" -> "Pin Messages"
        "addMembers" -> "Add Members"
        "changeInfo" -> "Change Group Info"
        else -> flag
    }

    fun linkName(d: Map<String, Any?>): String {
        if (d["primary"] == true) return "the main invite link"
        val n = d["name"] as? String ?: ""
        return if (n.isEmpty()) "an invite link" else "the invite link “$n”"
    }

    fun fariinWords(op: String): String = when (op) {
        "groupModSet" -> "changed this group's status"
        "groupModExpire" -> "lifted a limit on this group"
        "groupDisable" -> "disabled this group"
        "groupRestore" -> "restored this group"
        "groupVerify" -> "verified this group"
        "groupUnverify" -> "removed this group's verified badge"
        "groupReportSet" -> "changed this group's Report button"
        else -> "moderated this group"
    }

    fun list(words: List<String>): String =
        if (words.size <= 1) words.firstOrNull() ?: "" else words.dropLast(1).joinToString(", ") + " and " + words.last()

    /** The date pill: Today, Yesterday, "27 October" (this year) or with the year. */
    fun dayTitle(day: LocalDate, today: LocalDate, locale: Locale, monthDay: String, monthDayYear: String): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) monthDay else monthDayYear, locale))
    }

    /** The row's time, on the phone's 12/24 h setting. */
    fun timeText(ms: Double, is24h: Boolean, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms.toLong()).atZone(zone).format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale))

    /** The failure line (iOS load(more:) catch). */
    fun failure(permissionDenied: Boolean): String =
        if (permissionDenied) "Only the group's owner and admins can see this."
        else "Couldn't load recent actions. Check your connection and try again."

    /** The callable's request: the group, 50 at a time, after [before] for the next page. */
    fun request(cid: String, before: Map<String, Any?>?): Map<String, Any?> =
        buildMap { put("cid", cid); put("limit", 50); if (before != null) put("before", before) }

    /** A new page appended without the events already loaded. */
    fun merge(have: List<Event>, page: List<Event>): List<Event> {
        val ids = have.map { it.id }.toHashSet()
        return have + page.filter { it.id !in ids }
    }

    private fun strings(v: Any?): List<String> = (v as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}
