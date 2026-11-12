package com.fariin.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.R
import com.fariin.app.backup.ChatTransfer
import com.fariin.app.data.QrRules
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val PAGE_TRANSFER = "chats:transfer"

/**
 * Settings > Chats > Transfer Chats, a guided move (owner 2026-11-12, after the reference app's
 * flow on his Pixel; our own look and words). The OLD phone: which phone you are moving to, then
 * three steps that tick off (getting chats ready with a percentage, the code to scan, moving), and
 * Done once the new phone has them. The NEW phone: scan, then the same steps from its side. The
 * code must be shown before signing in on the new phone (that signs this one out).
 */
@Composable
internal fun TransferChatsPage(pop: () -> Unit, startScanning: Boolean = false) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    // "" start, "send" old phone, "scan" camera, "receive" new phone working
    var mode by remember { mutableStateOf(if (startScanning) "scan" else "") }
    var target by remember { mutableStateOf("android") }
    var qr by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableFloatStateOf(0f) }
    var line by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var got by remember { mutableStateOf(0 to 0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) mode = "scan" else line = "Fariin needs the camera to scan the code."
    }
    fun reset() { job?.cancel(); job = null; qr = null; ready = 0f; done = false; line = null; got = 0 to 0; mode = "" }
    BackHandler(enabled = mode != "") { reset() }

    when (mode) {
        "send" -> StepsPage("Moving your chats", onCancel = { reset() }) {
            LaunchedEffect(Unit) {
                job = scope.launch {
                    qr = runCatching { ChatTransfer.send(ctx, uid) { ready = it } }.getOrNull()
                    if (qr == null) line = "Couldn't get your chats ready. Check your connection and try again."
                }
            }
            LaunchedEffect(qr) {
                val tid = qr?.let { ChatTransfer.parse(it)?.tid } ?: return@LaunchedEffect
                while (!done) { delay(3000); if (ChatTransfer.taken(uid, tid)) done = true }
            }
            val phone = if (target == "iphone") "iPhone" else "Android phone"
            Step(if (qr != null) StepState.DONE else StepState.NOW, "Getting your chats ready",
                if (qr == null) "${(ready * 100).toInt()}% · This can take a few minutes." else null)
            Step(when { done -> StepState.DONE; qr != null -> StepState.NOW; else -> StepState.LATER }, "Connect your new phone",
                if (qr != null && !done) "On your new $phone, install Fariin and sign in to this same account. Then open Settings > Chats > Transfer Chats > Scan Code, and point it at this code." else null)
            if (qr != null && !done) QrMatrix(qr!!, Modifier.padding(vertical = 8.dp).fillMaxWidth(0.72f))
            Step(if (done) StepState.DONE else StepState.LATER, "Moving chats", if (done) "Your chats are on your new phone." else null)
            line?.let { Text(it, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
        }
        "scan" -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            CameraFeed(owner, onCamera = {}) { code ->
                if (mode != "scan" || ChatTransfer.parse(code) == null) return@CameraFeed
                mode = "receive"
                job = scope.launch {
                    line = ChatTransfer.receive(ctx, uid, code) { a, b -> got = a to b }
                    done = line == null
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Scan the code on your old phone", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
                Text("On the old phone: Settings > Chats > Transfer Chats", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text("Cancel", fontSize = 17.sp, color = Color.White, modifier = Modifier.clip(CircleShape).clickable { reset() }.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
        "receive" -> StepsPage("Receiving your chats", onCancel = if (done) null else ({ reset() })) {
            val (a, b) = got
            Step(StepState.DONE, "Connected to your old phone", null)
            Step(if (done) StepState.DONE else StepState.NOW, "Receiving chats",
                if (!done && b > 0) "$a of $b parts · Keep both phones open." else null)
            Step(if (done) StepState.DONE else StepState.LATER, "Opening chats on this phone", if (done) "Your chats are here." else null)
            line?.let { Text(it, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
            if (done) {
                Spacer(Modifier.height(16.dp))
                PrimaryCapsule("Done") { reset(); pop() }
            }
        }
        else -> Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                com.fariin.app.ui.components.FloatingBack(pop, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(12.dp))
                PhonesArt()
                Text("Move chats to a new phone", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                Text("Which phone are you moving to?", fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Choice("Android", "Samsung, Pixel and others", target == "android") { target = "android" }
                Choice("iPhone", "Any iPhone", target == "iphone") { target = "iphone" }
                Spacer(Modifier.height(4.dp))
                Text("Photos and videos are not moved yet. Your messages are locked on the way, and Fariin can't read them.",
                    fontSize = 13.sp, color = c.tertiary, textAlign = TextAlign.Center)
                line?.let { Text(it, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center) }
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryCapsule("Continue") { line = null; mode = "send" }
                Text("This is my new phone", fontSize = 16.sp, color = c.accent, modifier = Modifier.padding(top = 6.dp)
                    .clip(CircleShape).clickable {
                        line = null
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) mode = "scan"
                        else camera.launch(Manifest.permission.CAMERA)
                    }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

private enum class StepState { DONE, NOW, LATER }

@Composable
private fun StepsPage(title: String, onCancel: (() -> Unit)?, content: @Composable () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            PhonesArt()
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            content()
        }
        if (onCancel != null) Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).border(1.dp, c.separator, CircleShape).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label) }
        }
    }
}

/** One step: a tick when done, a spinner while it runs, a grey dot when it waits. */
@Composable
private fun Step(state: StepState, title: String, detail: String?) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.DONE -> Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.onAccent, modifier = Modifier.size(14.dp))
                }
                StepState.NOW -> com.fariin.app.ui.components.FariinSpinner(size = 20.dp)
                StepState.LATER -> Box(Modifier.size(12.dp).clip(CircleShape).background(c.fieldFill))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = if (state == StepState.NOW) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StepState.LATER) c.secondary else c.label)
            detail?.let { Text(it, fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
        .border(if (on) 2.dp else 0.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) c.accent else c.secondary, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(11.dp).clip(CircleShape).background(c.accent))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(sub, fontSize = 14.sp, color = c.secondary)
        }
    }
}

@Composable
private fun PrimaryCapsule(text: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) }
}

/** Our own small drawing: two phones with the chats passing between them. */
@Composable
private fun PhonesArt() {
    val c = Fariin.colors
    Canvas(Modifier.width(200.dp).height(110.dp)) {
        val w = size.width; val h = size.height
        val pw = w * 0.26f; val ph = h * 0.86f; val top = (h - ph) / 2
        fun phone(x: Float, fill: Color) {
            drawRoundRect(fill, Offset(x, top), Size(pw, ph), CornerRadius(pw * 0.18f))
            drawRoundRect(c.label, Offset(x, top), Size(pw, ph), CornerRadius(pw * 0.18f), style = Stroke(3.dp.toPx()))
            drawLine(c.label, Offset(x + pw * 0.38f, top + ph - 8.dp.toPx()), Offset(x + pw * 0.62f, top + ph - 8.dp.toPx()), 3.dp.toPx())
        }
        phone(0f, c.fieldFill)
        phone(w - pw, c.accent)
        // Three bubbles on the way across.
        for (i in 0 until 3) {
            val cx = w * (0.38f + 0.12f * i); val cy = h * (0.42f + if (i == 1) -0.08f else 0.04f)
            drawRoundRect(c.card, Offset(cx - 11.dp.toPx(), cy - 7.dp.toPx()), Size(22.dp.toPx(), 14.dp.toPx()), CornerRadius(7.dp.toPx()))
            drawRoundRect(c.label, Offset(cx - 11.dp.toPx(), cy - 7.dp.toPx()), Size(22.dp.toPx(), 14.dp.toPx()), CornerRadius(7.dp.toPx()), style = Stroke(2.dp.toPx()))
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
