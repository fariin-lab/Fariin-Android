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
    // Power Saving's video autoplay off (the default on a LOW phone, and under Battery Saver) or system
    // animations off: the still shows (iOS: Low Power / Reduce Motion). Each clip frame redraws the
    // whole window; on the Oppo the chat header's clip doubled the frames of every send. A clip the
    // person is choosing ([file], Edit Profile's preview) always plays.
    if (file == null && (!com.fariin.app.perf.rememberPowerState().autoplayVideos || !com.fariin.app.ui.thread.systemAnimationsOn())) return
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
