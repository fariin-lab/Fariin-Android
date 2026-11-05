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
    /** iOS AppLimits.pinnedChatsMax default (admin panel `pinned_chats_max`). */
    const val PINNED_MAX = 3

    private fun doc(cid: String) = FirebaseFirestore.getInstance().collection("conversations").document(cid)

    private suspend fun merge(me: String, cid: String, fields: Map<String, Any>): Boolean {
        if (me == DemoChats.ME || cid.isEmpty()) return true
        return try { doc(cid).set(fields, SetOptions.merge()).await(); true } catch (_: Exception) { false }
    }

    /** A new pin goes to the top: its rank is now, above every older pin (iOS pinOrder). */
    suspend fun setPinned(me: String, c: Conversation, on: Boolean): Boolean =
        if (on) merge(me, c.id, mapOf("pinnedBy" to mapOf(me to true), "pinOrder" to mapOf(me to System.currentTimeMillis().toDouble())))
        else merge(me, c.id, mapOf("pinnedBy" to mapOf(me to false)))

    suspend fun setArchived(me: String, c: Conversation, on: Boolean): Boolean =
        merge(me, c.id, mapOf("archivedBy" to mapOf(me to on)))

    suspend fun setMuted(me: String, c: Conversation, on: Boolean): Boolean =
        if (c.isFollower(me)) (me == DemoChats.ME) || com.fariin.app.channel.ChannelService.setMuted(c.id, on)
        else merge(me, c.id, mapOf("mutedBy" to mapOf(me to if (on) 9_999_999_999_999.0 else 0.0)))

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
}

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
