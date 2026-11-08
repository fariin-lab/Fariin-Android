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
)

/** The words and rules of the sheet, kept here so they can be tested. */
object ReportRules {
    const val NOTE_MAX = 500

    private fun shown(name: String?) = name?.trim()?.takeIf { it.isNotEmpty() }

    fun header(kind: ReportKind) = "Why are you reporting this ${kind.noun}?"
    fun privacy(name: String?) = "Reports are private. ${shown(name) ?: "They"} won't know it was you."
    fun confirmTitle(kind: ReportKind) = "Report this ${kind.noun}?"

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
    fun request(t: ReportTarget, reason: ReportReason, note: String, block: Boolean): Map<String, Any> {
        val m = linkedMapOf<String, Any>("kind" to t.kind.wire, "category" to reason.key, "block" to block)
        if (note.isNotEmpty()) m["note"] = note
        fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) m[k] = v }
        when (t.kind) {
            ReportKind.ACCOUNT -> { put("id", t.personUid); put("cid", t.cid) }
            ReportKind.STORY -> { put("id", t.storyId); put("uid", t.personUid) }
            ReportKind.CHANNEL, ReportKind.GROUP -> put("cid", t.cid)
            ReportKind.POST, ReportKind.MESSAGE -> { put("cid", t.cid); put("mid", t.messageId); put("uid", t.personUid) }
            ReportKind.REPLY -> { put("cid", t.cid); put("mid", t.messageId); put("rid", t.replyId); put("aid", t.answerId) }
        }
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
    suspend fun send(t: ReportTarget, reason: ReportReason, note: String, block: Boolean): String? {
        try {
            com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable("reportContent").withTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .call(ReportRules.request(t, reason, note, block)).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReportRules.errorText((e as? com.google.firebase.functions.FirebaseFunctionsException)?.code?.name)
        }
        val uid = t.personUid
        if (block && !uid.isNullOrEmpty()) {
            val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            // The report went; a failed block is said on its own and does not undo it.
            val ok = me != null && runCatching { FirestorePeople(me, CryptoHub.crypto).setBlocked(uid, true) }.getOrDefault(false)
            if (!ok) com.fariin.app.ui.components.Snack.show("Reported, but couldn't block. Try again from their profile.")
        }
        return null
    }
}

/** The one sheet's state: any screen asks, the host at the app's root shows it. */
object ReportFlow {
    private val _current = MutableStateFlow<ReportTarget?>(null)
    val current: StateFlow<ReportTarget?> = _current

    fun ask(t: ReportTarget) { _current.value = t }
    fun done() { _current.value = null }
}
