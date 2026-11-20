package com.fariin.app.ui.people

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.fariin.app.data.AvatarVideo
import com.fariin.app.data.AvatarVideoCache
import com.fariin.app.ui.theme.Fariin
import java.io.File

/** Whether the tab this sits in is the one on screen (MainShell's KeptTab); null = always. */
val LocalTabShown = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>?> { null }

/**
 * A muted, looping avatar clip over the still (iOS AvatarVideoLayer). Fully transparent until its
 * first frame is drawn, and whenever there is nothing to play, so the photo under it always shows.
 * Pauses when the app leaves the screen or [playing] is false; released when it leaves composition.
 * Never catches a touch. [file] (a local clip, Edit Profile's preview) wins over [uid]/[video].
 */
@Composable
fun AvatarVideoLayer(uid: String?, video: AvatarVideo?, modifier: Modifier = Modifier, file: File? = null, playing: Boolean = true) {
    val ctx = LocalContext.current.applicationContext
    val source by produceState<File?>(null, file, uid, video) {
        value = when {
            file != null -> file
            uid.isNullOrEmpty() || video == null || !video.on -> null
            else -> AvatarVideoCache.get(ctx, uid, video.v)
        }
    }
    val f = source ?: return
    // A new clip gets its own player and its own view (the view's factory runs once).
    androidx.compose.runtime.key(f) { ClipPlayer(f, modifier, playing) }
}

@Composable
private fun ClipPlayer(f: File, modifier: Modifier, playing: Boolean) {
    val ctx = LocalContext.current.applicationContext
    var shown by remember(f) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(250), label = "avatarVideo")
    val player = remember(f) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            addListener(object : Player.Listener { override fun onRenderedFirstFrame() { shown = true } })
            prepare()
        }
    }
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var started by remember { mutableStateOf(life.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    val tabShown = LocalTabShown.current?.value ?: true
    LaunchedEffect(player, started, playing, tabShown) { player.playWhenReady = started && playing && tabShown }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        modifier = modifier.graphicsLayer { this.alpha = alpha })
}

/**
 * A picked video longer than 10 seconds: which 10 seconds to use (the system has no trimmer to
 * borrow, as iOS does). A preview of the part from the slider's start, and Use Video.
 */
@Composable
internal fun AvatarVideoTrimSheet(uri: Uri, durationMs: Long, onUse: (Long) -> Unit, onCancel: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val maxStart = (durationMs - AvatarVideo.MAX_MS).coerceAtLeast(0L)
    var start by remember { mutableFloatStateOf(0f) }
    // The view fills the circle with the middle of the picture (as the clip will), not a squashed whole.
    val view = remember { arrayOfNulls<TextureView>(1) }
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    view[0]?.let { centerCrop(it, videoSize) }
                }
            })
            setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Plays the chosen 10 seconds over and over.
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(200)
            val s = (start * maxStart).toLong()
            if (player.currentPosition > s + AvatarVideo.MAX_MS || player.currentPosition < s - 300) player.seekTo(s)
        }
    }
    val motion = com.fariin.app.ui.components.rememberSheetMotion(onCancel)
    com.fariin.app.ui.components.FariinSheetDialog(motion, fill = c.card) { _ ->
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Text("Choose 10 Seconds", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(240.dp).clip(CircleShape).background(c.fieldFill)) {
                AndroidView(factory = { k -> TextureView(k).also { tv ->
                    view[0] = tv; player.setVideoTextureView(tv)
                    tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> centerCrop(v as TextureView, player.videoSize) }
                } }, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(20.dp))
            val s = (start * maxStart).toLong()
            Text("${clock(s)} to ${clock(minOf(durationMs, s + AvatarVideo.MAX_MS))}", fontSize = 15.sp, color = c.secondary)
            Slider(start, { start = it; player.seekTo((it * maxStart).toLong()) }, modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent).clickable { onUse(s); motion.close() },
                    contentAlignment = Alignment.Center) { Text("Use Video", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) }
                Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.fieldFill).clickable { motion.close() },
                    contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 17.sp, color = c.label) }
            }
        }
    }
}

/** Scales the picture so its middle fills [tv] (TextureView stretches it to the view by default). */
private fun centerCrop(tv: TextureView, size: androidx.media3.common.VideoSize) {
    val vw = size.width * size.pixelWidthHeightRatio
    val vh = size.height.toFloat()
    val w = tv.width.toFloat(); val h = tv.height.toFloat()
    if (vw <= 0f || vh <= 0f || w <= 0f || h <= 0f) return
    // Stretched to w x h first; undo that and scale up to cover.
    val cover = maxOf(w / vw, h / vh)
    val sx = vw * cover / w; val sy = vh * cover / h
    tv.setTransform(android.graphics.Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
