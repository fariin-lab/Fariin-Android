package com.fariin.app.ui.settings

import com.fariin.app.ui.components.floating
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AccountDeletion
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.MyProfile
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.SheetMotion
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Delete Account (owner 2026-11-07, same as iOS): two sheets from the red row on the Account page.
 * 1 "We're sorry to see you go", one required reason (Other opens a short text field that must be filled);
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

    // iOS DeleteAccountView.verify: prove it is you with the account's own door before the server is
    // asked (it refuses an old sign-in: "For your security, sign in again", owner's Pixel 2026-11-08).
    // Apple and Google show their own sheet; a password-only account types it on this sheet.
    val authRepo = remember { AuthRepository() }
    val providers = remember { authRepo.reauthProviders }
    val needsPassword = "apple.com" !in providers && "google.com" !in providers && "password" in providers
    var password by remember { mutableStateOf("") }

    fun delete() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val act = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
                    .firstOrNull { it is android.app.Activity } as? android.app.Activity
                try {
                    when {
                        "apple.com" in providers && act != null -> authRepo.reauthApple(act)
                        "google.com" in providers && act != null -> authRepo.reauthGoogle(act)
                        needsPassword -> authRepo.reauthEmail(password)
                    }
                } catch (e: com.fariin.app.auth.SheetCancelled) {
                    busy = false; return@launch   // a closed sign-in sheet is a decision, not a failure
                } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                    busy = false; error = "That password is not right."; return@launch
                }
                val due = AccountDeletion.schedule(code, text)
                Toast.makeText(app, "Deletion scheduled for ${fmt.format(Date(due))}", Toast.LENGTH_LONG).show()
                // Nothing of this account is kept on the phone (iOS wipes it at once here).
                com.fariin.app.device.SessionEnd.forDeletion = true
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
        1 -> ReasonSheet(code, text, onPick = { code = it }, onText = { text = it.take(AccountDeletion.REASON_TEXT_MAX) },
            onContinue = { stage = 2 }, onClosed = { if (stage == 1) closed() })
        2 -> ConfirmSheet(handle, fmt.format(Date(AccountDeletion.defaultDue())), busy, error,
            password = if (needsPassword) password else null, onPassword = { password = it },
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
    sheetRestAt(motion, if (code == AccountDeletion.OTHER) 720.dp else 640.dp)
    // A reason is required (owner 2026-11-08, same as iOS); "Other" also needs its box filled.
    val ready = code != null && (code != AccountDeletion.OTHER || text.trim().isNotEmpty())
    val into = remember { BringIntoViewRequester() }
    var typing by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    // Each step of the keyboard coming up keeps the box and Continue in sight.
    LaunchedEffect(typing, ime) { if (typing && ime > 0) into.bringIntoView() }
    FariinSheetDialog(motion, fill = c.bgSecondary, typing = true) { _ ->
        AccountSheetFrame(motion, closeEnabled = true) {
            // No heart icon above it (iOS e04e926e4).
            Title("We're sorry to see you go")
            Text("Tell us what happened so we can do better. Your answer goes only to the Fariin team.", fontSize = 15.sp,
                color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).selectableGroup()) {
                AccountDeletion.reasons.forEachIndexed { i, (key, label) ->
                    val on = code == key
                    // The radio sits at the leading edge and is always there, so picking never moves the text.
                    Row(Modifier.fillMaxWidth().selectable(selected = on, role = Role.RadioButton) { onPick(key) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Radio(on)
                        Box(Modifier.width(12.dp))
                        Text(label, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                    }
                    if (i < AccountDeletion.reasons.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 50.dp))
                }
            }
            if (code == "abusive_users") {
                Text("You can also report someone from their profile, or block them, without leaving.", fontSize = 13.sp,
                    color = c.secondary, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp))
            }
            // The box, Continue and Cancel are one block that is brought above the keyboard while typing.
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(into)) {
                if (code == AccountDeletion.OTHER) {
                    OutlinedTextField(text, onText, placeholder = { Text("Tell us more") }, maxLines = 4,
                        supportingText = { Text("${text.length}/${AccountDeletion.REASON_TEXT_MAX}", color = c.secondary) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).onFocusChanged { f ->
                            typing = f.isFocused
                            if (f.isFocused) motion.expand()   // full height, so the keyboard has room under it
                        })
                }
                // No Cancel under it (iOS: the X at the top right closes, on both steps).
                SheetPill("Continue", c.accent, ink = c.onAccent, enabled = ready, modifier = Modifier.padding(top = 24.dp)) { go[0] = true; motion.close() }
            }
        }
    }
}

/** 22dp radio: an empty ring, or the accent ring with an inner dot when picked. */
@Composable
private fun Radio(on: Boolean) {
    val c = Fariin.colors
    Box(Modifier.size(22.dp).border(if (on) 2.dp else 1.5.dp, if (on) c.accent else c.tertiary, CircleShape),
        contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(11.dp).clip(CircleShape).background(c.accent))
    }
}

@Composable
private fun ConfirmSheet(handle: String, dueText: String, busy: Boolean, error: String?,
                         /** Non-null: a password-only account types its password here (iOS SecureField). */
                         password: String? = null, onPassword: (String) -> Unit = {},
                         onDownload: () -> Unit, onDelete: () -> Unit, onClosed: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion(onClosed)
    sheetRestAt(motion, (if (error != null) 520 else 480).dp + (if (password != null) 64 else 0).dp)
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
            if (password != null) androidx.compose.material3.OutlinedTextField(password, onPassword,
                placeholder = { Text("Password", color = c.secondary) }, singleLine = true, enabled = !busy,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            if (error != null) Text(error, fontSize = 15.sp, color = c.red, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            SheetPill("Delete Account", c.red, busy = busy, enabled = password == null || password.isNotEmpty(),
                modifier = Modifier.padding(top = 20.dp)) { onDelete() }
        }
    }
}

/**
 * A first guess of the sheet's height ([height]) so it can start opening; [AccountSheetFrame] then
 * measures the content and moves the stop to fit it exactly (owner 2026-11-07: empty band under Cancel).
 */
@Composable
internal fun sheetRestAt(motion: SheetMotion, height: Dp) {
    val stop = restStop(height.value)
    if (motion.mid <= 0f) motion.mid = stop
}

/** The middle stop that leaves [heightDp] of sheet showing above the navigation bar. */
@Composable
private fun restStop(heightDp: Float): Float {
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    return with(density) { windowH - statusTop - 10.dp.toPx() - heightDp.dp.toPx() - navBottom }.coerceAtLeast(0f)
}

/**
 * The drag strip over the content. No corner X: every sheet here already ends with Cancel, and the
 * owner wants one way out, not two (2026-11-07, same as the contact Block sheet).
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun AccountSheetFrame(motion: SheetMotion, closeEnabled: Boolean, onClose: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var contentDp by remember { mutableFloatStateOf(0f) }
    if (contentDp > 0f) { val stop = restStop(contentDp); SideEffect { motion.fitMid(stop) } }
    val scroll = rememberScrollState()
    // imePadding: with the keyboard up the scroll ends at its top, so a field and the button under it
    // can be scrolled (and brought) into view instead of sitting behind it (same as ReportSheet).
    // One fitted height, as iOS (.height(contentHeight)): a pull up no longer carries Log Out or Delete
    // Account to full screen with an empty page under the button (owner's Pixel 2026-11-08).
    androidx.compose.runtime.SideEffect { motion.midIsTop = true }
    Box(Modifier.fillMaxSize().imePadding()) {
        // A pull down anywhere while the page is at its top moves the sheet with the finger (iOS);
        // taps, and every other drag, stay the page's own (sheetDrag atTop, as ReportSheet).
        // Measured inside the scroll (unbounded), so this is the content's own height with its padding.
        Column(Modifier.fillMaxWidth().sheetDrag(motion, atTop = { scroll.value == 0 }).verticalScroll(scroll)
            .onSizeChanged { contentDp = with(density) { it.height.toDp().value } }
            // Starts under the X (16 top + 44): a centred title ran beneath it, "go" hidden (owner 2026-11-08).
            .padding(horizontal = 24.dp).padding(top = 66.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
        Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
        // iOS 8d936377c / f2137acb4: an X at the top right instead of a Cancel under the button.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp).size(44.dp)
            .floating(CircleShape)
            .clickable(enabled = closeEnabled, onClickLabel = "Close") { (onClose ?: { motion.close() })() }
            .graphicsLayer { alpha = if (closeEnabled) 1f else 0.4f },
            contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Fariin.colors.label, modifier = Modifier.size(18.dp))
        }
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
internal fun SheetPill(label: String, fill: Color, modifier: Modifier = Modifier, busy: Boolean = false,
                       ink: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    // Disabled = the same capsule, fill and label together, at 35%. The layer is always in the chain
    // (only its alpha changes) and sits before the fill, so it fades the whole capsule, not the label
    // alone, and switching enabled never adds or drops a layer under a drawn fill (owner 2026-11-08:
    // the enabled Continue drew as a white capsule with white text on the Pixel, light mode).
    val fade = if (enabled || busy) 1f else 0.35f
    Box(modifier.fillMaxWidth().height(52.dp).graphicsLayer { alpha = fade }.clip(CircleShape).background(fill)
        .clickable(enabled = enabled && !busy, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
internal fun SheetCancel(label: String = "Cancel", enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp).clip(CircleShape).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center) {
        // Same Cancel as the contact and group sheets (PersonConfirmSheet): label colour, semibold.
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Fariin.colors.label else Fariin.colors.tertiary)
    }
}
