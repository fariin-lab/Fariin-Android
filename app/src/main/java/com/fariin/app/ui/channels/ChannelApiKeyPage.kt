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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = errText(e)
            } finally { busy = false }
        }
    }

    val scroll = rememberScrollState()
    BarPage("API Key", onBack, under = underBar(scroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(footer = if (freshKey != null)
                "Copy it now. Fariin keeps only the last 4 characters, so it can't be shown again."
            else "A program with this key can read, post, edit, delete and pin posts in this channel. It can't change the channel's settings, admins or owner.") {
                val k = freshKey
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                    }
                    k != null -> {
                        SelectionContainer {
                            Text(k, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.label,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp))
                        }
                        SettingsRow(if (copied) "Copied" else "Copy Key", titleColor = c.accent, trailing = Trailing.NONE, last = true) {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("API key", k))
                            copied = true
                        }
                    }
                    hasKey -> {
                        SettingsRow("Active key", value = "••••${hint ?: ""}", trailing = Trailing.NONE, last = createdAt == null)
                        createdAt?.let {
                            SettingsRow("Created", value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                                trailing = Trailing.NONE, last = true)
                        }
                    }
                    else -> SettingsRow("No key", titleColor = c.secondary, trailing = Trailing.NONE, last = true)
                }
            }
            if (!loading) {
                SettingsSection(footer = "New Key stops the old key at once. Revoke stops it with no new one.") {
                    SettingsRow(if (hasKey) "New Key" else "Create Key", titleColor = if (busy) c.secondary else c.accent,
                        trailing = Trailing.NONE, last = !hasKey) {
                        if (busy) return@SettingsRow
                        if (hasKey) confirmNew = true else run("create")
                    }
                    if (hasKey) SettingsRow("Revoke Key", titleColor = if (busy) c.secondary else c.red, trailing = Trailing.NONE, last = true) {
                        if (!busy) confirmRevoke = true
                    }
                }
                SettingsSection(header = "Address",
                    footer = "Send the key in the \"X-Api-Key\" header with a JSON body, for example {\"action\":\"post\",\"text\":\"Hello\"}. Actions: read, post, edit, delete, pin, unpin.") {
                    SelectionContainer {
                        Text(ChannelApiKey.ENDPOINT, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.label,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp))
                    }
                }
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
