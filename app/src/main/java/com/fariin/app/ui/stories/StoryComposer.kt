package com.fariin.app.ui.stories

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Sell
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

    fun start(context: android.content.Context, me: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice, picked: Set<String>,
              hours: Int, convs: List<Conversation>, captureSwitch: Boolean = false,
              lists: List<com.fariin.app.data.StoryListDoc> = emptyList()) {
        state.value = State.Uploading
        // The lists as they stand when Post Story is pressed (iOS reads the store at the same moment).
        val myFriends = com.fariin.app.data.StoryPrivacyStore.myFriends.value
        val hidden = com.fariin.app.data.StoryPrivacyStore.hidden.value
        val app = context.applicationContext
        scope.launch {
            // On disk first (iOS StoryOutbox): a kill mid-upload is finished at the next start.
            val ticket = com.fariin.app.data.StoryOutbox.write(app, me, media, caption, choice, picked, hours, captureSwitch, lists)
            state.value = try {
                StoryService(me).post(media, caption, choice, picked, hours, convs, captureSwitch, myFriends, hidden, lists, fixedId = ticket)
                ticket?.let { com.fariin.app.data.StoryOutbox.remove(app, it) }
                null
            } catch (e: StoryService.PostRefused) {
                ticket?.let { com.fariin.app.data.StoryOutbox.remove(app, it) }
                State.Failed(e.message ?: "Couldn't post your story.")
            } catch (e: Exception) {
                android.util.Log.w("StoryPost", "post failed", e)
                ticket?.let { com.fariin.app.data.StoryOutbox.release(it) }
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
    /** A finished text story (iOS onTextStory): straight to "Who can see your story"; back returns to the card. */
    textStory: Boolean = false,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
        StoryComposer(me, media, conversations, onClose, onPosted, textStory)
    }
}

@Composable
private fun StoryComposer(me: String, media: StoryMedia.Prepared, conversations: List<Conversation>, onClose: () -> Unit, onPosted: () -> Unit, textStory: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var caption by remember { mutableStateOf("") }
    var hours by remember { mutableIntStateOf(StoryRules.DEFAULT_HOURS) }
    var hoursMenu by remember { mutableStateOf(false) }
    var stickerTray by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    var askPlace by remember { mutableStateOf(false) }
    var audience by remember { mutableStateOf(textStory) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Decoded off the main thread (about 7 ms on the low-end phone); null until it lands.
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, media) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            BitmapFactory.decodeByteArray(media.jpeg, 0, media.jpeg.size)?.asImageBitmap()
        }
    }
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
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    // The picture's rounded frame (iOS story editor, owner screenshots 2026-11-04): under the status
    // bar, ending above the bar of tools; the picture is fitted inside it, edits keep its rectangle.
    val statusTop = WindowInsets.statusBars.getTop(density)
    val barPx = with(density) { EDIT_BAR.toPx() }
    val img = remember(box, bmp, statusTop, navBottom) {
        val b = bmp
        if (b == null || box.width == 0) Rect.Zero else {
            val fh = box.height - statusTop - navBottom - barPx
            val k = minOf(box.width / b.width.toFloat(), fh / b.height.toFloat())
            val w = b.width * k; val h = b.height * k
            Rect(Offset((box.width - w) / 2, statusTop + (fh - h) / 2), androidx.compose.ui.geometry.Size(w, h))
        }
    }
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
    // iOS chipSticker (Location, Link, Time): a white pill that moves like text; Link and Location
    // become a tap area on the posted story.
    fun addChip(label: String, link: String?) {
        val k = StoryEditRules.SPACE / img.width.coerceAtLeast(1f)
        val t = StoryTextItem(edits.newId(), StoryEditRules.SPACE / 2, img.height * k / 2)
        t.text = label; t.bg = StoryEditRules.Bg.SOLID; t.isChip = true; t.link = link
        edits.drawingOnTop = false
        edits.texts.add(t)
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
        val navDp = with(density) { navBottom.toDp() }
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = EDIT_BAR + navDp).clip(RoundedCornerShape(28.dp))) {
            // The photo itself is rounded (a gallery photo is a different shape from the frame, so
            // rounding only the frame left its corners square inside black bands).
            bmp?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)).clip(RoundedCornerShape(28.dp)))
                }
            }
            // A video plays here, looping, as on iOS (it only said "Video · 5 s" over its first frame).
            media.video?.let { v -> StoryEditVideo(v.file) }
        }
        if (canEdit) StoryEditCanvas(
            edits, img, editingId, drawing, StoryEditRules.penColor(penHue), penWidth, highlighter,
            onStroke = { stroking = it },
            onTapText = { if (!it.isChip) editingId = it.id },
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
        val chrome = !drawing && !dragging && editingId < 0
        // Close inside the frame's top corner (iOS).
        if (chrome) Box(Modifier.statusBarsPadding().padding(12.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .clickable { close() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
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
        if (chrome && !audience) {
            // The caption: a dark capsule on the picture's lower edge with the story's lifetime at its
            // end (iOS); it rises with the keyboard.
            val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
            val lift = maxOf(imeDp + 8.dp, EDIT_BAR + navDp + 12.dp)
            // Keyboard up: a tap anywhere on the picture puts it away (owner 2026-11-04).
            if (imeDp > 0.dp) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { focus.clearFocus(force = true); keyboard?.hide(); captionArmed = false }
            })
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = lift).padding(horizontal = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp)).background(Color.Black).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).padding(vertical = 10.dp).pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); captionArmed = true }
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (caption.isEmpty()) Text("Add a caption…", color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
                    BasicTextField(caption, { caption = it.take(1000) }, textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                        cursorBrush = SolidColor(Color.White), maxLines = 4,
                        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = captionArmed })
                }
                Box {
                    // Picking a lifetime must not hand the caption the focus (and the keyboard).
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable {
                        captionArmed = false; focus.clearFocus(force = true); keyboard?.hide(); hoursMenu = true
                    }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(26.dp).border(1.3.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
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
                                onClick = { captionArmed = false; hours = h; hoursMenu = false; focus.clearFocus(force = true) },
                            )
                        }
                    }
                }
            }
            // The bar on black under the frame: Aa, stickers and the pen in one capsule, NEXT on the right.
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().height(EDIT_BAR).fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canEdit) Row(Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Add text") { addText() }, contentAlignment = Alignment.Center) {
                        Text("Aa", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (media.video == null) Box(Modifier.size(48.dp).clip(CircleShape).clickable { stickerTray = true }, contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.EmojiEmotions, contentDescription = "Stickers", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { openPen() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Draw, contentDescription = "Draw", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).clickable(enabled = !baking) {
                    focus.clearFocus(); keyboard?.hide()
                    if (!canEdit || !StoryEditRules.needsBake(edits.texts.size, edits.strokes.size)) {
                        posted = media; audience = true; return@clickable
                    }
                    // iOS flatten: the text and the lines are burned into the photo, off the main thread.
                    baking = true
                    scope.launch {
                        val baked = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            runCatching {
                                StoryMedia.bake(media, chipTapTargets(edits, media.width, media.height)) { c, k -> bakeStoryEdits(c, k, edits) }
                            }.getOrNull()
                        }
                        baking = false
                        if (baked != null) { posted = baked; audience = true }
                    }
                }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("NEXT", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (editingId >= 0) edits.texts.firstOrNull { it.id == editingId }?.let { StoryTextEditor(it, img, onDone = { finishText() }) }
        if (stickerTray) StickerTray(onClose = { stickerTray = false },
            onTime = { stickerTray = false; addChip("🕒 " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()), null) },
            onLink = { stickerTray = false; askLink = true },
            onPlace = { stickerTray = false; askPlace = true })
        if (askLink) ChipPrompt("Link", "https://", onDone = { raw ->
            askLink = false
            com.fariin.app.data.StoryChips.link(raw)?.let { (label, url) -> addChip("🔗 $label", url) }
        }, onCancel = { askLink = false })
        if (askPlace) ChipPrompt("Location", "Place name", onDone = { raw ->
            askPlace = false
            com.fariin.app.data.StoryChips.place(raw)?.let { (label, url) -> addChip("📍 $label", url) }
        }, onCancel = { askPlace = false })
        if (baking) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable {}, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        if (audience) {
            ShareStoryAudiencePage(me, conversations, onBack = { if (textStory) onClose() else audience = false }) { choice, picked, lists, capture ->
                StoryUploads.start(ctx, me, posted, caption, choice, picked, hours, storyConvs(conversations), capture, lists)
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

/** iOS StoryStickerSheet's action pills: Location, Link, Time, on the dark tray. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StickerTray(onClose: () -> Unit, onTime: () -> Unit, onLink: () -> Unit, onPlace: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, containerColor = Color(0xFF1C1C1E)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrayPill("📍", "Location", Color(0xFFFF453A), onPlace)
            TrayPill("🔗", "Link", Color(0xFF0A84FF), onLink)
            TrayPill("🕒", "Time", Color(0xFFFF9F0A), onTime)
        }
    }
}

@Composable
private fun TrayPill(glyph: String, title: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text(title, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One line for a Link or Location chip; Done adds it. */
@Composable
private fun ChipPrompt(title: String, hint: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(hint) })
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Done") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") } })
}

/** The tools bar under the story editor's frame (Aa, stickers, pen | NEXT), as iOS. */
private val EDIT_BAR = 80.dp

/** The picked or recorded video, playing on a loop in the editor's frame (the first frame shows until it starts). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StoryEditVideo(uri: Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(com.fariin.app.R.layout.story_player, null) as androidx.media3.ui.PlayerView).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
