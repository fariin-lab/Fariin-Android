package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CropMath
import com.fariin.app.data.CropMath.Box as CBox
import kotlin.math.abs
import kotlin.math.roundToInt

private val CropBlue = Color(0xFF3DA1FD)
private val Glass = Color.Black.copy(alpha = 0.45f)

/**
 * The chat photo crop (iOS ChatCropView, opened inline from the editor's crop button): the photo
 * fitted on black with a free crop frame over it (every side and corner is a handle, the frame
 * moves when dragged inside, a 3 x 3 grid and corner brackets), pinch and drag move the picture
 * under it. Reset at the top right. At the bottom the straighten wheel, then X, the tool capsule
 * (turn left, flip, the ratio menu) and the tick.
 *
 * [onDone] gets the cut picture at the photo's own pixels; [onCancel] leaves it untouched.
 */
@Composable
internal fun PhotoCropPage(source: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val density = LocalDensity.current
    var img by remember { mutableStateOf(source) }
    val picture = remember(img) { img.asImageBitmap() }

    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var window by remember { mutableStateOf(CBox(0f, 0f, 0f, 0f)) }
    var crop by remember { mutableStateOf(CBox(0f, 0f, 0f, 0f)) }
    var aspect by remember { mutableStateOf<Float?>(null) }   // locked w / h, null = free
    var edited by remember { mutableStateOf(false) }          // any change made: Reset lights up
    var zoom by remember { mutableFloatStateOf(1f) }
    var angle by remember { mutableFloatStateOf(0f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }

    val minSide = with(density) { CropMath.FREE_MIN_SIDE_DP.dp.toPx() }
    val hit = with(density) { CropMath.FREE_HIT_DP.dp.toPx() }

    fun clampPan() {
        val (x, y) = CropMath.clampPan(panX, panY, window.width, window.height, zoom, angle)
        panX = x; panY = y
    }
    /** iOS layout: the photo fitted 16 in from the sides and 12 from the top and bottom, the frame reset to it. */
    fun layout() {
        if (canvas.width == 0) return
        val availW = canvas.width - with(density) { 32.dp.toPx() }
        val availH = canvas.height - with(density) { 24.dp.toPx() }
        val s = minOf(availW / img.width, availH / img.height)
        val w = img.width * s; val h = img.height * s
        window = CBox((canvas.width - w) / 2, (canvas.height - h) / 2, w, h)
        crop = CropMath.aspectFrame(window, aspect)
        clampPan()
    }
    fun setAspect(r: Float?) { edited = true; aspect = r; crop = CropMath.aspectFrame(window, r) }
    fun turned(m: Matrix): Bitmap = Bitmap.createBitmap(img, 0, 0, img.width, img.height, m, true)
    fun rotate() {
        edited = true
        img = turned(Matrix().apply { postRotate(-90f) })
        aspect = aspect?.let { 1f / it }
        layout()
    }
    fun flip() {
        edited = true
        img = turned(Matrix().apply { postScale(-1f, 1f) })
        layout()
    }
    fun reset() {
        img = source; aspect = null; edited = false
        angle = 0f; zoom = 1f; panX = 0f; panY = 0f
        layout()
    }
    /** iOS apply: the frame's part of the turned, zoomed picture, at the photo's own pixels. */
    fun apply() {
        if (leaving) return
        leaving = true
        val d = CropMath.displayFrame(window, zoom, angle, panX, panY)
        val ppp = img.width / d.width
        val out = Bitmap.createBitmap((crop.width * ppp).roundToInt().coerceAtLeast(1),
            (crop.height * ppp).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            scale(ppp, ppp)
            translate(-crop.left, -crop.top)
            rotate(angle, d.centerX, d.centerY)
            drawBitmap(img, null, RectF(d.left, d.top, d.right, d.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        onDone(out)
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar: Reset, alone on the right (the X and the tick are at the bottom, where the thumb is).
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Glass)
                    .clickable(enabled = edited) { reset() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Reset", color = if (edited) Color.White else Color.White.copy(alpha = 0.35f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { if (it != canvas) { canvas = it; layout() } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = down.position
                    val c0 = crop
                    // Corners first: they overlap the sides at every join, and a corner moves two sides.
                    val handle = CropMath.Handle.entries.sortedByDescending { it.isCorner }.firstOrNull { h ->
                        val cx = if (h.left) c0.left else if (h.right) c0.right else c0.centerX
                        val cy = if (h.top) c0.top else if (h.bottom) c0.bottom else c0.centerY
                        val hw = if (h.isCorner || h.left || h.right) hit / 2 else maxOf(1f, c0.width - hit) / 2
                        val hh = if (h.isCorner || h.top || h.bottom) hit / 2 else maxOf(1f, c0.height - hit) / 2
                        abs(p.x - cx) <= hw && abs(p.y - cy) <= hh
                    }
                    val inside = p.x in c0.left..c0.right && p.y in c0.top..c0.bottom
                    val pan0X = panX; val pan0Y = panY
                    var total = Offset.Zero
                    var pinched = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // iOS pinchGesture: zoom the picture, never under the floor, at most six times.
                            pinched = true; edited = true
                            val floor = CropMath.zoomFloor(window.width, window.height, angle)
                            zoom = (maxOf(zoom, floor) * ev.calculateZoom()).coerceIn(floor, CropMath.FREE_MAX_ZOOM)
                            clampPan()
                        } else if (!pinched) {
                            total += pressed[0].positionChange()
                            when {
                                handle != null -> { edited = true; crop = CropMath.resized(c0, handle, total.x, total.y, window, minSide, aspect) }
                                inside -> crop = CropMath.moved(c0, total.x, total.y, window)
                                else -> { edited = true; panX = pan0X + total.x; panY = pan0Y + total.y; clampPan() }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }
                }
            }) {
            // The frame's corners and side handles sit near the screen edges, where the system's back
            // swipe starts: they are kept for the frame (as gallery croppers do). Within the 200 dp
            // per edge the system allows.
            if (crop.width > 0f) for (h in CropMath.Handle.entries) if (h.isCorner || h.left || h.right) {
                val cx = if (h.left) crop.left else crop.right
                val cy = if (h.top) crop.top else if (h.bottom) crop.bottom else crop.centerY
                Box(Modifier.offset { IntOffset((cx - hit / 2).roundToInt(), (cy - hit / 2).roundToInt()) }
                    .size(CropMath.FREE_HIT_DP.dp).systemGestureExclusion())
            }
            Canvas(Modifier.fillMaxSize()) {
                if (window.width <= 0f) return@Canvas
                val d = CropMath.displayFrame(window, zoom, angle, panX, panY)
                clipRect(window.left, window.top, window.right, window.bottom) {
                    rotate(angle, Offset(d.centerX, d.centerY)) {
                        drawImage(picture, dstOffset = IntOffset(d.left.roundToInt(), d.top.roundToInt()),
                            dstSize = IntSize(d.width.roundToInt(), d.height.roundToInt()), filterQuality = FilterQuality.High)
                    }
                }
                // The dim outside the frame, the border, the thirds grid and the corner brackets.
                drawPath(Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRect(androidx.compose.ui.geometry.Rect(crop.left, crop.top, crop.right, crop.bottom))
                }, Color.Black.copy(alpha = 0.5f))
                drawRect(Color.White.copy(alpha = 0.9f), Offset(crop.left, crop.top),
                    androidx.compose.ui.geometry.Size(crop.width, crop.height), style = Stroke(1.dp.toPx()))
                val grid = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    val x = crop.left + crop.width * i / 3; val y = crop.top + crop.height * i / 3
                    drawLine(grid, Offset(x, crop.top), Offset(x, crop.bottom), 0.5.dp.toPx())
                    drawLine(grid, Offset(crop.left, y), Offset(crop.right, y), 0.5.dp.toPx())
                }
                val l = 22.dp.toPx()
                val br = Path().apply {
                    moveTo(crop.left, crop.top + l); lineTo(crop.left, crop.top); lineTo(crop.left + l, crop.top)
                    moveTo(crop.right - l, crop.top); lineTo(crop.right, crop.top); lineTo(crop.right, crop.top + l)
                    moveTo(crop.left, crop.bottom - l); lineTo(crop.left, crop.bottom); lineTo(crop.left + l, crop.bottom)
                    moveTo(crop.right - l, crop.bottom); lineTo(crop.right, crop.bottom); lineTo(crop.right, crop.bottom - l)
                }
                drawPath(br, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // Bottom: the straighten wheel, then X, one tool capsule, and the tick.
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StraightenWheel(angle) { angle = it; edited = true; clampPan() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Glass).clickable { if (!leaving) { leaving = true; onCancel() } },
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(Glass).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    CropTool(Icons.Outlined.RotateLeft, "Rotate", Color.White) { rotate() }
                    CropTool(Icons.Outlined.Flip, "Flip", Color.White) { flip() }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        CropTool(Icons.Outlined.AspectRatio, "Aspect ratio", if (aspect == null) Color.White else CropBlue) { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            val original = img.width / img.height.toFloat()
                            listOf("Original" to original, "Free" to null, "Square" to 1f, "4:5" to 4f / 5f,
                                "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f).forEach { (label, r) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { Box(Modifier.size(24.dp)) { if (aspect == r) Icon(Icons.Filled.Check, contentDescription = null) } },
                                    onClick = { menu = false; setAspect(r) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { apply() },
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.Black) }
            }
        }
    }
}

@Composable
private fun CropTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * The straighten wheel (iOS straightenWheel): a ruler you drag, a tick every 2 degrees and a taller
 * one every 10, the reading above it, and a green mark where the reading is taken. Turns the
 * picture, never the frame; within a degree of straight is straight.
 */
@Composable
private fun StraightenWheel(angle: Float, onAngle: (Float) -> Unit) {
    val now by rememberUpdatedState(angle)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (angle == 0f) "0" else "%.0f".format(angle), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (angle == 0f) Color.White.copy(alpha = 0.55f) else Color.White)
        Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = now
                var dx = 0f
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    dx += ch.positionChange().x
                    onAngle(CropMath.straighten(start, dx, size.width / 2f))
                    ch.consume()
                }
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val ticks = (-45..45).filter { it % 2 == 0 }
                val slot = size.width / ticks.size
                ticks.forEachIndexed { i, d ->
                    val x = slot * i + slot / 2
                    val h = (if (d % 10 == 0) 16 else 9).dp.toPx()
                    drawLine(Color.White.copy(alpha = if (d % 10 == 0) 0.75f else 0.3f),
                        Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), 1.dp.toPx())
                }
                val gx = size.width / 2 + angle / CropMath.MAX_STRAIGHTEN * (size.width / 2)
                val gh = 20.dp.toPx()
                drawLine(Color.Green, Offset(gx, (size.height - gh) / 2), Offset(gx, (size.height + gh) / 2), 2.dp.toPx())
            }
        }
    }
}
