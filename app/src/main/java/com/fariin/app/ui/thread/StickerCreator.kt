package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.StickerFavorites
import com.fariin.app.data.StickerRef
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/** The finished art, at most 512 x 512, transparent where there is no subject (iOS StickerArt). */
internal class MadeSticker(val cutout: Bitmap?, val rounded: Bitmap, val photo: Bitmap)

/**
 * Make a sticker from a photo (iOS StickerCreator, owner 2026-10-17): the subject is lifted out on
 * this phone (ML Kit's on-device subject segmentation, as iOS uses Vision's foreground mask),
 * shown on a checkerboard, and Send Sticker saves it to Favorites and sends it. NOTHING LEAVES THE
 * PHONE but the sticker itself. No subject found: the whole photo as a rounded square. Every
 * failure says so instead of closing silently. The photo comes from Android's photo picker.
 */
@Composable
internal fun StickerCreatorPage(photo: Uri, onClose: () -> Unit, onSend: (StickerRef) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var art by remember { mutableStateOf<MadeSticker?>(null) }
    var noArt by remember { mutableStateOf(false) }
    var useOriginal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // iOS framing: when the photo itself is the sticker, a drag moves it and a pinch zooms it (1 to 4)
    // inside the square, which it always covers. Kept in stage pixels; save turns them into the crop.
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var stagePx by remember { mutableFloatStateOf(1f) }
    val framing = art != null && (useOriginal || art?.cutout == null)

    LaunchedEffect(photo) {
        val made = withContext(Dispatchers.Default) { StickerArtMaker.make(ctx, photo) }
        if (made == null) noArt = true else art = made
    }

    fun save() {
        val a = art ?: return
        if (saving) return
        saving = true
        val framed = framing
        scope.launch {
            val png = withContext(Dispatchers.Default) {
                val image = if (framed) StickerArtMaker.roundedSquare(a.photo, framedCrop(a.photo, zoom, offset, stagePx)) ?: a.rounded
                    else a.cutout ?: a.rounded
                ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            val made = runCatching { StickerFavorites.addMine(png, "🙂") }.getOrNull()
            saving = false
            if (made == null) { saveError = "The sticker couldn't be saved. Check your free space and try again."; return@launch }
            onSend(made)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
        BackHandler { onClose() }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            // iOS stageRect: a square between the top bar (60) and the bottom chrome (176), at most
            // 440, 48 in from the sides; corners at 14% of the side.
            val side = min(maxWidth.value - 48f, min(maxHeight.value - 60f - 176f - 24f - 60f, 440f)).coerceAtLeast(120f).dp
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    Box(Modifier.padding(start = 12.dp).size(44.dp).align(Alignment.CenterStart).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Text("New Sticker", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(side).clip(RoundedCornerShape(side * 0.14f)), contentAlignment = Alignment.Center) {
                        Checkerboard(Modifier.fillMaxSize())
                        val a = art
                        if (a != null) {
                            if (framing) {
                                val photoBmp = remember(a) { a.photo.asImageBitmap() }
                                BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                                    val inner = maxWidth
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(inner * 0.14f))
                                        .onSizeChanged { stagePx = it.width.toFloat().coerceAtLeast(1f) }
                                        .pointerInput(a) {
                                            detectTransformGestures { _, pan, z, _ ->
                                                zoom = (zoom * z).coerceIn(1f, 4f)
                                                offset = clampOffset(offset + pan, zoom, stagePx, a.photo)
                                            }
                                        }) {
                                        // The photo at its aspect-filled size (not clipped to the square
                                        // by the image itself), so a move brings the rest of it in.
                                        val k = inner.value / min(a.photo.width, a.photo.height).coerceAtLeast(1)
                                        Image(photoBmp, contentDescription = "Sticker", contentScale = ContentScale.FillBounds,
                                            modifier = Modifier.align(Alignment.Center).wrapContentSize(unbounded = true)
                                                .requiredSize((a.photo.width * k).dp, (a.photo.height * k).dp).graphicsLayer {
                                                scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                                            })
                                    }
                                }
                            } else Image(a.cutout!!.asImageBitmap(), contentDescription = "Sticker", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                        }
                    }
                }
                // Status line, the Cutout / Original switch, Send Sticker (iOS bottom chrome).
                Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                    val a = art
                    when {
                        a == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            com.fariin.app.ui.components.LoadingLine("Finding the subject…", fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f), spinnerColor = Color.White)
                        }
                        a.cutout == null -> Text("No subject was found, so the whole photo will be the sticker.",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    }
                }
                Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                    if (art?.cutout != null) Segmented(useOriginal) { useOriginal = it }
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(CircleShape)
                        .background(if (art == null) Color.White.copy(alpha = 0.4f) else Color.White)
                        .clickable(enabled = art != null && !saving) { save() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (saving) com.fariin.app.ui.components.ButtonSpinner(Color.Black)
                    else Text("Send Sticker", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (noArt) AlertDialog(onDismissRequest = onClose, title = { Text("Couldn't Make a Sticker") },
            text = { Text("This photo couldn't be made into a sticker. Try another one.") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } })
        saveError?.let { msg ->
            AlertDialog(onDismissRequest = { saveError = null }, title = { Text("Couldn't Save") }, text = { Text(msg) },
                confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } })
        }
    }
}

/** iOS segmented Picker, 240 wide: Cutout | Original. */
@Composable
private fun Segmented(original: Boolean, onPick: (Boolean) -> Unit) {
    Row(Modifier.width(240.dp).height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.16f)).padding(2.dp)) {
        listOf(false to "Cutout", true to "Original").forEach { (v, label) ->
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(7.dp))
                .background(if (v == original) Color.White.copy(alpha = 0.32f) else Color.Transparent)
                .clickable { onPick(v) }, contentAlignment = Alignment.Center) {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** iOS StickerCheckerboard: the transparency grid behind the art. */
/** iOS clamped: the photo, aspect-filled in the square and zoomed, never shows an empty edge. */
private fun clampOffset(o: Offset, zoom: Float, side: Float, photo: Bitmap): Offset {
    val k = side / min(photo.width, photo.height).coerceAtLeast(1)
    val maxX = maxOf(0f, (photo.width * k * zoom - side) / 2)
    val maxY = maxOf(0f, (photo.height * k * zoom - side) / 2)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/** iOS framedCrop: the part of the photo the square shows, in the photo's pixels. */
private fun framedCrop(photo: Bitmap, zoom: Float, o: Offset, side: Float): android.graphics.Rect {
    val s0 = min(photo.width, photo.height).toFloat()
    val visible = s0 / zoom
    val pxPerPt = s0 / (side * zoom)
    val cx = photo.width / 2f - o.x * pxPerPt
    val cy = photo.height / 2f - o.y * pxPerPt
    val l = (cx - visible / 2).roundToInt().coerceIn(0, photo.width - 1)
    val t = (cy - visible / 2).roundToInt().coerceIn(0, photo.height - 1)
    val v = visible.roundToInt().coerceAtMost(minOf(photo.width - l, photo.height - t)).coerceAtLeast(1)
    return android.graphics.Rect(l, t, l + v, t + v)
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    ComposeCanvas(modifier.background(Color(0xFF2C2C2E))) {
        val cell = 12.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) if ((r + c) % 2 == 0)
            drawRect(Color(0xFF3A3A3C), Offset(c * cell, r * cell), Size(cell, cell))
    }
}

/** iOS StickerArtMaker: 512 px art, a 12 px white outline round a lifted subject. */
internal object StickerArtMaker {
    const val SIDE = 512
    private const val OUTLINE = 12f

    fun make(ctx: android.content.Context, uri: Uri): MadeSticker? {
        val photo = decode(ctx, uri, 2048) ?: return null
        val rounded = roundedSquare(photo) ?: return null
        val cutout = runCatching { lift(photo) }.getOrNull()?.let { outlined(it) }
        return MadeSticker(cutout, rounded, photo)
    }

    private fun decode(ctx: android.content.Context, uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // Upright, as the photo is seen (EXIF rotation).
        val deg = ctx.contentResolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it).rotationDegrees
        } ?: 0
        if (deg == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
            android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrNull()

    /** The subject, cropped to it, transparent elsewhere; null when none is found. */
    private fun lift(photo: Bitmap): Bitmap? {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        val result = try { com.google.android.gms.tasks.Tasks.await(seg.process(InputImage.fromBitmap(photo, 0))) } finally { seg.close() }
        val fg = result.foregroundBitmap ?: return null
        // Crop to the opaque pixels.
        var minX = fg.width; var minY = fg.height; var maxX = -1; var maxY = -1
        val row = IntArray(fg.width)
        for (y in 0 until fg.height) {
            fg.getPixels(row, 0, fg.width, 0, y, fg.width, 1)
            for (x in 0 until fg.width) if ((row[x] ushr 24) > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0 || (maxX - minX) < 8 || (maxY - minY) < 8) return null
        return Bitmap.createBitmap(fg, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Scaled inside 512 x 512 with room for the outline; the silhouette grown by 12 px in white under it. */
    fun outlined(subject: Bitmap): Bitmap {
        val pad = OUTLINE + 2
        val room = SIDE - 2 * pad
        val k = min(room / subject.width, room / subject.height)
        val w = (subject.width * k).roundToInt().coerceAtLeast(1); val h = (subject.height * k).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(subject, w, h, true)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val x0 = (SIDE - w) / 2f; val y0 = (SIDE - h) / 2f
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN) }
        // iOS: the silhouette drawn in a ring of offsets (24 at the outline, 12 at half), filled white.
        for ((count, radius) in listOf(24 to OUTLINE, 12 to OUTLINE / 2)) for (i in 0 until count) {
            val a = 2 * Math.PI * i / count
            c.drawBitmap(scaled, x0 + (radius * cos(a)).toFloat(), y0 + (radius * sin(a)).toFloat(), white)
        }
        c.drawBitmap(scaled, x0, y0, white)
        c.drawBitmap(scaled, x0, y0, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The middle square of the photo, 512 px, rounded corners, transparent outside them. */
    fun roundedSquare(photo: Bitmap, crop: android.graphics.Rect? = null): Bitmap? {
        val s = min(photo.width, photo.height)
        if (s <= 0) return null
        val sq = if (crop != null) Bitmap.createBitmap(photo, crop.left, crop.top, crop.width(), crop.height())
            else Bitmap.createBitmap(photo, (photo.width - s) / 2, (photo.height - s) / 2, s, s)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = SIDE * 0.14f
        c.drawRoundRect(RectF(0f, 0f, SIDE.toFloat(), SIDE.toFloat()), r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(sq, null, RectF(0f, 0f, SIDE.toFloat(), SIDE.toFloat()), p)
        return out
    }
}
