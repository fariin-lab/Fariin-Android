package com.fariin.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * The report flow (owner 2026-11-08): one sheet for every Report in the app, a list of reasons, a
 * confirm page, a thanks page. The server and iOS take the same reason keys.
 */
enum class ReportReason(val key: String, val label: String) {
    NUDITY("nudity", "Nudity or sexual content"),
    CHILD_SAFETY("child_safety", "Child safety"),
    HATE("hate", "Hate speech or symbols"),
    SCAM("scam", "Scam or fraud"),
    VIOLENCE("violence", "Violence or dangerous groups"),
    ILLEGAL_GOODS("illegal_goods", "Selling illegal or restricted items"),
    BULLYING("bullying", "Bullying or harassment"),
    IMPERSONATION("impersonation", "Pretending to be someone else"),
    IP("ip", "Copyright or trademark"),
    SELF_HARM("self_harm", "Suicide or self-harm"),
    SPAM("spam", "Spam"),
    OTHER("other", "Something else"),
}

/** A narrower reason under a [ReportReason] (contract v2 `sub`). */
data class ReportSub(val id: String, val label: String)

/** The sub-reasons page of each reason: its title and rows. Spam and Something else have none. */
object ReportSubs {
    private fun s(vararg p: Pair<String, String>) = p.map { ReportSub(it.first, it.second) }

    val byReason: Map<ReportReason, Pair<String, List<ReportSub>>> = mapOf(
        ReportReason.NUDITY to ("What kind of sexual content?" to s(
            "nudity" to "Nudity or sexual activity", "sexual_messages" to "Sexual messages or requests",
            "intimate_images" to "Sharing private images of someone", "involves_minor" to "It involves someone under 18")),
        ReportReason.CHILD_SAFETY to ("What's happening?" to s(
            "abuse_material" to "Sexual images or video of a child", "grooming" to "An adult contacting a child in a sexual way",
            "child_harm" to "A child being hurt or in danger", "child_bullying" to "A child being bullied")),
        ReportReason.HATE to ("Who is being attacked?" to s(
            "race" to "Race or ethnicity", "religion" to "Religion", "gender_orientation" to "Gender or sexual orientation",
            "disability" to "Disability", "symbols" to "Hateful symbols")),
        ReportReason.SCAM to ("What kind of scam?" to s(
            "money_offer" to "Fake investment or money offer", "asking_money" to "Asking for money or gift cards",
            "phishing" to "Trying to steal accounts or passwords", "fake_seller" to "Fake shop or seller")),
        ReportReason.VIOLENCE to ("What's the problem?" to s(
            "threat" to "A threat to hurt someone", "extremism" to "Terrorism or extremist group",
            "graphic" to "Graphic violence", "incitement" to "Calling for violence")),
        ReportReason.ILLEGAL_GOODS to ("What is being sold?" to s(
            "drugs" to "Drugs", "weapons" to "Weapons", "animals" to "Animals", "other_goods" to "Something else")),
        ReportReason.BULLYING to ("Who is being bullied?" to s(
            "me" to "Me", "someone_i_know" to "Someone I know", "someone_else" to "Someone else",
            "private_info" to "Sharing someone's private information")),
        ReportReason.IMPERSONATION to ("Who are they pretending to be?" to s(
            "me" to "Me", "someone_i_know" to "Someone I know", "public_figure" to "A celebrity or public figure",
            "business" to "A business or organization")),
        ReportReason.IP to ("What is being copied?" to s(
            "copyright" to "My photos, music or other work", "trademark" to "My brand name or logo")),
        ReportReason.SELF_HARM to ("What are you seeing?" to s(
            "suicide" to "Suicide or self-injury", "eating_disorder" to "Eating disorder")),
    )

    fun title(r: ReportReason): String? = byReason[r]?.first
    fun of(r: ReportReason): List<ReportSub> = byReason[r]?.second.orEmpty()

    /** The words for a stored category and sub (My reports): "Reason \u203A Sub-reason". */
    fun label(category: String?, sub: String?): String {
        val r = ReportReason.entries.firstOrNull { it.key == category } ?: return ""
        // involves_minor is stored under child safety; its words live under nudity.
        val found = of(r).firstOrNull { it.id == sub }
            ?: if (sub == "involves_minor") of(ReportReason.NUDITY).firstOrNull { it.id == sub } else null
        return ReportRules.reasonLine(r, found)
    }
}

/** What is being reported; [noun] is the word the sheet uses for it. */
enum class ReportKind(val noun: String, val wire: String) {
    STORY("story", "story"), MESSAGE("message", "message"), CHANNEL("channel", "channel"), GROUP("group", "group"),
    ACCOUNT("account", "user"), POST("post", "post"), REPLY("reply", "reply"),
}

/**
 * One thing to report. [name] is whose it is (the person, or the channel or group), shown in the
 * privacy line and on the Block switch. [personUid] is the person behind it, when there is one;
 * a channel or a group has none and gets no Block switch. The ids say where it is: [cid] the chat,
 * channel or group, [messageId] a message or post, [storyId] a story, [replyId]/[answerId] a reply
 * under a channel post. [onSent] runs after the server took it, with whether Block was on.
 * [onLeave] set (a member reporting a group or channel): page 2 offers "Leave <name>", and this
 * runs after the report when it was on (the old Report and Leave).
 */
data class ReportTarget(
    val kind: ReportKind,
    val name: String? = null,
    val personUid: String? = null,
    val cid: String = "",
    val messageId: String? = null,
    val storyId: String? = null,
    val replyId: String? = null,
    val answerId: String? = null,
    val onSent: (blocked: Boolean) -> Unit = {},
    val onLeave: (() -> Unit)? = null,
    /** The words of a single group message, sent only when the reporter keeps the share switch on. */
    val messageText: String? = null,
)

/** The words and rules of the sheet, kept here so they can be tested. */
object ReportRules {
    const val NOTE_MAX = 500

    private fun shown(name: String?) = name?.trim()?.takeIf { it.isNotEmpty() }

    fun header(kind: ReportKind) = "Why are you reporting this ${kind.noun}?"
    fun privacy(name: String?) = "Reports are private. ${shown(name) ?: "They"} won't know it was you."
    fun confirmTitle(kind: ReportKind) = "Report this ${kind.noun}?"

    /** The confirm page's "Reason \u203A Sub-reason". */
    fun reasonLine(reason: ReportReason, sub: ReportSub?) = if (sub == null) reason.label else "${reason.label} \u203A ${sub.label}"

    /** The category sent: a nudity report that involves a minor goes as child safety. */
    fun category(reason: ReportReason, sub: ReportSub?): String =
        if (reason == ReportReason.NUDITY && sub?.id == "involves_minor") ReportReason.CHILD_SAFETY.key else reason.key

    const val THANKS_TITLE = "Thanks for letting us know"
    const val THANKS_BODY = "We'll review it. If it breaks our rules, we'll take it down. You can see what happened in Settings > Privacy > My reports."

    /** The safety box on the thanks page, by what was reported; null for none. */
    fun safetyBox(reason: ReportReason, sub: ReportSub?): String? = when {
        category(reason, sub) == ReportReason.CHILD_SAFETY.key -> "If a child is in danger right now, call your local emergency number."
        reason == ReportReason.SELF_HARM -> "If someone may hurt themselves, contact your local emergency number or a crisis line near you. You're not alone."
        reason == ReportReason.VIOLENCE && sub?.id == "threat" -> "If anyone is in danger right now, call your local emergency number."
        else -> null
    }

    /** Mute is offered on the thanks page for a 1:1 (an account) and a group. */
    fun canMute(t: ReportTarget): Boolean = t.cid.isNotEmpty() && (t.kind == ReportKind.ACCOUNT || t.kind == ReportKind.GROUP)
    fun muteLabel(name: String?) = "Mute ${shown(name) ?: "this chat"}"

    /** A person is involved: not a whole channel or group, and never myself. */
    fun canBlock(t: ReportTarget, me: String): Boolean =
        t.kind != ReportKind.CHANNEL && t.kind != ReportKind.GROUP && !t.personUid.isNullOrEmpty() && t.personUid != me

    fun blockLabel(name: String?) = "Block ${shown(name) ?: "this person"}"
    fun leaveLabel(t: ReportTarget) = "Leave ${shown(t.name) ?: "this ${t.kind.noun}"}"

    /** The note only goes with Something else, trimmed and cut to [NOTE_MAX]. */
    fun note(reason: ReportReason, typed: String): String =
        if (reason == ReportReason.OTHER) typed.trim().take(NOTE_MAX) else ""

    fun counter(typed: String) = "${typed.length}/$NOTE_MAX"

    /** The words for a refused report, by the callable's error code name. */
    fun errorText(code: String?): String = when (code) {
        "RESOURCE_EXHAUSTED" -> "You've sent a lot of reports today. Try again tomorrow."
        "FAILED_PRECONDITION" -> "You can't report your own content."
        "PERMISSION_DENIED" -> "You can't send reports right now."
        else -> ReportSender.FAILED
    }

    /**
     * The `reportContent` request (fariin-plans/report-contract.md): kind, category, note, block,
     * and the ids each kind needs. Empty ids are left out.
     */
    /** The share switch on the confirm page: only a single group message with words. */
    fun canShareText(t: ReportTarget) = t.kind == ReportKind.MESSAGE && !t.messageText.isNullOrBlank()
    const val SHARE_TEXT_LABEL = "Share this message with Fariin's review team"
    const val SHARE_TEXT_NOTE = "Only this one message is shared. Your chat stays private."

    fun request(t: ReportTarget, reason: ReportReason, note: String, block: Boolean, sub: ReportSub? = null,
                shareText: Boolean = false): Map<String, Any> {
        val m = linkedMapOf<String, Any>("kind" to t.kind.wire, "category" to category(reason, sub), "block" to block)
        if (sub != null) m["sub"] = sub.id
        if (note.isNotEmpty()) m["note"] = note
        fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) m[k] = v }
        when (t.kind) {
            ReportKind.ACCOUNT -> { put("id", t.personUid); put("cid", t.cid) }
            ReportKind.STORY -> { put("id", t.storyId); put("uid", t.personUid) }
            ReportKind.CHANNEL, ReportKind.GROUP -> put("cid", t.cid)
            ReportKind.POST, ReportKind.MESSAGE -> { put("cid", t.cid); put("mid", t.messageId); put("uid", t.personUid) }
            ReportKind.REPLY -> { put("cid", t.cid); put("mid", t.messageId); put("rid", t.replyId); put("aid", t.answerId) }
        }
        // Never the words of an account, group, channel or whole chat (contract v2.1 #1).
        if (shareText && canShareText(t)) m["messageText"] = t.messageText!!.take(1000)
        return m
    }
}

/** The one network call of the report flow. */
object ReportSender {
    const val FAILED = "Couldn't send the report. Check your connection and try again."

    /**
     * Sends the report; with [block], then blocks the person on the app's own block path (the
     * server only records the choice). Null when it went, else the words to show.
     */
    suspend fun send(t: ReportTarget, reason: ReportReason, note: String, block: Boolean, sub: ReportSub? = null,
                     shareText: Boolean = false): String? {
        try {
            com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable("reportContent").withTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .call(ReportRules.request(t, reason, note, block, sub, shareText)).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReportRules.errorText((e as? com.google.firebase.functions.FirebaseFunctionsException)?.code?.name)
        }
        // The report went; a failed block is said on its own and does not undo it.
        if (block && !block(t)) com.fariin.app.ui.components.Snack.show("Reported, but couldn't block. Try again from their profile.")
        return null
    }

    private fun people(): FirestorePeople? =
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { FirestorePeople(it, CryptoHub.crypto) }

    /** The app's own block path (users/{me}/blocked/{uid}), after the report went. */
    suspend fun block(t: ReportTarget): Boolean {
        val uid = t.personUid?.takeIf { it.isNotEmpty() } ?: return false
        return runCatching { people()?.setBlocked(uid, true) == true }.getOrDefault(false)
    }

    /** Mutes the chat for good, as the chat's own Mute does (mutedBy.<me>). */
    suspend fun mute(t: ReportTarget): Boolean =
        t.cid.isNotEmpty() && runCatching { people()?.setMuted(t.cid, true) == true }.getOrDefault(false)
}

/** The one sheet's state: any screen asks, the host at the app's root shows it. */
object ReportFlow {
    private val _current = MutableStateFlow<ReportTarget?>(null)
    val current: StateFlow<ReportTarget?> = _current

    fun ask(t: ReportTarget) { _current.value = t }
    fun done() { _current.value = null }
}

/** One of my own reports, as `myReports` gives it (contract v2). */
data class MyReport(
    val id: String,
    val kind: String,
    val category: String,
    val sub: String?,
    val createdAtMs: Long,
    val status: String,
    val targetName: String,
    val targetThumb: String?,
)

/** Settings > Privacy > My reports: the list, 30 at a time, newest first. */
object MyReports {
    enum class Status(val label: String) { RECEIVED("Received"), ACTION_TAKEN("Action taken"), NO_VIOLATION("No rule broken") }

    fun status(raw: String?): Status = when (raw) {
        "removed", "banned", "warned", "action_taken", "actioned" -> Status.ACTION_TAKEN
        "no_violation" -> Status.NO_VIOLATION
        else -> Status.RECEIVED
    }

    /** What was reported, in words: "Account", "Story", ... with its name when known. */
    fun what(r: MyReport): String {
        val noun = when (r.kind) {
            "user" -> "Account"; "message" -> "Message"; "story" -> "Story"; "channel" -> "Channel"
            "group" -> "Group"; "post" -> "Post"; "reply" -> "Reply"; else -> "Report"
        }
        return if (r.targetName.isBlank()) noun else "$noun \u00B7 ${r.targetName.trim()}"
    }

    /** Milliseconds from a number (ms or s), a {_seconds} map or an ISO string; 0 when unreadable. */
    fun millis(v: Any?): Long = when (v) {
        is Number -> v.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }
        is Map<*, *> -> ((v["_seconds"] ?: v["seconds"]) as? Number)?.toLong()?.times(1000) ?: 0L
        is String -> runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
        else -> 0L
    }

    fun parse(data: Any?): List<MyReport> {
        val items = (data as? Map<*, *>)?.get("items") as? List<*> ?: return emptyList()
        return items.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            MyReport(id, m["kind"] as? String ?: "", m["category"] as? String ?: "", (m["sub"] as? String)?.ifEmpty { null },
                millis(m["createdAt"]), m["status"] as? String ?: "open", m["targetName"] as? String ?: "",
                (m["targetThumb"] as? String)?.ifEmpty { null })
        }
    }

    /** Whether the server says there are older ones (`more`); without the flag, a full page means maybe. */
    fun more(data: Any?, got: Int): Boolean = ((data as? Map<*, *>)?.get("more") as? Boolean) ?: (got >= PAGE_SIZE)

    data class Page(val items: List<MyReport>, val more: Boolean)

    /** The one call: a page of my reports older than [before] (createdAt ms); null when it failed. */
    suspend fun page(before: Long? = null): Page? = runCatching {
        val args = if (before != null) mapOf("before" to before) else emptyMap<String, Any>()
        val r = com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
            .getHttpsCallable("myReports").withTimeout(20, java.util.concurrent.TimeUnit.SECONDS).call(args).await()
        val items = parse(r.getData())
        Page(items, more(r.getData(), items.size))
    }.getOrNull()

    const val PAGE_SIZE = 30
}
