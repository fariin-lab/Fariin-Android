package com.fariin.app.ui.channels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.fariin.app.R
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.components.GlassAlertAction
import com.fariin.app.ui.components.GlassAlertStyle
import com.fariin.app.ui.components.underBar
import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Channel API key (owner 2026-11-10, iOS ChannelApiKeyView). A secret key that lets a program (the
 * owner's own AI script) read, post, edit, delete and pin in THIS channel, any time.
 *
 * Only the accounts in [MAKERS] see the row, and only in a channel they own. The server checks the
 * same (functions-groups `channelApiKey`), so this list only hides the row. The key is shown ONCE
 * when made: the server keeps only its hash and the last 4 characters.
 */
object ChannelApiKey {
    val MAKERS = setOf(
        "SVf0zxDVZzO8oLbGvM150QWgIWd2",   // @v
        "a74hUgOoNYOmxMDoNCWI4nO81xH3",   // @kream
        // TEMPORARY (owner 2026-11-10): @roodo, the test Pixel's account. REMOVE after the test.
        "xOAiiGNxpff1r2Clfrru2gdrUW13",   // @roodo
    )
    const val ENDPOINT = "https://me-central1-kulan-2ef85.cloudfunctions.net/channelApi"

    fun shows(isOwner: Boolean, me: String) = isOwner && me in MAKERS

    suspend fun call(cid: String, action: String): Map<*, *> =
        (FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("channelApiKey")
            .withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid, "action" to action)).await().getData() as? Map<*, *>)
            ?: emptyMap<String, Any>()
}

@Composable
fun ChannelApiKeyScreen(cid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var hasKey by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var createdAt by remember { mutableStateOf<Long?>(null) }
    var freshKey by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    /** Which action is running, so only that row shows the spinner. */
    var running by remember { mutableStateOf<String?>(null) }

    fun errText(e: Exception) = (e.message ?: "").ifBlank { "Something went wrong. Try again." }

    LaunchedEffect(cid) {
        try {
            val d = ChannelApiKey.call(cid, "status")
            hasKey = d["hasKey"] as? Boolean ?: false
            hint = d["hint"] as? String
            createdAt = (d["createdAt"] as? Number)?.toLong()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = errText(e)
        }
        loading = false
    }

    fun run(action: String) {
        busy = true
        running = action
        scope.launch {
            try {
                val d = ChannelApiKey.call(cid, action)
                val k = d["key"] as? String
                if (action == "create" && k != null) {
                    freshKey = k; copied = false; hasKey = true
                    hint = d["hint"] as? String; createdAt = System.currentTimeMillis()
                } else {
                    freshKey = null; hasKey = false; hint = null; createdAt = null
                }
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = errText(e)
            } finally { busy = false; running = null }
        }
    }

    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    }
    val scroll = rememberScrollState()
    BarPage("API Key", onBack, under = underBar(scroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = top).navigationBarsPadding()) {
            // 1. What this is.
            Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(c.blue), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_sf_key), null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Text("Channel API", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 12.dp))
                Text("Let your own program read and post in this channel, any time.", fontSize = 15.sp, color = c.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }

            // 2. The key.
            SettingsSection(header = "Key", footer = when {
                freshKey != null -> "Copy it now. Fariin keeps only the last 4 characters, so it can't be shown again."
                hasKey -> "New Key stops the old key at once. Revoke stops it with no new one."
                else -> "Only you can see this page. The key works for this channel only."
            }) {
                val k = freshKey
                val state = when { loading -> 0; k != null -> 1; hasKey -> 2; else -> 3 }
                androidx.compose.animation.AnimatedContent(state, label = "apiKeyState",
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, delayMillis = 80))
                            + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(240, delayMillis = 80), initialScale = 0.96f))
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)))
                            .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f) })
                    }) { st ->
                Column {
                when (st) {
                    0 -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    1 -> if (k != null) {
                        SelectionContainer {
                            Text(k, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.label,
                                modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp))
                                    .background(c.fieldFill).padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                        SettingsRow(if (copied) "Copied" else "Copy Key",
                            icon = painterResource(if (copied) R.drawable.ic_sf_checkmark else R.drawable.ic_sf_doc_on_doc),
                            titleColor = c.blue, trailing = Trailing.NONE) { copy("API key", k); copied = true }
                    }
                    2 -> SettingsRow("Active", icon = painterResource(R.drawable.ic_sf_key), value = "••••${hint ?: ""}",
                        subtitle = createdAt?.let { "Created " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                        trailing = Trailing.NONE,
                        accessory = { LiveDot() })
                    else -> SettingsRow("No key yet", icon = painterResource(R.drawable.ic_sf_key), titleColor = c.secondary, trailing = Trailing.NONE)
                }
                }
                }
                if (!loading) {
                    SettingsRow(if (hasKey) "New Key" else "Create Key",
                        icon = painterResource(if (hasKey) R.drawable.ic_sf_arrow_clockwise else R.drawable.ic_plus_ios),
                        titleColor = if (busy) c.secondary else c.blue, trailing = Trailing.NONE, last = !hasKey,
                        accessory = { if (running == "create") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.secondary) }) {
                        if (busy) return@SettingsRow
                        if (hasKey) confirmNew = true else run("create")
                    }
                    androidx.compose.animation.AnimatedVisibility(hasKey,
                        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
                        SettingsRow("Revoke Key", icon = painterResource(R.drawable.ic_sf_trash),
                            titleColor = if (busy) c.secondary else c.red, trailing = Trailing.NONE, last = true,
                            accessory = { if (running == "revoke") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.secondary) }) {
                            if (!busy) confirmRevoke = true
                        }
                    }
                }
            }

            if (!loading) {
                // 3. How to connect.
                var addressCopied by remember { mutableStateOf(false) }
                SettingsSection(header = "Connect") {
                    SettingsRow("Address", icon = painterResource(R.drawable.ic_sf_link), subtitle = ChannelApiKey.ENDPOINT, trailing = Trailing.NONE,
                        accessory = {
                            Icon(painterResource(if (addressCopied) R.drawable.ic_sf_checkmark else R.drawable.ic_sf_doc_on_doc), "Copy",
                                tint = c.blue, modifier = Modifier.size(20.dp))
                        }) { copy("Address", ChannelApiKey.ENDPOINT); addressCopied = true }
                    SettingsRow("Key goes in header", icon = painterResource(R.drawable.ic_sf_lock), value = "X-Api-Key",
                        trailing = Trailing.NONE, last = true)
                }

                // 4. What the key can do: the plain words, and the action as the program sends it.
                SettingsSection(header = "What it can do", footer = "It can't change the channel's settings, admins or owner.") {
                    ApiAction(R.drawable.ic_sf_doc_text, "Read posts", "read")
                    ApiAction(R.drawable.ic_sf_textformat, "Post text", "post + text")
                    ApiAction(R.drawable.ic_sf_photo, "Post a photo", "post + image or imageUrl")
                    ApiAction(R.drawable.ic_sf_pencil, "Edit a post", "edit + id + text")
                    ApiAction(R.drawable.ic_sf_trash, "Delete a post", "delete + id")
                    ApiAction(R.drawable.ic_sf_pin, "Pin or unpin", "pin / unpin + id", last = true)
                }

                // 5. One example to copy.
                val example = "POST ${ChannelApiKey.ENDPOINT}\nX-Api-Key: <your key>\nContent-Type: application/json\n\n{\"action\":\"post\",\"text\":\"Hello\"}"
                var exampleCopied by remember { mutableStateOf(false) }
                SettingsSection(header = "Example", footer = "Up to 20 changes a minute and 500 a day. Photos: JPEG or PNG, up to 10 MB.") {
                    Text(example, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.label,
                        modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp))
                            .background(c.fieldFill).padding(horizontal = 12.dp, vertical = 10.dp))
                    SettingsRow(if (exampleCopied) "Copied" else "Copy Example",
                        icon = painterResource(if (exampleCopied) R.drawable.ic_sf_checkmark else R.drawable.ic_sf_doc_on_doc),
                        titleColor = c.blue, trailing = Trailing.NONE, last = true) { copy("Example", example); exampleCopied = true }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmNew) GlassAlert(onDismissRequest = { confirmNew = false }, title = "Make a new key?",
        message = "The old key stops working right away. Any program using it must get the new one.",
        actions = listOf(
            GlassAlertAction("New Key", GlassAlertStyle.Red) { confirmNew = false; run("create") },
            GlassAlertAction("Cancel", GlassAlertStyle.Bold) { confirmNew = false },
        ))
    if (confirmRevoke) GlassAlert(onDismissRequest = { confirmRevoke = false }, title = "Revoke this key?",
        message = "Programs using it can no longer read or post in this channel.",
        actions = listOf(
            GlassAlertAction("Revoke", GlassAlertStyle.Red) { confirmRevoke = false; run("revoke") },
            GlassAlertAction("Cancel", GlassAlertStyle.Bold) { confirmRevoke = false },
        ))
    error?.let { msg ->
        GlassAlert(onDismissRequest = { error = null }, title = "Couldn't do that", message = msg,
            actions = listOf(GlassAlertAction("OK", GlassAlertStyle.Bold) { error = null }))
    }
}

/** One thing the key can do: the plain words, and the action as the program sends it. */
@Composable
private fun ApiAction(icon: Int, title: String, action: String, last: Boolean = false) =
    SettingsRow(title, icon = painterResource(icon), subtitle = action, trailing = Trailing.NONE, last = last)

/** The green "live" dot: grows in with a spring when a key turns active. */
@Composable
private fun LiveDot() {
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f)) }
    Box(Modifier.size(8.dp).graphicsLayer { scaleX = grow.value; scaleY = grow.value }.clip(CircleShape).background(Color(0xFF34C759)))
}
