package com.fariin.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AccountDeletion
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.MyProfile
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.SheetMotion
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Delete Account (owner 2026-11-07, same as iOS): two sheets from the red row on the Account page.
 * 1 "Why are you leaving?", an optional single reason (Other opens a short text field);
 * 2 "Delete @username?", what happens, Download my data first, and the red Delete Account.
 * Success logs out straight to the front door with a toast giving the date; failure says why and
 * lets the button be tapped again.
 *
 * The request lives in this host, not in a sheet: a sheet swiped away while it runs does not cancel
 * it, and its answer still logs out (or shows the error as a toast). [onDone] runs once nothing is
 * left on screen and nothing is running.
 */
@Composable
internal fun DeleteAccountFlow(profile: MyProfile?, onDone: () -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(1) }           // 1 reason, 2 confirm, 0 gone
    var code by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val handle = profile?.handle.orEmpty()
    val fmt = remember { DateFormat.getDateInstance(DateFormat.LONG) }

    fun closed() { stage = 0; if (!busy) onDone() }

    fun delete() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val due = AccountDeletion.schedule(code, text)
                Toast.makeText(app, "Deletion scheduled for ${fmt.format(Date(due))}", Toast.LENGTH_LONG).show()
                onSignOut()
            } catch (e: Exception) {
                val msg = AuthRepository.messageFor(e)
                busy = false
                if (stage == 2) error = msg
                else { Toast.makeText(app, msg, Toast.LENGTH_LONG).show(); onDone() }
            }
        }
    }

    when (stage) {
        1 -> ReasonSheet(code, text, onPick = { code = if (code == it) null else it }, onText = { text = it.take(AccountDeletion.REASON_TEXT_MAX) },
            onContinue = { stage = 2 }, onClosed = { if (stage == 1) closed() })
        2 -> ConfirmSheet(handle, fmt.format(Date(AccountDeletion.defaultDue())), busy, error,
            onDownload = {
                scope.launch {
                    try { AccountReport.share(ctx) }
                    catch (e: Exception) { toast(ctx, e.message ?: "Could not load everything. Check your connection and try again.") }
                }
            },
            onDelete = { delete() }, onClosed = { closed() })
    }
}

@Composable
private fun ReasonSheet(code: String?, text: String, onPick: (String) -> Unit, onText: (String) -> Unit,
                        onContinue: () -> Unit, onClosed: () -> Unit) {
    val c = Fariin.colors
    val go = remember { booleanArrayOf(false) }
    // Continue opens the next sheet only after this one has gone, so it never opens under it.
    val motion = rememberSheetMotion { if (go[0]) onContinue() else onClosed() }
    sheetRestAt(motion, if (code == AccountDeletion.OTHER) 640.dp else 560.dp)
    FariinSheetDialog(motion, fill = c.bgSecondary, typing = true) { _ ->
        AccountSheetFrame(motion, closeEnabled = true) {
            Title("Why are you leaving?")
            Text("Optional. Only the reason is kept, not who gave it.", fontSize = 15.sp, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) {
                AccountDeletion.reasons.forEachIndexed { i, (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(key) }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                        Box(Modifier.width(12.dp))
                        if (code == key) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.blue, modifier = Modifier.size(20.dp))
                        else Box(Modifier.size(20.dp))
                    }
                    if (i < AccountDeletion.reasons.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (code == AccountDeletion.OTHER) {
                OutlinedTextField(text, onText, placeholder = { Text("Tell us more (optional)") }, maxLines = 4,
                    supportingText = { Text("${text.length}/${AccountDeletion.REASON_TEXT_MAX}", color = c.secondary) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            SheetPill("Continue", c.blue, modifier = Modifier.padding(top = 24.dp)) { go[0] = true; motion.close() }
            SheetCancel { motion.close() }
        }
    }
}

@Composable
private fun ConfirmSheet(handle: String, dueText: String, busy: Boolean, error: String?,
                         onDownload: () -> Unit, onDelete: () -> Unit, onClosed: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onClosed)
    sheetRestAt(motion, if (error != null) 520.dp else 480.dp)
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        AccountSheetFrame(motion, closeEnabled = !busy) {
            Title(if (handle.isEmpty()) "Delete your account?" else "Delete @$handle?")
            Column(Modifier.padding(top = 20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Line("Your account will be deleted on $dueText. Sign in before then to cancel.")
                Line("Your profile, photo and stories are removed.")
                Line("Messages you sent stay with the people who got them.")
            }
            Text("Download my data first", fontSize = 15.sp, color = c.blue,
                modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = !busy) { onDownload() }.padding(vertical = 6.dp))
            if (error != null) Text(error, fontSize = 15.sp, color = c.red, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            SheetPill("Delete Account", c.red, busy = busy, modifier = Modifier.padding(top = 20.dp)) { onDelete() }
            SheetCancel(enabled = !busy) { motion.close() }
        }
    }
}

/** Rests the sheet with about [height] showing, as tall as its content; a drag up gives the rest. */
@Composable
internal fun sheetRestAt(motion: SheetMotion, height: Dp) {
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    motion.mid = with(density) { windowH - statusTop - 10.dp.toPx() - height.toPx() - navBottom }.coerceAtLeast(0f)
}

/** The drag strip and the round X in the corner, as the app's other sheets. */
@Composable
internal fun AccountSheetFrame(motion: SheetMotion, closeEnabled: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 56.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
        Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
        FloatingIcon(Icons.Filled.Close, contentDescription = "Close", onClick = { if (closeEnabled) motion.close() },
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp), circle = true)
    }
}

@Composable
private fun Title(s: String) {
    Text(s, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Fariin.colors.label, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Line(s: String) {
    Text(s, fontSize = 16.sp, color = Fariin.colors.label, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun SheetPill(label: String, fill: Color, modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(fill)
        .clickable(enabled = !busy, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
internal fun SheetCancel(label: String = "Cancel", enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp).clip(CircleShape).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 17.sp, color = if (enabled) Fariin.colors.blue else Fariin.colors.tertiary)
    }
}
