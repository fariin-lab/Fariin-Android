package com.fariin.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.backup.ChatTransfer
import com.fariin.app.data.QrRules
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val PAGE_TRANSFER = "chats:transfer"

/**
 * Settings > Chats > Transfer Chats (plan phase 4, same flow as iOS): the OLD phone shows a code,
 * the NEW phone (signed in to the same account) scans it. Show the code FIRST: signing in on the new
 * phone signs the old one out. Shown only while [ChatTransfer.ENABLED].
 */
@Composable
internal fun TransferChatsPage(pop: () -> Unit, startScanning: Boolean = false) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    var mode by remember { mutableStateOf(if (startScanning) "scan" else "") }
    var qr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) mode = "scan" else line = "Fariin needs the camera to scan the code."
    }

    when (mode) {
        "show" -> ScrollPage("Transfer Chats", { mode = "" }) {
            LaunchedEffect(Unit) {
                busy = true
                qr = runCatching { ChatTransfer.send(ctx, uid) }.getOrNull()
                busy = false
                if (qr == null) line = "Couldn't get your chats ready. Check your connection."
            }
            // Watch for the new phone taking it (the folder's header goes last).
            LaunchedEffect(qr) {
                val tid = qr?.let { ChatTransfer.parse(it)?.tid } ?: return@LaunchedEffect
                while (!done) { delay(3000); if (ChatTransfer.taken(uid, tid)) done = true }
            }
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    done -> Text("Done on your new phone.", fontSize = 17.sp, color = c.label)
                    qr != null -> {
                        QrMatrix(qr!!, Modifier.fillMaxWidth(0.8f))
                        Text("On your new phone, sign in to this account, then scan this code. Keep this screen open. The code works for one hour.",
                            fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                    }
                    busy -> Text("Getting your chats ready…", fontSize = 15.sp, color = c.secondary)
                }
                line?.let { Text(it, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center) }
            }
        }
        "scan" -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            if (!busy) CameraFeed(owner, onCamera = {}) { code ->
                if (busy || ChatTransfer.parse(code) == null) return@CameraFeed
                busy = true
                scope.launch {
                    line = ChatTransfer.receive(ctx, uid, code) { got, all -> line = "Receiving $got of $all" }
                    busy = false
                    if (line == null) { done = true; mode = "" }
                }
            }
            Text(line ?: "Scan the code on your old phone.", fontSize = 16.sp, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp))
        }
        else -> ScrollPage("Transfer Chats", pop) {
            SettingsSection(footer = "Moves your chats straight to your new phone, Android or iPhone. Photos and videos are not included yet.") {
                SettingsRow("Show Code on This Phone", trailing = Trailing.NONE, titleColor = c.accent) { line = null; done = false; mode = "show" }
                SettingsRow("Scan Code From Old Phone", trailing = Trailing.NONE, titleColor = c.accent, last = true) {
                    line = null
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) mode = "scan"
                    else camera.launch(Manifest.permission.CAMERA)
                }
            }
            if (done) SettingsSection { Text("Your chats are on this phone.", fontSize = 15.sp, color = c.label, modifier = Modifier.padding(16.dp)) }
            line?.let { ErrorNote(it) }
        }
    }
}

/** A plain square code, black on white, from the app's own QR matrix. */
@Composable
private fun QrMatrix(text: String, modifier: Modifier) {
    val m = remember(text) { QrRules.matrix(text) }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(16.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = m.size
            val cell = size.width / n
            for (y in 0 until n) for (x in 0 until n) if (m[y][x])
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
