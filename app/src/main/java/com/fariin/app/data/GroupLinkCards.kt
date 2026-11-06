package com.fariin.app.data

import com.fariin.app.channel.ChannelService
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await

/**
 * The card of one of OUR group or channel links in a chat: an invite (fariin.com/g/<code>) or a
 * public @name (fariin.com/<name>). The sealed card the sender built is only a snapshot; the bubble
 * shows the group's CURRENT name, photo and size, read from the server (`groupLinkPreview` lite,
 * `publicGroupPreview`), so a rename or a new photo shows up.
 *
 * Battery: one lookup per link per [FRESH_MS], shared by every bubble holding it, kept in memory
 * only; nothing polls. Scrolling back to a bubble reads the memory, not the network.
 */
object GroupLinkCards {
    sealed interface Target {
        val key: String
        data class Invite(val code: String) : Target { override val key get() = "g/$code" }
        data class Name(val name: String) : Target { override val key get() = "n/" + name.lowercase() }
    }

    /** What the server says about a link now. */
    sealed interface Live {
        /** [byName]: found through a public @name (not an invite); [isMember]: I am in it / follow it. */
        data class Found(val title: String, val photoUrl: String?, val count: Int, val isChannel: Boolean,
                         val byName: Boolean = false, val isMember: Boolean = false) : Live {
            val countLine: String get() = countLine(count, isChannel, byName)
            /** The card's action words, a public @name only: an invite card is as it was. */
            val action: String? get() = actionLine(byName, isChannel, isMember)
        }
        /** Revoked, expired, deleted or no longer public. */
        data object Gone : Live
        /** No answer (offline): the bubble keeps the sender's snapshot. */
        data object Unknown : Live
    }

    const val FRESH_MS = 5 * 60_000L
    private const val RETRY_MS = 60_000L

    /** An invite or public-name address, else null (every other link, a person's /u/ link included). */
    fun target(url: String): Target? {
        com.fariin.app.ui.people.GroupInviteLink.code(url)?.let { return Target.Invite(it) }
        if (QrRules.handleFrom(url) != null) return null
        return QrRules.bareNameFrom(url)?.let { Target.Name(it) }
    }

    private val SIZE_LINE = Regex("^(Channel invite · )?[0-9.,]+[KkMB]? (members?|followers?)$", RegexOption.IGNORE_CASE)

    /**
     * Which sealed card the bubble draws as a group card. An invite always; a bare name only when
     * the sender found a group behind it (its card says "N members" / "N followers"), since the
     * same address is a person's username too (iOS linkPreviewBody does the same test).
     */
    fun cardTarget(url: String, snapshotDesc: String): Target? = when (val t = target(url)) {
        is Target.Invite -> t
        is Target.Name -> t.takeIf { SIZE_LINE.matches(snapshotDesc.trim()) }
        null -> null
    }

    /** The snapshot's own mark for a dead invite (the sender's phone was told so). */
    fun snapshotSaysGone(title: String, desc: String): Boolean = title == "Unavailable" && desc.isEmpty()

    /** "N members"; a channel "N followers", and a public channel's @name card "Channel invite · N followers". */
    fun countLine(n: Int, channel: Boolean, byName: Boolean = false): String = when {
        channel && byName -> "Channel invite · $n ${if (n == 1) "follower" else "followers"}"
        channel -> "$n ${if (n == 1) "follower" else "followers"}"
        else -> "$n ${if (n == 1) "member" else "members"}"
    }

    /** A public @name card's action: Open Channel / View Channel, Open Group when I am in the group. */
    fun actionLine(byName: Boolean, channel: Boolean, member: Boolean): String? = when {
        !byName -> null
        channel -> if (member) "Open Channel" else "View Channel"
        member -> "Open Group"
        else -> null
    }

    // ------------------------------------------------------------------ the lookup (memory only)

    private class Kept(val at: Long, val live: Live)

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kept = ConcurrentHashMap<String, Kept>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Live>>()

    /** What is in memory for [t], fresh or not (the bubble's first frame), or null. */
    fun peek(t: Target): Live? = kept[t.key]?.live

    /** The link's current card: memory while fresh, else one server call shared by every caller. */
    suspend fun load(t: Target): Live {
        val now = System.currentTimeMillis()
        kept[t.key]?.let { k ->
            val ttl = if (k.live == Live.Unknown) RETRY_MS else FRESH_MS
            if (now - k.at < ttl) return k.live
        }
        // Lazy, so it is in the map before it can finish and take itself out.
        val fresh = io.async(start = CoroutineStart.LAZY) {
            val live = runCatching { ask(t) }.getOrDefault(Live.Unknown)
            // An offline answer never hides a good one we already had.
            val prev = kept[t.key]?.live
            kept[t.key] = Kept(System.currentTimeMillis(), if (live == Live.Unknown && prev != null && prev != Live.Unknown) prev else live)
            inFlight.remove(t.key)
            kept[t.key]!!.live
        }
        val task = inFlight.putIfAbsent(t.key, fresh) ?: fresh
        if (task !== fresh) fresh.cancel()
        task.start()
        return task.await()
    }

    private suspend fun ask(t: Target): Live = when (t) {
        is Target.Invite -> try {
            val d = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupLinkPreview")
                .withTimeout(10, TimeUnit.SECONDS)
                .call(mapOf("code" to t.code, "lite" to true)).await().getData() as? Map<*, *>
            if (d == null) Live.Unknown else {
                val channel = d["channel"] == true
                Live.Found((d["title"] as? String)?.trim()?.ifEmpty { null } ?: if (channel) "Channel" else "Group",
                    (d["photoUrl"] as? String)?.ifEmpty { null }, (d["memberCount"] as? Number)?.toInt() ?: 0, channel)
            }
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.NOT_FOUND || e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED) Live.Gone
            else Live.Unknown
        }
        is Target.Name -> when (val r = ChannelService.preview(null, t.name)) {
            is ChannelService.PreviewResult.Found -> r.preview.let { p ->
                Live.Found(p.title, p.photoUrl, if (p.isChannel) p.shownCount else p.memberCount, p.isChannel,
                    byName = true, isMember = p.isMember)
            }
            is ChannelService.PreviewResult.Refused -> Live.Gone
            is ChannelService.PreviewResult.Failed -> Live.Unknown
        }
    }
}
