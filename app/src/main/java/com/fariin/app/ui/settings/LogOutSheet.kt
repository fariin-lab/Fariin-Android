package com.fariin.app.ui.settings

import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.MyProfile
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.theme.Fariin

/**
 * Log out of this account (owner 2026-11-07): a sheet as tall as its content, with the photo and
 * name, "Log out of @username?", one line, and a red Log Out that closes the sheet and then ends
 * the session (the "Logging out..." screen shows it), and Cancel. Only this account is logged out; the app then shows the front door.
 *
 * Texts still waiting to send are wiped with the session: the line says how many and Cancel
 * becomes Wait (a send is tried now), as the old dialog did.
 */
@Composable
internal fun LogOutSheet(profile: MyProfile?, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    val c = Fariin.colors
    var busy by remember { mutableStateOf(false) }
    val unsent = remember { com.fariin.app.device.SessionEnd.unsentCount() }
    val drafts = remember { com.fariin.app.local.Drafts.count() }
    val warning = com.fariin.app.device.SessionEnd.unsentLine(unsent, afterRevoke = false, drafts = drafts)
    val wait = remember { booleanArrayOf(false) }
    // Log Out closes the sheet first, then the session ends behind the "Logging out..." screen
    // (owner 2026-11-10, as iOS): the sheet's only job is the tap.
    val go = remember { booleanArrayOf(false) }
    // The phone keeps this account's message history after Log Out (device-storage plan): a
    // choice here removes it too (2026-11-12, both apps).
    var wipeHistory by remember { mutableStateOf(false) }
    val me = remember { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }
    val motion = rememberSheetMotion {
        if (wait[0]) com.fariin.app.AppLifecycle.reconnect()
        onDismiss()
        if (go[0]) {
            if (wipeHistory) me?.let { com.fariin.app.local.LocalDb.deleteFor(it) }
            onSignOut()
        }
    }
    sheetRestAt(motion, if (warning != null) 500.dp else 440.dp)
    val handle = profile?.handle.orEmpty()
    val name = profile?.name.orEmpty()
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        // The X (iOS 8d936377c, no Cancel): with texts still waiting it is the old Wait (a send is tried now).
        AccountSheetFrame(motion, closeEnabled = !busy, onClose = { wait[0] = warning != null; motion.close() }) {
            Avatar(profile?.photoUrl, 56.dp)
            if (name.isNotEmpty()) Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(if (handle.isEmpty()) "Log out?" else "Log out of @$handle?", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = c.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            Text(warning ?: "You can log back in any time. Your messages stay on this phone.", fontSize = 15.sp, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 16.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable(enabled = !busy) { wipeHistory = !wipeHistory }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (wipeHistory) c.red else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.5.dp, if (wipeHistory) c.red else c.secondary.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (wipeHistory) androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark),
                        null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                }
                Text("Delete messages from this phone", fontSize = 16.sp, color = c.label, modifier = Modifier.padding(start = 12.dp))
            }
            SheetPill(if (warning != null) "Log Out Anyway" else "Log Out", c.red, busy = busy,
                modifier = Modifier.padding(top = 24.dp)) {
                if (!busy) { busy = true; go[0] = true; motion.close() }
            }
        }
    }
}
