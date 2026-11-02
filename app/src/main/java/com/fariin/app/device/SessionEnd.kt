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

    private fun wipe(c: Context) {
        NotifStore.clearAccount(c)
        NotificationPoster.clearAll(c)
        Push.clearAccount(c)
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
