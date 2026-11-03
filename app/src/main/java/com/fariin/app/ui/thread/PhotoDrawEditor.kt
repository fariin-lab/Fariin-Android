package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.fariin.app.data.StoryEditRules
import com.fariin.app.media.BitmapDecode
import com.fariin.app.media.MediaRules
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.stories.StoryEditCanvas
import com.fariin.app.ui.stories.StoryEdits
import com.fariin.app.ui.stories.StoryPenBar
import com.fariin.app.ui.stories.StoryPenTopBar
import com.fariin.app.ui.stories.bakeStoryEdits
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** iOS accent on the editor's black page (0x3DA1FD): the send circle and the view-once switch when on. */
private val EditorBlue = Color(0xFF3DA1FD)

/**
 * The chat photo editor's pen (iOS ChatImageEditor opened with startDrawing): the photo on black,
 * and the story pen on it (same top bar, colour track, pen / highlighter / width, X and tick).
 *
 * From the viewer ([editOnly] false): the tick burns the lines into the photo and lands on the
 * editor page (X, the pen button, the caption with the view-once switch, and Send); Send hands the
 * edited copy to [onSend] for the same chat. From the attach sheet ([editOnly] true, iOS
 * MediaApprovalView's pen): the tick hands the edited copy straight back through [onReturn].
 *
 * X in the pen, or system back, leaves at once with nothing drawn and asks Discard / Keep Drawing
 * with lines on the photo, as iOS's Cancel does.
 */
@Composable
internal fun PhotoDrawEditor(
    source: Uri,
    editOnly: Boolean,
    onClose: () -> Unit,
    allowViewOnce: Boolean = false,
    onSend: (Uri, String, Boolean) -> Unit = { _, _, _ -> },
    onReturn: (Uri) -> Unit = {},
) {
    var back by remember { mutableStateOf<() -> Unit>({ onClose() }) }
    Dialog(
        onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EditorWindow()
        // iOS: the editor is always dark, whatever the phone is set to.
        FariinTheme(dark = true) {
            EditorBody(source, editOnly, allowViewOnce, onClose, onSend, onReturn) { back = it }
        }
    }
}

/** No dim behind, light bar icons on the black (as the viewer's window). */
@Composable
private fun EditorWindow() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}

@Composable
private fun EditorBody(
    source: Uri, editOnly: Boolean, allowViewOnce: Boolean, onClose: () -> Unit,
    onSend: (Uri, String, Boolean) -> Unit, onReturn: (Uri) -> Unit, setBack: (() -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // The photo as it stands: decoded at the HD side (2560), and replaced by each bake.
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var changed by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        base = withContext(Dispatchers.IO) {
            sweepOldEdits(ctx)
            BitmapDecode.fromUri(ctx, source, MediaRules.PhotoQuality.HIGH.maxSide)
        }
    }
    val picture = remember(base) { base?.asImageBitmap() }

    val edits = remember { StoryEdits() }
    var drawing by remember { mutableStateOf(true) }   // iOS startDrawing: the pen is open on arrival
    var penHue by remember { mutableFloatStateOf(StoryEditRules.PEN_START_HUE) }
    var penWidth by remember { mutableFloatStateOf(StoryEditRules.PEN_START_WIDTH) }
    var highlighter by remember { mutableStateOf(false) }
    var askClearAll by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var viewOnce by remember { mutableStateOf(false) }

    // The photo fits the space between the top bar and the bottom chrome (iOS canvasArea); the
    // bottom height is not taken while the keyboard is up, so the photo never moves with it.
    var box by remember { mutableStateOf(IntSize.Zero) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val imeUp = WindowInsets.ime.getBottom(density) > 0
    val topPx = WindowInsets.statusBars.getTop(density) + with(density) { (44 + 16).dp.roundToPx() }
    val gapPx = with(density) { 12.dp.roundToPx() }
    val img = remember(box, bottomPx, topPx, base) {
        val b = base
        val areaH = box.height - topPx - bottomPx - gapPx
        if (b == null || box.width == 0 || areaH <= 0) Rect.Zero else {
            val k = minOf(box.width / b.width.toFloat(), areaH / b.height.toFloat())
            val w = b.width * k; val h = b.height * k
            Rect(Offset((box.width - w) / 2, topPx + (areaH - h) / 2), Size(w, h))
        }
    }

    /** The lines burned into the photo at its own pixels (iOS bakeDrawing), then cleared. */
    suspend fun bake() {
        val b = base ?: return
        if (edits.strokes.isEmpty()) return
        val out = withContext(Dispatchers.Default) {
            runCatching {
                val m = b.copy(Bitmap.Config.ARGB_8888, true)
                bakeStoryEdits(android.graphics.Canvas(m), m.width / StoryEditRules.SPACE, edits)
                m
            }.getOrNull()
        } ?: return
        base = out; changed = true
        edits.strokes.clear()
    }

    /** The photo to send or hand back: the original when nothing was drawn, else a JPEG copy. */
    suspend fun output(): Uri {
        val b = base
        if (!changed || b == null) return source
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = File(editDir(ctx), "edit-${UUID.randomUUID()}.jpg")
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            }.getOrDefault(source)
        }
    }

    fun leavePen() {
        edits.strokes.clear()
        if (editOnly) onClose() else drawing = false
    }
    fun cancelPen() { if (edits.strokes.isEmpty()) leavePen() else askDiscard = true }
    fun finishPen() {
        if (busy) return
        busy = true
        scope.launch {
            bake()
            if (editOnly) { onReturn(output()); onClose() } else { drawing = false; busy = false }
        }
    }
    fun send() {
        // One send per editor (iOS `sent`): a double tap posted the photo twice there.
        if (sent || busy) return
        sent = true; busy = true
        scope.launch { onSend(output(), caption.trim(), viewOnce) }
    }
    setBack { if (drawing) cancelPen() else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }) {
        picture?.let { p ->
            Canvas(Modifier.fillMaxSize()) {
                if (img.width > 0f) drawImage(
                    p, dstOffset = IntOffset(img.left.toInt(), img.top.toInt()),
                    dstSize = IntSize(img.width.toInt(), img.height.toInt()), filterQuality = FilterQuality.High,
                )
            }
        }
        if (picture == null) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
            modifier = Modifier.align(Alignment.Center).size(28.dp))
        if (drawing && picture != null) StoryEditCanvas(
            edits, img, -1L, true, StoryEditRules.penColor(penHue), penWidth, highlighter,
            onStroke = {}, onTapText = {}, onDrag = { _, _ -> }, onDrop = { _, _ -> },
        )

        if (drawing) {
            StoryPenTopBar(edits, onClearAll = { askClearAll = true }, modifier = Modifier.align(Alignment.TopCenter))
            StoryPenBar(
                penHue, { penHue = it }, highlighter, { highlighter = it }, penWidth, { penWidth = StoryEditRules.nextPenWidth(penWidth) },
                onCancel = { cancelPen() }, onDone = { finishPen() },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { if (!imeUp) bottomPx = it.height },
            )
        } else {
            // The editor's own X, 20 in from the edge (iOS top button).
            Box(
                Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp).size(44.dp).floating()
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            EditorBar(
                caption, { caption = it }, viewOnce, allowViewOnce,
                onViewOnce = { viewOnce = !viewOnce; if (viewOnce) caption = "" },
                onPen = { drawing = true }, onSend = { send() }, toolsShown = !imeUp,
                modifier = Modifier.align(Alignment.BottomCenter).imePadding()
                    .onSizeChanged { if (!imeUp) bottomPx = it.height }.navigationBarsPadding(),
            )
        }
        if (busy && !drawing) Box(Modifier.fillMaxSize().clickable(enabled = false) {})
    }

    val red = Color(0xFFE5484D)
    // The story pen's two questions, in its words.
    if (askClearAll) AlertDialog(
        onDismissRequest = { askClearAll = false }, title = { Text("Clear all drawing?") },
        confirmButton = { TextButton({ edits.strokes.clear(); askClearAll = false }) { Text("Clear All", color = red) } },
        dismissButton = { TextButton({ askClearAll = false }) { Text("Cancel") } },
    )
    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false }, title = { Text("Discard your drawing?") },
        confirmButton = { TextButton({ askDiscard = false; leavePen() }) { Text("Discard", color = red) } },
        dismissButton = { TextButton({ askDiscard = false }) { Text("Keep Drawing") } },
    )
}

/**
 * The editor page's bottom (iOS bottomBar): the round pen tool, then the caption capsule with the
 * view-once switch inside it ("View Once Media" in place of the caption while it is on) and the
 * blue send circle. The tools hide while the keyboard is up.
 */
@Composable
private fun EditorBar(
    caption: String, onCaption: (String) -> Unit, viewOnce: Boolean, allowViewOnce: Boolean,
    onViewOnce: () -> Unit, onPen: () -> Unit, onSend: () -> Unit, toolsShown: Boolean, modifier: Modifier,
) {
    val grey = Color(0xFF8E8E93)
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (toolsShown) Row {
            Box(Modifier.size(40.dp).floating().clickable(onClick = onPen), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Draw, contentDescription = "Draw", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier.weight(1f).heightIn(min = 40.dp).floating(RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (viewOnce) {
                        Text("View Once Media", color = grey, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else {
                        if (caption.isEmpty()) Text("Add a caption…", color = grey, fontSize = 17.sp)
                        BasicTextField(caption, { onCaption(it.take(1000)) }, textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                            cursorBrush = SolidColor(Color.White), maxLines = 7, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (allowViewOnce) Box(Modifier.size(40.dp).clickable(onClick = onViewOnce), contentAlignment = Alignment.Center) {
                    // iOS 1.circle / 1.circle.fill.
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).then(
                            if (viewOnce) Modifier.background(EditorBlue) else Modifier.border(1.5.dp, Color.White, CircleShape)
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text("1", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(EditorBlue).clickable(onClick = onSend), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun editDir(ctx: android.content.Context) = File(ctx.cacheDir, "edited").apply { mkdirs() }

/** Edited copies are read once by the send or the attach sheet; a day later they are only clutter. */
private fun sweepOldEdits(ctx: android.content.Context) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    editDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
}
