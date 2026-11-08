package com.fariin.app.ui.thread

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.fariin.app.data.Message
import com.fariin.app.media.LowEnd
import com.fariin.app.media.OnceBurn
import com.fariin.app.media.ViewedOnce
import java.io.File
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * Where a view-once photo is seen (owner redesign 2026-11-07, as on iOS): full screen and dark,
 * kept out of screenshots and screen recordings (FLAG_SECURE on this window). Pinch to zoom and
 * pan freely. It stays until X (or the system Back) closes it or the app goes to the background:
 * no swipe away, no timer, no save, share, forward or draw.
 * The photo is spent the moment it is in hand (ViewedOnce here, the server burn there), and the
 * plain copy this phone made is deleted when the page goes.
 */
@Composable
internal fun OneTimePhotoPage(m: Message, cid: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val media = chatMedia()
    var file by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        LaunchedEffect(m.id) {
            val url = m.imageUrl
            val f = url?.let { media.file(it, m.enc) }
            if (f == null) failed = true else file = f
        }
        // Full screen: the screen's long side, a low-RAM phone capped as the shared viewer does.
        val longPx = remember { ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) } }
        val cap = remember { LowEnd.viewerSide(longPx * 2, LowEnd.isLowRam(ctx)) }
        val bmp = rememberBitmap(file, cap, maxLongPx = cap)
        if (bmp != null) LaunchedEffect(m.id) {
            ViewedOnce.mark(ctx, m.id)
            OnceBurn.burn(cid, m.id)
        }
        DisposableEffect(file) { onDispose { file?.delete() } }

        CloseWhenBackgrounded(onClose)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                // Pinch to zoom (1x to 5x), drag while zoomed, double tap for 2.5x and back.
                bmp != null -> Image(bmp, "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else {
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                                }
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                failed -> Text("Could not load this photo.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                else -> com.fariin.app.ui.components.FariinSpinner(size = 30.dp, stroke = 3.dp, color = Color.White)
            }
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = Color.White, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                DashedOnceRing(Color.White.copy(alpha = 0.9f), showOne = true, side = 20.dp, digitSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text("View once photo", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/**
 * Leaving a view-once viewer is final, and going to the background is leaving: the page closes
 * on ON_STOP, so coming back finds the bubble "Opened", never the open media.
 */
@Composable
internal fun CloseWhenBackgrounded(onClose: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) close() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
