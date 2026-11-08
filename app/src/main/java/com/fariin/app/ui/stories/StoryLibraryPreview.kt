package com.fariin.app.ui.stories

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.fariin.app.media.RecentMedia
import com.fariin.app.ui.thread.attachThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A long press on a library tile (iOS StoryPhotoPreview, the picture iOS lifts in the tile's
 * context menu): the screen dims, the picture comes up big in its own shape, and under it the one
 * action the tap already does ("Add to Story", or "Select" while ticking several). A tap anywhere
 * else puts it away. A medium tap on the lift, as iOS.
 *
 * The shape is fixed from the first picture there is, the tile's own (it keeps the photo's aspect),
 * as iOS measures its preview once at the lift; the sharp one only replaces the pixels.
 */
@Composable
internal fun StoryLibraryPreview(
    item: RecentMedia.Item,
    first: ImageBitmap?,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val sharp by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { attachThumb(context, item.uri, item.isVideo, 1280) }
    }
    val shape = first ?: sharp
    val ratio = shape?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
    val shown = sharp ?: first
    val lift = remember { Animatable(0f) }
    val leaving = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) {
        storyHaptic(view, StoryHaptic.MEDIUM)
        if (com.fariin.app.ui.components.Motion.on) lift.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 520f)) else lift.snapTo(1f)
    }
    // Down first, then what was asked (iOS runs a menu action once the menu has gone).
    val close: (() -> Unit) -> Unit = { then ->
        if (!leaving[0]) {
            leaving[0] = true
            scope.launch {
                if (com.fariin.app.ui.components.Motion.on) lift.animateTo(0f, tween(150))
                then()
            }
        }
    }
    Dialog(onDismissRequest = { close(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        InstantDialogWindow()
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = lift.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { close(onDismiss) } },
            contentAlignment = Alignment.Center,
        ) {
            // iOS box: at most 360 wide (20 off each edge) and 62% of the screen tall (560 at most),
            // the picture's own shape fitted inside, never under 80 on its short side.
            val wide = minOf(maxWidth - 40.dp, 360.dp)
            val tall = minOf(maxHeight * 0.62f, 560.dp)
            val bw = if (wide / tall > ratio) maxOf(80.dp, tall * ratio) else wide
            val bh = if (wide / tall > ratio) tall else maxOf(80.dp, wide / ratio)
            Column(
                Modifier.graphicsLayer {
                    val s = 0.86f + 0.14f * lift.value
                    scaleX = s; scaleY = s
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(bw, bh).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    shown?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.width(250.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2C2C2E))
                        .clickable { close(onAction) }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(actionLabel, color = Color.White, fontSize = 17.sp)
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_plus_circle), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** A full-screen dialog window that appears and goes with no window animation (the content moves itself). */
@Composable
internal fun InstantDialogWindow() {
    com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(0) }
}
