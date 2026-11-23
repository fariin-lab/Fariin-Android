package com.fariin.app.ui.chats

import androidx.compose.runtime.collectAsState
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.data.Conversation
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.debug.DemoChats
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * The chat list's own writes, field for field as iOS ChatService (setPinned, setArchived,
 * setMuted, markUnread, deleteForMe), for the selection bar and the overflow menu. Every write is
 * on MY key of the shared conversation doc, so nothing here touches the other person's state.
 * The debug demo (made-up chats) never writes.
 */
object ChatListActions {
    /** iOS AppLimits.pinnedChatsMax (admin panel `pinned_chats_max`, 5 when it has none); with Fariin Plus
     *  visible and morePins "on", the larger of that and plusState limits.pinnedChats. */
    val PINNED_MAX: Int get() {
        val free = com.fariin.app.data.AppLimits.pinnedChatsMax
        val plus = com.fariin.app.data.PlusStore
        val more = plus.pinnedChats
        return if (plus.visible && plus.state("morePins") == com.fariin.app.data.PlusStore.State.ON && more != null) maxOf(free, more) else free
    }

    private fun doc(cid: String) = com.fariin.app.Fb.db.collection("conversations").document(cid)

    private suspend fun merge(me: String, cid: String, fields: Map<String, Any>): Boolean {
        if (me == DemoChats.ME || cid.isEmpty()) return true
        return try { doc(cid).set(fields, SetOptions.merge()).await(); true } catch (_: Exception) { false }
    }

    /**
     * A new pin goes to the top (iOS setPinned): its rank is now, or one above the highest pin in
     * [chats] when that one is higher (a pin moved by hand on my other device can be).
     */
    suspend fun setPinned(me: String, c: Conversation, on: Boolean, chats: List<Conversation> = emptyList()): Boolean =
        if (c.isFollower(me)) (me == DemoChats.ME || com.fariin.app.channel.ChannelService.setFlag(c.id, "pinned", on))
        else if (on) {
            val top = chats.filter { it.id != c.id && it.isPinned(me) }.maxOfOrNull { it.pinRank(me) } ?: 0.0
            merge(me, c.id, mapOf("pinnedBy" to mapOf(me to true), "pinOrder" to mapOf(me to maxOf(top + 1, System.currentTimeMillis().toDouble()))))
        }
        else merge(me, c.id, mapOf("pinnedBy" to mapOf(me to false)))

    suspend fun setArchived(me: String, c: Conversation, on: Boolean): Boolean =
        if (c.isFollower(me)) (me == DemoChats.ME || com.fariin.app.channel.ChannelService.setFlag(c.id, "archived", on))
        else merge(me, c.id, mapOf("archivedBy" to mapOf(me to on)))

    suspend fun setMuted(me: String, c: Conversation, on: Boolean): Boolean =
        setMute(me, c, if (on) com.fariin.app.data.GroupInfoCircles.FOREVER else 0.0)

    /**
     * iOS ChatService.setMute: mute until [until] epoch ms (0 = unmute, far future = Always), the
     * same `mutedBy.<me>` the info pages write. A channel I follow keeps its own entry, where Always
     * is stored as 0 = no end ([MuteFor.channelEntry]).
     */
    suspend fun setMute(me: String, c: Conversation, until: Double): Boolean {
        if (c.isFollower(me)) {
            if (me == DemoChats.ME) return true
            val (on, end) = MuteFor.channelEntry(until, System.currentTimeMillis().toDouble())
            return com.fariin.app.channel.ChannelService.setMuted(c.id, on, end)
        }
        return merge(me, c.id, mapOf("mutedBy" to mapOf(me to until)))
    }

    /**
     * A channel I only follow keeps its read mark on my follow entry (iOS ChatService.resetUnread /
     * markUnread): read = every post, or one post back for Mark as Unread. The channel's own
     * document is refused to a follower.
     */
    /** The shade copy of a chat goes with it (K-31) or once it is read (K-75). */
    private val appCtx get() = com.google.firebase.FirebaseApp.getInstance().applicationContext

    suspend fun setUnread(me: String, c: Conversation, on: Boolean): Boolean {
        // Read from the list (one row or many): its notifications go, as opening the chat does (K-75).
        if (!on) runCatching { com.fariin.app.push.NotificationPoster.clearRead(appCtx, listOf(c.id)) }
        return setUnreadDoc(me, c, on)
    }

    private suspend fun setUnreadDoc(me: String, c: Conversation, on: Boolean): Boolean =
        if (c.isFollower(me)) (me == DemoChats.ME || com.fariin.app.channel.ChannelService.setRead(c.id, if (on) maxOf(0, c.postCount - 1) else c.postCount))
        else if (on) merge(me, c.id, mapOf("markedUnread" to mapOf(me to true)))
        else merge(me, c.id, buildMap {
            put("markedUnread", mapOf(me to false)); put("unreadCount", mapOf(me to 0))
            // A real read, as opening the chat (iOS resetUnread + markRead): the sender's ticks too,
            // unless my read receipts are off or I blocked them (then nothing tells them).
            if (com.fariin.app.data.PrivacySettings.readReceipts() && !c.isBlockedByMe(me))
                put("lastRead", mapOf(me to com.google.firebase.firestore.FieldValue.serverTimestamp()))
        })

    /** Delete for me: the chat empties and leaves my list until something new arrives (iOS deleteForMe). */
    suspend fun deleteForMe(me: String, c: Conversation): Boolean =
        deleteForMeDoc(me, c).also { clearShade(c.id) }

    private fun clearShade(cid: String) { runCatching { com.fariin.app.push.NotificationPoster.clearChat(appCtx, cid) } }

    private suspend fun deleteForMeDoc(me: String, c: Conversation): Boolean =
        // Server time, not the phone's (iOS): the mark is compared with server stamps on all my devices.
        merge(me, c.id, mapOf("clearedAt" to mapOf(me to com.fariin.app.data.ServerClock.nowMsSynced()), "unreadCount" to mapOf(me to 0)))
            // The phone's own copy of the chat goes too (device-storage phase 1).
            .also { ok -> if (ok) { com.fariin.app.local.LocalDb.clearChat(me, c.id); com.fariin.app.local.LocalCache.forgetThread(me, c.id) } }

    /** iOS ChatService.clearMyMessages: every message I sent in [cid], deleted, 450 a batch. */
    suspend fun clearMyMessages(me: String, cid: String): Boolean = runCatching {
        val db = com.fariin.app.Fb.db
        val mine = db.collection("conversations").document(cid).collection("messages").whereEqualTo("authorId", me)
        // F-2: a group that hides older history is read from my history start (index: authorId +
        // createdAt). Until that index is live the plain read is kept, so the clear still works.
        val from = if (com.fariin.app.crypto.Wire.isGroupCid(cid)) com.fariin.app.data.CryptoHub.listedConversation(cid)?.historyStart(me) else null
        val docs = (if (from == null) null else runCatching { com.fariin.app.data.GroupManage.startAt(mine, from).get().await() }.getOrNull())
            ?.documents ?: mine.get().await().documents
        docs.chunked(450).forEach { part -> db.batch().also { b -> part.forEach { b.delete(it.reference) } }.commit().await() }
    }.isSuccess

    /** True when pinning [adding] more would pass the cap; says so in a snackbar. */
    fun pinsFull(me: String, all: List<Conversation>, adding: Int): Boolean {
        // The Fariin chat counts when it is pinned and in the list (iOS ChatService pin cap).
        val official = com.fariin.app.data.OfficialChannel.view.value
        // Only pins the list shows count (iOS ChatService pin cap): not archived, cleared or an
        // incoming request, which would refuse a pin iOS allows.
        val pinned = all.count { it.isPinned(me) && !it.isArchived(me) && !it.isCleared(me) && !com.fariin.app.data.ChatList.isIncomingRequest(it, me) } +
            if (official.state.pinned && official.latest != null) 1 else 0
        if (pinned + adding <= PINNED_MAX) return false
        val plus = com.fariin.app.data.PlusStore
        val freeMax = com.fariin.app.data.AppLimits.pinnedChatsMax
        val morePins = plus.state("morePins")
        val plusMax = plus.plusPinLimit
        // Plus visible with a Plus number above the free one (iOS ChatService pin cap, 2026-11-20):
        // the limit sheet with the Free N / Plus M bar. Locked: its button opens the Plus page; a Plus
        // member at the Plus limit gets "Got It". An older server without the two numbers, or the
        // feature on without Plus: the plain line below.
        if (plus.visible && plus.pinnedChatsFree != null && plusMax != null && plusMax > freeMax &&
            (morePins == com.fariin.app.data.PlusStore.State.LOCKED || (morePins == com.fariin.app.data.PlusStore.State.ON && plus.isPlus))) {
            pinGate = PinGate.Limit(pinned, freeMax, plusMax)
            return true
        }
        // Plus is named only to an account that can see it, and only while it is "locked" for it;
        // then the way to Plus is on the line itself (iOS Learn More opens the morePins gate).
        val locked = plus.visible && morePins == com.fariin.app.data.PlusStore.State.LOCKED
        val more = if (locked) " Fariin Plus lets you pin more." else ""
        val msg = "You can pin up to $PINNED_MAX chats. Unpin one to pin this chat.$more"
        if (locked) Snack.show(msg, action = "Learn More", long = true) { pinGate = PinGate.Feature }
        else Snack.show(msg)
        return true
    }

    /** What a full pin cap opens over the chat list (drawn by [PinGateHost]). */
    sealed interface PinGate {
        /** The limit sheet: [count] pinned now, [free] without Plus, [plus] with it. */
        data class Limit(val count: Int, val free: Int, val plus: Int) : PinGate
        /** The locked morePins feature sheet. */
        data object Feature : PinGate
    }

    var pinGate by mutableStateOf<PinGate?>(null)

    /** Draws [pinGate] while it is set; the chat list hosts it once. */
    @Composable
    fun PinGateHost() {
        when (val g = pinGate) {
            null -> {}
            is PinGate.Limit -> com.fariin.app.ui.settings.PlusLimitSheet(g.count, g.free, g.plus) { pinGate = null }
            PinGate.Feature -> com.fariin.app.ui.settings.PlusGateSheet("morePins") { pinGate = null }
        }
    }

    /**
     * What Delete does to one chat, as iOS ChatService.deleteChats: a group or channel I am IN is
     * left (an owner hands it on, [com.fariin.app.data.GroupRules.leavePlan]); a channel I only
     * follow is unfollowed; anything else (a 1:1, a group I was already removed from) leaves my
     * list until something new arrives.
     */
    fun deleteKind(me: String, c: Conversation): DeleteKind = when {
        c.isFollower(me) -> DeleteKind.UNFOLLOW
        // My own channel is deleted for everyone, never left (iOS LeaveGroupAlert ownsChannel).
        c.isChannel && c.createdBy == me && c.users.contains(me) -> DeleteKind.DELETE_CHANNEL
        c.isGroup && c.users.contains(me) -> if (c.isChannel) DeleteKind.LEAVE_CHANNEL else DeleteKind.LEAVE_GROUP
        else -> DeleteKind.HIDE
    }

    /**
     * The confirm sheet's words for [targets]. Always one real action: iOS asks a single group
     * "Delete Group" / a single channel "Leave Channel" (LeaveGroupAlert), a single chat "Delete
     * Chat", and a mixed pick "Delete N chats?" with one Delete that handles each by its kind.
     */
    fun deletePlan(me: String, targets: List<Conversation>): DeletePlan {
        val kinds = targets.map { deleteKind(me, it) }
        val one = targets.singleOrNull()
        if (one != null) {
            val name = one.displayName(me)
            return when (kinds.single()) {
                DeleteKind.DELETE_CHANNEL ->
                    DeletePlan("Delete Channel", "This permanently deletes the channel and all its posts for everyone. Its followers lose it too. This cannot be undone.", "Delete for Everyone")
                DeleteKind.UNFOLLOW, DeleteKind.LEAVE_CHANNEL ->
                    DeletePlan("Leave Channel?", "Are you sure you want to leave $name?", "Leave Channel")
                DeleteKind.LEAVE_GROUP ->
                    DeletePlan("Delete Group?", "Are you sure you want to leave $name?", "Delete Group")
                // iOS MainShell: the same words for a person and a former group; the other person keeps theirs.
                DeleteKind.HIDE ->
                    DeletePlan("Delete this chat?", "This removes the chat from your list. It comes back if you get a new message.", "Delete Chat")
            }
        }
        val groups = kinds.count { it == DeleteKind.LEAVE_GROUP }
        val channels = kinds.count { it == DeleteKind.LEAVE_CHANNEL || it == DeleteKind.UNFOLLOW }
        fun count(n: Int, word: String) = if (n == 1) "the $word" else "the $n ${word}s"
        val leave = listOfNotNull(
            if (groups > 0) count(groups, "group") else null,
            if (channels > 0) count(channels, "channel") else null,
        )
        val body = if (leave.isEmpty()) "The chats are removed from your list. They come back if you get a new message."
            else "You will leave ${leave.joinToString(" and ")} you selected." +
                when (kinds.count { it == DeleteKind.HIDE }) {
                    0 -> ""
                    1 -> " The other chat is removed from your list."
                    else -> " The other chats are removed from your list."
                }
        return DeletePlan("Delete ${targets.size} chats?", body, "Delete")
    }

    /** Leave a group, or a channel I am a member of (owner or admin), with ChatService.leaveGroup. */
    suspend fun leave(me: String, cid: String) {
        if (me == DemoChats.ME) return
        clearShade(cid)
        com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.crypto).leaveGroup(cid)
    }

    /** Delete [c] by its [deleteKind] at once (the multi-select path, as iOS deleteChats: no Undo). */
    suspend fun deleteByKind(me: String, c: Conversation): Boolean {
        clearShade(c.id)
        return deleteByKindDoc(me, c)
    }

    private suspend fun deleteByKindDoc(me: String, c: Conversation): Boolean = when (deleteKind(me, c)) {
        DeleteKind.HIDE -> deleteForMe(me, c)
        DeleteKind.UNFOLLOW -> me == DemoChats.ME || runCatching { com.fariin.app.channel.ChannelService.unfollow(c.id) }.isSuccess
        DeleteKind.LEAVE_GROUP, DeleteKind.LEAVE_CHANNEL -> runCatching { leave(me, c.id) }.isSuccess
        // Several picked: an owned channel is kept (iOS deleteChats); the caller says so.
        DeleteKind.DELETE_CHANNEL -> true
    }

    /** iOS ChatService.deleteGroup for my own channel. */
    suspend fun deleteOwnChannel(cid: String) {
        com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
            .getHttpsCallable("deleteGroup").call(mapOf("cid" to cid)).await()
    }
}

enum class DeleteKind { HIDE, UNFOLLOW, LEAVE_GROUP, LEAVE_CHANNEL, DELETE_CHANNEL }

/** The delete sheet's title, sentence and its one action button (Cancel comes with the sheet). */
data class DeletePlan(val title: String, val body: String, val button: String)

/** A tab's title, or the connection's words while it is down (iOS connectionTitle on the Chats,
 *  Stories and Settings bars): "Waiting for network" / "Connecting". */
@Composable
fun rememberConnectionTitle(title: String): String {
    val link by com.fariin.app.data.ConnectionStatus.state.collectAsState()
    val online = rememberOnline()
    return com.fariin.app.data.ConnectionStatus.label(link) ?: if (!online) "Waiting for network" else title
}

/** False while the phone has no working network (the top bar then says "Waiting for network…"). */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember {
        mutableStateOf(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) {
                online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
        }
        try { cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb) } catch (_: Exception) {}
        onDispose { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    }
    return online
}
