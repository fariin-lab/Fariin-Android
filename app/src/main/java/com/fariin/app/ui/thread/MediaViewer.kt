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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
    /** [once]: a view-once photo (cid + message id): no Save, spent the moment it shows. */
    data class Photo(val url: String, val enc: EncMeta?, val once: Pair<String, String>? = null) : Viewing()
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
            val once = (v as? Viewing.Photo)?.once
            if (once == null) SaveButton(v, Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopEnd))
            else Text("View once", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopEnd))
        }
    }
}

/** Pinch to zoom (1x to 5x), drag while zoomed, double tap for 2.5x and back. */
@Composable
private fun ZoomablePhoto(v: Viewing.Photo, onClose: () -> Unit) {
    val file = rememberPlainFile(v.url, v.enc)
    val ctx = LocalContext.current
    // Full screen: twice the screen's long side keeps a 2x zoom sharp; a low-RAM phone stops at
    // 1440, so a big camera photo never needs 48 MB of pixels.
    val longPx = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    val cap = com.fariin.app.media.LowEnd.viewerSide(longPx * 2, com.fariin.app.media.LowEnd.isLowRam(ctx))
    val bmp = rememberBitmap(file, cap, maxLongPx = cap)
    // iOS: a view-once photo is spent the moment the picture is in hand (ViewedOnce.mark, then the
    // server burn); the plain copy this phone opened is removed when the viewer closes.
    if (v.once != null && bmp != null) LaunchedEffect(v.once) {
        com.fariin.app.media.ViewedOnce.mark(ctx, v.once.second)
        com.fariin.app.media.OnceBurn.burn(v.once.first, v.once.second)
    }
    if (v.once != null) DisposableEffect(file) { onDispose { file?.delete() } }
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

/** Save to the phone's gallery (MediaStore, no storage permission). */
@Composable
private fun SaveButton(v: Viewing, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (url, enc) = when (v) { is Viewing.Photo -> v.url to v.enc; is Viewing.Video -> v.url to v.enc }
    val file = rememberPlainFile(url, enc)
    val doSave: () -> Unit = {
        file?.let { f ->
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.fariin.app.media.GallerySaver.save(ctx, f, v is Viewing.Video)
                }
                android.widget.Toast.makeText(ctx, if (ok) "Saved to Photos" else "Couldn't save", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Android 8 and 9: the storage permission is asked the first time Save is tapped.
    val askStorage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSave()
        else android.widget.Toast.makeText(ctx, com.fariin.app.media.GallerySaver.PERMISSION_NOTICE, android.widget.Toast.LENGTH_SHORT).show()
    }
    IconButton(enabled = file != null, modifier = modifier, onClick = {
        if (com.fariin.app.media.GallerySaver.needsPermission(ctx)) askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else doSave()
    }) { Icon(Icons.Filled.Download, "Save", tint = Color.White) }
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
