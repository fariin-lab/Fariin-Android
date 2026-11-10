package com.fariin.app.ui.stories

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush as GBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.StoryEditRules
import com.fariin.app.data.StoryEditRules.Bg
import com.fariin.app.data.StoryEditRules.Font

/** One text on the picture (iOS TextOverlay). Position, size and turn are in edit space. */
class StoryTextItem(val id: Long, cx: Float, cy: Float) {
    var text by mutableStateOf("")
    var cx by mutableFloatStateOf(cx)
    var cy by mutableFloatStateOf(cy)
    var scale by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)
    var phase by mutableFloatStateOf(1f)
    var font by mutableStateOf(Font.ROUNDED)
    var bg by mutableStateOf(Bg.PLAIN)
    val color get() = StoryEditRules.textColor(phase)
    /** A sticker chip (iOS chipSticker: Location, Link, Time): moves and sizes like text, never opens the text editor. */
    var isChip = false
    /** What a tap on the posted chip opens (Link, Location); null for Time. */
    var link: String? = null
    /** A tray sticker (iOS StickerOverlay): its picture, drawn [imageW] x [imageH] edit units, never typed into. */
    var image by androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    /** A Location/Link/Time chip's recipe (iOS ChipRecipe), kept so a tap can bake it again in the next colour. */
    var chipSymbol: StoryChipSymbol? = null
    var chipStyle = StoryChipStyle.WHITE
    var imageW = 0f
    var imageH = 0f
    /** Something to draw: a sticker, or words. */
    val hasContent get() = image != null || text.isNotBlank()

    private var cached: StaticLayout? = null
    private var cacheKey: Any? = null

    /** The laid-out words, built again only when the words or their style change. Main thread. */
    fun layout(): StaticLayout {
        val key = listOf(text, font, bg, phase)
        cached?.let { if (key == cacheKey) return it }
        return buildStoryTextLayout(text, font, bg, color).also { cached = it; cacheKey = key }
    }
}

/** One pen line, already a Path in edit space, so drawing it allocates nothing. */
class StoryStroke(val color: Int, val width: Float, val highlighter: Boolean) {
    val path = Path()
    var lastX = 0f
    var lastY = 0f
}

/** Everything placed on the photo. Snapshot state, so the screen redraws when it changes. */
class StoryEdits {
    val texts = mutableStateListOf<StoryTextItem>()
    val strokes = mutableStateListOf<StoryStroke>()
    /** Bumped on every point of a live stroke; the canvas reads it to redraw. */
    var tick by mutableIntStateOf(0)
    /** iOS drawingOnTop: whichever was touched last sits on top, on screen and in the bake. */
    var drawingOnTop by mutableStateOf(false)
    private var nextId = 1L
    fun newId() = nextId++
    val isEmpty get() = texts.isEmpty() && strokes.isEmpty()
}

fun storyTypeface(f: Font): Typeface = when (f) {
    Font.ROUNDED -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    Font.CLASSIC -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
    Font.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
    Font.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

private fun composeFamily(f: Font): FontFamily = when (f) {
    Font.ROUNDED -> FontFamily.SansSerif
    Font.CLASSIC -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    Font.SERIF -> FontFamily.Serif
    Font.MONO -> FontFamily.Monospace
}

/** iOS storyStyledText as a StaticLayout: centred lines, as wide as the widest line, within 90%. */
fun buildStoryTextLayout(text: String, font: Font, bg: Bg, color: Int): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = storyTypeface(font)
        textSize = StoryEditRules.TEXT_SIZE
        this.color = if (bg == Bg.SOLID) StoryEditRules.badgeInk(color) else color
        if (bg == Bg.PLAIN) setShadowLayer(StoryEditRules.SHADOW_RADIUS, 0f, StoryEditRules.SHADOW_DY, StoryEditRules.TEXT_SHADOW)
    }
    val s = text.ifEmpty { " " }
    val maxW = (StoryEditRules.TEXT_MAX_WIDTH - 2 * StoryEditRules.padH(bg)).toInt()
    fun build(w: Int) = StaticLayout.Builder.obtain(s, 0, s.length, paint, w).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    val first = build(maxW)
    var widest = 0f
    for (i in 0 until first.lineCount) widest = maxOf(widest, first.getLineWidth(i))
    return build(kotlin.math.ceil(widest).toInt().coerceIn(1, maxW))
}

private fun boxW(l: StaticLayout, bg: Bg) = l.width + 2 * StoryEditRules.padH(bg)
private fun boxH(l: StaticLayout, bg: Bg) = l.height + 2 * StoryEditRules.padV(bg)
/** The item's own box in edit units: a sticker's picture, or the text's badge. */
private fun itemW(t: StoryTextItem) = if (t.image != null) t.imageW else boxW(t.layout(), t.bg)
private fun itemH(t: StoryTextItem) = if (t.image != null) t.imageH else boxH(t.layout(), t.bg)
private val stickerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

/** iOS StickerOverlay.baseWidth: 150 pt across before any pinch. */
const val STORY_STICKER_WIDTH = 150f * 3f

/**
 * Draws the edits in edit space onto [c] (already scaled so one unit is one edit-space unit). The
 * screen and the bake both come here, so the posted picture is the one on screen. [skip] is the
 * text being typed, drawn by the field instead. [layoutOf] lets the bake build its own layouts.
 */
fun drawStoryEdits(
    c: android.graphics.Canvas, e: StoryEdits, ink: Paint, badge: Paint, rect: RectF,
    skip: Long = -1, layoutOf: (StoryTextItem) -> StaticLayout = { it.layout() },
    /** The photo's pinch and pan in edit units (StoryCardRules.strokeMatrix): the pen lines ride the
     *  picture as iOS draws them (`.scaleEffect(photoZoom).offset(photoOffset)`); the texts stay put. */
    strokeMatrix: android.graphics.Matrix? = null,
) {
    fun strokes() {
        if (strokeMatrix != null) { c.save(); c.concat(strokeMatrix) }
        for (s in e.strokes) {
            ink.color = s.color
            ink.alpha = StoryEditRules.inkAlpha(s.highlighter)
            ink.strokeWidth = s.width
            c.drawPath(s.path, ink)
        }
        if (strokeMatrix != null) c.restore()
    }
    fun texts() {
        for (t in e.texts) {
            if (t.id == skip || !t.hasContent) continue
            val pic = t.image
            if (pic != null) {
                c.save()
                c.translate(t.cx, t.cy); c.rotate(t.rotation); c.scale(t.scale, t.scale)
                rect.set(-t.imageW / 2, -t.imageH / 2, t.imageW / 2, t.imageH / 2)
                c.drawBitmap(pic, null, rect, stickerPaint)
                c.restore()
                continue
            }
            val l = layoutOf(t)
            val w = boxW(l, t.bg); val h = boxH(l, t.bg)
            c.save()
            c.translate(t.cx, t.cy); c.rotate(t.rotation); c.scale(t.scale, t.scale); c.translate(-w / 2, -h / 2)
            if (t.bg != Bg.PLAIN) {
                badge.color = if (t.bg == Bg.SEMI) StoryEditRules.SEMI_BADGE else t.color
                rect.set(0f, 0f, w, h)
                c.drawRoundRect(rect, StoryEditRules.CORNER, StoryEditRules.CORNER, badge)
            }
            c.translate(StoryEditRules.padH(t.bg), StoryEditRules.padV(t.bg))
            l.draw(c)
            c.restore()
        }
    }
    if (e.drawingOnTop) { texts(); strokes() } else { strokes(); texts() }
}

fun newInkPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
}

/** The bake's drawing: fresh paints and layouts, so it never shares objects with the screen. */
fun bakeStoryEdits(c: android.graphics.Canvas, pxPerUnit: Float, e: StoryEdits, strokeMatrix: android.graphics.Matrix? = null) {
    c.save()
    c.scale(pxPerUnit, pxPerUnit)
    drawStoryEdits(c, e, newInkPaint(), Paint(Paint.ANTI_ALIAS_FLAG), RectF(),
        layoutOf = { buildStoryTextLayout(it.text, it.font, it.bg, it.color) }, strokeMatrix = strokeMatrix)
    c.restore()
}

/** Topmost text under edit-space point (x, y), or null. */
fun hitText(e: StoryEdits, x: Float, y: Float, slop: Float): StoryTextItem? =
    e.texts.lastOrNull { t ->
        t.hasContent && StoryEditRules.hitsBox(x, y, t.cx, t.cy, itemW(t), itemH(t), t.scale, t.rotation, slop)
    }

/**
 * The photo's own layer: draws the edits clipped to the photo [img] (screen px) and owns the
 * gestures on them. Pen mode: one finger draws. Otherwise: a text follows one finger, pinches and
 * turns with two, a tap opens it for typing, and a drag onto the bin removes it.
 */
@Composable
fun StoryEditCanvas(
    edits: StoryEdits, img: Rect, editingId: Long, drawing: Boolean,
    penColor: Int, penWidth: Float, highlighter: Boolean,
    onStroke: (Boolean) -> Unit, onTapText: (StoryTextItem) -> Unit,
    onDrag: (StoryTextItem?, Offset) -> Unit, onDrop: (StoryTextItem, Offset) -> Unit,
    /** See [drawStoryEdits]: a new line is stored under the photo's pinch, so it moves with it after. */
    strokeMatrix: android.graphics.Matrix? = null,
) {
    val onStrokeNow by rememberUpdatedState(onStroke)
    val onTapNow by rememberUpdatedState(onTapText)
    val onDragNow by rememberUpdatedState(onDrag)
    val onDropNow by rememberUpdatedState(onDrop)
    val ink = remember { newInkPaint() }
    val badge = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val rect = remember { RectF() }
    val slop = LocalViewConfiguration.current.touchSlop
    val k = if (img.width > 1f) StoryEditRules.SPACE / img.width else 1f
    val gestures = if (drawing) Modifier.pointerInput(img, penColor, penWidth, highlighter, strokeMatrix) {
        // Touches are on the picture as zoomed; the line is kept in the picture's own units.
        val inv = android.graphics.Matrix().also { m -> strokeMatrix?.invert(m) }
        val pt = FloatArray(2)
        fun unzoom(x: Float, y: Float): Pair<Float, Float> {
            if (strokeMatrix == null) return x to y
            pt[0] = x; pt[1] = y; inv.mapPoints(pt)
            return pt[0] to pt[1]
        }
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!img.contains(down.position)) return@awaitEachGesture
            down.consume()
            val (x0, y0) = StoryEditRules.toSpace(down.position.x, down.position.y, img.left, img.top, img.width, img.height)
                .let { (a, b) -> unzoom(a, b) }
            val s = StoryStroke(penColor, StoryEditRules.penStroke(penWidth, highlighter), highlighter)
            s.path.moveTo(x0, y0); s.path.lineTo(x0 + 0.1f, y0); s.lastX = x0; s.lastY = y0
            edits.strokes.add(s)
            edits.drawingOnTop = true
            onStrokeNow(true)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                val (x, y) = StoryEditRules.toSpace(ch.position.x, ch.position.y, img.left, img.top, img.width, img.height)
                    .let { (a, b) -> unzoom(a, b) }
                // Midpoint curves: smooth lines from the touch samples, no new objects per point.
                s.path.quadTo(s.lastX, s.lastY, (s.lastX + x) / 2, (s.lastY + y) / 2)
                s.lastX = x; s.lastY = y
                ch.consume()
                edits.tick++
            }
            s.path.lineTo(s.lastX, s.lastY)
            edits.tick++
            onStrokeNow(false)
        }
    } else Modifier.pointerInput(img) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val (x0, y0) = StoryEditRules.toSpace(down.position.x, down.position.y, img.left, img.top, img.width, img.height)
            if (!img.contains(down.position)) return@awaitEachGesture
            val t = hitText(edits, x0, y0, 12f * k * density) ?: return@awaitEachGesture
            down.consume()
            var moved = false
            var travel = 0f
            var finger = down.position
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) break
                val pan = ev.calculatePan(); val zoom = ev.calculateZoom(); val turn = ev.calculateRotation()
                ev.changes.firstOrNull { it.pressed }?.let { finger = it.position }
                if (!moved) {
                    travel += ev.changes.first().positionChange().getDistance()
                    if (travel > slop || ev.changes.count { it.pressed } > 1) {
                        moved = true
                        edits.drawingOnTop = false
                        // Brought to the front, like iOS: the one in the hand is on top.
                        edits.texts.remove(t); edits.texts.add(t)
                    }
                }
                if (moved) {
                    t.cx = (t.cx + pan.x * k).coerceIn(0f, StoryEditRules.SPACE)
                    t.cy = (t.cy + pan.y * k).coerceIn(0f, img.height * k)
                    t.scale = StoryEditRules.clampScale(t.scale * zoom)
                    t.rotation += turn
                    onDragNow(t, finger)
                }
                ev.changes.forEach { it.consume() }
            }
            if (moved) onDropNow(t, finger) else onTapNow(t)
        }
    }
    Canvas(Modifier.fillMaxSize().then(gestures)) {
        @Suppress("UNUSED_VARIABLE") val redraw = edits.tick
        // Reading the lists and every text's fields here subscribes this draw to them.
        edits.texts.forEach { it.cx; it.cy; it.scale; it.rotation; it.text; it.phase; it.font; it.bg }
        edits.strokes.size
        edits.drawingOnTop
        drawIntoCanvas { cv ->
            val n = cv.nativeCanvas
            n.save()
            n.clipRect(img.left, img.top, img.right, img.bottom)
            n.translate(img.left, img.top)
            n.scale(1f / k, 1f / k)
            drawStoryEdits(n, edits, ink, badge, rect, skip = editingId, strokeMatrix = strokeMatrix)
            n.restore()
        }
    }
}

/** The bin, only while a text is in the hand; red and larger with the finger on it (iOS trash). */
@Composable
fun StoryTrash(hot: Boolean, modifier: Modifier) {
    Box(
        modifier.scale(if (hot) 1.25f else 1f).size(52.dp).clip(CircleShape)
            .background(if (hot) Color.Red else Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), contentDescription = "Delete", tint = Color.White) }
}

@Composable
private fun RoundButton(onClick: () -> Unit, light: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (light) Color.White else Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Typing a text (iOS StoryTextToolEditor): the picture dims, the words sit in the middle in their
 * style, the colour bar stands at the left edge, and above the keyboard: Aa (font), the badge and
 * Done. A tap on the dim is Done too. The text goes away if it is left empty.
 */
@Composable
fun StoryTextEditor(t: StoryTextItem, img: Rect, onDone: () -> Unit) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(t.id) { focus.requestFocus() }
    val pxPerUnit = if (img.width > 1f) img.width / StoryEditRules.SPACE else 1f
    val unit = with(density) { (pxPerUnit * t.scale).toDp() }
    val color = Color(t.color)
    val ink = if (t.bg == Bg.SOLID) Color(StoryEditRules.badgeInk(t.color)) else color
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val field = Modifier.widthIn(max = unit * StoryEditRules.TEXT_MAX_WIDTH).width(IntrinsicSize.Max).let {
                    if (t.bg == Bg.PLAIN) it else it.clip(RoundedCornerShape(unit * StoryEditRules.CORNER))
                        .background(if (t.bg == Bg.SEMI) Color(StoryEditRules.SEMI_BADGE) else color)
                }.padding(horizontal = unit * StoryEditRules.padH(t.bg), vertical = unit * StoryEditRules.padV(t.bg))
                BasicTextField(
                    t.text, { t.text = it.take(500) },
                    textStyle = TextStyle(
                        color = ink, fontFamily = composeFamily(t.font), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        fontSize = with(density) { (StoryEditRules.TEXT_SIZE * pxPerUnit * t.scale).toSp() },
                        shadow = if (t.bg == Bg.PLAIN) Shadow(Color(StoryEditRules.TEXT_SHADOW), Offset(0f, 3f), 9f) else null,
                    ),
                    cursorBrush = SolidColor(if (t.bg == Bg.SOLID) ink else Color.White),
                    modifier = field.focusRequester(focus),
                )
                ColorBar(t.phase, { t.phase = it }, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 4.dp)) {
                    Box(Modifier.size(44.dp).clickable { t.font = t.font.next() }, contentAlignment = Alignment.Center) {
                        Text("Aa", color = Color.White, fontSize = 17.sp, fontFamily = composeFamily(t.font), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.size(44.dp).clickable { t.bg = t.bg.next() }, contentAlignment = Alignment.Center) {
                        Icon(if (t.bg == Bg.PLAIN) ImageVector.vectorResource(R.drawable.ic_sf_textformat) else ImageVector.vectorResource(R.drawable.ic_sf_textformat),
                            contentDescription = "Text background", tint = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                RoundButton(onDone, light = true) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = "Done", tint = Color.Black) }
            }
        }
    }
}

/** The text colour bar standing at the left edge: white at the top, black at the bottom. */
@Composable
private fun ColorBar(phase: Float, onPhase: (Float) -> Unit, modifier: Modifier) {
    val stops = remember { StoryEditRules.TEXT_STOPS.reversed().map { Color(0xff000000.toInt() or it) } }
    Box(modifier.width(28.dp).heightIn(max = 240.dp).fillMaxHeight(0.8f)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                onPhase(1f - (down.position.y / size.height).coerceIn(0f, 1f))
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    onPhase(1f - (ch.position.y / size.height).coerceIn(0f, 1f)); ch.consume()
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            drawRoundRect(GBrush.verticalGradient(stops), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
        }
        Canvas(Modifier.fillMaxSize()) {
            val y = (1f - phase) * size.height
            drawCircle(Color.White, radius = size.width / 2, center = Offset(size.width / 2, y))
            drawCircle(Color(StoryEditRules.textColor(phase)), radius = size.width / 2 - 3.dp.toPx(), center = Offset(size.width / 2, y))
        }
    }
}

/** The pen's top bar: undo on the left, Clear All on the right, only once there is a line (iOS penTopBar). */
@Composable
fun StoryPenTopBar(edits: StoryEdits, onClearAll: () -> Unit, modifier: Modifier) {
    if (edits.strokes.isEmpty()) return
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton({ if (edits.strokes.isNotEmpty()) edits.strokes.removeAt(edits.strokes.lastIndex) }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_uturn_left), contentDescription = "Undo", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Text("Clear All", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClearAll)
                .padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/**
 * The pen bar (iOS penBar): the colour track (white, then the hues), and below it X (cancel),
 * the pen / highlighter / width group, and the tick that keeps the drawing.
 */
@Composable
fun StoryPenBar(
    hue: Float, onHue: (Float) -> Unit, highlighter: Boolean, onHighlighter: (Boolean) -> Unit,
    width: Float, onWidth: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier,
) {
    val track = remember { listOf(Color.White) + StoryEditRules.PEN_TRACK_HUES.map { Color(StoryEditRules.penColor(it)) } }
    val current = Color(StoryEditRules.penColor(hue))
    val blue = Color(0xFF3DA1FD)
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp)) {
        Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp)
            .pointerInput(Unit) {
                // The left 4% is the white end; the rest maps onto the hues, as the track is drawn.
                fun at(x: Float): Float { val p = (x / size.width).coerceIn(0f, 1f); return if (p < 0.04f) 0f else (p - 0.04f) / 0.96f * 0.98f + 0.02f }
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume(); onHue(at(down.position.x))
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        onHue(at(ch.position.x)); ch.consume()
                    }
                }
            }) {
            Canvas(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                drawRoundRect(GBrush.horizontalGradient(track), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
            Canvas(Modifier.fillMaxSize()) {
                val p = if (hue <= 0f) 0f else 0.04f + (hue - 0.02f).coerceAtLeast(0f) / 0.98f * 0.96f
                val c = Offset(p * size.width, size.height / 2)
                drawCircle(Color.White, radius = 13.dp.toPx(), center = c)
                drawCircle(current, radius = 10.dp.toPx(), center = c)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(onCancel) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Cancel", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clickable { onHighlighter(false) }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_pencil), contentDescription = "Pen", tint = if (!highlighter) blue else Color.White)
                }
                Box(Modifier.size(40.dp).clickable { onHighlighter(true) }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_paintbrush), contentDescription = "Highlighter", tint = if (highlighter) blue else Color.White)
                }
                Box(Modifier.size(40.dp).clickable(onClick = onWidth), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(minOf(width + 6f, 24f).dp).clip(CircleShape).background(current))
                }
            }
            Spacer(Modifier.weight(1f))
            RoundButton(onDone, light = true) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = "Done", tint = Color.Black) }
        }
    }
}

/**
 * The posted story's tap areas for the chips that open something (iOS chipSticker action): the chip's
 * box, in fractions of the picture, centred where it sits, with its turn. [aspect] = width / height.
 */
fun chipTapTargets(e: StoryEdits, mediaW: Int, mediaH: Int): List<com.fariin.app.data.StoryTapTarget> {
    if (mediaW <= 0 || mediaH <= 0) return emptyList()
    val spaceH = StoryEditRules.SPACE * mediaH / mediaW
    return e.texts.filter { it.isChip && !it.link.isNullOrEmpty() && it.hasContent }.map { t ->
        com.fariin.app.data.StoryTapTarget(
            x = (t.cx / StoryEditRules.SPACE).toDouble(), y = (t.cy / spaceH).toDouble(),
            w = (itemW(t) * t.scale / StoryEditRules.SPACE).toDouble(), h = (itemH(t) * t.scale / spaceH).toDouble(),
            rotation = Math.toRadians(t.rotation.toDouble()), url = t.link!!, aspect = mediaW.toDouble() / mediaH)
    }
}
