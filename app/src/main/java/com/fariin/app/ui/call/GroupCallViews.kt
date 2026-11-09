package com.fariin.app.ui.call

import com.fariin.app.ui.components.GlassAlert
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fariin.app.call.CallLinkDefaults
import com.fariin.app.call.CallLinkKey
import com.fariin.app.call.CallLinks
import com.fariin.app.call.FaceCluster
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupCallWords
import com.fariin.app.call.GroupInvite
import com.fariin.app.call.LinkPeek
import com.fariin.app.call.LinkRequest
import com.fariin.app.ui.components.Avatar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

private val SysGreen = Color(0xFF30D158)
private val SysRed = Color(0xFFFF453A)
private val Card = Color(0xFF1C1C1E)

// ====================================================================== the incoming group ring

/**
 * iOS IncomingGroupCallScreen: on black, the call's name and "Group call" / "Group video call" at
 * the top, the faces in one round 270 plate in the middle, a Mute pill (join with the mic off),
 * and at the foot one card: "<starter> is ringing you" with a green dot and the faces small beside
 * it, then Ignore and a green Join. System back is Ignore's quiet twin: it only closes the screen,
 * the notification keeps ringing until the minute is over.
 */
@Composable
fun IncomingGroupCallScreen(invite: GroupInvite, onIgnore: () -> Unit, onJoin: (muted: Boolean) -> Unit) {
    var muted by remember { mutableStateOf(false) }
    val starter = invite.faces.firstOrNull()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(invite.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (invite.video) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (invite.video) "Group video call" else "Group call", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            FacePlate(invite)
            Spacer(Modifier.weight(1f))
            Row(Modifier.height(48.dp).clip(CircleShape).background(if (muted) Color.White else Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape).clickable { muted = !muted }
                .semantics { contentDescription = if (muted) "Mute, on" else "Mute, off" }
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), null, tint = if (muted) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mute", color = if (muted) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xFF1C1C1C))) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SysGreen))
                    Spacer(Modifier.width(10.dp))
                    Text("${starter?.name ?: "Someone"} is ringing you", color = Color.White, fontSize = 17.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Row {
                        invite.faces.take(3).forEachIndexed { i, f ->
                            Box(Modifier.offset(x = (-10 * i).dp).border(2.dp, Color(0xFF1C1C1C), CircleShape)) { Avatar(f.photo, 30.dp) }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).height(54.dp).clip(CircleShape).background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickable(onClick = onIgnore), contentAlignment = Alignment.Center) {
                        Text("Ignore", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f).height(54.dp).clip(CircleShape).background(SysGreen).clickable { onJoin(muted) }, contentAlignment = Alignment.Center) {
                        Text("Join", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** iOS FaceCluster: up to four faces in one round plate, the starter large at the upper left. */
@Composable
private fun FacePlate(invite: GroupInvite) {
    val faces = invite.faces.take(4)
    val spots = FaceCluster.spots(faces.size)
    Box(Modifier.size(FaceCluster.PLATE.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        faces.forEachIndexed { i, f ->
            val s = spots[i]
            Box(Modifier.offset(s.x.dp, s.y.dp)) { Avatar(f.photo, s.size.dp) }
        }
    }
}

// ====================================================================== the pre-join screen

/**
 * iOS CallLobbyView, the screen before a link call: my own camera full screen (my photo when it is
 * off), the call's name with "Call link" and who is in it at the top, a camera and a mic button
 * (the mic starts OFF: you choose to be heard before you walk in), then Leave and Join in one bar.
 * Join reads "Ask to Join" when the creator lets people in one by one, spins while the join runs and
 * the screen stays up through "Waiting to be let in"; a refusal comes back as a short note over the
 * buttons and Join works again. The link is asked about on open and every 5 s; a dead link turns the
 * screen into "This call link is no longer valid" with one Close. Camera access is asked when the
 * screen opens (the moment the preview needs it); the mic when its button is turned on.
 */
@Composable
fun CallLobbyScreen(keyText: String, videoFromPath: Boolean, g: GroupCallUi, onLeave: () -> Unit, onJoin: (video: Boolean, mic: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val key = remember(keyText) { CallLinkKey.ofText(keyText) }
    var title by remember { mutableStateOf(CallLinkDefaults.NAME) }
    var voiceOnly by remember { mutableStateOf(!videoFromPath) }
    var cameraOn by remember { mutableStateOf(true) }
    var micOn by remember { mutableStateOf(true) }   // iOS 2026-11-08: you go in with your mic on
    var cameraDenied by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(granted(ctx, Manifest.permission.CAMERA)) }
    var released by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf<LinkPeek?>(null) }
    var gone by remember { mutableStateOf(key == null) }
    var starting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteAt by remember { mutableLongStateOf(0L) }
    var myPhoto by remember { mutableStateOf<String?>(null) }
    var passAt by remember { mutableLongStateOf(0L) }   // when the join pass was last asked for ahead of the tap
    LaunchedEffect(Unit) { GroupCall.warmJoin() }
    val locked = starting || g.connecting || g.waiting || g.joined
    val showsCamera = cameraOn && !voiceOnly && !cameraDenied
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) cameraReady = true else cameraDenied = true }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) micOn = true }

    fun flash(text: String) { note = text; noteAt = System.currentTimeMillis() }

    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me != null) myPhoto = try { FirebaseFirestore.getInstance().collection("users").document(me).get().await().getString("photoUrl") } catch (_: Exception) { null }
        // The link's own document first: revoked, expired or missing says so at once (its creator
        // keeps the server's say, a revoked link's call can still be running).
        val d = CallLinks.readDoc(k.roomId)
        val creator = d?.get("creatorUid") == me
        val expired = (d?.get("expiresAt") as? com.google.firebase.Timestamp)?.toDate()?.before(java.util.Date()) == true
        if (!creator && (d?.get("revoked") == true || expired)) { gone = true; return@LaunchedEffect }
        // iOS: the join pass ahead of the tap, asked for BEFORE the camera prompt (which can sit on
        // screen); only where the join needs no approval, and not when the server already said no.
        if (d != null) {
            val approval = d["restrictions"] == "adminApproval"
            val serverSaysNo = peek?.let { it.gone || (it.approval && !it.iAmCreator) } ?: false
            if ((creator || !approval) && !serverSaysNo) { GroupCall.prefetchLinkPass(k.roomId); passAt = System.currentTimeMillis() }
        }
        val enc = d?.get("encName") as? String
        if (!enc.isNullOrEmpty()) k.decryptName(enc)?.takeIf { it.isNotEmpty() }?.let { title = it }
        if (d?.get("video") == false) voiceOnly = true
        if (!voiceOnly && !cameraReady) askCamera.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(key, locked) {
        val k = key ?: return@LaunchedEffect
        while (!gone && !locked) {
            CallLinks.peek(k)?.let { p ->
                if (!starting) {
                    peek = p
                    // iOS apply/renewPass: a gone or now-approval link drops the pass; a healthy one
                    // renews it past 10 s, the old one staying usable until the new one lands.
                    if (p.gone || (p.approval && !p.iAmCreator)) { GroupCall.dropLinkPass(); passAt = 0L }
                    else if (!p.full && !locked && System.currentTimeMillis() - passAt >= 10_000) {
                        GroupCall.prefetchLinkPass(k.roomId, renew = passAt != 0L); passAt = System.currentTimeMillis()
                    }
                    if (p.gone) gone = true
                    if (p.title.isNotEmpty() && p.title != CallLinkDefaults.NAME) title = p.title
                    if (!p.video) voiceOnly = true
                }
            }
            delay(5_000)
        }
    }
    // A refusal from the join comes back as the service's notice: a note, and Join works again.
    LaunchedEffect(g.notice) { g.notice?.let { flash(it); starting = false; GroupCall.clearNotice() } }
    LaunchedEffect(g.cid, g.connecting) { if (g.cid == null && !g.connecting) starting = false }
    LaunchedEffect(noteAt) { if (note != null) { delay(4_000); note = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (gone) {
            Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_link), null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("This call link is no longer valid", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(Modifier.weight(1f))
                LobbyBar { BarPill("Close", Color.Black.copy(alpha = 0.35f), Modifier.weight(1f), onLeave) }
            }
            return@Box
        }
        if (showsCamera && cameraReady && !released) CameraPreview(Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(myPhoto, 180.dp) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_link), null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Call link", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                }
                val line = if (g.waiting) "Waiting to be let in" else peek?.let { GroupCallWords.whoIsHere(it.names, it.count) }
                if (line != null) Text(line, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, textAlign = TextAlign.Center,
                    maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            note?.let {
                Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 10.dp))
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LobbyRound(if (showsCamera) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video_off), showsCamera,
                    if (voiceOnly) "Camera unavailable on a voice call" else if (showsCamera) "Turn camera off" else "Turn camera on",
                    enabled = !voiceOnly && !locked, dim = voiceOnly) {
                    if (cameraDenied) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    else cameraOn = !cameraOn
                }
                LobbyRound(if (micOn) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), micOn, if (micOn) "Mute" else "Unmute", enabled = !locked) {
                    if (micOn) micOn = false
                    else if (granted(ctx, Manifest.permission.RECORD_AUDIO)) micOn = true
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Spacer(Modifier.height(16.dp))
            LobbyBar {
                BarPill("Leave", Color.Black.copy(alpha = 0.35f), Modifier.weight(1f), onLeave)
                val joinTitle = if (peek?.approval == true && peek?.iAmCreator != true) "Ask to Join" else "Join"
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape).background(SysGreen)
                    .clickable(enabled = !locked) {
                        if (peek?.full == true) { flash("This call is full"); return@clickable }
                        note = null; starting = true; released = true
                        onJoin(showsCamera, micOn)
                    }.semantics { contentDescription = if (!locked) joinTitle else if (g.waiting) "Waiting to be let in" else "Joining" },
                    contentAlignment = Alignment.Center) {
                    if (locked) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else Text(joinTitle, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun granted(c: android.content.Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

/** iOS: a capsule 44 in from each edge, 14 of padding round two 48 pills 8 apart, 22 above the bottom. */
@Composable
private fun LobbyBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.padding(start = 44.dp, end = 44.dp, bottom = 22.dp).fillMaxWidth().clip(CircleShape)
        .background(Color(0xFF2C2C2E).copy(alpha = 0.7f)).border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun BarPill(text: String, fill: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).background(fill).border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The lobby's round buttons: white with a black glyph when on, dark glass when off. */
@Composable
private fun LobbyRound(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, label: String, enabled: Boolean = true, dim: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(if (on) Color.White else Color.Black.copy(alpha = 0.45f))
        .then(if (dim) Modifier.scale(1f) else Modifier).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = (if (on) Color.Black else Color.White).copy(alpha = if (dim) 0.4f else 1f), modifier = Modifier.size(24.dp))
    }
}

/**
 * The front camera for the lobby: a plain CameraX preview, nothing published. COMPATIBLE mode
 * (a TextureView), so the picture stays put while the screen around it changes. The camera is let
 * go when this leaves the screen, before the call starts its own.
 */
@Composable
private fun CameraPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        var provider: ProcessCameraProvider? = null
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            try {
                val p = f.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { try { provider?.unbindAll() } catch (_: Exception) {} }
    }
    AndroidView({ view }, modifier)
}

// ====================================================================== link requests (the creator)

/** iOS CallLinkAnswerButtons: the red X and the green check, 36 circles in 44 targets 12 apart. */
@Composable
private fun AnswerButtons(name: String, enabled: Boolean = true, onAnswer: (Boolean) -> Unit) {
    var last by remember { mutableLongStateOf(0L) }
    fun tap(approve: Boolean) {
        // The next card comes in under the same finger: an answer within 0.5 s of the last is dropped.
        val now = System.currentTimeMillis()
        if (now - last < 500) return
        last = now; onAnswer(approve)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false to SysRed, true to SysGreen).forEach { (approve, tint) ->
            Box(Modifier.size(44.dp).clickable(enabled = enabled) { tap(approve) }
                .semantics { contentDescription = if (approve) "Let in $name" else "Deny $name" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                    Icon(if (approve) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * iOS CallLinkRequestStack, above the call controls for the link's creator: one card per person
 * for one or two (the second behind the first, 95% and 8 showing), from three on the first card
 * with an "N more" capsule that opens everyone. A card comes in from 12 below; a tap on the name
 * shows who is asking.
 */
@Composable
fun CallLinkRequestStack(requests: List<LinkRequest>, modifier: Modifier = Modifier) {
    var showAll by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LinkRequest?>(null) }
    val cards = if (requests.size > 2) requests.take(1) else requests
    val more = if (requests.size > 2) requests.size - 1 else 0
    Box(modifier.fillMaxWidth()) {
        cards.reversed().forEach { p ->
            androidx.compose.runtime.key(p.uid) {
                val back = cards.size == 2 && p.uid == cards.last().uid
                val front = cards.size == 2 && !back
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = slideInVertically(spring(dampingRatio = 1f, stiffness = 439f)) { 36 } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()) {
                    RequestCard(p, more, Modifier.padding(top = if (front) 8.dp else 0.dp).scale(if (back) 0.95f else 1f),
                        enabled = !back, onDetails = { details = p }, onMore = { showAll = true })
                }
            }
        }
    }
    if (showAll) CallLinkBulkRequestsSheet(requests) { showAll = false }
    details?.let { d ->
        // The details (iOS CallLinkRequestDetailsSheet), as a dialog with its buttons on the right.
        GlassAlert(onDismissRequest = { details = null },
            icon = { Avatar(d.photo, 72.dp) },
            title = { Text(d.name, textAlign = TextAlign.Center) },
            text = { Text("Would like to join", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            dismissButton = { TextButton(onClick = { details = null; GroupCall.answerRequest(d.uid, false) }) { Text("Deny", color = SysRed) } },
            confirmButton = { TextButton(onClick = { details = null; GroupCall.answerRequest(d.uid, true) }) { Text("Let In") } })
    }
    LaunchedEffect(requests.map { it.uid }) { if (details != null && requests.none { it.uid == details?.uid }) details = null }
}

@Composable
private fun RequestCard(p: LinkRequest, more: Int, modifier: Modifier, enabled: Boolean, onDetails: () -> Unit, onMore: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().shadow(8.dp, shape).clip(shape).background(Color(0xFF2C2C2E))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = enabled, onClick = onDetails)
                .semantics { contentDescription = "${p.name} would like to join" }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.photo, 48.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_chevron_right), null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text("Would like to join", color = Color.White, fontSize = 15.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            AnswerButtons(p.name, enabled) { GroupCall.answerRequest(p.uid, it) }
        }
        if (more > 0) {
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(bottom = 6.dp).clickable(onClick = onMore)
                .semantics { contentDescription = "$more more waiting to join" }, contentAlignment = Alignment.Center) {
                Text("$more more", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * iOS CallLinkBulkRequestsSheet: "N waiting" with a close button, one row per person (40 photo,
 * name, the two answers), and Deny All / Approve All at the foot, each asking first with the
 * people that were on screen at that moment. Closes itself once nobody is waiting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLinkBulkRequestsSheet(requests: List<LinkRequest>, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }
    LaunchedEffect(requests.isEmpty()) { if (requests.isEmpty()) onClose() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(),
        containerColor = Color.Black, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)) {
                Text("${requests.size} waiting", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            LazyColumn(Modifier.padding(16.dp).clip(RoundedCornerShape(26.dp)).background(Card)) {
                items(requests, key = { it.uid }) { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.photo, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        AnswerButtons(p.name) { GroupCall.answerRequest(p.uid, it) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable { confirm = false to requests.map { it.uid } }, contentAlignment = Alignment.Center) {
                    Text("Deny All", color = SysRed, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f).heightIn(min = 50.dp).clip(CircleShape).background(SysGreen)
                    .clickable { confirm = true to requests.map { it.uid } }, contentAlignment = Alignment.Center) {
                    Text("Approve All", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    confirm?.let { (approve, uids) ->
        val n = uids.size
        GlassAlert(onDismissRequest = { confirm = null },
            title = { Text(if (approve) (if (n == 1) "Let 1 person in?" else "Let all $n people in?") else (if (n == 1) "Deny 1 request?" else "Deny all $n requests?")) },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = { confirm = null; GroupCall.answerAll(approve, uids) }) {
                    Text(if (approve) "Approve All" else "Deny All", color = if (approve) Color.Unspecified else SysRed)
                }
            })
    }
}
