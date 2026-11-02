package com.fariin.app.ui.stories

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.fariin.app.data.StoryEditRules
import com.fariin.app.data.Conversation
import com.fariin.app.data.StoryAudienceChoice
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.StoryRules
import com.fariin.app.data.StoryService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The post in flight (iOS postStoryBackground): the composer closes at once and the Stories row
 * says "Uploading…" until the story is up, or that it could not be posted.
 */
object StoryUploads {
    sealed interface State {
        data object Uploading : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(me: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice, picked: Set<String>,
              hours: Int, convs: List<Conversation>, captureSwitch: Boolean = false,
              lists: List<com.fariin.app.data.StoryListDoc> = emptyList()) {
        state.value = State.Uploading
        // The lists as they stand when Post Story is pressed (iOS reads the store at the same moment).
        val myFriends = com.fariin.app.data.StoryPrivacyStore.myFriends.value
        val hidden = com.fariin.app.data.StoryPrivacyStore.hidden.value
        scope.launch {
            state.value = try {
                StoryService(me).post(media, caption, choice, picked, hours, convs, captureSwitch, myFriends, hidden, lists)
                null
            } catch (e: StoryService.PostRefused) {
                State.Failed(e.message ?: "Couldn't post your story.")
            } catch (e: Exception) {
                android.util.Log.w("StoryPost", "post failed", e)
                State.Failed("Couldn't post your story. Check your connection and try again.")
            }
        }
    }
}

/**
 * The editor after picking a photo or video (iOS StoryEditorView, the parts Android has): the
 * picture, "Add a caption…", the lifetime menu (6, 12, 24, 48 hours) and Next, which opens
 * "Who can see your story" with Post Story.
 */
@Composable
fun StoryComposerDialog(
    me: String,
    media: StoryMedia.Prepared,
    conversations: List<Conversation>,
    onClose: () -> Unit,
    /** After Post Story; the camera closes with it (iOS closeAfterPost). Close alone goes back to the camera. */
    onPosted: () -> Unit = onClose,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        StoryComposer(me, media, conversations, onClose, onPosted)
    }
}

@Composable
private fun StoryComposer(me: String, media: StoryMedia.Prepared, conversations: List<Conversation>, onClose: () -> Unit, onPosted: () -> Unit) {
    var caption by remember { mutableStateOf("") }
    var hours by remember { mutableIntStateOf(StoryRules.DEFAULT_HOURS) }
    var hoursMenu by remember { mutableStateOf(false) }
    var audience by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val bmp = remember(media) { BitmapFactory.decodeByteArray(media.jpeg, 0, media.jpeg.size)?.asImageBitmap() }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // The editor tools (iOS StoryEditorView Aa and pen). Photos only: iOS burns them into a video
    // by re-encoding it, which Android does not do yet.
    val canEdit = media.video == null && bmp != null
    val edits = remember { StoryEdits() }
    var editingId by remember { mutableLongStateOf(-1L) }
    var drawing by remember { mutableStateOf(false) }
    var penSnapshot by remember { mutableStateOf<List<StoryStroke>>(emptyList()) }
    var penHue by remember { mutableFloatStateOf(StoryEditRules.PEN_START_HUE) }
    var penWidth by remember { mutableFloatStateOf(StoryEditRules.PEN_START_WIDTH) }
    var highlighter by remember { mutableStateOf(false) }
    var stroking by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var trashHot by remember { mutableStateOf(false) }
    var askClearAll by remember { mutableStateOf(false) }
    var askDiscardPen by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var baking by remember { mutableStateOf(false) }
    var posted by remember { mutableStateOf(media) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var captionArmed by remember { mutableStateOf(true) }
    val img = remember(box, bmp) {
        if (bmp == null || box.width == 0) Rect.Zero else {
            val k = minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())
            val w = bmp.width * k; val h = bmp.height * k
            Rect(Offset((box.width - w) / 2, (box.height - h) / 2), androidx.compose.ui.geometry.Size(w, h))
        }
    }
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val bin = with(density) { Offset(box.width / 2f, box.height - navBottom - 36.dp.toPx() - 26.dp.toPx()) }
    val binSlop = with(density) { 12.dp.toPx() }
    val binSize = with(density) { 52.dp.toPx() }

    fun finishText() {
        // Focus off first: dropped with the field still focused, it lands on the caption.
        focus.clearFocus(force = true); keyboard?.hide()
        edits.texts.firstOrNull { it.id == editingId }?.let { if (it.text.isBlank()) edits.texts.remove(it) }
        editingId = -1L
        // The caption comes back in the same frame and the window would hand it the focus (and the
        // keyboard). It can take focus again once a finger is on it.
        captionArmed = false
    }
    fun addText() {
        val k = StoryEditRules.SPACE / img.width.coerceAtLeast(1f)
        val t = StoryTextItem(edits.newId(), StoryEditRules.SPACE / 2, img.height * k / 2)
        edits.drawingOnTop = false
        edits.texts.add(t)
        editingId = t.id
    }
    fun openPen() { penSnapshot = edits.strokes.toList(); drawing = true }
    fun closePen(discard: Boolean) {
        if (discard) { edits.strokes.clear(); edits.strokes.addAll(penSnapshot) }
        drawing = false
    }
    fun cancelPen() { if (edits.strokes.toList() != penSnapshot) askDiscardPen = true else closePen(false) }
    fun close() { if (!edits.isEmpty) askDiscard = true else onClose() }

    BackHandler(enabled = editingId >= 0) { finishText() }
    BackHandler(enabled = drawing && editingId < 0) { cancelPen() }
    BackHandler(enabled = !drawing && editingId < 0 && !audience && !edits.isEmpty) { askDiscard = true }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }) {
        if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (canEdit) StoryEditCanvas(
            edits, img, editingId, drawing, StoryEditRules.penColor(penHue), penWidth, highlighter,
            onStroke = { stroking = it },
            onTapText = { editingId = it.id },
            onDrag = { _, p ->
                dragging = true
                val hot = StoryEditRules.isOverTrash(p.x, p.y, bin.x, bin.y, binSize, binSlop)
                if (hot != trashHot) trashHot = hot
            },
            onDrop = { t, p ->
                if (StoryEditRules.isOverTrash(p.x, p.y, bin.x, bin.y, binSize, binSlop)) edits.texts.remove(t)
                dragging = false; trashHot = false
            },
        )
        if (media.video != null) {
            Text("Video · ${media.video.info.durationSec.toInt()} s", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
        val chrome = !drawing && !dragging && editingId < 0
        if (chrome) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable { close() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (canEdit) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable { addText() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.TextFields, contentDescription = "Add text", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable { openPen() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Draw, contentDescription = "Draw", tint = Color.White)
                }
            }
            Box {
                Box(
                    Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable { hoursMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(24.dp).border(1.3.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$hours", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                DropdownMenu(hoursMenu, onDismissRequest = { hoursMenu = false }) {
                    Text("Choose how long the story will be visible.", fontSize = 13.sp, color = Fariin.colors.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(220.dp))
                    StoryRules.HOURS.forEach { h ->
                        DropdownMenuItem(
                            text = { Text("$h Hours") },
                            trailingIcon = { if (h == hours) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { hours = h; hoursMenu = false },
                        )
                    }
                }
            }
        }
        if (drawing && !stroking && editingId < 0) {
            StoryPenTopBar(edits, onClearAll = { askClearAll = true }, modifier = Modifier.align(Alignment.TopCenter))
            StoryPenBar(
                penHue, { penHue = it }, highlighter, { highlighter = it }, penWidth, { penWidth = StoryEditRules.nextPenWidth(penWidth) },
                onCancel = { cancelPen() }, onDone = { closePen(false) }, modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (dragging) StoryTrash(trashHot, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp))
        // Gone while the audience page shows: a caption field left behind kept the keyboard up
        // over Post Story.
        if (chrome && !audience) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f))
                .navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).padding(vertical = 8.dp).pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); captionArmed = true }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (caption.isEmpty()) Text("Add a caption…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                BasicTextField(caption, { caption = it.take(1000) }, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White), maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusProperties { canFocus = captionArmed })
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Next", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).clickable(enabled = !baking) {
                    focus.clearFocus(); keyboard?.hide()
                    if (!canEdit || !StoryEditRules.needsBake(edits.texts.size, edits.strokes.size)) {
                        posted = media; audience = true; return@clickable
                    }
                    // iOS flatten: the text and the lines are burned into the photo, off the main thread.
                    baking = true
                    scope.launch {
                        val baked = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            runCatching { StoryMedia.bake(media) { c, k -> bakeStoryEdits(c, k, edits) } }.getOrNull()
                        }
                        baking = false
                        if (baked != null) { posted = baked; audience = true }
                    }
                }.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
        if (editingId >= 0) edits.texts.firstOrNull { it.id == editingId }?.let { StoryTextEditor(it, img, onDone = { finishText() }) }
        if (baking) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable {}, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        if (audience) {
            ShareStoryAudiencePage(me, conversations, onBack = { audience = false }) { choice, picked, lists, capture ->
                StoryUploads.start(me, posted, caption, choice, picked, hours, storyConvs(conversations), capture, lists)
                onPosted()
            }
        }
    }
    val red = Color(0xFFE5484D)
    if (askClearAll) AlertDialog(
        onDismissRequest = { askClearAll = false }, title = { Text("Clear all drawing?") },
        confirmButton = { TextButton({ edits.strokes.clear(); askClearAll = false }) { Text("Clear All", color = red) } },
        dismissButton = { TextButton({ askClearAll = false }) { Text("Cancel") } },
    )
    if (askDiscardPen) AlertDialog(
        onDismissRequest = { askDiscardPen = false }, title = { Text("Discard your drawing?") },
        confirmButton = { TextButton({ askDiscardPen = false; closePen(true) }) { Text("Discard", color = red) } },
        dismissButton = { TextButton({ askDiscardPen = false }) { Text("Keep Drawing") } },
    )
    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false }, title = { Text("Discard this story?") },
        confirmButton = { TextButton({ askDiscard = false; onClose() }) { Text("Discard", color = red) } },
        dismissButton = { TextButton({ askDiscard = false }) { Text("Cancel") } },
    )
}

/** Picks a photo or video, prepares it off the main thread, then the caller opens the composer. */
suspend fun prepareStoryMedia(context: android.content.Context, uri: Uri): Result<StoryMedia.Prepared> =
    kotlinx.coroutines.withContext(Dispatchers.Default) {
        try {
            StoryMedia.prepare(context, uri)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Couldn't open this file."))
        } catch (e: StoryMedia.TooBig) {
            Result.failure(e)
        }
    }
