package com.fariin.app.ui.stories

import com.fariin.app.ui.components.isNew
import com.fariin.app.ui.components.NewCornerPill
import com.fariin.app.data.NewBadgeSpots
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.fariin.app.data.StoryEditRules
import com.fariin.app.data.Conversation
import com.fariin.app.data.StoryAudienceChoice
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.StoryCardRules
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
    /** The picture being posted, for the uploading My Story card (iOS storyBudget.inFlight image). */
    val pending = MutableStateFlow<ByteArray?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Posts still going: "Uploading…" stays until the last of several back-to-back posts is done. */
    private var inFlight = 0
    /** The first failure among the posts in flight, shown once they are all done. */
    private var failure: State.Failed? = null

    @Synchronized private fun begin() { inFlight++ }

    @Synchronized private fun finish(result: State?) {
        if (result is State.Failed && failure == null) failure = result
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (inFlight == 0) {
            state.value = failure
            failure = null
            pending.value = null
        }
    }

    fun start(context: android.content.Context, me: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice, picked: Set<String>,
              hours: Int, convs: List<Conversation>, captureSwitch: Boolean = false,
              lists: List<com.fariin.app.data.StoryListDoc> = emptyList(),
              /** A clip with edits to burn in, trim or mute: exported here, off the editor (iOS does it at post time). */
              videoEdit: com.fariin.app.media.StoryVideoCut.Edit? = null) {
        // A group story picked on the Share Story page, handed over for this call only (GroupStoryCompose).
        val group = com.fariin.app.data.GroupStoryCompose.pending
        begin()
        state.value = State.Uploading
        pending.value = media.jpeg
        // The lists as they stand when Post Story is pressed (iOS reads the store at the same moment).
        val myFriends = com.fariin.app.data.StoryPrivacyStore.myFriends.value
        val hidden = com.fariin.app.data.StoryPrivacyStore.hidden.value
        val app = context.applicationContext
        scope.launch {
            var outcome: State? = null
            // The edited clip first, as one new file; everything after posts it like a picked one.
            var exported: java.io.File? = null
            try {
            val source = media.video
            val media = if (videoEdit == null || source == null) media else {
                val file = java.io.File(app.cacheDir, "story-edit-${System.nanoTime()}.mp4")
                val part = if (com.fariin.app.media.StoryVideoCut.edit(app, source.file, file, videoEdit))
                    runCatching { StoryMedia.prepareVideo(app, Uri.fromFile(file)) }.getOrNull() else null
                val clip = part?.video
                if (part == null || clip == null) {
                    file.delete()
                    outcome = State.Failed("Couldn't prepare this video for your story.")
                    return@launch
                }
                exported = file
                StoryMedia.Prepared(part.jpeg, part.blurThumb, StoryMedia.Video(clip.file, clip.info.copy(bytes = file.length())),
                    part.width, part.height, media.stickers, media.repostOf)
            }
            val video = media.video
            val pieces = video?.let { com.fariin.app.data.StoryLimits.segments(it.info.durationSec) }.orEmpty()
            outcome = if (video == null || pieces.isEmpty()) {
                postOne(app, me, media, caption, choice, picked, hours, convs, captureSwitch, myFriends, hidden, lists, group)
            } else {
                // iOS postVideo: a clip past 90 s goes as several stories in order, each its own
                // 90 s piece; the caption and the tap areas ride the first, the one seen first.
                var result: State? = null
                for ((i, range) in pieces.withIndex()) {
                    val file = java.io.File(app.cacheDir, "story-part-${System.nanoTime()}.mp4")
                    try {
                        val part = if (com.fariin.app.media.StoryVideoCut.cut(app, video.file, file, range))
                            StoryMedia.prepareVideo(app, Uri.fromFile(file)) else null
                        if (part == null) {
                            result = State.Failed(if (i == 0) "Couldn't prepare this video for your story."
                                else "Only the first $i parts of this video were posted. The rest couldn't be prepared.")
                            break
                        }
                        val clip = part.video?.let { v -> StoryMedia.Video(v.file, v.info.copy(bytes = file.length())) }
                        val one = StoryMedia.Prepared(part.jpeg, part.blurThumb, clip, part.width, part.height,
                            if (i == 0) media.stickers else emptyList(), media.repostOf)
                        result = postOne(app, me, one, if (i == 0) caption else "", choice, picked, hours, convs, captureSwitch, myFriends, hidden, lists, group)
                        if (result != null) break
                    } finally {
                        file.delete()   // the outbox keeps its own copy
                    }
                }
                result
            }
            } catch (e: Exception) {
                android.util.Log.w("StoryPost", "post failed", e)
                outcome = State.Failed(NETWORK_FAILED)
            } finally {
                exported?.delete()   // the outbox keeps its own copy
                finish(outcome)
                com.fariin.app.data.StoryLimits.refresh(me)
            }
        }
    }

    const val NETWORK_FAILED = "Couldn't post your story. Check your connection and try again."

    /**
     * Try Again, and the connection coming back (iOS StoryOutbox.retry): a failed post goes again
     * from its ticket on disk. [quiet] (the reconnect) shows no "Uploading…", and only clears the
     * failure once nothing is left waiting.
     */
    fun retry(context: android.content.Context, me: String, convs: List<Conversation>, quiet: Boolean = false) {
        val app = context.applicationContext
        if (state.value == State.Uploading || !com.fariin.app.data.StoryOutbox.hasWaiting(app, me)) return
        if (!quiet) state.value = State.Uploading
        scope.launch {
            com.fariin.app.data.StoryOutbox.resume(app, me, convs)
            val waiting = com.fariin.app.data.StoryOutbox.hasWaiting(app, me)
            if (!quiet && state.value == State.Uploading) state.value = if (waiting) State.Failed(NETWORK_FAILED) else null
            else if (quiet && !waiting && state.value is State.Failed) state.value = null
            com.fariin.app.data.StoryLimits.refresh(me)
        }
    }

    /** One story through the outbox; null when it is up, else what to say. */
    private suspend fun postOne(app: android.content.Context, me: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice,
                                picked: Set<String>, hours: Int, convs: List<Conversation>, captureSwitch: Boolean,
                                myFriends: com.fariin.app.data.StoryListDoc, hidden: Set<String>,
                                lists: List<com.fariin.app.data.StoryListDoc>, group: com.fariin.app.data.GroupStoryPost?): State? {
        // On disk first (iOS StoryOutbox): a kill mid-upload is finished at the next start.
        val ticket = com.fariin.app.data.StoryOutbox.write(app, me, media, caption, choice, picked, hours, captureSwitch, lists, group)
        return try {
            StoryService(me).post(media, caption, choice, picked, hours, convs, captureSwitch, myFriends, hidden, lists, fixedId = ticket, group = group)
            ticket?.let { com.fariin.app.data.StoryOutbox.remove(app, it) }
            null
        } catch (e: StoryService.PostRefused) {
            ticket?.let { com.fariin.app.data.StoryOutbox.remove(app, it) }
            State.Failed(e.message ?: "Couldn't post your story.")
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            // The rules said no (iOS isPermanentPostFailure): the day's or the hour's ceiling, and
            // trying again at the next start would only be refused again.
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                ticket?.let { com.fariin.app.data.StoryOutbox.remove(app, it) }
                com.fariin.app.data.StoryLimits.refresh(me)
                State.Failed(com.fariin.app.data.StoryLimits.refusedText())
            } else {
                android.util.Log.w("StoryPost", "post failed", e)
                ticket?.let { com.fariin.app.data.StoryOutbox.release(it) }
                State.Failed(NETWORK_FAILED)
            }
        } catch (e: Exception) {
            android.util.Log.w("StoryPost", "post failed", e)
            ticket?.let { com.fariin.app.data.StoryOutbox.release(it) }
            State.Failed(NETWORK_FAILED)
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
    /**
     * After Post Story the picture flies into My Story in the list behind (iOS StoryPostFlight);
     * [onFlightStart] lets the screens between (camera, library) go first so the list shows.
     * Off (the repost from the viewer): the editor closes at once as before.
     */
    flyHome: Boolean = false,
    onFlightStart: () -> Unit = {},
    /** The + at the caption's start (iOS addMoreButton): opens the library to tick more for this post. Null = no +. */
    onAddMore: (() -> Unit)? = null,
    /** Built under a picture still flying in from the library: drawn once it lands ([hidden] false). */
    hidden: Boolean = false,
) {
    // One picture, one item: the repost and anything else that posts a single story.
    val items = remember(media) { androidx.compose.runtime.mutableStateListOf(StoryItemState(media, null)) }
    StoryComposerDialog(me, items, conversations, onClose, onPosted, textStory, flyHome, onFlightStart, onAddMore, hidden)
}

/**
 * The editor over several pictures at once (iOS StoryEditorView `items`): [items] is the post, in
 * order, and the caller's + adds to it. Each item keeps its own caption, edits, pinch, trim and
 * sound; Post Story posts every one as its own story. Empty = nothing to show (the caller closes).
 */
@Composable
internal fun StoryComposerDialog(
    me: String,
    items: androidx.compose.runtime.snapshots.SnapshotStateList<StoryItemState>,
    conversations: List<Conversation>,
    onClose: () -> Unit,
    onPosted: () -> Unit = onClose,
    textStory: Boolean = false,
    flyHome: Boolean = false,
    onFlightStart: () -> Unit = {},
    onAddMore: (() -> Unit)? = null,
    hidden: Boolean = false,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
        StoryComposer(me, items, conversations, onClose, onPosted, textStory, flyHome, onFlightStart, onAddMore, hidden)
    }
}

/**
 * One picture of the post and everything done to it (iOS DraftItem). Snapshot state, so the editor
 * shows whichever item is current and switching items (the strip) keeps each one's work as it was.
 */
internal class StoryItemState(val media: StoryMedia.Prepared, val uri: Uri?) {
    var caption by mutableStateOf("")
    val edits = StoryEdits()
    /** 0: nobody has framed it yet, so it shows at its default (iOS DraftItem.zoom); any other number is the owner's. */
    var zoomSet by mutableFloatStateOf(0f)
    var pan by mutableStateOf(Offset.Zero)
    /** A clip's sound and length (iOS DraftItem.muted / trimStart / trimEnd; trimEnd 0 = not trimmed). */
    var muted by mutableStateOf(false)
    var trimStart by mutableFloatStateOf(0f)
    var trimEnd by mutableFloatStateOf(0f)
    val isVideo get() = media.video != null
    val clipSeconds get() = (media.video?.info?.durationSec ?: 0.0).toFloat()
    /** iOS hasWork, for this item: words, anything placed, framed by hand, cut or silenced. */
    fun hasWork() = caption.isNotBlank() || !edits.isEmpty || zoomSet > 0f || muted || StoryTrimRules.isTrimmed(trimStart, trimEnd, clipSeconds)
}

/** Where an item's picture sits on the card, the same sums on screen and in the render. */
private class ItemFrame(val img: Rect, val zoomDefault: Float, val zoom: Float, val space: Rect, val shown: Rect,
                        val strokeMatrix: android.graphics.Matrix?)

private fun itemFrame(bw: Int, bh: Int, card: Rect, zoomable: Boolean, isVideo: Boolean, zoomSet: Float, pan: Offset): ItemFrame {
    // The picture fitted in the card; the zoom and pan move it from here.
    val img = if (bw <= 0 || bh <= 0 || card.width <= 1f) Rect.Zero else {
        val k = minOf(card.width / bw.toFloat(), card.height / bh.toFloat())
        val w = bw * k; val h = bh * k
        Rect(Offset(card.left + (card.width - w) / 2, card.top + (card.height - h) / 2), androidx.compose.ui.geometry.Size(w, h))
    }
    // iOS defaultZoom: a portrait photo opens filling the card, so what is seen is what is posted.
    val zoomDefault = if (bw <= 0 || bh <= 0 || card.height <= 1f || isVideo) 1f else StoryCardRules.defaultZoom(bw, bh, card.width / card.height)
    val zoom = if (!zoomable) 1f else if (zoomSet > 0f) zoomSet else zoomDefault
    // Where the edits live: the card itself when the picture can move under them (iOS: one space,
    // fixed to the card, that no zoom can move), else the picture.
    val space = if (zoomable) card else img
    val shown = if (img.width <= 1f) Rect.Zero else Rect(
        Offset(card.center.x + pan.x - img.width * zoom / 2, card.center.y + pan.y - img.height * zoom / 2),
        androidx.compose.ui.geometry.Size(img.width * zoom, img.height * zoom))
    // The pen lines ride the picture (iOS draws the drawing with the photo's zoom and offset).
    val m = if (!zoomable || space.width <= 1f) null else {
        val k = StoryEditRules.SPACE / space.width
        val v = StoryCardRules.strokeTransform(StoryEditRules.SPACE, space.height * k, zoom, pan.x * k, pan.y * k)
        android.graphics.Matrix().apply { setValues(floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5], 0f, 0f, 1f)) }
    }
    return ItemFrame(img, zoomDefault, zoom, space, shown, m)
}

/**
 * One item as it will go up (iOS send renders every item from its own edits): a photo flattened
 * with its pinch and edits, or a clip with the recipe its export follows in StoryUploads. Off the
 * main thread.
 */
private fun renderItem(it: StoryItemState, card: Rect, textStory: Boolean): Pair<StoryMedia.Prepared, com.fariin.app.media.StoryVideoCut.Edit?> {
    val base = it.media
    val edits = it.edits
    val pan = it.pan
    val bmp = BitmapFactory.decodeByteArray(base.jpeg, 0, base.jpeg.size) ?: return base to null
    val ground = runCatching { RepostCard.edgeColours(bmp) }.getOrNull() ?: (0xFF000000.toInt() to 0xFF000000.toInt())
    val zoomable = StoryCardRules.zoomable(textStory, base.stickers.isNotEmpty() || base.repostOf != null)
    val isVideo = base.video != null
    val fr = itemFrame(bmp.width, bmp.height, card, zoomable, isVideo, it.zoomSet, pan)
    bmp.recycle()
    val img = fr.img; val shown = fr.shown; val zoom = fr.zoom; val m = fr.strokeMatrix
    if (isVideo) {
        // iOS videoBurnIn: the edits, the pinch, the cut and the sound travel to the export.
        val range = StoryTrimRules.rangeMs(it.trimStart, it.trimEnd, it.clipSeconds)
        val art = !edits.isEmpty
        val framed = zoom > 1.001f || kotlin.math.abs(pan.x) > 0.5f || kotlin.math.abs(pan.y) > 0.5f
        if (!art && !framed && !it.muted && range == null) return base to null
        val onCard = (art || framed) && card.width > 1f && shown.width > 1f
        // 540 across like every story clip, the card's own shape, even sides for the encoder.
        val cw = if (onCard) 540 else 0
        val ch = if (onCard) (kotlin.math.round(540f * card.height / card.width / 2f).toInt() * 2).coerceAtLeast(2) else 0
        val visible = shown.intersect(card)
        val crop = if (onCard && framed && visible.width > 1f && visible.height > 1f) android.graphics.RectF(
            (visible.left - shown.left) / shown.width, (visible.top - shown.top) / shown.height,
            (visible.right - shown.left) / shown.width, (visible.bottom - shown.top) / shown.height) else null
        var chips = emptyList<com.fariin.app.data.StoryTapTarget>()
        val overlay = if (!onCard) null else {
            val o = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(o)
            val f = cw / card.width
            // The card's canvas round the clip, where the clip leaves the card open.
            c.save()
            c.clipOutRect((visible.left - card.left) * f, (visible.top - card.top) * f,
                (visible.right - card.left) * f, (visible.bottom - card.top) * f)
            StoryMedia.paintCanvas(c, cw, ch, ground.first, ground.second)
            c.restore()
            if (art) bakeStoryEdits(c, cw / StoryEditRules.SPACE, edits, m)
            chips = chipTapTargets(edits, cw, ch)
            o
        }
        val posted = StoryMedia.Prepared(base.jpeg, base.blurThumb, base.video, base.width, base.height, base.stickers + chips, base.repostOf)
        return posted to com.fariin.app.media.StoryVideoCut.Edit(range, it.muted, crop, cw, ch, overlay)
    }
    // iOS flatten's one fast path: nothing placed, never framed, and the picture fills the card.
    val untouched = edits.isEmpty && kotlin.math.abs(zoom - 1f) < 0.001f && pan == Offset.Zero &&
        img.width >= card.width - 1f && img.height >= card.height - 1f
    if ((!zoomable && !StoryEditRules.needsBake(edits.texts.size, edits.strokes.size)) || (zoomable && untouched)) return base to null
    if (!zoomable) return (StoryMedia.bake(base, chipTapTargets(edits, base.width, base.height)) { c, k -> bakeStoryEdits(c, k, edits) } ?: base) to null
    // iOS flatten: the card as it stands, 1080 wide.
    val outW = StoryRules.PHOTO_WIDTHS.first()
    val outH = kotlin.math.round(outW * card.height / card.width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val f = outW / card.width.coerceAtLeast(1f)
    val dst = android.graphics.RectF((shown.left - card.left) * f, (shown.top - card.top) * f,
        (shown.right - card.left) * f, (shown.bottom - card.top) * f)
    return (StoryMedia.renderCard(base, outW, outH, dst, ground.first, ground.second,
        chipTapTargets(edits, outW, outH)) { c, k -> bakeStoryEdits(c, k, edits, m) } ?: base) to null
}

/** A strip thumbnail (iOS itemStrip): 44 square, 8 corners, the current one ringed in blue with the only X. */
@Composable
private fun StripThumb(item: StoryItemState, current: Boolean, onTap: () -> Unit, onRemove: () -> Unit) {
    val pic by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item) {
        value = kotlinx.coroutines.withContext(Dispatchers.Default) {
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(item.media.jpeg, 0, item.media.jpeg.size, o)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E)).clickable(onClick = onTap)
            .then(if (current) Modifier.border(2.dp, Color(0xFF0A84FF), RoundedCornerShape(8.dp)) else Modifier)) {
            pic?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (item.isVideo) Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(13.dp))
        }
        if (current) Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(22.dp).clip(CircleShape)
            .clickable(onClickLabel = "Remove", onClick = onRemove), contentAlignment = Alignment.Center) {
            Box(Modifier.size(17.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
    }
}

@Composable
private fun StoryComposer(me: String, items: androidx.compose.runtime.snapshots.SnapshotStateList<StoryItemState>, conversations: List<Conversation>,
                          onClose: () -> Unit, onPosted: () -> Unit, textStory: Boolean = false,
                          flyHome: Boolean = false, onFlightStart: () -> Unit = {}, onAddMore: (() -> Unit)? = null,
                          hidden: Boolean = false) {
    if (items.isEmpty()) return
    // ARRIVING FROM THE LIBRARY (owner's video 2026-11-08: the bare picture sat ~170 ms, then every
    // control popped in at once). The editor is built while the picture flies, unseen; when it lands
    // the card shows in the very place the flying copy drew it and the controls fade in (iOS).
    val chromeIn = remember { androidx.compose.animation.core.Animatable(if (hidden) 0f else 1f) }
    LaunchedEffect(hidden) { if (!hidden) chromeIn.animateTo(1f, androidx.compose.animation.core.tween(220)) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The item on the card (iOS index): the strip moves it, the X takes one out.
    var index by remember { mutableIntStateOf(0) }
    if (index > items.lastIndex) index = items.lastIndex
    val cur = items[index]
    val media = cur.media
    var captionFocused by remember { mutableStateOf(false) }
    var hours by remember { mutableIntStateOf(StoryRules.DEFAULT_HOURS) }
    var hoursMenu by remember { mutableStateOf(false) }
    var stickerTray by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    var askPlace by remember { mutableStateOf(false) }
    var audience by remember { mutableStateOf(textStory) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Decoded off the main thread (about 7 ms on the low-end phone); null until it lands.
    val base = media
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, base) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            BitmapFactory.decodeByteArray(base.jpeg, 0, base.jpeg.size)?.asImageBitmap()
        }
    }
    // The picture's colours, blurred, as the card's wash behind a photo that does not fill it (iOS StoryCanvas).
    val wash = remember(media) {
        runCatching {
            val b = android.util.Base64.decode(media.blurThumb, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }.getOrNull()
    }
    // The story canvas's two colours (iOS StoryCanvas): the card's ground behind a picture that does
    // not fill it, in the editor and in the posted card alike.
    val edges by androidx.compose.runtime.produceState<Pair<Int, Int>?>(null, bmp) {
        val b = bmp ?: return@produceState
        value = kotlinx.coroutines.withContext(Dispatchers.Default) { runCatching { RepostCard.edgeColours(b.asAndroidBitmap()) }.getOrNull() }
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isVideo = base.video != null
    // Pinch and pan of the picture on the card (iOS ZoomableImageView, baked into the post). Not on a
    // text story's card, a repost or a picture already carrying tap areas: those post as they are.
    val zoomable = StoryCardRules.zoomable(textStory, media.stickers.isNotEmpty() || media.repostOf != null)
    // A clip only zooms in: its zoom becomes the export's crop (iOS videoBurnIn).
    val minZoom = if (isVideo) StoryCardRules.VIDEO_MIN_ZOOM else StoryCardRules.MIN_ZOOM
    val clipSeconds = cur.clipSeconds
    var trimming by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // Every item as it will go up, filled in by Next (a text story's card goes as it is).
    var prepared by remember {
        mutableStateOf<List<Pair<StoryMedia.Prepared, com.fariin.app.media.StoryVideoCut.Edit?>>>(
            if (textStory) items.map { Pair(it.media, null) } else emptyList())
    }
    // The drag that closes the editor back to the picker (iOS handleDismissPan); 0 at rest.
    var dragY by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }

    // The editor tools (iOS StoryEditorView Aa and pen). Photos only: iOS burns them into a video
    // by re-encoding it, which Android does not do yet.
    val canEdit = bmp != null
    val edits = cur.edits
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
    // The post's flight home: the picture, where it starts, its corners. Null until Post Story.
    var flight by remember { mutableStateOf<Triple<androidx.compose.ui.graphics.ImageBitmap, Rect, Float>?>(null) }
    // The editor's black and the share sheet leave as the flight starts (iOS: black 0.15 s, sheet 0.3 s).
    val leaveBlack = remember { androidx.compose.animation.core.Animatable(1f) }
    val leaveSheet = remember { androidx.compose.animation.core.Animatable(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var captionArmed by remember { mutableStateOf(true) }
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    // The picture's rounded frame (iOS story editor, owner screenshots 2026-11-04): under the status
    // bar, ending above the bar of tools; the picture is fitted inside it, edits keep its rectangle.
    val statusTop = WindowInsets.statusBars.getTop(density)
    val barPx = with(density) { EDIT_BAR.toPx() }
    // THE CARD (iOS StoryEditorView.cardSize): full width, 8 under the status bar, 9:16 or as tall as
    // the room above the tool bar allows, 40 corners. It never moves (the keyboard does not resize it).
    val cardTopPx = with(density) { CARD_TOP.toPx() }
    val card = remember(box, statusTop, navBottom) {
        if (box.width == 0) Rect.Zero else {
            val room = box.height - statusTop - cardTopPx - navBottom - barPx
            val h = minOf(kotlin.math.ceil(box.width * 16f / 9f), room).coerceAtLeast(1f)
            Rect(Offset(0f, statusTop + cardTopPx), androidx.compose.ui.geometry.Size(box.width.toFloat(), h))
        }
    }
    // Where the current item's picture sits (itemFrame: the same sums the render uses).
    val frame = itemFrame(bmp?.width ?: 0, bmp?.height ?: 0, card, zoomable, isVideo, cur.zoomSet, cur.pan)
    val img = frame.img
    val zoomDefault = frame.zoomDefault
    fun currentZoom() = if (!zoomable) 1f else if (cur.zoomSet > 0f) cur.zoomSet else zoomDefault
    val zoom = frame.zoom
    val space = frame.space
    val shown = frame.shown
    val strokeMatrix = frame.strokeMatrix
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
        val k = StoryEditRules.SPACE / space.width.coerceAtLeast(1f)
        val t = StoryTextItem(edits.newId(), StoryEditRules.SPACE / 2, space.height * k / 2)
        edits.drawingOnTop = false
        edits.texts.add(t)
        editingId = t.id
    }
    // iOS chipSticker (Location, Link, Time): a white pill that moves like text; Link and Location
    // become a tap area on the posted story.
    fun addChip(label: String, link: String?) {
        val k = StoryEditRules.SPACE / space.width.coerceAtLeast(1f)
        val t = StoryTextItem(edits.newId(), StoryEditRules.SPACE / 2, space.height * k / 2)
        t.text = label; t.bg = StoryEditRules.Bg.SOLID; t.isChip = true; t.link = link
        edits.drawingOnTop = false
        edits.texts.add(t)
    }
    // iOS addSticker: the tray's sticker in the middle of the card, 150 pt across, moving like text.
    fun addSticker(pic: android.graphics.Bitmap) {
        val k = StoryEditRules.SPACE / space.width.coerceAtLeast(1f)
        val t = StoryTextItem(edits.newId(), StoryEditRules.SPACE / 2, space.height * k / 2)
        t.image = pic
        t.imageW = STORY_STICKER_WIDTH
        t.imageH = STORY_STICKER_WIDTH * pic.height / pic.width.coerceAtLeast(1)
        edits.drawingOnTop = false
        edits.texts.add(t)
    }
    fun openPen() { penSnapshot = edits.strokes.toList(); drawing = true }
    fun closePen(discard: Boolean) {
        if (discard) { edits.strokes.clear(); edits.strokes.addAll(penSnapshot) }
        drawing = false
    }
    fun cancelPen() { if (edits.strokes.toList() != penSnapshot) askDiscardPen = true else closePen(false) }
    // Work that a close would lose (iOS hasWork): anything placed, or the picture framed by hand.
    // Several items is work in itself (iOS: items.count > 1); a close asks before dropping them all.
    fun hasWork() = items.size > 1 || items.any { it.hasWork() }
    // The tools in the hand belong to the item being left (iOS select / restoreCurrent).
    fun dropTools() {
        focus.clearFocus(force = true); keyboard?.hide()
        editingId = -1L; drawing = false; trimming = false; scrubMs = null; stickerTray = false
        dragging = false; trashHot = false
    }
    fun select(i: Int) {
        if (i == index || i !in items.indices) return
        dropTools(); index = i
    }
    // iOS remove: the last one cannot go; the next one takes the card with its own tools, not these.
    fun remove(i: Int) {
        if (items.size <= 1 || i !in items.indices) return
        dropTools()
        items.removeAt(i)
        if (index >= items.size) index = items.size - 1 else if (i < index) index -= 1
    }
    fun close() { if (hasWork()) askDiscard = true else onClose() }

    // A pinch let go past either end springs back inside, and the picture's edge back onto the card's.
    fun settleZoom() {
        if (!zoomable || img.width <= 1f) return
        val z0 = currentZoom(); val p0 = cur.pan
        val z1 = z0.coerceIn(minZoom, StoryCardRules.MAX_ZOOM)
        val (px, py) = StoryCardRules.clampPan(p0.x, p0.y, img.width, img.height, z1, card.width, card.height)
        if (z1 == z0 && px == p0.x && py == p0.y) return
        scope.launch {
            androidx.compose.animation.core.animate(0f, 1f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 500f)) { t, _ ->
                cur.zoomSet = z0 + (z1 - z0) * t
                cur.pan = Offset(p0.x + (px - p0.x) * t, p0.y + (py - p0.y) * t)
            }
        }
    }
    // The drag let go (iOS handleDismissPan): a third of the screen or a fast flick closes, the
    // black going with it; anything less springs back.
    fun releaseDrag(velocityY: Float) {
        val far = kotlin.math.abs(dragY) > box.height / 3f || kotlin.math.abs(velocityY) > with(density) { 1000.dp.toPx() }
        val from = dragY
        if (far) {
            leaving = true
            val to = (if (from + velocityY * 0.1f >= 0f) 1f else -1f) * box.height
            scope.launch {
                androidx.compose.animation.core.animate(from, to,
                    animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { v, _ -> dragY = v }
                onClose()
            }
        } else scope.launch {
            androidx.compose.animation.core.animate(from, 0f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 400f)) { v, _ -> dragY = v }
        }
    }

    BackHandler(enabled = editingId >= 0) { finishText() }
    BackHandler(enabled = drawing && editingId < 0) { cancelPen() }
    BackHandler(enabled = !drawing && editingId < 0 && !audience && hasWork()) { askDiscard = true }

    // Two fingers pinch and move the picture (iOS: its pan takes two touches); one finger dragged up
    // or down on the card closes the editor back to the picker under it. A text under the finger
    // takes the touch first (the canvas inside sees it before this does).
    val dragProgress = if (box.height > 0) (kotlin.math.abs(dragY) / (box.height / 2f)).coerceIn(0f, 1f) else 0f
    val cardGestures = if (drawing || editingId >= 0 || flight != null || audience || leaving || baking || trimming) Modifier
    else Modifier.pointerInput(cur, zoomable, img, card, zoomDefault) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed || !card.contains(down.position)) return@awaitEachGesture
            var mode = 0          // 0 not known yet, 1 pinch, 2 closing drag, 3 nothing
            var travel = Offset.Zero
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            while (true) {
                val ev = awaitPointerEvent()
                if (mode == 0 && ev.changes.any { it.isConsumed }) mode = 3
                val pressed = ev.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2 && zoomable && (mode == 0 || mode == 1) && img.width > 1f) {
                    mode = 1
                    val z0 = currentZoom()
                    val z1 = StoryCardRules.liveZoom(z0 * ev.calculateZoom(), minZoom, StoryCardRules.MAX_ZOOM)
                    val f = z1 / z0
                    val at = ev.calculateCentroid(useCurrent = true)
                    val prev = at - ev.calculatePan()
                    val c = card.center
                    // The point under the fingers stays under them while it grows.
                    cur.pan = Offset(at.x - c.x - (prev.x - c.x - cur.pan.x) * f, at.y - c.y - (prev.y - c.y - cur.pan.y) * f)
                    cur.zoomSet = z1
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed) { if (mode == 2) break else continue }
                if (mode == 0 && pressed == 1) {
                    travel += ch.positionChange()
                    if (kotlin.math.abs(travel.y) > slop && kotlin.math.abs(travel.y) > kotlin.math.abs(travel.x)) {
                        // With work on it, iOS asks first (maybePresentDiscardAlert) instead of closing.
                        if (hasWork()) { askDiscard = true; mode = 3 } else mode = 2
                    } else if (travel.getDistance() > slop) mode = 3
                }
                if (mode == 2) {
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    dragY += ch.positionChange().y
                    ch.consume()
                }
            }
            when (mode) {
                1 -> settleZoom()
                2 -> releaseDrag(tracker.calculateVelocity().y)
            }
        }
    }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }
        .background(Color.Black.copy(alpha = leaveBlack.value * (1f - dragProgress))).onSizeChanged { box = it }) {
        val navDp = with(density) { navBottom.toDp() }
        // The card and everything on it move with the closing drag; the gestures sit outside the
        // moving layer so the finger is read in the screen's own frame.
        Box(Modifier.fillMaxSize().then(cardGestures)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = dragY
            val k = 1f - 0.12f * dragProgress
            scaleX = k; scaleY = k
        }) {
        if (card.width > 1f && flight == null) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, card.top.toInt()) }
            .size(with(density) { card.width.toDp() }, with(density) { card.height.toDp() })
            .clip(RoundedCornerShape(CARD_CORNER)).background(Color(0xFF1C1C1E))) {
            val e = edges
            if (zoomable) {
                // The story canvas, the same two colours the posted card is drawn on (iOS StoryCanvas).
                if (e != null) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(e.first), Color(e.second)))))
            } else {
                // The wash: the picture's own colours behind a photo that does not fill the card.
                wash?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.9f }) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            }
            // A clip's poster stays under the player until its first frame is up.
            bmp?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)).graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = cur.pan.x; translationY = cur.pan.y
                        })
                }
            }
            // A video plays here, looping, as on iOS (it only said "Video · 5 s" over its first frame).
            media.video?.let { v ->
                StoryEditVideo(v.file, cur.muted, (cur.trimStart * 1000).toLong(), if (cur.trimEnd > 0f) (cur.trimEnd * 1000).toLong() else 0L, scrubMs,
                    Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = cur.pan.x; translationY = cur.pan.y })
            }
        }
        if (canEdit && flight == null) androidx.compose.runtime.key(cur) { StoryEditCanvas(
            edits, space, editingId, drawing, StoryEditRules.penColor(penHue), penWidth, highlighter,
            onStroke = { stroking = it },
            onTapText = { if (!it.isChip && it.image == null) editingId = it.id },
            onDrag = { _, p ->
                dragging = true
                val hot = StoryEditRules.isOverTrash(p.x, p.y, bin.x, bin.y, binSize, binSlop)
                if (hot != trashHot) trashHot = hot
            },
            onDrop = { t, p ->
                if (StoryEditRules.isOverTrash(p.x, p.y, bin.x, bin.y, binSize, binSlop)) edits.texts.remove(t)
                dragging = false; trashHot = false
            },
            strokeMatrix = strokeMatrix,
        ) }
        }
        }
        val chrome = !drawing && !dragging && editingId < 0 && flight == null && dragY == 0f && !leaving && !trimming
        // Close inside the frame's top corner (iOS).
        if (chrome) Box(Modifier.graphicsLayer { alpha = chromeIn.value }.statusBarsPadding().padding(12.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .clickable { close() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
        // A clip's sound, beside the X (iOS keeps it at the top: it only flips a state).
        if (chrome && isVideo && !audience) Box(Modifier.statusBarsPadding().padding(start = 64.dp, top = 12.dp).size(44.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)).clickable { cur.muted = !cur.muted }, contentAlignment = Alignment.Center) {
            Icon(if (cur.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (cur.muted) "Sound off" else "Sound on", tint = Color.White)
        }
        if (trimming) base.video?.let { v ->
            StoryTrimBar(v.file, clipSeconds, cur.trimStart, cur.trimEnd,
                onScrub = { scrubMs = it },
                onCancel = { scrubMs = null; trimming = false },
                onDone = { s, e ->
                    cur.trimStart = if (s < 0.05f) 0f else s
                    cur.trimEnd = if (e >= clipSeconds - 0.05f) 0f else e
                    scrubMs = null; trimming = false
                },
                modifier = Modifier.align(Alignment.BottomCenter))
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
            Column(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeIn.value }.padding(bottom = lift).fillMaxWidth()) {
            // The strip (iOS itemStrip): only with more than one item, right-aligned over the caption,
            // gone while the caption is being typed.
            if (items.size > 1 && !captionFocused) Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(end = 4.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                items.forEachIndexed { i, item ->
                    androidx.compose.runtime.key(item) {
                        StripThumb(item, i == index, onTap = { select(i) }, onRemove = { remove(i) })
                    }
                }
            }
            Row(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp)).background(Color.Black)
                    .padding(start = if (onAddMore != null) 4.dp else 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onAddMore != null) Box(Modifier.size(width = 38.dp, height = 40.dp).clip(CircleShape).clickable {
                    captionArmed = false; focus.clearFocus(force = true); keyboard?.hide(); onAddMore()
                }, contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_add_media), contentDescription = "Add more photos or videos",
                        tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Box(
                    Modifier.weight(1f).padding(vertical = 10.dp).pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); captionArmed = true }
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (cur.caption.isEmpty()) Text("Add a caption…", color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
                    BasicTextField(cur.caption, { cur.caption = it.take(com.fariin.app.data.StoryLimits.CAPTION_CHARS) }, textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                        cursorBrush = SolidColor(Color.White), maxLines = 4,
                        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = captionArmed }
                            .onFocusChanged { captionFocused = it.isFocused })
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
            }
            // The bar on black under the frame: Aa, stickers and the pen in one capsule, NEXT on the right.
            Row(
                Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeIn.value }.navigationBarsPadding().height(EDIT_BAR).fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canEdit) Row(Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Add text") { addText() }, contentAlignment = Alignment.Center) {
                        Box {
                            Text("Aa", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            NewCornerPill(isNew(NewBadgeSpots.STORY_EDITOR_TEXT))
                        }
                    }
                    // A clip's length (iOS scissors; not on a reposted clip, which is someone else's video).
                    if (isVideo && media.repostOf == null) Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Trim") {
                        focus.clearFocus(force = true); keyboard?.hide(); trimming = true
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ContentCut, contentDescription = "Trim",
                            tint = if (StoryTrimRules.isTrimmed(cur.trimStart, cur.trimEnd, clipSeconds)) Color(0xFFFFD60A) else Color.White,
                            modifier = Modifier.size(24.dp))
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { stickerTray = true }, contentAlignment = Alignment.Center) {
                        Box {
                            Icon(androidx.compose.material.icons.Icons.Outlined.EmojiEmotions, contentDescription = "Stickers", tint = Color.White, modifier = Modifier.size(24.dp))
                            NewCornerPill(isNew(NewBadgeSpots.STORY_EDITOR_STICKERS))
                        }
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { openPen() }, contentAlignment = Alignment.Center) {
                        Box {
                            Icon(Icons.Filled.Draw, contentDescription = "Draw", tint = Color.White, modifier = Modifier.size(24.dp))
                            NewCornerPill(isNew(NewBadgeSpots.STORY_EDITOR_DRAW))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).clickable(enabled = !baking) {
                    focus.clearFocus(); keyboard?.hide()
                    if (card.width <= 1f) return@clickable
                    // iOS send: every item rendered from its own edits, once, here; the share sheet
                    // waits for all of them.
                    baking = true
                    val all = items.toList()
                    val c = card
                    scope.launch {
                        val out = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            all.map { item -> runCatching { renderItem(item, c, textStory) }.getOrNull() }
                        }
                        baking = false
                        if (out.all { it != null }) { prepared = out.filterNotNull(); audience = true }
                    }
                }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("NEXT", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (editingId >= 0) edits.texts.firstOrNull { it.id == editingId }?.let { StoryTextEditor(it, space, onDone = { finishText() }) }
        if (stickerTray) StoryStickerTray(onClose = { stickerTray = false },
            onSticker = { g ->
                stickerTray = false
                scope.launch { StoryStickers.bitmap(ctx, g)?.let { addSticker(it) } }
            },
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
            com.fariin.app.ui.components.FariinSpinner(size = 44.dp, stroke = 5.dp, color = Color.White)
        }
        if (audience) Box(Modifier.fillMaxSize().graphicsLayer { translationY = leaveSheet.value * box.height }) {
            ShareStoryAudiencePage(me, conversations, onBack = { if (textStory) onClose() else audience = false }) { choice, picked, lists, capture ->
                if (flight != null) return@ShareStoryAudiencePage
                // Every item, each its own story, in the post's order (iOS send: one sheet for all).
                val outs: List<Pair<StoryMedia.Prepared, com.fariin.app.media.StoryVideoCut.Edit?>> =
                    if (prepared.size == items.size) prepared else items.map { Pair(it.media, null) }
                items.forEachIndexed { i, item ->
                    val (p, job) = outs[i]
                    StoryUploads.start(ctx, me, p, item.caption, choice, picked, hours, storyConvs(conversations), capture, lists,
                        videoEdit = if (p.video != null) job else null)
                }
                // ONE flight home, the item on the card.
                val flown = outs[index.coerceIn(0, outs.lastIndex)].first
                val pic = if (flyHome) runCatching { BitmapFactory.decodeByteArray(flown.jpeg, 0, flown.jpeg.size)?.asImageBitmap() }.getOrNull() else null
                if (pic == null) { onPosted(); return@ShareStoryAudiencePage }
                // From the picture as the card shows it: a rendered card wears its 40 corners.
                val cardRect = if (flown !== media && zoomable && !isVideo) card else img
                val from = if (cardRect.width > 1f) cardRect else card
                val fills = from.height >= card.height - 1f && from.width >= card.width - 1f
                flight = Triple(pic, from, if (fills) with(density) { CARD_CORNER.toPx() } else 0f)
                onFlightStart()
                scope.launch { leaveBlack.animateTo(0f, androidx.compose.animation.core.tween(150)) }
                scope.launch { leaveSheet.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            }
        }
        flight?.let { (pic, from, radius) -> PostFlightLayer(pic, from, radius, onDone = onPosted) }
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
        } catch (e: StoryMedia.TooLong) {
            Result.failure(e)
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
internal val EDIT_BAR = 80.dp
/** iOS StoryEditorView: the card sits 8 under the status bar with 40 corners. */
internal val CARD_TOP = 8.dp
internal val CARD_CORNER = 40.dp

/** The picked or recorded video, playing on a loop in the editor's frame (the first frame shows until it starts). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StoryEditVideo(uri: Uri, muted: Boolean = false, startMs: Long = 0L, endMs: Long = 0L, scrubMs: Long? = null,
                           modifier: Modifier = Modifier) {
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
    // Sound off is heard at once (iOS previewPlayer.isMuted).
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    // The trimmed piece loops; a handle in the hand shows its frame instead.
    LaunchedEffect(player, startMs, endMs, scrubMs) {
        val scrub = scrubMs
        if (scrub != null) { player.playWhenReady = false; player.seekTo(scrub); return@LaunchedEffect }
        player.playWhenReady = true
        val p0 = player.currentPosition
        if (p0 < startMs || (endMs > 0L && p0 >= endMs)) player.seekTo(startMs)
        while (true) {
            kotlinx.coroutines.delay(40)
            val p = player.currentPosition
            if ((endMs > 0L && p >= endMs) || p < startMs - 150L) player.seekTo(startMs)
        }
    }
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
        modifier = Modifier.fillMaxSize().then(modifier),
    )
}
