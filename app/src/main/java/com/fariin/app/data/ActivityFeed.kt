package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Activity (owner 2026-11-09, shared contract fariin-activity/SPEC.md): replies and reactions to MY
 * comments on channel posts. Written only by the server (`users/{uid}/activity/{id}` and
 * `users/{uid}/activityMeta/summary`); the app reads them and calls `markActivityRead`.
 */
data class ActivityItem(
    val id: String,
    /** "reply" or "react". */
    val kind: String,
    val at: Double,
    val read: Boolean,
    val cid: String,
    val chTitle: String,
    val chPhoto: String,
    val mid: String,
    val post: String,
    val thumb: String,
    /** "photo", "video" or "". */
    val thumbKind: String,
    /** My comment: thread id, answer id ("" = the thread itself), its words. */
    val mineRid: String,
    val mineAid: String,
    val mineText: String,
    val fromUid: String,
    val fromName: String,
    val fromPhoto: String,
    /** Reply: their words, and where their reply is. */
    val text: String,
    val newRid: String,
    val newAid: String,
    /** React: the latest emoji, how many others, the comment's counts. */
    val emoji: String,
    val others: Int,
    val rx: Map<String, Int>,
) {
    val isReact get() = kind == "react"

    companion object {
        fun parse(id: String, d: Map<String, Any?>): ActivityItem? {
            val kind = d["kind"] as? String ?: return null
            if (kind != "reply" && kind != "react") return null
            val cid = d["cid"] as? String ?: return null
            val mid = d["mid"] as? String ?: return null
            fun m(k: String) = d[k] as? Map<*, *> ?: emptyMap<String, Any?>()
            fun Map<*, *>.s(k: String) = this[k] as? String ?: ""
            val mine = m("mine"); val from = m("from"); val nw = m("new")
            val rx = (d["rx"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val n = (v as? Number)?.toInt() ?: 0
                (k as? String)?.takeIf { n > 0 }?.let { it to n }
            }.toMap()
            return ActivityItem(
                id = id, kind = kind, at = Conversation.millis(d["at"]), read = d["read"] as? Boolean ?: true,
                cid = cid, chTitle = d["chTitle"] as? String ?: "", chPhoto = d["chPhoto"] as? String ?: "",
                mid = mid, post = d["post"] as? String ?: "", thumb = d["thumb"] as? String ?: "",
                thumbKind = d["thumbKind"] as? String ?: "",
                mineRid = mine.s("rid"), mineAid = mine.s("aid"), mineText = mine.s("t"),
                fromUid = from.s("u"), fromName = from.s("n"), fromPhoto = from.s("p"),
                text = d["text"] as? String ?: "", newRid = nw.s("rid"), newAid = nw.s("aid"),
                emoji = d["emoji"] as? String ?: "", others = (d["others"] as? Number)?.toInt() ?: 0, rx = rx,
            )
        }
    }
}

/** The chat list row's numbers (`activityMeta/summary`). */
data class ActivitySummary(val unread: Int, val kind: String, val name: String, val text: String, val emoji: String, val at: Double)

object ActivityRules {
    /** "Nan" or "Nan and 3 others". From the channel itself (from.u ""): the channel's name. */
    fun who(i: ActivityItem): String {
        val n = i.fromName.ifEmpty { i.chTitle.ifEmpty { "Someone" } }
        return if (i.isReact && i.others > 0) "$n and ${i.others} ${if (i.others == 1) "other" else "others"}" else n
    }

    /** "Your comment: …" or "Your reply: …" (mine was an answer). */
    fun mineLine(i: ActivityItem): String = (if (i.mineAid.isNotEmpty()) "Your reply: " else "Your comment: ") + i.mineText

    /** The chat list preview. */
    fun preview(s: ActivitySummary): String {
        val n = s.name.ifEmpty { "Someone" }
        return if (s.kind == "react") "$n reacted ${s.emoji} to your comment".replace("  ", " ")
        else "$n replied to your comment: ${s.text}"
    }

    /** Up to 3 pills, most used first. */
    fun pills(rx: Map<String, Int>): List<Pair<String, Int>> =
        rx.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }

    /** What the tap flashes: their reply for a reply, my comment for a reaction. */
    fun focus(i: ActivityItem): ReplyFocus? =
        if (i.isReact) PostReplyRules.focusOf(i.mid, i.mineRid, i.mineAid)
        else PostReplyRules.focusOf(i.mid, i.newRid.ifEmpty { i.mineRid }, i.newAid)
}

object ActivityFeed {
    private val _summary = MutableStateFlow<ActivitySummary?>(null)
    /** null until read, and while nothing is unread the row stays away anyway. */
    val summary: StateFlow<ActivitySummary?> = _summary
    private val _items = MutableStateFlow<List<ActivityItem>?>(null)
    val items: StateFlow<List<ActivityItem>?> = _items
    private val _itemsFresh = MutableStateFlow(false)
    /** The rows came from the server, not the phone's cache (what was unread is only known then). */
    val itemsFresh: StateFlow<Boolean> = _itemsFresh
    private var summaryReg: ListenerRegistration? = null
    private var itemsReg: ListenerRegistration? = null
    private var owner = ""

    private fun user(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid)

    /** Signed in: the summary, for the chat list row. */
    fun start(me: String) {
        if (me == owner && summaryReg != null) return
        stop()
        owner = me
        if (me.isEmpty()) return
        summaryReg = user(me).collection("activityMeta").document("summary").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val d = snap.data
            val last = d?.get("last") as? Map<*, *>
            _summary.value = if (d == null) null else ActivitySummary(
                unread = (d["unread"] as? Number)?.toInt() ?: 0,
                kind = last?.get("kind") as? String ?: "", name = last?.get("n") as? String ?: "",
                text = last?.get("text") as? String ?: "", emoji = last?.get("emoji") as? String ?: "",
                at = Conversation.millis(last?.get("at")))
        }
    }

    fun stop() {
        summaryReg?.remove(); summaryReg = null
        stopItems()
        owner = ""
        _summary.value = null
        _items.value = null
    }

    /** The page is open: its rows, newest first (100 at most; the server keeps 30 days). */
    fun startItems() {
        if (owner.isEmpty() || itemsReg != null) return
        _itemsFresh.value = false
        itemsReg = user(owner).collection("activity").orderBy("at", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null) return@addSnapshotListener
                _items.value = snap.documents.mapNotNull { ActivityItem.parse(it.id, it.data ?: return@mapNotNull null) }
                if (!snap.metadata.isFromCache) _itemsFresh.value = true
            }
    }

    fun stopItems() { itemsReg?.remove(); itemsReg = null }

    /** Everything read (no ids), or just these. The row on the chat list leaves at once. */
    suspend fun markRead(ids: List<String>? = null): Boolean {
        if (ids == null) _summary.value = _summary.value?.copy(unread = 0)
        return runCatching {
            FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("markActivityRead")
                .call(if (ids == null) emptyMap<String, Any>() else mapOf("ids" to ids)).await()
        }.isSuccess
    }
}
