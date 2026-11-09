package com.fariin.app.ui.call

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.fariin.app.ui.components.FariinSpinner
import androidx.compose.ui.res.vectorResource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fariin.app.call.AudioRoute
import com.fariin.app.call.CallMachine
import com.fariin.app.call.CallRouting
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import com.fariin.app.call.CallPhase
import com.fariin.app.call.CallUi
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupMember
import com.fariin.app.call.RtcEngine
import com.fariin.app.ui.components.Avatar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import io.livekit.android.renderer.TextureViewRenderer
import androidx.compose.animation.core.animateFloat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import com.fariin.app.call.GroupCallAdmin
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.aspectRatio
import com.fariin.app.R
import com.fariin.app.call.CallManager
import com.fariin.app.call.SharePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack

// iOS keeps the call screens flat black (no gradient, no blurred photo: its 2026-07-11 decision).
private val Backdrop = Color.Black
/** The call window's fill (owner 2026-11-08, the reference's floating card). */
private val PipFill = Color(0xFF1B1D21)
private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
/** iOS noAnswerBar's tint (0x22C55E). */
private val NoAnswerGreen = Color(0xFF22C55E)
// The dark glass of iOS's call screens: a light veil on black with a faint white rim.
private val Glass = Color.White.copy(alpha = 0.14f)
private val GlassRim = Color.White.copy(alpha = 0.18f)
private val GlassOn = Color.White
// The capsule under the buttons (iOS: dark ultra-thin material, black 25% over it, white 12% rim).
private val CapsuleFill = Color(0xFF1A1A1C).copy(alpha = 0.82f)
private val CapsuleRim = Color.White.copy(alpha = 0.12f)
private val TileFill = Color(0xFF1F1F21)

/** A dark glass surface: the veil and the rim, clipped to [shape]. */
private fun Modifier.darkGlass(shape: Shape, fill: Color = Glass): Modifier =
    clip(shape).background(fill).border(0.5.dp, GlassRim, shape)

/** A tap with no ripple, for the screen-wide show/hide surface. */
@Composable
private fun Modifier.plainTap(onTap: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)

/**
 * The controls that step aside on a video call (iOS autoHideAfter): up when the call connects, gone
 * 5 s later, back on a tap anywhere; a tap while they show sends them away. Every button press
 * restarts the clock ([touch]). Only while there is a picture to clear the way for ([allowed]).
 */
private class Chrome {
    var visible by mutableStateOf(true)
    var poke by mutableIntStateOf(0)
    /** A finger is dragging my card: no hiding under it (iOS: the clock stops for the drag). */
    var held by mutableStateOf(false)
    fun touch() { visible = true; poke++ }
    fun tap(allowed: Boolean) { if (!visible) touch() else if (allowed) visible = false }
}

@Composable
private fun rememberChrome(allowed: Boolean): Chrome {
    val c = remember { Chrome() }
    LaunchedEffect(allowed, c.visible, c.poke, c.held) {
        if (!allowed) { c.visible = true; return@LaunchedEffect }
        if (c.visible && !c.held) { delay(5_000); c.visible = false }
    }
    return c
}

/**
 * The 1:1 call screen after iOS CallView: a header (minimize at the start, the bold name and the
 * stage words or clock in the middle, "Muted" between them while they are muted), their video full
 * screen or their big photo, my camera in a card at the bottom corner with the flip glyph on it,
 * and one dark capsule: share, camera, speaker, mute, end. A ring shows Decline and Accept instead.
 *
 * Screen share (iOS shareLayout, owner 2026-10-10): while I share, or they do, the call is a rounded
 * card under the header ("You're sharing your screen" with Stop Sharing, or their screen whole) with
 * the two people as tiles under it; their screen opens full screen from the card's corner button.
 */
@Composable
fun CallScreen(
    ui: CallUi,
    onAccept: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit,
    onMute: () -> Unit, onRoute: (AudioRoute) -> Unit, onCamera: () -> Unit, onFlip: () -> Unit,
    onMinimize: (() -> Unit)? = null, onShare: () -> Unit = {},
    onCallAgain: () -> Unit = {}, onVoiceMessage: () -> Unit = {}, onCloseNoAnswer: () -> Unit = {},
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val connected = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
    val remoteVideo = ui.remoteVideo.takeIf { ui.remoteCameraOn && s.phase != CallPhase.INCOMING }
    val demoVideo = ui.peerUid == "demo" && ui.remoteCameraOn && s.phase == CallPhase.ACTIVE
    // Calling on video: my own preview fills the screen until they pick up (iOS showLocalFull).
    val localFull = ui.cameraOn && s.phase == CallPhase.OUTGOING
    val anyVideo = ui.cameraOn || ui.remoteCameraOn || ui.sharing || ui.remoteScreen
    val chrome = rememberChrome(connected && anyVideo)
    // The header and the capsule fade: in over 0.2 s, away over 0.28 s, ease in and out (iOS).
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f,
        tween(if (chrome.visible) 200 else 280, easing = EaseInOut), label = "chrome")
    val local = ui.localVideo
    val cardUp = ui.cameraOn && !localFull && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED
    // A tap on my card swaps the two pictures: mine big, theirs in the card (iOS isLocalExpanded).
    var swapped by remember { mutableStateOf(false) }
    LaunchedEffect(cardUp) { if (!cardUp) swapped = false }
    // Their screen on the stage (their camera's track carries it), or the demo's made-up one.
    val theirScreen = ui.remoteScreen && connected && (remoteVideo != null || ui.peerUid == "demo")
    var shareFull by remember { mutableStateOf(false) }
    LaunchedEffect(theirScreen) { if (!theirScreen) shareFull = false }
    // Portrait always (iOS OrientationLock), except their shared screen full size: the phone may turn
    // to watch it sideways (iOS CallView landscapeWanted). Back to portrait when it closes.
    val orientAct = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(shareFull) {
        orientAct?.requestedOrientation = if (shareFull) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { orientAct?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    val shareLayout = connected && (ui.sharing || (theirScreen && !shareFull))
    // A share (mine or theirs) starts with the controls hidden; a tap brings them (iOS, 2026-10-10).
    LaunchedEffect(shareLayout) { if (shareLayout) chrome.visible = false }
    LaunchedEffect(shareLayout) { if (shareLayout) swapped = false }
    val mineBig = !shareLayout && !shareFull && (localFull || (swapped && cardUp))
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        // One renderer for the big picture whoever is in it, so a swap only moves the tracks.
        val bigTrack = if (shareLayout || shareFull) null else if (mineBig) local else remoteVideo
        val bigDemo = !shareLayout && !shareFull && bigTrack == null && (if (mineBig) swapped else demoVideo)
        when {
            bigTrack != null -> VideoView(bigTrack, mirror = mineBig && ui.frontCamera, modifier = Modifier.fillMaxSize())
            bigDemo && mineBig -> DemoVideo(Modifier.fillMaxSize(), MeA, MeB)
            bigDemo -> DemoVideo(Modifier.fillMaxSize(), ThemA, ThemB)
        }
        val showFace = bigTrack == null && !bigDemo && !shareLayout && !shareFull
        // Their screen full screen: whole, pinch to zoom, a tap shows or hides the controls.
        if (shareFull) ShareStage(remoteVideo, Modifier.fillMaxSize(), zoomable = true) { chrome.tap(true) }
        // Above the video, under every control: a tap shows or hides the controls.
        else Box(Modifier.fillMaxSize().plainTap { chrome.tap(connected && anyVideo) })
        // Their photo is its own layer, centred on the whole screen (owner 2026-11-06: between the
        // header and the controls, the "Muted" line the header gains on their mute pushed it down).
        // Decoration only: it takes no taps, so the show/hide tap still reaches the layer under it.
        // Composed before the header, but a screen reader still reads the header (name, status)
        // first, then the photo, then the controls (index 0), as on iOS.
        if (showFace) Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true; traversalIndex = -1f },
            contentAlignment = Alignment.Center) {
            Avatar(ui.peerPhoto, 180.dp, Modifier
                .shadow(26.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .semantics { contentDescription = ui.peerName })
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = if (ui.held && s.live) "On hold" else CallMachine.label(s, now)
            CallHeader(
                name = ui.peerName, status = status,
                muted = ui.remoteMuted && connected, mutedLabel = "${ui.peerName.substringBefore(' ')} is muted",
                left = if (onMinimize != null && s.phase != CallPhase.INCOMING && s.phase != CallPhase.ENDED) {
                    { HeaderCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_collapse), "Minimize call", 48.dp) { chrome.touch(); onMinimize() } }
                } else null,
                modifier = Modifier.alpha(shown).semantics { isTraversalGroup = true; traversalIndex = -2f },
                // No shade over the flat colour and the photo (owner 2026-11-06: it read as a
                // shadow); video under the header still gets it, for the white words.
                scrim = !showFace,
            )
            Spacer(Modifier.weight(1f))
            when (s.phase) {
                CallPhase.INCOMING -> IncomingButtons(s.video, onAccept, onDecline)
                CallPhase.ENDED -> if (ui.noAnswer) NoAnswerCard(s, onCallAgain, onVoiceMessage, onCloseNoAnswer)
                    else Spacer(Modifier.height(BarHeight))
                else -> ControlBar(ui, chrome, onEnd, onMute, onRoute, onCamera, onShare, Modifier.alpha(shown))
            }
            Spacer(Modifier.navigationBarsPadding().height(22.dp))
        }
        // Controls away: the first tap anywhere only brings them back (hidden buttons take no taps).
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        // The share card and the two people, above the catch-all so Stop Sharing works with the
        // controls away too; it never reaches under the header or the capsule while they show.
        if (shareLayout) ShareLayout(ui, chrome, remoteVideo, local, onStop = { chrome.touch(); onShare() },
            onFull = { chrome.touch(); shareFull = true })
        // Back from their full-screen share to the card.
        if (shareFull) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 16.dp, bottom = 22.dp + BarHeight + 14.dp)
            .alpha(shown), contentAlignment = Alignment.BottomEnd) {
            if (chrome.visible) HeaderCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_collapse), "Exit full screen", 40.dp) { chrome.touch(); shareFull = false }
        }
        // My camera: a card at the bottom corner, smaller while the controls are away (iOS pipLayer).
        // Above the catch-all tap, so it can be dragged and tapped while the controls are away too.
        if (cardUp && !shareLayout) {
            SelfCard(grown = chrome.visible, chrome = chrome,
                label = if (swapped) ui.peerName else "Your video",
                onFlip = if (!swapped && local != null) { { chrome.touch(); onFlip() } } else null,
                // TWO STAGES (iOS tapTile): the small card only brings the controls back; a tap on the
                // grown card swaps the pictures, and the next one swaps them back.
                onTap = { if (chrome.visible) swapped = !swapped; chrome.touch() },
            ) {
                val m = Modifier.fillMaxSize()
                val cardTrack = if (swapped) remoteVideo else local
                when {
                    cardTrack != null -> CardVideoView(cardTrack, mirror = !swapped && ui.frontCamera, modifier = m)
                    !swapped -> DemoVideo(m, MeA, MeB)
                    demoVideo -> DemoVideo(m, ThemA, ThemB)
                    // Their camera is off: their photo holds the card (iOS tileContent).
                    else -> Box(m.background(TileFill), contentAlignment = Alignment.Center) { Avatar(ui.peerPhoto, 64.dp) }
                }
            }
        }
        SharePill(ui)
    }
}

/**
 * The one small pill under the header (iOS topPill): "Amina is sharing their screen" once when
 * their share begins (3 s), or my share's notice when it ended without my Stop (2.5 s).
 */
@Composable
private fun SharePill(ui: CallUi) {
    var text by remember { mutableStateOf<String?>(null) }
    val first = ui.peerName.substringBefore(' ')
    LaunchedEffect(ui.remoteScreen) {
        if (ui.remoteScreen) { text = "$first is sharing their screen"; delay(3_000); text = null }
    }
    LaunchedEffect(ui.shareNotice) {
        val n = ui.shareNotice ?: return@LaunchedEffect
        text = n; delay(2_500); text = null
        CallManager.clearShareNotice()
    }
    val a by animateFloatAsState(if (text != null) 1f else 0f, tween(300, easing = EaseInOut), label = "pill")
    var last by remember { mutableStateOf("") }
    text?.let { last = it }
    if (a > 0f) Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.alpha(a).height(32.dp).darkGlass(CircleShape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(last, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * iOS shareLayoutView: the card under the header and the two people under it, on a spring when the
 * controls come and go (they rise to the status bar and sink to the bottom edge without them).
 */
@Composable
private fun ShareLayout(ui: CallUi, chrome: Chrome, remote: VideoTrack?, local: VideoTrack?, onStop: () -> Unit, onFull: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val topIn = insets.calculateTopPadding()
    val bottomIn = insets.calculateBottomPadding()
    val spring = spring<Dp>(dampingRatio = 0.9f, stiffness = 322.3f)
    // Clear of the header: its name and clock, and the "Muted" line while they are muted.
    val header = if (ui.remoteMuted) 108.dp else 86.dp
    val top by animateDpAsState(if (chrome.visible) topIn + header else topIn + 8.dp, spring, label = "shareTop")
    val bottom by animateDpAsState(if (chrome.visible) bottomIn + 22.dp + BarHeight + 14.dp else maxOf(bottomIn, 12.dp) + 4.dp, spring, label = "shareBottom")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileH = minOf(150.dp, maxOf(96.dp, maxHeight * 0.17f))
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShareCard(ui, chrome, remote, onStop, onFull, Modifier.weight(1f).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Them: their camera, never their screen (it rides on that same track). Me: my live camera.
                ShareTile(remote.takeIf { ui.remoteCameraOn && !ui.remoteScreen }, false, ui.peerName, ui.peerPhoto, tileH) { chrome.tap(true) }
                // My own tile showed the grey person (owner 2026-11-08, screen share): my profile photo.
                ShareTile(local.takeIf { ui.cameraOn && !ui.sharing }, ui.frontCamera, "You", rememberMyPhoto(), tileH) { chrome.tap(true) }
            }
        }
    }
}

/** iOS shareCard: my share's card with Stop Sharing, or their screen whole with the full-screen button. */
@Composable
private fun ShareCard(ui: CallUi, chrome: Chrome, remote: VideoTrack?, onStop: () -> Unit, onFull: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(ShareCardFill).plainTap { chrome.tap(true) }) {
        if (ui.sharing) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                if (ui.sharePhase == SharePhase.STARTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Starting…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(44.dp))
                    Text("You're sharing your screen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Row(Modifier.height(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onStop)
                        .padding(horizontal = 22.dp).semantics { contentDescription = "Stop Sharing" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Sharing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            ShareStage(remote, Modifier.fillMaxSize(), zoomable = false) { chrome.tap(true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                HeaderCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_expand), "Full screen", 40.dp, onFull)
            }
        }
    }
}

/**
 * Their shared screen, whole (never cropped, never mirrored), on a TextureView so the card and the
 * full-screen stage can move and scale it. [zoomable]: pinch and pan, a double tap goes back to 1x.
 * The demo call draws its made-up picture instead.
 */
@Composable
private fun ShareStage(track: VideoTrack?, modifier: Modifier, zoomable: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val gestures = if (zoomable) Modifier
        .pointerInput(Unit) {
            detectTransformGestures { _, p, z, _ ->
                scale = (scale * z).coerceIn(1f, 4f)
                val maxX = size.width * (scale - 1) / 2
                val maxY = size.height * (scale - 1) / 2
                pan = if (scale == 1f) Offset.Zero else Offset((pan.x + p.x).coerceIn(-maxX, maxX), (pan.y + p.y).coerceIn(-maxY, maxY))
            }
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = 1f; pan = Offset.Zero }) }
    else Modifier.plainTap(onTap)
    Box(modifier.then(gestures).semantics { contentDescription = "Shared screen" }) {
        val m = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }
        if (track != null) ScreenVideoView(track, m) else DemoScreen(m)
    }
}

/** The demo call's made-up shared screen: a phone-shaped page, whole, on the dark stage. */
@Composable
private fun DemoScreen(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight(0.92f).aspectRatio(9f / 19.5f).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFECEFF4), Color(0xFFC9D2DE)))))
    }
}

/** One person under the share card (iOS shareTile): their live camera, else their photo. */
@Composable
private fun ShareTile(track: VideoTrack?, mirror: Boolean, name: String, photo: String?, height: Dp, onTap: () -> Unit) {
    Box(Modifier.size(height * 0.74f, height).clip(RoundedCornerShape(22.dp)).background(ShareTileFill).plainTap(onTap)
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        if (track != null) CardVideoView(track, mirror, Modifier.fillMaxSize())
        else Avatar(photo, minOf(64.dp, height * 0.42f))
    }
}

/** A WebRTC track drawn whole into a TextureView (a shared screen: aspect fit, never mirrored). */
@Composable
private fun ScreenVideoView(track: VideoTrack, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { r ->
            val old = r.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(r) } catch (_: Exception) {}
                track.addSink(r)
                r.tag = track
            }
        },
        onRelease = { r ->
            try { (r.tag as? VideoTrack)?.removeSink(r) } catch (_: Exception) {}
            r.release()
        },
    )
}

// iOS Color(white: 0.12) for the share card, 0.18 for the tiles under it.
private val ShareCardFill = Color(0xFF1F1F1F)
private val ShareTileFill = Color(0xFF2E2E2E)

// The demo call's made-up pictures: theirs green, mine slate.
private val ThemA = Color(0xFF3A5A40)
private val ThemB = Color(0xFF1B2B1F)
private val MeA = Color(0xFF6B7A8F)
private val MeB = Color(0xFF2E3A48)

/** iOS's ease in and out (`.easeInOut`). */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
/** iOS `.spring(duration: 0.4)`: no bounce. The card's size and home move on it as one relayout. */
private val CardSpring = spring<Float>(dampingRatio = 1f, stiffness = 246.7f)
/** iOS PipTileDrag's snap to a corner: `.spring(response: 0.35, dampingFraction: 0.78)`. */
private val SnapSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 322.3f)
/** The card at full size with the controls up, and small without (iOS: a 240 and 140 pt box, 9:16). */
private val CardBig = DpSize(135.dp, 240.dp)
private val CardSmall = DpSize(79.dp, 140.dp)

/**
 * My card (iOS pipLayer + PipTileDrag). [grown] with the controls up: 135 by 240 above the capsule;
 * without, 79 by 140 near the edge, size and home on one spring. A drag carries it anywhere and the
 * release snaps it to the nearest corner, judged by where the throw was going. The picture inside
 * is drawn at the full size and scaled, so the video surface is never resized while it moves.
 */
@Composable
private fun SelfCard(grown: Boolean, chrome: Chrome, label: String, onFlip: (() -> Unit)?, onTap: () -> Unit,
                     content: @Composable () -> Unit) {
    val t by animateFloatAsState(if (grown) 1f else 0f, CardSpring, label = "card")
    // The corner as fractions of the travel: x 0 = the end edge, 1 = the start edge; y 0 = bottom, 1 = top.
    val fx = remember { Animatable(0f) }
    val fy = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bars = WindowInsets.systemBars
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = lerp(CardSmall.width, CardBig.width, t)
        val h = lerp(CardSmall.height, CardBig.height, t)
        val px = with(density) {
            val wPx = w.toPx(); val hPx = h.toPx()
            val gutter = 12.dp.toPx()
            val endX = constraints.maxWidth - bars.getRight(this, LayoutDirection.Ltr) - gutter - wPx
            val startX = bars.getLeft(this, LayoutDirection.Ltr) + gutter
            // Home clears the capsule with the controls up (iOS barClear: bar + 56), else 12 off the edge.
            val bottomY = constraints.maxHeight - bars.getBottom(this) - lerp(12.dp, BarHeight + 56.dp, t).toPx() - hPx
            // The top corner sits under the header (iOS headerClear: safe top + 76).
            val topY = minOf(bars.getTop(this) + 76.dp.toPx(), bottomY)
            floatArrayOf(if (rtl) startX else endX, if (rtl) endX else startX, bottomY, topY)
        }
        val travel by rememberUpdatedState(px)
        val shape = RoundedCornerShape(18.dp)
        Box(Modifier
            .offset { IntOffset(lerp(px[0], px[1], fx.value).roundToInt(), lerp(px[2], px[3], fy.value).roundToInt()) }
            .size(w, h)
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape).border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .pointerInput(Unit) {
                val vt = VelocityTracker()
                var path = Offset.Zero
                fun settle() {
                    chrome.held = false
                    val v = vt.calculateVelocity()
                    val spanX = travel[1] - travel[0]; val spanY = travel[3] - travel[2]
                    // Where the throw was going (UIKit's projection at the normal deceleration rate).
                    val goX = if (spanX == 0f) 0f else fx.value + v.x * 0.499f / spanX
                    val goY = if (spanY == 0f) 0f else fy.value + v.y * 0.499f / spanY
                    scope.launch { fx.animateTo(if (goX > 0.5f) 1f else 0f, SnapSpring, if (spanX == 0f) 0f else v.x / spanX) }
                    scope.launch { fy.animateTo(if (goY > 0.5f) 1f else 0f, SnapSpring, if (spanY == 0f) 0f else v.y / spanY) }
                }
                detectDragGestures(
                    onDragStart = { chrome.held = true; vt.resetTracking(); path = Offset.Zero },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                ) { change, d ->
                    change.consume()
                    path += d
                    vt.addPosition(change.uptimeMillis, path)
                    val spanX = travel[1] - travel[0]; val spanY = travel[3] - travel[2]
                    scope.launch {
                        if (spanX != 0f) fx.snapTo((fx.value + d.x / spanX).coerceIn(0f, 1f))
                        if (spanY != 0f) fy.snapTo((fy.value + d.y / spanY).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .semantics { contentDescription = label; onClick { onTap(); true } }) {
            // Drawn at the big size and scaled down: the size moves every frame, the surface never does.
            Box(Modifier.requiredSize(CardBig).graphicsLayer {
                scaleX = w / CardBig.width; scaleY = h / CardBig.height
            }) { content() }
            // The flip glyph belongs to my live camera's card (iOS: no flip in the bar).
            if (onFlip != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).darkGlass(CircleShape)
                .clickable(onClick = onFlip).semantics { contentDescription = "Flip camera" },
                contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_flip_camera), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The control bar's height: the 56 circle and 12 above and below (iOS CallView.barHeight). */
private val BarHeight = 80.dp

/**
 * The call header (iOS topBar): a circle at the start, the 22 sp bold name over the 15 sp line in
 * the middle, the same room kept free at the end (or [right]) so the name stays centred, over a
 * soft dark scrim that keeps white text legible on bright video ([scrim] off when no video is under it).
 */
@Composable
private fun CallHeader(
    name: String, status: String, muted: Boolean, mutedLabel: String,
    left: (@Composable () -> Unit)?, modifier: Modifier = Modifier,
    right: (@Composable () -> Unit)? = null, side: Dp = 48.dp, scrim: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (scrim) Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))) else Modifier)
            .statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        // Top-aligned: the circle centres on the name and clock, and "Muted" grows the block
        // downward without moving it (iOS headerButtons guide).
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = (48.dp - side) / 2).size(side)) { left?.invoke() }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            // Their mute is the word on its own line between the name and the clock (iOS, 2026-10-10).
            if (muted) Text("Muted", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.semantics { contentDescription = mutedLabel })
            Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(top = (48.dp - side) / 2).size(side)) { right?.invoke() }
    }
}

/** A header button: a white glyph in a dark glass circle. */
@Composable
private fun HeaderCircle(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).darkGlass(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun DemoVideo(modifier: Modifier, a: Color, b: Color) {
    Box(modifier.background(Brush.linearGradient(listOf(a, b))))
}

@Composable
private fun IncomingButtons(video: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        LabeledCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_phone_down), "Decline", Red, onDecline)
        LabeledCircle(if (video) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), "Accept", Green, onAccept)
    }
}

@Composable
private fun LabeledCircle(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).darkGlass(CircleShape, color).clickable(onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * iOS noAnswerBar: under "No answer" / "Couldn't connect", one card with the way forward as a wide
 * green button (Call again, or Try again after a failed connect), and Voice message and Close under it.
 */
@Composable
private fun NoAnswerCard(s: com.fariin.app.call.CallStatus, onCallAgain: () -> Unit, onVoiceMessage: () -> Unit, onClose: () -> Unit) {
    val retry = if (s.endReason == com.fariin.app.call.EndReason.FAILED) "Try again" else "Call again"
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().darkGlass(RoundedCornerShape(30.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(NoAnswerGreen).clickable(onClick = onCallAgain)
                .semantics { contentDescription = retry },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (s.video) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(retry, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NoAnswerSecondary(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic), "Voice message", Modifier.weight(1f), onVoiceMessage)
            NoAnswerSecondary(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), "Close", Modifier.weight(1f), onClose)
        }
    }
}

@Composable
private fun NoAnswerSecondary(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).darkGlass(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** The dark capsule the round buttons sit in (iOS controlBar background). */
@Composable
private fun ControlCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.padding(horizontal = 12.dp).clip(CircleShape).background(CapsuleFill).border(1.dp, CapsuleRim, CircleShape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** iOS order: share, camera, speaker, mute next to end under the right thumb. Flip is on my camera's card. */
@Composable
private fun ControlBar(ui: CallUi, chrome: Chrome, onEnd: () -> Unit, onMute: () -> Unit, onRoute: (AudioRoute) -> Unit,
                       onCamera: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    ControlCapsule(modifier) {
        val size = 56.dp
        val sharingOn = ui.sharePhase != SharePhase.OFF
        ShareCircle(lit = ui.sharePhase == SharePhase.LIVE, on = sharingOn, enabled = sharingOn || ui.canStartShare, size = size) {
            chrome.touch(); onShare()
        }
        // Dimmed while a share of mine runs or is being started (iOS shareBlocksCamera).
        // Locked while my outgoing call rings (iOS 30da1e203): a video call stays video until answered.
        val camOk = (ui.cameraOn || !sharingOn) && ui.status.phase != CallPhase.OUTGOING
        Box(Modifier.alpha(if (camOk) 1f else 0.4f)) {
            Circle(if (ui.cameraOn) ImageVector.vectorResource(R.drawable.ic_call_video) else ImageVector.vectorResource(R.drawable.ic_call_video_off), active = !ui.cameraOn,
                label = if (ui.cameraOn) "Turn camera off" else "Turn camera on", size = size) { chrome.touch(); if (camOk) onCamera() }
        }
        SpeakerButton(ui.route, size) { chrome.touch(); onRoute(it) }
        Circle(if (ui.muted) ImageVector.vectorResource(R.drawable.ic_mic_off) else ImageVector.vectorResource(R.drawable.ic_mic), active = ui.muted,
            label = if (ui.muted) "Unmute" else "Mute", size = size) { chrome.touch(); onMute() }
        EndCircle("End call", size, onEnd)
    }
}

/**
 * Share Screen (iOS shareCircle): dimmed = not yet (no connected call, or they are sharing), glass =
 * ready, solid white with a dark glyph = my screen is really going out. Stopping is never disabled.
 */
@Composable
private fun ShareCircle(lit: Boolean, on: Boolean, enabled: Boolean, size: Dp, onClick: () -> Unit) {
    val label = if (lit) "Stop Sharing" else if (on) "Cancel Sharing" else "Share Screen"
    Box(Modifier.alpha(if (enabled) 1f else 0.4f).size(size)
        .then(if (lit) Modifier.clip(CircleShape).background(GlassOn) else Modifier.darkGlass(CircleShape))
        .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_share_screen), null, tint = if (lit) Color.Black else Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun EndCircle(label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).darkGlass(CircleShape, Red).clickable(onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_phone_down), null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

/**
 * The speaker button. With only the phone and the speaker it switches between them; with a
 * headset or Bluetooth in, it opens the list of places the sound can go (the iOS route picker).
 */
@Composable
private fun SpeakerButton(r: com.fariin.app.call.RouteState, size: Dp, onRoute: (AudioRoute) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val icon = when (r.current) {
        AudioRoute.BLUETOOTH -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_bluetooth)
        AudioRoute.HEADSET -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_headphones)
        // One speaker picture, lit (white circle) when on, as iOS; the struck-through "off" speaker
        // read as broken (owner 2026-11-05: "looks super cheap").
        else -> ImageVector.vectorResource(R.drawable.ic_speaker_on)
    }
    val label = if (CallRouting.speakerTap(r) is CallRouting.Tap.Picker) "Audio: " + CallRouting.label(r.current, r.bluetoothName) else "Speaker"
    Box {
        Circle(icon, active = CallRouting.speakerLit(r), label = label, size = size, onClick = {
            when (val t = CallRouting.speakerTap(r)) {
                is CallRouting.Tap.Switch -> onRoute(t.to)
                CallRouting.Tap.Picker -> open = true
            }
        })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (route in CallRouting.pickerList(r)) {
                DropdownMenuItem(
                    text = { Text(CallRouting.label(route, r.bluetoothName)) },
                    leadingIcon = { Icon(when (route) {
                        AudioRoute.BLUETOOTH -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_bluetooth)
                        AudioRoute.HEADSET -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_headphones)
                        AudioRoute.EARPIECE -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice)
                        AudioRoute.SPEAKER -> androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_speaker_on)
                    }, null) },
                    trailingIcon = { if (route == r.current) Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark), "In use") },
                    onClick = { open = false; onRoute(route) },
                )
            }
        }
    }
}

/**
 * The picture-in-picture window, as the iOS one carries the whole call: their video big with mine
 * in a corner tile (mine big while theirs is off), else their photo with the name and the stage
 * words or clock. A mic-off badge while they are muted, and one on my tile (or by the name) while I am.
 */
@Composable
fun PipScreen(ui: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.status.connectedAtMs) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ui.status
    val connected = s.phase == CallPhase.ACTIVE || s.phase == CallPhase.RECONNECTING
    val status = if (ui.held && s.live) "On hold" else CallMachine.label(s, now)
    val theirMute = ui.remoteMuted && connected
    BoxWithConstraints(Modifier.fillMaxSize().background(PipFill), contentAlignment = Alignment.Center) {
        val roomy = maxHeight >= 180.dp
        val remote = ui.remoteVideo.takeIf { ui.remoteCameraOn }
        val local = ui.localVideo.takeIf { ui.cameraOn }
        val demoThem = ui.peerUid == "demo" && ui.remoteCameraOn
        val bigIsMine = remote == null && !demoThem && local != null
        when {
            remote != null -> VideoView(remote, mirror = false, modifier = Modifier.fillMaxSize(), fit = ui.remoteScreen)
            demoThem -> DemoVideo(Modifier.fillMaxSize(), ThemA, ThemB)
            local != null -> VideoView(local, mirror = ui.frontCamera, modifier = Modifier.fillMaxSize())
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(8.dp)) {
                // BOTH OF US with no camera (owner 2026-11-08, the reference's call window): their photo,
                // then mine under it, smaller, so the window reads as a call between two people.
                val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                val myPhoto by androidx.compose.runtime.produceState<String?>(null, me) {
                    if (me != null) value = runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users")
                        .document(me).get(com.google.firebase.firestore.Source.CACHE).await().getString("photoUrl") }.getOrNull()
                        ?: runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me).get().await().getString("photoUrl") }.getOrNull()
                }
                Box {
                    Avatar(ui.peerPhoto, 66.dp, Modifier.semantics { contentDescription = ui.peerName })
                    if (theirMute) PipMicOff("${ui.peerName.substringBefore(' ')} is muted", Modifier.align(Alignment.BottomEnd).offset(4.dp, 2.dp))
                }
                Spacer(Modifier.height(14.dp))
                Avatar(myPhoto, 40.dp, Modifier.semantics { contentDescription = "You" })
                // The name and clock only where the window has room for them under the two faces.
                if (roomy) Spacer(Modifier.height(6.dp))
                if (roomy) Text(ui.peerName.substringBefore(' '), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (roomy) Text(status, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
                // Mine in words (owner 2026-11-05: two identical icons, whose was whose?).
                if (ui.muted && roomy) Text("You're muted", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        val video = remote != null || demoThem || local != null
        if (video) {
            // The stage words or clock along the bottom over the video.
            Text(status, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            // My tile in the corner while their video is big.
            if (!bigIsMine && local != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp, 64.dp).clip(RoundedCornerShape(8.dp))) {
                VideoView(local, mirror = ui.frontCamera, modifier = Modifier.fillMaxSize(), overlay = true)
            }
            if (ui.muted) PipMicOff("You are muted", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        if (theirMute && video) PipMicOff("${ui.peerName.substringBefore(' ')} is muted", Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

/** The small round mic-off badge of the picture-in-picture window. */
@Composable
private fun PipMicOff(label: String, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(ImageVector.vectorResource(R.drawable.ic_mic_off), null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

/** iOS callCircle: dark glass when off, solid white with a dark glyph when the switch is on. */
@Composable
private fun Circle(icon: ImageVector, active: Boolean, label: String, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).then(if (active) Modifier.clip(CircleShape).background(GlassOn) else Modifier.darkGlass(CircleShape))
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

/** A WebRTC track drawn into a SurfaceViewRenderer (the in-call video). */
@Composable
fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier = Modifier, overlay: Boolean = false, fit: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                // No hardware scaler (owner's video 2026-11-05: my camera "flickered" zoomed in for two
                // frames about a second into a video call). With it on, the surface's buffer is sized
                // to each frame, so when the sender starts and lowers the resolution, a frame or two
                // shows at the old buffer size, cropped and enlarged. Scaled by GL into a surface the
                // size of the view, a size change is seamless.
                setEnableHardwareScaler(false)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { r ->
            r.setMirror(mirror)
            val old = r.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(r) } catch (_: Exception) {}
                track.addSink(r)
                r.tag = track
            }
        },
        onRelease = { r ->
            try { (r.tag as? VideoTrack)?.removeSink(r) } catch (_: Exception) {}
            r.release()
        },
    )
}

/**
 * A WebRTC track drawn into a TextureView, for my card. The card's size moves on a spring; a
 * SurfaceView there drops its frames and flickers ("rejecting buffer" on every frame, Pixel 5,
 * 2026-11-03), while a TextureView is drawn with the rest of the screen and takes the scale, the
 * rounded clip and the drag like any other view.
 */
@Composable
private fun CardVideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                init(RtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            }
        },
        update = { r ->
            r.setMirror(mirror)
            val old = r.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(r) } catch (_: Exception) {}
                track.addSink(r)
                r.tag = track
            }
        },
        onRelease = { r ->
            try { (r.tag as? VideoTrack)?.removeSink(r) } catch (_: Exception) {}
            r.release()
        },
    )
}

// ====================================================================== group room

/**
 * A group call after iOS GroupCallView: the header (the title over the status line, the people
 * button at the end), the stage, the link creator's request cards, and the capsule: camera, mute,
 * end. The header and capsule step aside on video as the 1:1 screen does. A group chat's call
 * with nobody else here yet (and my camera off) shows the group's photo on black with the pulse
 * while it rings (iOS groupWaiting + GroupCallRingingView). [GroupCallUi.members] lists me first.
 */
@Composable
fun GroupCallScreen(g: GroupCallUi, onMic: () -> Unit, onCamera: () -> Unit, onLeave: () -> Unit, onShare: () -> Unit = {},
                    onMinimize: (() -> Unit)? = null, pip: Boolean = false,
                    onHand: (Boolean) -> Unit = {}, onReact: (String) -> Unit = {},
                    onRoute: (AudioRoute) -> Unit = {}, onFlip: () -> Unit = {}) {
    // The small window with no video anywhere: the people's faces, as the reference's floating card.
    if (pip && !g.cameraOn && g.members.none { it.video != null || it.screen != null }) { GroupPipFaces(g); return }
    androidx.compose.runtime.CompositionLocalProvider(LocalRaisedHands provides g.hands.toSet()) {
        GroupCallStage(g, onMic, onCamera, onLeave, onShare, onMinimize, pip, onHand, onReact, onRoute, onFlip)
    }
}

/**
 * The group call window without video: a 2x2 grid of round faces, the others first and me last;
 * with more than four people the last cell says "+N" for the rest.
 */
@Composable
private fun GroupPipFaces(g: GroupCallUi) {
    val people = g.members.filter { !it.local } + g.members.filter { it.local }
    val shown = if (people.size > 4) people.take(3) else people
    val rest = people.size - shown.size
    BoxWithConstraints(Modifier.fillMaxSize().background(PipFill).padding(10.dp), contentAlignment = Alignment.Center) {
        val cell = ((minOf(maxWidth, maxHeight) - 8.dp) / 2).coerceAtLeast(24.dp)
        val cells: List<GroupMember?> = shown + if (rest > 0) listOf(null) else emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            cells.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        if (m != null) Avatar(m.photo, cell, Modifier.semantics { contentDescription = if (m.local) "You" else m.name })
                        else Box(Modifier.size(cell).clip(CircleShape).background(Color(0xFF2C2F35)), contentAlignment = Alignment.Center) {
                            Text("+$rest", color = Color.White, fontSize = (cell.value * 0.32f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** iOS GroupCallMoreMenu's social part: the reaction row and Raise Hand / Lower Hand. */
@Composable
private fun GroupMoreSocial(g: GroupCallUi, onReact: (String) -> Unit, onHand: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        com.fariin.app.call.GroupCall.QUICK_EMOJIS.forEach { e ->
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onReact(e) }, contentAlignment = Alignment.Center) {
                Text(e, fontSize = 24.sp)
            }
        }
    }
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(if (g.myHand) "Lower Hand" else "Raise Hand", color = Color.White) },
        leadingIcon = { Icon(painterResource(if (g.myHand) R.drawable.ic_sf_hand_raised_fill else R.drawable.ic_sf_hand_raised), null,
            tint = Color.White, modifier = Modifier.size(22.dp)) },
        onClick = { onHand(!g.myHand) })
}

/** Raised hands in the call, read by every tile layout (iOS tile.isHandRaised). */
internal val LocalRaisedHands = androidx.compose.runtime.compositionLocalOf { emptySet<String>() }

@Composable
private fun GroupCallStage(g: GroupCallUi, onMic: () -> Unit, onCamera: () -> Unit, onLeave: () -> Unit, onShare: () -> Unit,
                           onMinimize: (() -> Unit)?, pip: Boolean, onHand: (Boolean) -> Unit, onReact: (String) -> Unit,
                           onRoute: (AudioRoute) -> Unit, onFlip: () -> Unit) {
    var focus by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf(false) }
    var tileConfirm by remember { mutableStateOf<HostConfirm?>(null) }
    val me = g.members.firstOrNull { it.local } ?: g.members.firstOrNull()
    val meId = me?.id
    val remotes = g.members.filter { it.id != meId }
    // Gone, or no picture any more (camera and screen off): back to the grid (iOS 686b9eed5).
    LaunchedEffect(g.members.map { Triple(it.id, it.video != null, it.screen != null) }) {
        if (focus != null && remotes.none { it.id == focus && (it.video != null || it.screen != null) }) focus = null
    }
    // iOS: no pin in the two-person look (the other person is the whole stage already).
    LaunchedEffect(remotes.size) { if (remotes.size < 2) focus = null }
    // iOS fa5c76f87 (owner 2026-11-08): a group or link call's header and controls never hide.
    val chromeMayHide = false
    val allowed = chromeMayHide && g.joined && !people && g.requests.isEmpty() && (g.cameraOn || g.members.any { it.video != null || it.screen != null })
    // A shared screen is what the room is looking at: its presenter takes the stage unless I picked
    // someone (iOS GroupCallStage: the user's pin, then the presenter, else the grid).
    // Two sharing at once: the newest share takes the stage (iOS updateMode, shareStartedAt).
    val presenter = remotes.filter { it.screen != null }.maxByOrNull { it.screenSinceMs }?.id
    var announced by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(presenter) {
        val p = remotes.firstOrNull { it.id == presenter }
        if (p != null && announced != p.id) {
            announced = p.id; GroupCall.say("${p.name.substringBefore(' ')} is sharing their screen")
            focus = null   // iOS updateMode: a share that starts clears my pin; the screen takes the stage
        }
        if (p == null) announced = null
    }
    val chrome = rememberChrome(allowed)
    val shown by animateFloatAsState(if (chrome.visible) 1f else 0f, tween(280), label = "chrome")
    val waitingScreen = g.linkRoom == null && !g.cameraOn && !g.waiting && (g.connecting || (g.joined && remotes.isEmpty()))
    // iOS duoSharePair: me and one other person with a screen shared by either of us. With the
    // controls away, the card takes the header's and the bar's room (iOS padding -(height + 16)).
    val duoShare = g.joined && remotes.size == 1 && (g.sharing || remotes[0].screen != null)
    val room by animateFloatAsState(if (duoShare && !chrome.visible) 0f else 1f, tween(280), label = "chromeRoom")
    // iOS GroupCallDuoView: me and one other person, nothing shared: the 1:1 look. They fill the
    // screen (video, or their photo large); my card floats in a corner when anyone has video.
    val duo = !pip && g.joined && remotes.size == 1 && me != null && !duoShare
    var swapped by remember { mutableStateOf(false) }
    LaunchedEffect(duo) { if (!duo) swapped = false }
    Box(Modifier.fillMaxSize().background(if (pip) PipFill else Backdrop)) {
        if (waitingScreen) GroupWaiting(g.groupPhoto, pulse = g.calling)
        if (duo) DuoFeed(if (swapped && duoTile(me!!, remotes[0])) me else remotes[0], meId)
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = com.fariin.app.call.GroupCallWords.subtitle(g.waiting, g.connecting, g.reconnecting, g.joined, g.calling, g.members.size)
            // In the small window (picture in picture) only the people show, as iOS's floating card.
            // iOS duoPair header: two people (with or without a share) read like a 1:1 call, their name,
            // "Muted" while they are, and the clock since I came in instead of "2 in call".
            val pairOther = if (g.joined && remotes.size == 1) remotes[0] else null
            var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
            if (pairOther != null && g.joinedAt > 0) LaunchedEffect(Unit) { while (true) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
            val pairStatus = if (pairOther != null && g.joinedAt > 0 && !g.reconnecting)
                com.fariin.app.call.CallMachine.clock(((clockNow - g.joinedAt) / 1000).toInt()) else status
            if (!pip) CallHeader(
                name = pairOther?.name?.takeIf { it.isNotEmpty() } ?: g.title, status = pairStatus,
                muted = pairOther != null && !pairOther.micOn, mutedLabel = "Muted",
                left = onMinimize?.let { m -> { HeaderCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_collapse), "Minimize call", 44.dp) { chrome.touch(); m() } } },
                side = 44.dp,
                right = { HeaderCircle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_person2), "Participants", 44.dp) { chrome.touch(); people = true } },
                modifier = Modifier.alpha(shown).roomFactor(room),
            )
            Spacer(Modifier.height(2.dp))
            // iOS callTileMenu: a long press on someone's tile offers Pin / Unpin and, to a host,
            // Mute or Allow to Speak and Remove.
            val tileMenu = TileMenu(g, focus, canPin = remotes.size >= 2, onPin = { id -> chrome.touch(); focus = if (focus == id) null else id },
                onConfirm = { tileConfirm = it })
            Box(Modifier.weight(1f).fillMaxWidth().plainTap { chrome.tap(allowed) }) {
                if (!waitingScreen && !duo) androidx.compose.runtime.CompositionLocalProvider(LocalTileMenu provides tileMenu) {
                    GroupStage(g.members, meId, g.activeSpeaker, focus ?: presenter, g.sharing, pinned = focus,
                        onTile = { id ->
                            chrome.touch()
                            // A strip tile goes large (pinned); my own tile does nothing (iOS).
                            // Only someone with a picture (camera or screen) goes large (iOS 686b9eed5).
                            val t = remotes.firstOrNull { it.id == id }
                            if (id != meId && t != null && (t.video != null || t.screen != null) && (remotes.size >= 2 || presenter != null)) focus = id
                        },
                        // iOS GroupCallFocusView: a tap on the large tile shows or hides the controls,
                        // a swipe down unpins it.
                        onLargeTap = { chrome.tap(allowed) },
                        onUnpin = { chrome.touch(); focus = null },
                        onStopShare = { chrome.touch(); onShare() })
                }
                // iOS `notes`: who joined or left, and the call's short notes, under the header.
                if (!pip) GroupCallNotes(g, Modifier.align(Alignment.TopCenter))
            }
            if (g.linkCreator && g.requests.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CallLinkRequestStack(g.requests, Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.height(16.dp * room))
            if (!pip) ControlCapsule(Modifier.alpha(shown).roomFactor(room)) {
                val shareOn = g.sharing || g.sharePending
                // iOS bar (GroupCallView controls): More, Camera, Speaker, Mic, End. Share Screen and
                // Flip Camera are in More; More is lit white while I share.
                Box {
                    Circle(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_more_ios), g.sharing, "More", 56.dp) { chrome.touch(); more = true }
                    androidx.compose.material3.DropdownMenu(more, onDismissRequest = { more = false }, containerColor = Color(0xFF2C2C2E)) {
                        if (shareOn || (g.joined && presenter == null)) androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (shareOn) "Stop Sharing" else "Share Screen", color = Color.White) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(22.dp)) },
                            onClick = { more = false; chrome.touch(); onShare() })
                        if (g.cameraOn) androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Flip Camera", color = Color.White) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_flip_camera), null, tint = Color.White, modifier = Modifier.size(22.dp)) },
                            onClick = { more = false; onFlip() })
                        if (g.joined) GroupMoreSocial(g, onReact = { more = false; onReact(it) }, onHand = { more = false; onHand(it) })
                    }
                }
                // A voice-only call: the camera dimmed and still (iOS cameraLocked).
                Box(Modifier.alpha(if (g.cameraLocked) 0.35f else 1f)) {
                    Circle(if (g.cameraOn) ImageVector.vectorResource(R.drawable.ic_call_video) else ImageVector.vectorResource(R.drawable.ic_call_video_off),
                        !g.cameraOn && !g.cameraLocked, if (g.cameraOn) "Turn camera off" else "Turn camera on", 56.dp) {
                        chrome.touch(); if (!g.cameraLocked) onCamera()
                    }
                }
                SpeakerButton(g.route, 56.dp) { chrome.touch(); onRoute(it) }
                Box {
                    Circle(if (g.micOn) ImageVector.vectorResource(R.drawable.ic_mic) else ImageVector.vectorResource(R.drawable.ic_mic_off), !g.micOn, if (g.micOn) "Mute" else "Unmute", 56.dp) {
                        chrome.touch(); onMic()
                    }
                    // Muted by an admin: a small red lock says why the mic will not come on (iOS micLockedByAdmin).
                    if (g.micLocked) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(20.dp).clip(CircleShape)
                        .background(Color(0xFFFF3B30)).semantics { contentDescription = "Muted by an admin" }, contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_lock_fill), null, tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                }
                EndCircle("End call", 56.dp, onLeave)
            }
        }
        if (duo && duoTile(me!!, remotes[0])) {
            val other = remotes[0]
            val small = if (swapped) other else me
            SelfCard(grown = chrome.visible, chrome = chrome, label = if (small.local) "Your video" else "${small.name}'s video",
                onFlip = if (small.local && small.video != null) ({ onFlip() }) else null,
                onTap = { if (!chrome.visible) chrome.touch() else { chrome.touch(); swapped = !swapped } }) {
                DuoCardContent(small)
            }
        }
        // iOS GroupCallRaisedHandsPill: under the header while any hand is up.
        if (!pip && g.hands.isNotEmpty()) RaisedHandsPill(g, meId, onTap = { people = true }, onLower = { onHand(false) },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
        // iOS GroupCallSocialViews: reactions rise at the lower left with the sender's name, 4 s each.
        // iOS GroupCallReactionsOverlay: the newest five rows, 12 apart; each the emoji at 28 and the
        // name ("You" for mine) in a dark capsule, sliding up and fading in as it arrives.
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 120.dp).widthIn(max = 217.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            g.reactions.takeLast(5).forEach { r ->
                androidx.compose.runtime.key(r.id) {
                    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
                    LaunchedEffect(Unit) { enter.animateTo(1f, tween(200)) }
                    Row(Modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 36.dp.toPx() },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(r.emoji, fontSize = 28.sp, maxLines = 1)
                        Spacer(Modifier.width(18.dp))
                        Text(if (r.uid.isNotEmpty() && r.uid == meId) "You" else r.name, color = Color.White, fontSize = 15.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(CircleShape).background(Color(0xFF1C1C1E).copy(alpha = 0.75f)).padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (!chrome.visible) Box(Modifier.fillMaxSize().plainTap { chrome.touch() }.semantics { contentDescription = "Show call controls" })
        if (people) GroupCallParticipantsSheet(g, meId, onFocus = { id -> if (remotes.size >= 2) focus = id; people = false }) { people = false }
        else HostDialogs(g, tileConfirm) { tileConfirm = null }
    }
}

/**
 * iOS GroupCallRingingView as a group chat's call uses it while nobody has come: the group's
 * photo (160) a little above the middle on plain black, two white rings 1.2 s apart pulsing out of
 * it (each 2.4 s, ease-out, to 1.5 x, fading) while the call rings. No words: the header says them.
 */
@Composable
fun GroupWaiting(photo: String?, pulse: Boolean, size: Dp = 160.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
            if (pulse) for (i in 0..1) {
                val p = (phase + i * 0.5f) % 1f
                val e = 1 - (1 - p) * (1 - p)
                Box(Modifier.size(size).graphicsLayer { scaleX = 1 + 0.5f * e; scaleY = 1 + 0.5f * e; alpha = 0.9f * (1 - e) }
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape))
            }
            Box(Modifier.shadow(26.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))) {
                Avatar(photo, size, Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            }
        }
    }
}

/** iOS GroupCallMotion.layout: spring(response 0.38, damping 1). */
private fun <T> stageSpring() = spring<T>(dampingRatio = 1f, stiffness = 273f)

/**
 * The stage (iOS GroupCallGridView, GroupCallFocusView, GroupCallSelfView). Alone: my tile fills
 * it. One other: they fill it and my camera is the 90 x 160 card at the bottom right (a tap makes it
 * 170 x 300). Two or more: the grid (GroupCallLayout, me first) with the rest in the 96 strip under
 * it, the active speaker always on the grid. A tapped person goes large over the strip of everyone
 * else. Every tile moves to its new frame on the one stage spring.
 */
@Composable
private fun GroupStage(members: List<GroupMember>, meId: String?, speaker: String?, focus: String?, iAmSharing: Boolean,
                       pinned: String? = null, onTile: (String) -> Unit, onLargeTap: () -> Unit = {}, onUnpin: () -> Unit = {},
                       onStopShare: () -> Unit = {}) {
    val me = members.firstOrNull { it.id == meId }
    val remotes = members.filter { it.id != meId }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val big = remotes.firstOrNull { it.id == focus }
        when {
            remotes.isEmpty() -> if (me != null) MemberTile(me, true, false, Modifier.fillMaxSize(), alone = true, iAmSharing = iAmSharing) {}
            // iOS GroupCallDuoShareView: two people and a shared screen (either side's): the screen, or
            // my sharing card, in one big rounded card; the two people side by side under it.
            remotes.size == 1 && me != null && (iAmSharing || remotes[0].screen != null) -> {
                val other = remotes[0]
                val tileH = minOf(110f, maxOf(84f, h * 0.13f)).dp
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (iAmSharing) SharingCard(onStopShare, onLargeTap, Modifier.weight(1f).fillMaxWidth())
                    else MemberTile(other, false, false, Modifier.weight(1f).fillMaxWidth(), large = true, onClick = onLargeTap)
                    Row(Modifier.fillMaxWidth().height(tileH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MemberTile(other.copy(screen = null), false, other.id == speaker, Modifier.weight(1f).fillMaxHeight(), onClick = onLargeTap)
                        MemberTile(me, true, false, Modifier.weight(1f).fillMaxHeight(), iAmSharing = false, onClick = onLargeTap)
                    }
                }
            }
            // iOS GroupCallStagePager iAmSharing: the sharing card with Stop Sharing, everyone else under it.
            iAmSharing -> Column(Modifier.fillMaxSize()) {
                SharingCard(onStopShare, onLargeTap, Modifier.weight(1f).fillMaxWidth()
                    .padding(start = com.fariin.app.call.GroupCallMetrics.INSET.dp, end = com.fariin.app.call.GroupCallMetrics.INSET.dp,
                        top = com.fariin.app.call.GroupCallMetrics.INSET.dp))
                Strip(com.fariin.app.call.GroupStagePlan.newestFirst(remotes), meId, speaker, iAmSharing, onTile, Modifier)
            }
            big != null -> FocusStage(big, remotes, me, speaker, pinned == big.id, onTile, onLargeTap, onUnpin)
            else -> {
                val full = com.fariin.app.call.GroupCallLayout.capacity(w, h)
                val cells = if (remotes.size >= 2) members.size else remotes.size
                val needsStrip = cells > full
                val gridH = if (needsStrip) h - (com.fariin.app.call.GroupCallMetrics.STRIP_HEIGHT - com.fariin.app.call.GroupCallMetrics.INSET) else h
                val cap = com.fariin.app.call.GroupCallLayout.capacity(w, gridH)
                val ids = com.fariin.app.call.GroupStagePlan.gridIds(meId, remotes.map { it.id }, speaker, cap)
                val layout = com.fariin.app.call.GroupCallLayout.grid(ids, w, gridH)
                val byId = members.associateBy { it.id }
                ids.forEach { id ->
                    val f = layout.frames[id] ?: return@forEach
                    val m = byId[id] ?: return@forEach
                    androidx.compose.runtime.key(id) {
                        val x by animateDpAsState(f.x.dp, stageSpring(), label = "x")
                        val y by animateDpAsState(f.y.dp, stageSpring(), label = "y")
                        val tw by animateDpAsState(f.w.dp, stageSpring(), label = "w")
                        val th by animateDpAsState(f.h.dp, stageSpring(), label = "h")
                        // A newcomer's tile fades in from 0.96 (iOS: new tiles enter with opacity + scale).
                        val enter = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(Unit) { enter.animateTo(1f, tween(250)) }
                        MemberTile(m, m.id == meId, m.id == speaker && m.id != meId, Modifier.offset(x, y).size(tw, th)
                            .graphicsLayer { alpha = enter.value; val k = 0.96f + 0.04f * enter.value; scaleX = k; scaleY = k },
                            iAmSharing = iAmSharing) { onTile(m.id) }
                    }
                }
                if (layout.overflow.isNotEmpty()) {
                    Strip(layout.overflow.mapNotNull { byId[it] }, meId, speaker, iAmSharing, onTile,
                        Modifier.align(Alignment.BottomStart).offset(y = 0.dp))
                }
                if (remotes.size == 1 && me != null) SelfCard(me, w, h, iAmSharing, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/**
 * One person large (iOS GroupCallFocusView) over the strip of everyone else. A tap on the large tile
 * shows or hides the controls; a swipe down unpins it (only a pin; an auto-shown presenter stays).
 * Top left: "Presenting" on a shared screen, the raised hand with the name, else "Pinned". A presenter's
 * face stays in the strip (iOS: the screen is a tile of its own); I sit fixed at the strip's end.
 */
@Composable
private fun FocusStage(big: GroupMember, remotes: List<GroupMember>, me: GroupMember?, speaker: String?, isPinned: Boolean,
                       onTile: (String) -> Unit, onLargeTap: () -> Unit, onUnpin: () -> Unit) {
    val m = com.fariin.app.call.GroupCallMetrics
    val presenting = big.screen != null
    // iOS GroupCallStripView: newest first (GroupCallPriority.newestFirst).
    val stripPeople = com.fariin.app.call.GroupStagePlan.newestFirst(remotes.filter { it.id != big.id || presenting })
    val hands = LocalRaisedHands.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(m.INSET.dp)
            .pointerInput(isPinned) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = { if (isPinned && dy > 80.dp.toPx() && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) onUnpin() }) { c, d ->
                    c.consume(); dx += d.x; dy += d.y
                }
            }) {
            MemberTile(big, false, big.id == speaker, Modifier.fillMaxSize(), large = true, onClick = onLargeTap)
            val label = when {
                presenting -> "Presenting"
                big.id in hands -> null
                isPinned -> "Pinned"
                else -> null
            }
            if (label != null) Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 5.dp))
            else if (big.id in hands) RaisedHandChip(big.name, Modifier.padding(10.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Strip(stripPeople, me?.id, speaker, false, onTile, Modifier.weight(1f))
            if (me != null) MemberTile(me, true, false, Modifier.padding(end = m.STRIP_LEADING.dp, top = m.INSET.dp, bottom = m.STRIP_INSET.dp)
                .size(m.STRIP_TILE.dp), strip = true) {}
        }
    }
}

/** iOS showsTile: the corner card shows only when someone in the two-person call has video. */
private fun duoTile(me: GroupMember, other: GroupMember): Boolean = me.video != null || other.video != null

/** iOS GroupCallDuoView's big feed: the person's video filling the screen under a top scrim, else their photo at 180. */
@Composable
private fun DuoFeed(big: GroupMember, meId: String?) {
    val v = big.video
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        androidx.compose.animation.Crossfade(v != null, animationSpec = androidx.compose.animation.core.tween(200), label = "duoFeed") { live ->
            if (live && v != null) Box(Modifier.fillMaxSize()) {
                LiveKitVideo(v, Modifier.fillMaxSize(), mirror = big.id == meId)
                Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(big.photo, 180.dp, Modifier.shadow(26.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            }
        }
        if (big.poor) Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 72.dp).size(22.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f)).semantics { contentDescription = "${big.name}, poor connection" }, contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_wifi_slash), null, tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
}

/** What the corner card shows (iOS tileContent): the video, else the photo on black with a small camera-off mark. */
@Composable
private fun DuoCardContent(m: GroupMember) {
    val v = m.video
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (v != null) LiveKitVideo(v, Modifier.fillMaxSize(), mirror = m.local)
        else {
            Avatar(m.photo, 54.dp)
            Icon(ImageVector.vectorResource(R.drawable.ic_call_video_off), null, tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(13.dp))
        }
        if (!m.local && !m.micOn) Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_mic_off), "Muted", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** Lays a bar out at [f] of its height (0 = gone), clipped, so the stage below grows into its room. */
private fun Modifier.roomFactor(f: Float): Modifier = if (f >= 1f) this else this.clipToBounds().layout { m, c ->
    val p = m.measure(c)
    val h = (p.height * f).toInt()
    layout(p.width, h) { p.place(0, h - p.height) }
}

/** iOS GroupCallSharingCard: what I see while my screen goes out, with Stop Sharing. */
@Composable
private fun SharingCard(onStop: () -> Unit, onTap: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(com.fariin.app.call.GroupCallMetrics.TILE_CORNER.dp)).background(Color(0xFF1F1F1F))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(20.dp)) {
            Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(44.dp))
            Text("You're sharing your screen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Row(Modifier.height(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onStop)
                .padding(horizontal = 22.dp).semantics { contentDescription = "Stop sharing screen" },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stop Sharing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * iOS GroupCallRaisedHandsPill: the hand and a count ("3", "You +2"); the first hand opens it to say
 * who ("Ana +1 raised a hand", "Your hand is raised", with Lower for mine), then it folds after 4 s.
 * A tap on the folded pill opens it; on the open one, the people list.
 */
@Composable
private fun RaisedHandsPill(g: GroupCallUi, meId: String?, onTap: () -> Unit, onLower: () -> Unit, modifier: Modifier = Modifier) {
    val hands = g.hands
    val mine = meId != null && meId in hands
    var expanded by remember { mutableStateOf(false) }
    var openedAt by remember { mutableStateOf(0L) }
    fun expand() { expanded = true; openedAt = System.nanoTime() }
    var before by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(hands) {
        if (hands.isEmpty()) expanded = false
        else if (before.isEmpty()) expand()
        else if (expanded) openedAt = System.nanoTime()   // still changing while open: stays open longer
        before = hands
    }
    LaunchedEffect(mine) { if (mine) expand() }
    LaunchedEffect(expanded, openedAt) { if (expanded) { kotlinx.coroutines.delay(4_000); expanded = false } }
    val others = (hands.size - 1).coerceAtLeast(0)
    val first = g.members.firstOrNull { it.id == hands.firstOrNull() }?.name?.trim()?.substringBefore(' ')?.ifEmpty { null } ?: "Someone"
    val text = if (expanded) {
        if (mine) (if (others > 0) "You +$others raised a hand" else "Your hand is raised")
        else (if (others > 0) "$first +$others raised a hand" else "$first raised a hand")
    } else if (mine) (if (hands.size > 1) "You +${hands.size - 1}" else "You") else "${hands.size}"
    val corner by animateDpAsState(if (expanded) 10.dp else 24.dp, spring(dampingRatio = 1f, stiffness = 439f), label = "pillCorner")
    val shape = RoundedCornerShape(corner)
    Row(modifier.padding(horizontal = 16.dp).then(if (expanded) Modifier.widthIn(max = 512.dp).fillMaxWidth() else Modifier)
        .clip(shape).background(Color(0xFF1C1C1E).copy(alpha = 0.85f)).animateContentSize(spring(dampingRatio = 1f, stiffness = 439f)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.then(if (expanded) Modifier.weight(1f) else Modifier).clickable { if (expanded) onTap() else expand() }
            .padding(horizontal = if (expanded) 12.dp else 16.dp, vertical = 12.dp)
            .semantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_hand_raised_fill), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, color = Color.White, fontSize = 15.sp, maxLines = if (expanded) 3 else 1)
        }
        if (expanded && mine) Text("Lower", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onLower).padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                .semantics { contentDescription = "Lower hand" })
    }
}

/** iOS CallRaisedHandChip: the hand in a white circle, with the name in a dark capsule when given. */
@Composable
private fun RaisedHandChip(name: String?, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = if (name == null) 0f else 0.5f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_hand_raised_fill), "Hand raised", tint = Color.Black, modifier = Modifier.size(15.dp))
        }
        if (name != null) {
            Spacer(Modifier.width(6.dp))
            Text(name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
        }
    }
}

/** iOS GroupCallStripView: 96 squares 4 apart, 16 from the leading edge, 12 under it. */
@Composable
private fun Strip(people: List<GroupMember>, meId: String?, speaker: String?, iAmSharing: Boolean, onTile: (String) -> Unit, modifier: Modifier) {
    val m = com.fariin.app.call.GroupCallMetrics
    LazyRow(modifier.fillMaxWidth().height(m.STRIP_HEIGHT.dp).padding(top = m.INSET.dp, bottom = m.STRIP_INSET.dp),
        horizontalArrangement = Arrangement.spacedBy(m.STRIP_SPACING.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = m.STRIP_LEADING.dp, end = m.STRIP_LEADING.dp)) {
        items(people, key = { it.id }) { p ->
            MemberTile(p, p.id == meId, p.id == speaker && p.id != meId, Modifier.size(m.STRIP_TILE.dp), strip = true, iAmSharing = iAmSharing) { if (p.id != meId) onTile(p.id) }
        }
    }
}

/** My card with one other person here (iOS GroupCallSelfView): 90 x 160, a tap grows it on a 0.3 s spring. */
@Composable
private fun SelfCard(me: GroupMember, stageW: Float, stageH: Float, iAmSharing: Boolean, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val (tw, th) = com.fariin.app.call.GroupStagePlan.selfSize(expanded, stageW, stageH)
    val anim = spring<Dp>(dampingRatio = 1f, stiffness = 439f)
    val w by animateDpAsState(tw.dp, anim, label = "selfW")
    val h by animateDpAsState(th.dp, anim, label = "selfH")
    val shape = RoundedCornerShape(com.fariin.app.call.GroupCallMetrics.TILE_CORNER.dp)
    Box(modifier.padding(end = com.fariin.app.call.GroupCallMetrics.SELF_TRAILING.dp, bottom = com.fariin.app.call.GroupCallMetrics.STRIP_INSET.dp)
        .shadow(4.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))) {
        MemberTile(me, true, false, Modifier.size(w, h), pip = true, iAmSharing = iAmSharing) { expanded = !expanded }
    }
}

/**
 * One person's tile (iOS GroupCallTileView): video filling it, or the camera-off look (a dark
 * gradient with their round photo centred, sized by iOS avatarSize); the name ("You" on mine) in
 * a dark pill at the bottom start with the slashed mic inside it while muted (a lone round badge
 * on strip and pip tiles); the poor-network mark at the top start; the green rim and glow fading
 * in over 0.2 s on the active speaker (iOS SpeakerGlow).
 */
@Composable
private fun MemberTile(
    m: GroupMember, isMe: Boolean, activeSpeaker: Boolean, modifier: Modifier,
    large: Boolean = false, strip: Boolean = false, pip: Boolean = false, alone: Boolean = false, iAmSharing: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = com.fariin.app.call.GroupCallMetrics
    val shape = RoundedCornerShape(if (alone) 0.dp else metrics.TILE_CORNER.dp)
    val glow by animateFloatAsState(if (activeSpeaker) 1f else 0f, tween(200), label = "glow")
    val tileMenu = LocalTileMenu.current?.takeIf { !isMe && !pip && it.offers(m) }
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        if (glow > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }
            .shadow(14.dp, shape, clip = false, ambientColor = Green.copy(alpha = 0.35f), spotColor = Green.copy(alpha = 0.75f)))
        if (tileMenu != null) DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Color(0xFF2C2C2E)) {
            Text(m.name, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            HostMenuItems(tileMenu.g, m, canBlock = GroupCallAdmin.ownsLink(tileMenu.g.linkRoom != null, tileMenu.g.myRole, tileMenu.g.linkCreator), onConfirm = tileMenu.onConfirm, close = { menuOpen = false },
                pinned = if (tileMenu.canPin) tileMenu.pinnedId == m.id else null, onPin = { tileMenu.onPin(m.id) })
        }
        BoxWithConstraints(Modifier.matchParentSize().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF383838), Color(0xFF1F1F1F))))
            .then(if (tileMenu != null) Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }, onLongClickLabel = "Actions")
                else Modifier.clickable(onClick = onClick))
            .semantics {
                contentDescription = listOfNotNull(if (isMe) "You" else m.name, m.role.badge?.lowercase(), "speaking".takeIf { activeSpeaker },
                    "muted".takeIf { !m.micOn }, "camera off".takeIf { m.video == null }, "presenting".takeIf { m.screen != null },
                    "poor connection".takeIf { m.poor }).joinToString(", ")
            },
            contentAlignment = Alignment.Center) {
            val w = maxWidth.value
            val hh = maxHeight.value
            val v = m.video
            // The large tile shows a presenter's screen whole (never cropped, never mirrored); a strip
            // shows their face, not a second unreadable copy of the screen (iOS preferScreen).
            val scr = m.screen?.takeIf { large }
            if (scr != null) LiveKitVideo(scr, Modifier.fillMaxSize(), fit = true)
            else if (v != null) LiveKitVideo(v, Modifier.fillMaxSize(), mirror = isMe)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(m.photo, com.fariin.app.call.GroupStagePlan.avatarSize(w, hh).dp)
                if (alone) {
                    Spacer(Modifier.height(10.dp))
                    Text(m.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            val inset = if (w >= 170) 8.dp else 4.dp
            if (!strip && !pip && !alone && w >= 90) {
                Row(Modifier.align(Alignment.BottomStart).padding(inset).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!m.micOn && !large) { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), null, tint = Color.White, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(if (isMe) "You" else m.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (!m.micOn && !large) {
                val compact = pip && w < 60
                Box(Modifier.align(Alignment.BottomStart).padding(inset).size(if (compact) 16.dp else 28.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), null, tint = Color.White, modifier = Modifier.size(if (compact) 9.dp else 16.dp))
                }
            }
            // iOS GroupCallTileView: media still arriving = a spinner over the photo; a camera that is on
            // and never shows = "Can't show video" (a tap explains).
            if (scr == null && v == null && m.connecting) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center) { FariinSpinner(size = if (w > 102) 32.dp else 20.dp, color = Color.White) }
            else if (scr == null && v == null && m.videoUnavailable) {
                var why by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { why = true },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_call_video_off), null, tint = Color.White, modifier = Modifier.size(if (w > 102) 24.dp else 16.dp))
                    if (w > 102) Text("Can't show video", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
                if (why) AlertDialog(onDismissRequest = { why = false }, title = { Text("Can't show video") },
                    text = { Text("${m.name}'s video can't be shown right now. Their connection may be weak.") },
                    confirmButton = { TextButton({ why = false }) { Text("OK") } })
            }
            // I am sharing my screen (owner 2026-10-28): my own tile says so (iOS sharingCard).
            if (isMe && iAmSharing) Column(Modifier.fillMaxSize().background(ShareCardFill.copy(alpha = 0.92f)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                Icon(painterResource(R.drawable.ic_share_screen), null, tint = Color.White, modifier = Modifier.size(if (w >= 150) 32.dp else 20.dp))
                if (w >= 150) Text("You're sharing your screen", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp))
            }
            // iOS tile hand mark: the white circle at the top start (the large tile has its own chip).
            if (m.id in LocalRaisedHands.current && !large && !(pip && w < 60)) RaisedHandChip(null, Modifier.align(Alignment.TopStart).padding(inset))
            if (m.poor && !(pip && w < 60)) {
                Box(Modifier.align(if (large) Alignment.TopEnd else Alignment.TopStart).padding(inset).size(22.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_wifi_slash), null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        if (glow > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = glow }
            .border(metrics.SPEAKING_BORDER.dp, Green.copy(alpha = 0.95f), shape))
    }
}

/**
 * The people in the call (iOS GroupCallParticipantsSheet): always dark, full height, "Participants"
 * ("Waiting for others" while I am alone) with a close button at the end; the link creator's
 * "N waiting to join" row; then "In call · N": a 36 photo with a thin green ring on the active
 * speaker, the name (semibold while speaking, "You" on mine), the Host / Admin badge under it,
 * and only the marks that say something: poor network, camera on, muted. A tap on someone shows
 * them large and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCallParticipantsSheet(g: GroupCallUi, meId: String?, onFocus: (String) -> Unit, onClose: () -> Unit) {
    var showRequests by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<HostConfirm?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1_500); copied = false } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val isLink = g.linkRoom != null
    val canBlock = GroupCallAdmin.ownsLink(isLink, g.myRole, g.linkCreator)
    val endBar = GroupCallAdmin.showEndForAll(g.myRole, g.joined)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black, contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxSize().padding(bottom = if (endBar) 8.dp else 24.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)) {
                Text(com.fariin.app.call.GroupCallWords.sheetTitle(g.members.size <= 1), color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            // iOS groupAddSection / shareSection: Add People first (it rings), then the link's Copy
            // Link and Share Link. Everyone in the call may hand the link on.
            if (g.joined && (!isLink || g.linkUrl != null)) {
                SheetSection {
                    Column {
                        SheetPlainRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_add_member), "Add People") { showAdd = true }
                        g.linkUrl?.let { url ->
                            SheetPlainRow(if (copied) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_doc_on_doc), if (copied) "Copied" else "Copy Link") {
                                copyLink(context, url); copied = true
                            }
                            SheetPlainRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_share), "Share Link") { shareLink(context, url) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            // iOS approvalRow: the link's owner turns Approve New People on or off from the call.
            if (g.linkCreator && g.linkRoom != null) {
                LinkApprovalRow(g.linkRoom!!)
                Spacer(Modifier.height(20.dp))
            }
            if (g.linkCreator && g.requests.isNotEmpty()) {
                SheetSection {
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable { showRequests = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_add_member), null, tint = Color(0xFF0A84FF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(com.fariin.app.call.GroupCallWords.waitingTitle(g.requests.size), color = Color(0xFF0A84FF), fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_chevron_right), null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            // iOS GroupCallRaisedHandsSection: who has a hand up, oldest first; Lower on my own row.
            if (g.hands.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Raised hands", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(" · ${g.hands.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
                SheetSection {
                    Column {
                        g.hands.forEach { uid ->
                            val who = g.members.firstOrNull { it.id == uid }
                            val mine = uid == meId
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp)
                                .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Avatar(who?.photo, 36.dp) }
                                Spacer(Modifier.width(8.dp))
                                Text(if (mine) "You" else who?.name ?: "Someone", color = Color.White, fontSize = 17.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (mine) Text("Lower", color = Color(0xFF0A84FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Lower my hand") { GroupCall.setHand(false) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp))
                                Spacer(Modifier.width(8.dp))
                                Icon(ImageVector.vectorResource(R.drawable.ic_sf_hand_raised_fill), null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In call", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(" · ${g.members.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                // iOS: "Mute all" beside the count for whoever may mute, once two people can be muted.
                if (GroupCallAdmin.showMuteAll(meId, g.myRole, g.members)) {
                    Text("Mute all", color = Color(0xFF0A84FF).copy(alpha = if (g.mutingAll) 0.4f else 1f), fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !g.mutingAll) { GroupCall.muteAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            // The list scrolls in the room left; the owner's End bar stays pinned under it.
            Column(Modifier.weight(1f)) {
            if (isLink && g.joined) NotHereYet(g, meId)
            SheetSection {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(g.members, key = { it.id }) { m ->
                        val mine = m.id == meId
                        val speaking = m.id == g.activeSpeaker && !mine
                        val ring by animateFloatAsState(if (speaking) 1f else 0f, tween(200), label = "ring")
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .then(if (mine) Modifier else Modifier.clickable { onFocus(m.id) })
                            .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Avatar(m.photo, 36.dp)
                                Box(Modifier.size(44.dp).graphicsLayer { alpha = ring }.border(2.dp, Green, CircleShape))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (mine) "You" else m.name, color = Color.White, fontSize = 17.sp,
                                    fontWeight = if (speaking) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                m.role.badge?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (m.poor) Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_wifi_slash), "Poor connection", tint = Color(0xFFFF9F0A), modifier = Modifier.size(20.dp))
                                if (m.video != null) Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                val host = GroupCallAdmin.mayModerate(meId, g.myRole, m)
                                if (!m.micOn && !host) Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), "Muted", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                            }
                            if (GroupCallAdmin.mayModerate(meId, g.myRole, m)) HostRowControls(g, m, canBlock) { confirm = it }
                        }
                    }
                }
            } }
            if (endBar) {
                // iOS endBar: the owner only, pinned under the list.
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF1C1C1E)).clickable { confirm = HostConfirm.End }, contentAlignment = Alignment.Center) {
                    Text("End Call for Everyone", color = Color(0xFFFF453A), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (showRequests) CallLinkBulkRequestsSheet(g.requests) { showRequests = false }
    if (showAdd) GroupAddPeopleSheet(g) { showAdd = false }
    HostDialogs(g, confirm) { confirm = null }
}

/**
 * iOS notConnectedSection (owner 2026-10-23): on a link call, everyone Add People rang who is not in
 * the call now, with how it went ("Ringing…", "Declined", "Missed", "Left") and a bell: somebody who
 * declined or missed is not rung again; the bell nudges them instead. No bell for someone who left.
 */
@Composable
private fun NotHereYet(g: GroupCallUi, meId: String?) {
    val room = g.linkRoom ?: return
    var invites by remember(room) { mutableStateOf<List<com.fariin.app.call.LinkInvite>>(emptyList()) }
    val people = remember(room) { androidx.compose.runtime.mutableStateMapOf<String, Pair<String, String?>>() }
    val nudgedAt = remember(room) { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(3_000); toast = null } }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }
    androidx.compose.runtime.DisposableEffect(room) {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val reg = db.collection("callLinks").document(room).collection("invites").addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            invites = snap.documents.mapNotNull { d ->
                val uid = d.getString("uid")?.ifEmpty { null } ?: d.id
                com.fariin.app.call.LinkInvite(uid, d.getTimestamp("at")?.toDate()?.time, d.getString("sid")?.ifEmpty { null }, d.getString("state") ?: "ringing")
            }.sortedBy { it.atMs ?: 0L }
            for (i in invites) if (i.uid !in people) {
                people[i.uid] = "User" to null
                db.collection("users").document(i.uid).get().addOnSuccessListener { u ->
                    people[i.uid] = (u.getString("name")?.ifEmpty { null } ?: "User") to u.getString("photoUrl")?.ifEmpty { null }
                }
            }
        }
        onDispose { reg.remove() }
    }
    val sid = GroupCall.roomSessionId()
    val here = g.members.map { it.id }.toSet()
    val rows = invites.filter { it.uid !in here && it.uid != meId && (sid == null || it.sid == null || it.sid == sid) }
    if (rows.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Not here yet", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(" \u00b7 ${rows.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    }
    SheetSection {
        Column {
            rows.forEach { i ->
                val (name, photo) = people[i.uid] ?: ("User" to null)
                val status = com.fariin.app.call.LinkInvite.status(i, now)
                val waiting = nudgedAt[i.uid]?.let { now - it < 60_000 } == true
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Avatar(photo, 36.dp) }
                    Spacer(Modifier.width(8.dp))
                    Text(name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1)
                    if (i.state != "joined") {
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                            .clickable(enabled = !waiting) {
                                nudgedAt[i.uid] = System.currentTimeMillis()
                                GroupCall.nudge(i.uid, name) { ok, line -> if (!ok) nudgedAt.remove(i.uid); toast = line }
                            }.graphicsLayer { alpha = if (waiting) 0.4f else 1f }
                            .semantics { contentDescription = "Nudge $name" }, contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_bell), null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
    toast?.let { Text(it, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
    Spacer(Modifier.height(20.dp))
}

/** iOS GroupCallParticipantsSheet.approvalRow: the switch, a spinner while it loads, Retry if it could not. */
@Composable
private fun LinkApprovalRow(roomId: String) {
    val scope = rememberCoroutineScope()
    var value by remember(roomId) { mutableStateOf<Boolean?>(null) }
    var failed by remember(roomId) { mutableStateOf(false) }
    var saving by remember(roomId) { mutableStateOf(false) }
    suspend fun load() {
        failed = false
        val d = runCatching { com.fariin.app.call.CallLinks.readDoc(roomId) }.getOrNull()
        if (d != null) value = d["restrictions"] == "adminApproval" else if (value == null) failed = true
    }
    LaunchedEffect(roomId) { load() }
    SheetSection {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Approve New People", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            val v = value
            when {
                v != null -> androidx.compose.material3.Switch(checked = v, enabled = !saving, onCheckedChange = { on ->
                    val before = v
                    value = on; saving = true
                    scope.launch {
                        val ok = runCatching { com.fariin.app.call.CallLinks.setApproval(roomId, on) }.isSuccess
                        if (!ok) { value = before; GroupCall.say("Couldn't change that. Try again.") }
                        saving = false
                    }
                }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Green))
                failed -> Text("Couldn't load. Retry", color = Color(0xFF0A84FF), fontSize = 15.sp,
                    modifier = Modifier.clickable { scope.launch { load() } })
                else -> FariinSpinner(size = 18.dp, color = Color.White)
            }
        }
    }
}

/**
 * A row I may act on (iOS inCallRow with canModerate): the one-tap mic (mute, or red and struck
 * through while an admin's mute holds: tap to allow them to speak; a self-muted person's plain
 * muted mic mutes nothing more) and the "..." menu.
 */
@Composable
private fun HostRowControls(g: GroupCallUi, m: GroupMember, canBlock: Boolean, onConfirm: (HostConfirm) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val blocked = shownBlocked(g, m)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = when { blocked -> "Allow ${m.name} to speak"; m.micOn -> "Mute ${m.name}"; else -> "${m.name} is muted" }
        Box(Modifier.size(44.dp).clip(CircleShape)
            .clickable(enabled = blocked || m.micOn) { GroupCall.tapMic(m.id, mute = !blocked) }
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(if (blocked || !m.micOn) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic), null,
                tint = if (blocked) Color(0xFFFF453A) else Color.White.copy(alpha = if (m.micOn) 0.9f else 0.6f), modifier = Modifier.size(22.dp))
        }
        Box {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }.semantics { contentDescription = "Actions for ${m.name}" },
                contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_more_ios), null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF2C2C2E)) {
                HostMenuItems(g, m, canBlock, onConfirm, close = { menu = false })
            }
        }
    }
}

/** iOS inset grouped section on the dark sheet. */
@Composable
private fun SheetSection(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1E))) { content() }
}

/**
 * A LiveKit camera track in a tile, on a TextureView renderer: tiles change size on the stage
 * spring and the self card grows, and a TextureView scales with its layout where a SurfaceView
 * would tear or flash.
 */
@Composable
private fun LiveKitVideo(track: io.livekit.android.room.track.VideoTrack, modifier: Modifier, mirror: Boolean = false, fit: Boolean = false) {
    val room = GroupCall.room() ?: return
    AndroidView(modifier = modifier, factory = { ctx ->
        TextureViewRenderer(ctx).also {
            room.initVideoRenderer(it)
            it.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }, update = { r ->
        r.setMirror(mirror)
        val old = r.tag as? io.livekit.android.room.track.VideoTrack
        if (old !== track) { old?.removeRenderer(r); track.addRenderer(r); r.tag = track }
    }, onRelease = { r ->
        (r.tag as? io.livekit.android.room.track.VideoTrack)?.removeRenderer(r)
        r.release()
    })
}


/** My own profile photo, from this phone's copy first, then the server (calls' own tiles). */
@Composable
internal fun rememberMyPhoto(): String? {
    val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    val photo by androidx.compose.runtime.produceState<String?>(null, me) {
        if (me == null) return@produceState
        val ref = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me)
        value = runCatching { ref.get(com.google.firebase.firestore.Source.CACHE).await().getString("photoUrl") }.getOrNull()?.ifEmpty { null }
            ?: runCatching { ref.get().await().getString("photoUrl") }.getOrNull()?.ifEmpty { null }
    }
    return photo
}
