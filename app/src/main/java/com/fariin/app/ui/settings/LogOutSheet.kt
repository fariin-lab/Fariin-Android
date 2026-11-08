package com.fariin.app.ui.settings

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
 * name, "Log out of @username?", one line, a red Log Out that spins and locks while the session
 * ends, and Cancel. Only this account is logged out; the app then shows the front door.
 *
 * Texts still waiting to send are wiped with the session: the line says how many and Cancel
 * becomes Wait (a send is tried now), as the old dialog did.
 */
@Composable
internal fun LogOutSheet(profile: MyProfile?, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    val c = Fariin.colors
    var busy by remember { mutableStateOf(false) }
    val unsent = remember { com.fariin.app.device.SessionEnd.unsentCount() }
    val warning = com.fariin.app.device.SessionEnd.unsentLine(unsent, afterRevoke = false)
    val wait = remember { booleanArrayOf(false) }
    val motion = rememberSheetMotion {
        if (wait[0]) com.fariin.app.AppLifecycle.reconnect()
        onDismiss()
    }
    sheetRestAt(motion, if (warning != null) 440.dp else 380.dp)
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
            Text(warning ?: "You can log back in any time.", fontSize = 15.sp, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            SheetPill(if (warning != null) "Log Out Anyway" else "Log Out", c.red, busy = busy,
                modifier = Modifier.padding(top = 24.dp)) {
                if (!busy) { busy = true; onSignOut() }
            }
        }
    }
}
