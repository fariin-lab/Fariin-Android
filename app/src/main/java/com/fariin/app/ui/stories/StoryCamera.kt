package com.fariin.app.ui.stories

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fariin.app.data.StoryCameraRules
import com.fariin.app.data.StoryRules
import com.fariin.app.perf.DeviceClass
import com.fariin.app.perf.PerfClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The story camera (iOS StoryCameraView), opened by the chat list's camera button, the Stories
 * tab's add button and the launcher's New Story. Tap the shutter for a photo, hold it to record
 * (up to 60 s, letting go stops), flip between the lenses, flash where the lens has one, and the
 * gallery button bottom left opens the system photo and video picker.
 *
 * A capture is written to cache/story-camera and handed back as a content Uri, so it goes through
 * the same StoryMedia.prepare and StoryComposer as a picked item; there is no second upload path.
 *
 * Cheap phones: the preview asks for at most 1280 (960 on LOW) on the long side, video is 720p
 * (480p on LOW when the lens offers it) at a capped bitrate (StoryCameraRules.videoBitrate). The camera is unbound when
 * this screen leaves, while the composer is up ([active] false), and by CameraX itself when the
 * app goes to the background.
 *
 * TODO (iOS has these): slide to lock a recording, zoom pill (.5 / 1 / 3) and drag-up zoom,
 * tap to focus, the screen flash for the front lens, text stories, newest photo on the gallery card.
 */
@Composable
fun StoryCameraDialog(
    active: Boolean,
    error: String?,
    onCaptured: (Uri) -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        StoryCamera(active, error, onCaptured, onLibrary, onClose)
    }
}

private fun granted(context: Context, perm: String) =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

private suspend fun cameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val f = ProcessCameraProvider.getInstance(context)
    f.addListener({
        try { cont.resume(f.get()) } catch (e: Exception) { cont.resumeWithException(e) }
    }, ContextCompat.getMainExecutor(context))
}

/** Old captures are removed when the camera opens, unless a story upload may still be reading one. */
private fun cameraDir(context: Context): File = File(context.cacheDir, "story-camera").apply { mkdirs() }

private fun shareUri(context: Context, f: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", f)

@SuppressLint("MissingPermission")
@Composable
private fun StoryCamera(active: Boolean, error: String?, onCaptured: (Uri) -> Unit, onLibrary: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val lowEnd = remember { DeviceClass.get(context) == PerfClass.LOW }

    var hasCamera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = granted(context, Manifest.permission.CAMERA)
        hasMic = granted(context, Manifest.permission.RECORD_AUDIO)
        asked = true
    }
    LaunchedEffect(Unit) {
        if (StoryUploads.state.value != StoryUploads.State.Uploading) {
            withContext(Dispatchers.IO) { cameraDir(context).listFiles()?.forEach { it.delete() } }
        }
        // Ask for whatever is missing, once per opening (Android itself stops showing the dialog
        // after "Don't ask again"; the denied screen then points to Settings).
        val missing = listOfNotNull(
            Manifest.permission.CAMERA.takeIf { !hasCamera },
            Manifest.permission.RECORD_AUDIO.takeIf { !hasMic },
        )
        if (missing.isNotEmpty() && !asked) ask.launch(missing.toTypedArray())
        else asked = true
    }
    // Back from Settings with access switched on: pick it up without reopening the camera.
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasCamera = granted(context, Manifest.permission.CAMERA)
                hasMic = granted(context, Manifest.permission.RECORD_AUDIO)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var front by rememberSaveable { mutableStateOf(false) }
    var flashOn by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    // Flip is drawn only when the phone has both lenses (a button that does nothing is never drawn).
    var twoLenses by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }
    val captured by rememberUpdatedState(onCaptured)
    val bindNow = active && hasCamera

    // Bind (and rebind on a flip); unbind when the composer covers us or this screen leaves.
    DisposableEffect(bindNow, front) {
        var provider: ProcessCameraProvider? = null
        var bound: List<UseCase> = emptyList()
        val job = if (!bindNow) null else scope.launch {
            val p = try { cameraProvider(context) } catch (e: Exception) { failed = "Couldn't open the camera."; return@launch }
            provider = p
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val wantFront = front
            val usable = runCatching { p.hasCamera(selector) }.getOrDefault(false)
            twoLenses = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                .getOrDefault(false)
            if (!usable) {
                // A phone with one lens: use whichever it has.
                val other = if (wantFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (runCatching { p.hasCamera(other) }.getOrDefault(false)) front = !wantFront
                else failed = "Couldn't open the camera."
                return@launch
            }
            val previewLong = StoryCameraRules.previewLongSide(lowEnd)
            val previewSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(previewLong, previewLong * 9 / 16),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val photoLong = StoryCameraRules.photoLongSide(lowEnd)
            val photoSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(photoLong, photoLong * 9 / 16),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewSel).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(photoSel)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            // The video size this lens really offers, so the bitrate cap matches it.
            val want = if (lowEnd) Quality.SD else Quality.HD
            val offered = runCatching {
                Recorder.getVideoCapabilities(p.getCameraInfo(selector)).getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            }.getOrDefault(emptyList())
            val quality = when {
                want in offered || offered.isEmpty() -> want
                Quality.SD in offered -> Quality.SD
                else -> offered.last()
            }
            val longSide = when (quality) { Quality.UHD -> 3840; Quality.FHD -> 1920; Quality.HD -> 1280; Quality.SD -> 720; else -> 720 }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(StoryCameraRules.videoBitrate(longSide, lowEnd = lowEnd))
                .build()
            val video = VideoCapture.withOutput(recorder)
            // Old cameras cannot run preview + photo + video at once: then the photo is the preview's frame.
            val cam = try {
                p.bindToLifecycle(owner, selector, preview, image, video).also { bound = listOf(preview, image, video); imageCapture = image; videoCapture = video }
            } catch (_: Exception) {
                try {
                    p.bindToLifecycle(owner, selector, preview, video).also { bound = listOf(preview, video); imageCapture = null; videoCapture = video }
                } catch (_: Exception) {
                    try {
                        p.bindToLifecycle(owner, selector, preview, image).also { bound = listOf(preview, image); imageCapture = image; videoCapture = null }
                    } catch (e: Exception) {
                        android.util.Log.w("StoryCamera", "bind failed", e)
                        failed = "Couldn't open the camera."; null
                    }
                }
            }
            camera = cam
            hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
            if (cam != null) failed = null
        }
        onDispose {
            job?.cancel()
            recording?.stop(); recording = null
            camera = null; imageCapture = null; videoCapture = null
            provider?.unbind(*bound.toTypedArray())
        }
    }
    LaunchedEffect(flashOn, imageCapture) {
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun takePhoto() {
        if (busy || camera == null) return
        busy = true
        val file = File(cameraDir(context), "photo_${System.currentTimeMillis()}.jpg")
        val cap = imageCapture
        if (cap == null) {
            val bmp: Bitmap? = previewView.bitmap
            if (bmp == null) { busy = false; return }
            scope.launch {
                withContext(Dispatchers.IO) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                busy = false
                captured(shareUri(context, file))
            }
            return
        }
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = front }
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    captured(shareUri(context, file))
                }
                override fun onError(e: ImageCaptureException) {
                    android.util.Log.w("StoryCamera", "photo failed", e)
                    busy = false
                    failed = "Couldn't take the photo."
                }
            },
        )
    }

    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null || busy) return
        val file = File(cameraDir(context), "clip_${System.currentTimeMillis()}.mp4")
        val opts = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(StoryCameraRules.MAX_VIDEO_SECONDS * 1000L)
            .setFileSizeLimit(StoryRules.VIDEO_MAX_BYTES)
            .build()
        var pending = vc.output.prepareRecording(context, opts)
        if (hasMic) pending = pending.withAudioEnabled()
        recordSeconds = 0
        if (flashOn && hasFlash) camera?.cameraControl?.enableTorch(true)
        recording = pending.start(ContextCompat.getMainExecutor(context)) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> recordSeconds = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    camera?.cameraControl?.enableTorch(false)
                    val ms = ev.recordingStats.recordedDurationNanos / 1_000_000L
                    val okEnd = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                        ev.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    if (okEnd && StoryCameraRules.keepClip(ms) && file.length() > 0) {
                        android.util.Log.i("StoryCamera", "clip ${ms} ms, ${file.length()} bytes")
                        captured(shareUri(context, file))
                    } else {
                        if (ev.hasError() && StoryCameraRules.keepClip(ms)) android.util.Log.w("StoryCamera", "clip error ${ev.error}", ev.cause)
                        file.delete()
                    }
                }
                else -> {}
            }
        }
    }

    fun stopRecording() { recording?.stop() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val c = camera ?: return@detectTransformGestures
                        val z = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        c.cameraControl.setZoomRatio((z.zoomRatio * zoom).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                    }
                },
            )
        } else if (asked) {
            // Denied: say so and send them to the app's settings page, not a dead black screen.
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                Text("Camera access is off. Enable it in Settings", color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                Text(
                    "Open Settings", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }

        // Top: close at the start, flash at the end (only on a lens that has one), the clock in the middle.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChromeButton(Icons.Filled.Close, "Close") { recording?.stop(); onClose() }
            Spacer(Modifier.weight(1f))
            if (hasCamera && hasFlash) {
                ChromeButton(if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, if (flashOn) "Flash on" else "Flash off",
                    tint = if (flashOn) Color(0xFFFFD60A) else Color.White) {
                    flashOn = !flashOn
                    if (recording != null) camera?.cameraControl?.enableTorch(flashOn)
                }
            }
        }
        if (recording != null) {
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(StoryCameraRules.clock(recordSeconds), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFF3B30)).padding(horizontal = 14.dp, vertical = 5.dp))
                if (!hasMic) {
                    Spacer(Modifier.height(8.dp))
                    Text("No sound: microphone access is off", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
        (failed ?: error)?.let {
            Text(it, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))

        // Bottom: gallery, shutter, flip.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (recording == null) LibraryCard(onLibrary)
            }
            Spacer(Modifier.weight(1f))
            if (hasCamera) Shutter(recording != null, onTap = ::takePhoto, onHoldStart = ::startRecording, onHoldEnd = ::stopRecording)
            else Spacer(Modifier.size(84.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                if (recording == null && hasCamera && twoLenses) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable { front = !front }.semantics { contentDescription = "Flip camera" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Cameraswitch, contentDescription = null, tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint) }
}

/** iOS libraryCard: a little deck of cards. No photo on it, so no storage permission is needed. */
@Composable
private fun LibraryCard(onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = "Choose from library" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(x = (-5).dp).rotate(-12f).size(width = 28.dp, height = 36.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.size(width = 30.dp, height = 38.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.18f))
            .border(2.dp, Color.White, RoundedCornerShape(6.dp)))
    }
}

/** Tap = photo; hold 220 ms = record until the finger lifts (iOS shutter). */
@Composable
private fun Shutter(recording: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onHoldStart)
    val end by rememberUpdatedState(onHoldEnd)
    Box(
        Modifier.size(84.dp).border(5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = if (recording) "Stop recording" else "Take photo, hold to record" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val released = withTimeoutOrNull(StoryCameraRules.HOLD_MS) {
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        true
                    }
                    if (released == true) { tap(); return@awaitEachGesture }
                    start()
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    end()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        else Box(Modifier.size(70.dp).clip(CircleShape).background(Color.White))
    }
}
