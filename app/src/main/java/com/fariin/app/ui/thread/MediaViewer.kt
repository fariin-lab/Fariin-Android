package com.fariin.app.ui.thread

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fariin.app.crypto.EncMeta
import com.fariin.app.media.VoicePlayer

/** What the full-screen viewer shows. */
sealed class Viewing {
    data class Photo(val url: String, val enc: EncMeta?) : Viewing()
    data class Video(val url: String, val enc: EncMeta?) : Viewing()
}

@Composable
internal fun MediaViewer(v: Viewing, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (v) {
                is Viewing.Photo -> ZoomablePhoto(v, onClose)
                is Viewing.Video -> VideoPlayerView(v)
            }
            IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

/** Pinch to zoom (1x to 5x), drag while zoomed, double tap for 2.5x and back. */
@Composable
private fun ZoomablePhoto(v: Viewing.Photo, onClose: () -> Unit) {
    val file = rememberPlainFile(v.url, v.enc)
    val bmp = rememberBitmap(file, 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            Image(
                bmp, "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val e = awaitPointerEvent()
                                val zoom = e.calculateZoom()
                                val pan = e.calculatePan()
                                val multi = e.changes.size > 1
                                if (multi || scale > 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxX = (scale - 1) * size.width / 2
                                    val maxY = (scale - 1) * size.height / 2
                                    offset = if (scale == 1f) Offset.Zero else Offset(
                                        (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                                    )
                                    e.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (e.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}

@Composable
private fun VideoPlayerView(v: Viewing.Video) {
    val ctx = LocalContext.current
    val file = rememberPlainFile(v.url, v.enc)
    if (file == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
    }
    val player = remember(file) {
        VoicePlayer.stop()   // one sound at a time
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize())
}
