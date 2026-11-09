package com.fariin.app.ui.stories

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.StoryCardRules
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.aspectRatio
import com.fariin.app.data.StoryMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where each library tile sits on screen (screen px), so a picked photo can fly out of it (iOS
 * PickerFlySource / MediaOpenRects for the story scope). Keyed by the item's uri; [hiddenKey] is the
 * tile whose picture is in the air, drawn empty so it is never on screen twice.
 */
object StoryLibraryTiles {
    val rects = mutableStateMapOf<String, Rect>()
    val radii = mutableStateMapOf<String, Float>()
    var hiddenKey by mutableStateOf<String?>(null)

    fun key(uri: Uri): String = uri.toString()

    /** A tile for [uri] is on screen right now, so a pick of it can fly. */
    fun has(uri: Uri): Boolean = rects[key(uri)]?.let { it.width > 1f } == true
}

/** A picked library item on its way into the editor. */
class LibraryFlight(val uri: Uri, val media: StoryMedia.Prepared,
                    /** Closing the editor: from the card back into its tile (iOS, owner's video 2026-11-09). */
                    val back: Boolean = false)

/**
 * THE PICKED PHOTO FLIES OUT OF ITS TILE INTO THE EDITOR (iOS AddStorySheet.openLibraryImage /
 * flyFromTile, the reference's gallery animateIn): the editor's card, wash and all, aspect-filled
 * into the tile, grows to where the editor draws it over 0.4 s on the (0.38, 0.70, 0.125, 1.0)
 * cubic, its corners opening to the card's 40; the black under it fades in over 0.35 s. A video
 * flies its poster. The tile is empty while the copy is in the air.
 *
 * Its own window over the library; once it lands [onLanded] raises the editor over it with the
 * picture in the same place, and the copy goes a moment later ([onDone]) so there is no gap.
 * No tile on screen, or animations off: straight to the editor.
 */
@Composable
internal fun StoryLibraryFlightLayer(flight: LibraryFlight, onLanded: () -> Unit, onDone: () -> Unit) {
    val landed by rememberUpdatedState(onLanded)
    val done by rememberUpdatedState(onDone)
    val view = LocalView.current
    val density = LocalDensity.current
    val key = StoryLibraryTiles.key(flight.uri)
    val media = flight.media
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var wash by remember { mutableStateOf<ImageBitmap?>(null) }
    var edges by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var from by remember { mutableStateOf<Rect?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val t = remember { Animatable(0f) }
    val black = remember { Animatable(0f) }
    LaunchedEffect(flight) {
        val start = StoryLibraryTiles.rects[key]
        if (start == null || !com.fariin.app.ui.components.Motion.on) { landed(); done(); return@LaunchedEffect }
        photo = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(media.jpeg, 0, media.jpeg.size)?.asImageBitmap() }.getOrNull()
        }
        // The editor's ground behind a picture that does not fill the card (its two edge colours).
        edges = photo?.let { p -> withContext(Dispatchers.Default) { runCatching { RepostCard.edgeColours(p.asAndroidBitmap()) }.getOrNull() } }
        wash = withContext(Dispatchers.Default) {
            runCatching {
                val b = android.util.Base64.decode(media.blurThumb, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (photo == null) { landed(); done(); return@LaunchedEffect }
        snapshotFlow { box }.first { it.width > 0 }
        if (flight.back) {
            // BACK INTO THE TILE: the copy covers the editor's card where it was, the editor goes
            // under it ([onLanded]), then the copy shrinks into its tile as the black fades to the grid.
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            from = (StoryLibraryTiles.rects[key] ?: start).translate(-loc[0].toFloat(), -loc[1].toFloat())
            t.snapTo(1f); black.snapTo(1f)
            StoryLibraryTiles.hiddenKey = key
            withFrameNanos { }
            landed()
            withFrameNanos { }
            launch { black.animateTo(0f, tween(260, easing = LinearEasing)) }
            t.animateTo(0f, tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            if (StoryLibraryTiles.hiddenKey == key) StoryLibraryTiles.hiddenKey = null
            done()
            return@LaunchedEffect
        }
        // Read now, not at the tap: the grid may have moved while the picture was made ready.
        val now = StoryLibraryTiles.rects[key] ?: start
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        from = now.translate(-loc[0].toFloat(), -loc[1].toFloat())
        StoryLibraryTiles.hiddenKey = key
        launch { black.animateTo(1f, tween(350, easing = LinearEasing)) }
        t.animateTo(1f, tween(StoryPostFlight.FLIGHT_MS, easing = StoryPostFlight.EASING))
        landed()
        // The editor's window draws its first frames over the copy; then the copy and the hole go.
        delay(320)
        if (StoryLibraryTiles.hiddenKey == key) StoryLibraryTiles.hiddenKey = null
        done()
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        InstantDialogWindow()
        // The editor's card (StoryComposer: full width, 8 under the status bar, 9:16 or as tall as
        // the room above its 80 dp tool bar allows), read live so late insets still land right.
        val statusTop = WindowInsets.statusBars.getTop(density)
        val navBottom = WindowInsets.navigationBars.getBottom(density)
        val card = if (box.width == 0) Rect.Zero else {
            val top = statusTop + with(density) { EDITOR_CARD_TOP.toPx() }
            val room = box.height - top - navBottom - with(density) { EDITOR_BAR.toPx() }
            val h = minOf(kotlin.math.ceil(box.width * 16f / 9f), room).coerceAtLeast(1f)
            Rect(Offset(0f, top), Size(box.width.toFloat(), h))
        }
        // LAND AS THE EDITOR DRAWS IT (owner's video 2026-11-08: at the handover the face showed twice
        // and the card slid). The editor fits the picture, zooms it by its default zoom, over the
        // two edge colours when it can zoom, over the blurred wash otherwise: the copy does the same.
        val zoomable = StoryCardRules.zoomable(false, media.stickers.isNotEmpty() || media.repostOf != null)
        val pz = photo
        val zoom = if (!zoomable || pz == null || card.height <= 1f || media.video != null) 1f
            else StoryCardRules.defaultZoom(pz.width, pz.height, card.width / card.height)
        Box(Modifier.fillMaxSize().onSizeChanged { box = it }.background(Color.Black.copy(alpha = black.value))) {
            val f = from
            val pic = photo
            if (f != null && pic != null && card.width > 1f) {
                val k = t.value
                val r = Rect(Offset(lerp(f.left, card.left, k), lerp(f.top, card.top, k)),
                    Size(lerp(f.width, card.width, k), lerp(f.height, card.height, k)))
                val corner = with(density) { (CARD_CORNER.toPx() * k).toDp() }
                // The whole card, aspect-filled into the moving box (iOS flies the card composite).
                val s = maxOf(r.width / card.width, r.height / card.height)
                with(density) {
                    Box(Modifier.offset { IntOffset(r.left.toInt(), r.top.toInt()) }.size(r.width.toDp(), r.height.toDp())
                        .clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize((card.width * s).toDp(), (card.height * s).toDp()).background(Color(0xFF1C1C1E)),
                            contentAlignment = Alignment.Center) {
                            val e = edges
                            if (zoomable) {
                                if (e != null) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(e.first), Color(e.second)))))
                            } else {
                                wash?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.9f }) }
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                            }
                            Image(pic, null, contentScale = ContentScale.Fit,
                                modifier = Modifier.aspectRatio(pic.width.toFloat() / pic.height.coerceAtLeast(1))
                                    .graphicsLayer { scaleX = zoom; scaleY = zoom })
                        }
                    }
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** StoryComposer's own card numbers (its CARD_TOP and EDIT_BAR, private there): keep them the same. */
private val EDITOR_CARD_TOP = 8.dp
private val EDITOR_BAR = 80.dp
