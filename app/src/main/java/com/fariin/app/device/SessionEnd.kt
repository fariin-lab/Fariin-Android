package com.fariin.app.device

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fariin.app.push.NotificationPoster
import com.fariin.app.push.NotifStore
import com.fariin.app.push.Push
import com.google.firebase.auth.FirebaseAuth

/**
 * The two ways a session ends on this phone, in the iOS order (DeviceRegistry.removeThisDevice /
 * performRevokedSignOut, Push.unregister, then Auth, then the wipe). Everything that needs the
 * account's permission runs before Auth signs out.
 */
object SessionEnd {
    /** Log out tapped here: our own record goes, so it does not linger as a ghost in the list. */
    suspend fun signOut(c: Context) {
        DeviceRegistry.init(c)
        // iOS SessionWipe: "offline" goes out while the account can still write it.
        com.fariin.app.data.Presence.set(online = false, context = c)
        DeviceRegistry.removeThisDevice()
        Push.unregister(c)
        FirebaseAuth.getInstance().signOut()
        wipe(c)
    }

    /** The server removed this phone (another phone signed in, or it was signed out from one). */
    suspend fun revokedSignOut(c: Context) {
        DeviceRegistry.forgetAfterRevoke()
        Push.unregister(c)
        FirebaseAuth.getInstance().signOut()
        wipe(c)
    }

    /** "Delete it now" finished on the server (iOS RestoreAccountView): nothing to remove first. */
    suspend fun deletedNow(c: Context) {
        DeviceRegistry.stopWatching()
        Push.clearAccount(c)
        FirebaseAuth.getInstance().signOut()
        wipe(c)
    }

    private fun wipe(c: Context) {
        NotifStore.clearAccount(c)
        NotificationPoster.clearAll(c)
        com.fariin.app.system.FariinWidget.clear(c)
        Push.clearAccount(c)
        // The token outlives the account, so the Fariin chat's topics are left by hand.
        com.fariin.app.push.OfficialPushTopics.sync(c, muted = false, signedIn = false)
        // The phone's copies of this account's chats: opened messages, the list, drafts, unsent
        // texts, opened media and faces. None of it survives into the next account (iOS SessionWipe).
        com.fariin.app.local.LocalCache.wipe()
        com.fariin.app.local.Drafts.clear()
        com.fariin.app.local.SendQueue.wipe()
        com.fariin.app.call.CallLinks.reset()
        com.fariin.app.ui.call.LiveGroupCalls.stopAll()   // the live group-call watchers belong to the account
        com.fariin.app.media.ChatMedia.wipe(c)
        try { coil3.SingletonImageLoader.get(c).let { it.diskCache?.clear(); it.memoryCache?.clear() } } catch (_: Exception) {}
        com.fariin.app.ui.thread.DecodedPictures.clear()   // decoded chat photos held in memory
        com.fariin.app.data.SharedMediaSource.clear()      // All Media's opened messages
        com.fariin.app.data.ContactNames.clear()       // names I gave people belong to the account
        com.fariin.app.data.Glow.Cache.clear(c)       // the Glowing grid's last answer, every account's
        com.fariin.app.data.ChatLookStore.wipe(c)      // wallpapers, chat colours, wallpaper photos
        com.fariin.app.local.AtRest.forgetKey()
    }

}

/** The iOS alert after a remote sign-out, same wording, one OK. */
@Composable
fun SignedOutNotice(reason: DeviceRecord.Reason, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DeviceRecord.noticeTitle(reason)) },
        text = { Text(DeviceRecord.noticeMessage(reason)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
