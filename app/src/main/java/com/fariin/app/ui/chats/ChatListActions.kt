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
    /** iOS AppLimits.pinnedChatsMax (admin panel `pinned_chats_max`, 5 when it has none). */
    val PINNED_MAX: Int get() = com.fariin.app.data.AppLimits.pinnedChatsMax

    private fun doc(cid: String) = FirebaseFirestore.getInstance().collection("conversations").document(cid)

    private suspend fun merge(me: String, cid: String, fields: Map<String, Any>): Boolean {
        if (me == DemoChats.ME || cid.isEmpty()) return true
        return try { doc(cid).set(fields, SetOptions.merge()).await(); true } catch (_: Exception) { false }
    }

    /** A new pin goes to the top: its rank is now, above every older pin (iOS pinOrder). */
    suspend fun setPinned(me: String, c: Conversation, on: Boolean): Boolean =
        if (c.isFollower(me)) (me == DemoChats.ME || com.fariin.app.channel.ChannelService.setFlag(c.id, "pinned", on))
        else if (on) merge(me, c.id, mapOf("pinnedBy" to mapOf(me to true), "pinOrder" to mapOf(me to System.currentTimeMillis().toDouble())))
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

    suspend fun setUnread(me: String, c: Conversation, on: Boolean): Boolean =
        if (on) merge(me, c.id, mapOf("markedUnread" to mapOf(me to true)))
        else merge(me, c.id, mapOf("markedUnread" to mapOf(me to false), "unreadCount" to mapOf(me to 0)))

    /** Delete for me: the chat empties and leaves my list until something new arrives (iOS deleteForMe). */
    suspend fun deleteForMe(me: String, c: Conversation): Boolean =
        merge(me, c.id, mapOf("clearedAt" to mapOf(me to System.currentTimeMillis().toDouble()), "unreadCount" to mapOf(me to 0)))

    /** iOS ChatService.clearMyMessages: every message I sent in [cid], deleted, 450 a batch. */
    suspend fun clearMyMessages(me: String, cid: String): Boolean = runCatching {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val docs = db.collection("conversations").document(cid).collection("messages")
            .whereEqualTo("authorId", me).get().await().documents
        docs.chunked(450).forEach { part -> db.batch().also { b -> part.forEach { b.delete(it.reference) } }.commit().await() }
    }.isSuccess

    /** True when pinning [adding] more would pass the cap; says so in a snackbar. */
    fun pinsFull(me: String, all: List<Conversation>, adding: Int): Boolean {
        val pinned = all.count { it.isPinned(me) }
        if (pinned + adding <= PINNED_MAX) return false
        Snack.show("You can pin up to $PINNED_MAX chats. Unpin one to pin this chat.")
        return true
    }

    /**
     * What Delete does to one chat, as iOS ChatService.deleteChats: a group or channel I am IN is
     * left (an owner hands it on, [com.fariin.app.data.GroupRules.leavePlan]); a channel I only
     * follow is unfollowed; anything else (a 1:1, a group I was already removed from) leaves my
     * list until something new arrives.
     */
    fun deleteKind(me: String, c: Conversation): DeleteKind = when {
        c.isFollower(me) -> DeleteKind.UNFOLLOW
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
                DeleteKind.UNFOLLOW, DeleteKind.LEAVE_CHANNEL ->
                    DeletePlan("Leave Channel?", "Are you sure you want to leave $name?", "Leave Channel")
                DeleteKind.LEAVE_GROUP ->
                    DeletePlan("Delete Group?", "Are you sure you want to leave $name?", "Delete Group")
                DeleteKind.HIDE ->
                    if (one.isGroup) DeletePlan("Delete this chat?", "This removes the chat from your list. It comes back if you get a new message.", "Delete Chat")
                    else DeletePlan("Delete this chat?", "Delete for Me removes the chat from your list. The other person keeps their copy, unless you also delete your messages for everyone.", "Delete for Me")
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
        com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.crypto).leaveGroup(cid)
    }

    /** Delete [c] by its [deleteKind] at once (the multi-select path, as iOS deleteChats: no Undo). */
    suspend fun deleteByKind(me: String, c: Conversation): Boolean = when (deleteKind(me, c)) {
        DeleteKind.HIDE -> deleteForMe(me, c)
        DeleteKind.UNFOLLOW -> me == DemoChats.ME || runCatching { com.fariin.app.channel.ChannelService.unfollow(c.id) }.isSuccess
        DeleteKind.LEAVE_GROUP, DeleteKind.LEAVE_CHANNEL -> runCatching { leave(me, c.id) }.isSuccess
    }
}

enum class DeleteKind { HIDE, UNFOLLOW, LEAVE_GROUP, LEAVE_CHANNEL }

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
