package com.fariin.app.ui.photo

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CropMath
import com.fariin.app.media.BitmapDecode
import com.fariin.app.media.LowEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The circle photo cropper (iOS ProfilePhotoCropper, circle stage): the picked photo under a round
 * window as big as the screen allows, the rest dimmed but still visible, pinch to zoom (up to four
 * times the size that just covers the circle) and drag to move, never letting an edge of the photo
 * into the circle. The X (or the back gesture) closes it; the tick hands back the framed square as
 * a JPEG (quality 90, at most 1280 px), which the caller then sizes for its upload.
 *
 * A 1 GB phone: the photo is decoded sampled straight from its Uri (1440 px long side on a low-RAM
 * phone, 2048 otherwise), never at full size, and the cut runs off the main thread.
 */
@Composable
fun CircleCropper(uri: Uri, title: String, subtitle: String, onDone: (ByteArray) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var cutting by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val side = if (LowEnd.isLowRam(ctx)) 1440 else 2048
        val b = withContext(Dispatchers.IO) { BitmapDecode.fromUri(ctx, uri, side) }
        if (b == null) failed = true else source = b
    }
    LaunchedEffect(failed) { if (failed) onCancel() }
    BackHandler(onBack = onCancel)

    var scale by remember(uri) { mutableFloatStateOf(0f) }
    var offX by remember(uri) { mutableFloatStateOf(0f) }
    var offY by remember(uri) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val window = with(density) { CropMath.windowSide(wPx, hPx, 24.dp.toPx(), 200.dp.toPx()) }
        val img = source
        if (img != null) {
            val bmp = remember(img) { img.asImageBitmap() }
            val minS = CropMath.minScale(img.width, img.height, window)
            // First frame, or the window changed size (rotation): back inside the rules.
            if (scale < minS || scale > minS * CropMath.MAX_ZOOM) scale = CropMath.clampScale(if (scale == 0f) minS else scale, minS)
            offX = CropMath.clampOffset(offX, img.width, scale, window)
            offY = CropMath.clampOffset(offY, img.height, scale, window)
            Canvas(
                Modifier.fillMaxSize().pointerInput(img, window) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val (s, x, y) = CropMath.step(
                            img.width, img.height, window, scale, offX, offY,
                            zoom, centroid.x - size.width / 2f, centroid.y - size.height / 2f, pan.x, pan.y,
                        )
                        scale = s; offX = x; offY = y
                    }
                },
            ) {
                val cx = size.width / 2f + offX
                val cy = size.height / 2f + offY
                val dw = img.width * scale
                val dh = img.height * scale
                drawImage(
                    bmp,
                    dstOffset = IntOffset((cx - dw / 2f).roundToInt(), (cy - dh / 2f).roundToInt()),
                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                // Everything outside the circle goes quiet, the picture still showing through.
                val hole = Rect(Offset(size.width / 2f, size.height / 2f), window / 2f)
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, Size(size.width, size.height)))
                    addOval(hole)
                }
                drawPath(dim, Color.Black.copy(alpha = 0.55f))
            }
        } else {
            com.fariin.app.ui.components.FariinSpinner(Modifier.align(Alignment.Center), size = 30.dp, stroke = 3.dp, color = Color.White)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Cancel", onClick = onCancel)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
                Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            }
            if (cutting) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.ButtonSpinner(Color.White)
            } else RoundButton(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Done") {
                val b = source ?: return@RoundButton
                val r = CropMath.cropRect(b.width, b.height, window, scale, offX, offY)
                if (r[2] <= 0) return@RoundButton
                cutting = true
                scope.launch {
                    val jpeg = withContext(Dispatchers.Default) { cut(b, r) }
                    cutting = false
                    if (jpeg != null) onDone(jpeg) else onCancel()
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

/** The framed square, at most [CropMath.MAX_OUTPUT] px, as JPEG 90 (iOS jpegData(0.9)). */
private fun cut(src: Bitmap, r: IntArray): ByteArray? = runCatching {
    val (l, t, s) = Triple(r[0], r[1], r[2])
    val out = CropMath.outputSide(s)
    val sq = Bitmap.createBitmap(src, l, t, s, s)
    val sized = if (out < s) Bitmap.createScaledBitmap(sq, out, out, true).also { if (it !== sq) sq.recycle() } else sq
    val bytes = ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    if (sized !== src) sized.recycle()
    bytes
}.getOrNull()

/**
 * The Android Photo Picker, then the circle cropper. [launch] opens the picker; while a picked
 * photo waits for its crop, [Host] draws the cropper (callers show it in place of their page).
 */
class CirclePhotoPicker internal constructor(
    val launch: () -> Unit,
    /** The phone's camera (iOS ProfilePhotoAction.camera); the shot goes to the same cropper. */
    val takePhoto: () -> Unit,
    val pending: Uri?,
    private val clear: () -> Unit,
    private val onJpeg: (ByteArray) -> Unit,
) {
    @Composable
    fun Host(title: String, subtitle: String) {
        val u = pending ?: return
        CircleCropper(u, title, subtitle, onDone = { clear(); onJpeg(it) }, onCancel = clear)
    }
}

@Composable
fun rememberCirclePhotoPicker(onJpeg: (ByteArray) -> Unit): CirclePhotoPicker {
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Our own photo library sheet, photos only (iOS StoryLibraryPicker photosOnly), not the system
    // picker (owner 2026-11-08: "it doesn't use our advanced sheets"). A tap goes to the cropper.
    var library by rememberSaveable { mutableStateOf(false) }
    if (library) com.fariin.app.ui.stories.StoryLibrarySheet(busy = false, onPick = { uri -> library = false; pending = uri },
        onClose = { library = false }, photosOnly = true)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shot?.let { pending = it } }
    val openCamera = {
        runCatching {
            val dir = java.io.File(ctx.cacheDir, "chat-camera").apply { mkdirs() }
            val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files",
                java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg"))
            shot = u
            camera.launch(u)
        }.onFailure { com.fariin.app.ui.components.Snack.show("Couldn't open the camera.") }
        Unit
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else com.fariin.app.ui.components.Snack.show("Allow the camera in Settings to take a photo.")
    }
    return CirclePhotoPicker(
        launch = { library = true },
        takePhoto = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
            else askCamera.launch(android.Manifest.permission.CAMERA)
        },
        pending = pending,
        clear = { pending = null },
        onJpeg = onJpeg,
    )
}
