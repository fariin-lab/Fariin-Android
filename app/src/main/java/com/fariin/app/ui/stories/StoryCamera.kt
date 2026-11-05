package com.fariin.app.ui.stories

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.camera.core.FocusMeteringAction
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
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

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
 * While holding: slide right onto the lock to keep recording hands free (the shutter turns into a
 * stop button), slide up to zoom. Tap the picture to focus there (a yellow mark shows where). The
 * zoom pill (.5 / 1 / 3, only the stops this lens can reach) sits above the shutter. On the front
 * lens the flash is the screen: white at full brightness while the photo is taken.
 *
 * The gallery card stays a plain card: Android only lets an app read the newest photo with a
 * READ_MEDIA_IMAGES (storage) permission, and the camera asks for none; the picker needs none.
 *
 * Double tap the picture to flip; flick up to open the library (iOS CameraPreview and its swipe).
 * The CAMERA / TEXT switch (or a sideways flick) turns the page into a text story: words on a
 * colour, drawn to a picture and posted as a photo story, exactly as iOS posts it (StoryTextCard).
 */
@Composable
fun StoryCameraDialog(
    active: Boolean,
    error: String?,
    onCaptured: (Uri) -> Unit,
    onLibrary: () -> Unit,
    onClose: () -> Unit,
    /** A finished TEXT story, already drawn to the picture that posts (iOS onTextStory). */
    onTextStory: (com.fariin.app.data.StoryMedia.Prepared) -> Unit = {},
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()   // no grey band over the status bar
        StoryCamera(active, error, onCaptured, onLibrary, onClose, onTextStory)
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
private fun StoryCamera(
    active: Boolean, error: String?, onCaptured: (Uri) -> Unit, onLibrary: () -> Unit, onClose: () -> Unit,
    onTextStory: (com.fariin.app.data.StoryMedia.Prepared) -> Unit,
) {
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
    val library by rememberUpdatedState(onLibrary)
    // CAMERA and TEXT are two things this one page shows (iOS Mode). Text mode's words live here,
    // so going to the camera and back keeps what he typed.
    var textMode by rememberSaveable { mutableStateOf(false) }
    var storyText by rememberSaveable { mutableStateOf("") }
    var styleIndex by rememberSaveable { mutableIntStateOf(0) }
    var fontIndex by rememberSaveable { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    // iOS syncDetectedLink: the link in the words is fetched 400 ms after typing stops, so the card is
    // ready by the time the arrow is pressed.
    LaunchedEffect(storyText, textMode) {
        if (!textMode) return@LaunchedEffect
        val url = com.fariin.app.data.TextStoryRules.firstLink(storyText) ?: return@LaunchedEffect
        delay(400)
        com.fariin.app.data.LinkPreviews.draft(url)
    }
    // The text page slides in over the picture (iOS .snappy 0.25). The preview itself never moves;
    // the camera is let go once the card covers it (iOS stops the session in text mode).
    val textIn by animateFloatAsState(if (textMode) 1f else 0f, tween(250, easing = FastOutSlowInEasing), label = "textMode")
    var cameraParked by remember { mutableStateOf(false) }
    LaunchedEffect(textMode) { if (textMode) { delay(260); cameraParked = true } else cameraParked = false }
    val bindNow = active && hasCamera && !cameraParked
    val view = LocalView.current
    val window = remember(view) { (view.parent as? DialogWindowProvider)?.window }

    // Slide to lock (see StoryCameraRules.stopsOnRelease for who stops a recording).
    var locked by remember { mutableStateOf(false) }
    var lockedByThisTouch by remember { mutableStateOf(false) }
    var lockProgress by remember { mutableFloatStateOf(0f) }
    // Zoom, in the pill's units (= CameraX zoom ratio: 1 is the main lens, 0.5 the wide one).
    var lensMin by remember { mutableFloatStateOf(1f) }
    var lensMax by remember { mutableFloatStateOf(1f) }
    var pillZoom by remember { mutableFloatStateOf(1f) }   // the lit stop; -1 after a pinch or drag
    var zoomAtRecordStart by remember { mutableFloatStateOf(1f) }
    // Tap to focus: where, and a counter so a second tap restarts the mark.
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusTaps by remember { mutableIntStateOf(0) }
    // The front lens's flash: the screen.
    var screenFlash by remember { mutableStateOf(false) }
    var brightnessBefore by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(window) {
        onDispose { brightnessBefore?.let { b -> window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = b } } } }
    }

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
            pillZoom = 1f
            // The lens's zoom range, for the pill and the drag (LiveData, updates only on a change).
            cam?.cameraInfo?.zoomState?.observe(owner) { z -> lensMin = z.minZoomRatio; lensMax = z.maxZoomRatio }
        }
        onDispose {
            camera?.cameraInfo?.zoomState?.removeObservers(owner)
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
                    locked = false; lockedByThisTouch = false; lockProgress = 0f
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

    fun setBrightness(b: Float) {
        val w = window ?: return
        w.attributes = w.attributes.also { it.screenBrightness = b }
    }

    /** iOS captureWithScreenFlashIfNeeded: front lens + flash on = white screen at full brightness,
     *  120 ms for the exposure to see the light, the photo, then the screen put back 350 ms later. */
    fun shoot() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (!StoryCameraRules.needsScreenFlash(flashOn, front, hasFlash)) { takePhoto(); return }
        if (busy || camera == null) return
        scope.launch {
            // Saved once: a second shot inside the flash must not save the full brightness as "before".
            if (brightnessBefore == null) {
                brightnessBefore = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            screenFlash = true
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
            delay(120)
            takePhoto()
            delay(350)
            screenFlash = false
            brightnessBefore?.let { setBrightness(it) }
            brightnessBefore = null
        }
    }

    fun focusAt(at: Offset) {
        val c = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(at.x, at.y)
        // Auto focus and exposure on that spot; CameraX goes back to continuous focus after 5 s,
        // so a tap does not leave the lens stuck there (iOS resets on subject-area change).
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
        runCatching { c.cameraControl.startFocusAndMetering(action) }
        focusAt = at
        focusTaps++
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun currentZoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(lensMin, lensMax))
    }

    fun holdStart() {
        locked = false; lockedByThisTouch = false; lockProgress = 0f
        zoomAtRecordStart = currentZoom()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startRecording()
    }

    /** The finger moved while holding: right is the lock, up is the zoom (never both at once). */
    fun holdMove(dxDp: Float, upDp: Float) {
        if (recording == null) return
        if (!locked) {
            lockProgress = StoryCameraRules.lockProgress(dxDp)
            if (StoryCameraRules.reachesLock(dxDp)) {
                locked = true; lockedByThisTouch = true; lockProgress = 1f
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        StoryCameraRules.dragZoom(zoomAtRecordStart, upDp, dxDp, lensMax)?.let { setZoom(it); pillZoom = -1f }
    }

    /** iOS flipCamera: the button and the double tap both come here. Not mid-clip: a rebind would end it. */
    fun flipCamera() {
        if (!hasCamera || !twoLenses || recording != null) return
        front = !front
        pillZoom = 1f
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun holdEnd() {
        if (!locked) lockProgress = 0f
        if (recording != null && StoryCameraRules.stopsOnRelease(locked, lockedByThisTouch)) stopRecording()
        lockedByThisTouch = false
    }

    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(
                factory = { previewView },
                // A rounded frame under the status bar that stops above the bar, as iOS's camera
                // (owner screenshots 2026-11-04): black around it, the bar's controls on black.
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = BAR_HEIGHT + navBottom)
                    .clip(RoundedCornerShape(28.dp))
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val c = camera ?: return@detectTransformGestures
                            val z = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            if (zoom == 1f) return@detectTransformGestures
                            c.cameraControl.setZoomRatio((z.zoomRatio * zoom).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                            pillZoom = -1f
                        }
                    }
                    // A double tap flips (iOS CameraPreview): the focus tap waits until it is clearly
                    // not the first half of one, so a flip never drops a focus mark on the way.
                    .pointerInput(camera) { detectTapGestures(onTap = { focusAt(it) }, onDoubleTap = { flipCamera() }) }
                    .pointerInput(Unit) {
                        watchSwipes(textMode = { textMode }, blocked = { recording != null || typing },
                            onLibrary = { library() }, onMode = { textMode = it })
                    },
            )
            focusAt?.let { at -> key(focusTaps) { FocusMark(at) } }
        } else if (asked) {
            // Denied: say so and send them to the app's settings page, not a dead black screen.
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
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

        // The text page, sliding in from the right over the still picture.
        if (textIn > 0f) {
            StoryTextCard(
                storyText, { storyText = it }, styleIndex, { styleIndex++ }, fontIndex, { fontIndex++ },
                onTyping = { typing = it }, bottomReserve = BAR_HEIGHT + navBottom, active = textMode,
                onClose = { onClose() },
                modifier = Modifier
                    .graphicsLayer { translationX = (1f - textIn) * size.width }
                    .pointerInput(Unit) {
                        watchSwipes(textMode = { textMode }, blocked = { typing },
                            onLibrary = {}, onMode = { textMode = it })
                    },
            )
        }

        // The camera's own chrome rides off to the left as the text page arrives.
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -textIn * size.width; alpha = 1f - textIn }) {
        // Top: close at the start, flash at the end (only on a lens that has one), the clock in the middle.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChromeButton(Icons.Filled.Close, "Close") { recording?.stop(); onClose() }
            Spacer(Modifier.weight(1f))
            if (hasCamera && camera != null && StoryCameraRules.showsFlashButton(front, hasFlash)) {
                ChromeButton(if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, if (flashOn) "Flash on" else "Flash off",
                    tint = if (flashOn) Color(0xFFFFD60A) else Color.White) {
                    flashOn = !flashOn
                    if (recording != null && hasFlash) camera?.cameraControl?.enableTorch(flashOn)
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

        // Above the bar: the zoom pill, then the shutter with the lock 78 dp to its right while recording.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = BAR_HEIGHT + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val stops = if (hasCamera && camera != null) StoryCameraRules.zoomStops(lensMin, lensMax) else emptyList()
            if (stops.isNotEmpty()) {
                ZoomPill(stops, pillZoom) { s -> pillZoom = s; setZoom(s) }
                Spacer(Modifier.height(20.dp))
            }
            Box(contentAlignment = Alignment.Center) {
                if (hasCamera) {
                    Shutter(
                        recording = recording != null, locked = locked,
                        isRecording = { recording != null },
                        onTap = { if (recording == null) shoot() },
                        onHoldStart = ::holdStart, onHoldMove = ::holdMove, onHoldEnd = ::holdEnd,
                    )
                } else Spacer(Modifier.size(84.dp))
                if (recording != null) {
                    Box(Modifier.offset(x = 78.dp)) {
                        LockButton(locked, lockProgress) {
                            // A second finger on the lock while the first still holds the shutter: that
                            // finger's release is then the tail of a lock, not a press on stop.
                            if (!locked) lockedByThisTouch = true
                            locked = true; lockProgress = 1f
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
            }
        }
        }

        // The bar (iOS bottomBar): the CAMERA / TEXT switch dead centre, the library and the flip
        // beside it on the camera, the send arrow on the text page. Hidden, never removed, while typing.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(BAR_HEIGHT)
                .padding(horizontal = 20.dp).graphicsLayer { alpha = if (typing) 0f else 1f },
            contentAlignment = Alignment.Center,
        ) {
            ModeSwitch(textMode) { t -> if (!typing && recording == null) textMode = t }
            if (!typing) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (textIn < 0.5f) {
                    // While recording, the library and the flip step aside.
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        if (recording == null) LibraryCard(onLibrary)
                    }
                    Spacer(Modifier.weight(1f))
                    if (recording == null && hasCamera && twoLenses) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickable { flipCamera() }.semantics { contentDescription = "Flip camera" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Cameraswitch, contentDescription = null, tint = Color.White) }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    val has = com.fariin.app.data.TextStoryRules.hasText(storyText)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (has) Color.White else Color.White.copy(alpha = 0.18f))
                            .clickable(enabled = has && !rendering) {
                                rendering = true
                                scope.launch {
                                    // The first address in the words gets the page's card, as on iOS: only
                                    // when the page answered (usually already fetched while typing, below).
                                    val link = com.fariin.app.data.TextStoryRules.firstLink(storyText)?.let {
                                        com.fariin.app.data.LinkPreviews.draftWithin(it, 4_000)
                                    }
                                    val out = withContext(Dispatchers.Default) {
                                        runCatching { renderTextStory(storyText, styleIndex, fontIndex, link) }.getOrNull()
                                    }
                                    rendering = false
                                    out?.let { onTextStory(it) }
                                }
                            }
                            .semantics { contentDescription = "Next" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (rendering) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            tint = if (has) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        // The screen is the front lens's flash: white over everything while the photo is taken.
        if (screenFlash) Box(Modifier.fillMaxSize().background(Color.White))
    }
}

/**
 * iOS StoryCameraView's swipe: a flick judged at the release, never tracking the finger, and only
 * watched (nothing is consumed, so pinch and taps still work). Up opens the library. One finger
 * only, and nothing while a recording is or was running in this touch.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.watchSwipes(
    textMode: () -> Boolean,
    blocked: () -> Boolean,
    onLibrary: () -> Unit,
    onMode: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        var multi = false
        var refused = blocked()
        var last = down.position
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (e.changes.size > 1) multi = true
            if (blocked()) refused = true
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            last = c.position
            if (!c.pressed) break
        }
        if (multi || refused) return@awaitEachGesture
        val d = last - down.position
        when (StoryCameraRules.swipe(d.x.toDp().value, d.y.toDp().value, textMode())) {
            StoryCameraRules.Swipe.LIBRARY -> onLibrary()
            StoryCameraRules.Swipe.TO_TEXT -> onMode(true)
            StoryCameraRules.Swipe.TO_CAMERA -> onMode(false)
            StoryCameraRules.Swipe.NONE -> {}
        }
    }
}

/** iOS barHeight. */
private val BAR_HEIGHT = 88.dp

/**
 * The CAMERA / TEXT switch (iOS ComposerTypeSwitch): a 40 dp dark capsule, titles in capitals, the
 * chosen one bold; the light pill slides between them on the same 250 ms as the page.
 */
@Composable
private fun ModeSwitch(text: Boolean, onPick: (Boolean) -> Unit) {
    val seg = 92.dp
    val x by animateDpAsState(if (text) seg else 0.dp, tween(250, easing = FastOutSlowInEasing), label = "modePill")
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(width = seg, height = 34.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)))
        Row {
            listOf("CAMERA" to false, "TEXT" to true).forEach { (label, isText) ->
                val on = isText == text
                Box(
                    Modifier.size(width = seg, height = 34.dp).clip(RoundedCornerShape(50)).clickable { onPick(isText) }
                        .semantics { contentDescription = if (isText) "Text story" else "Camera" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** iOS FocusReticle: a thin yellow square that arrives a little large, settles, holds, fades. */
@Composable
private fun FocusMark(at: Offset) {
    val scale = remember { Animatable(1.35f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
        delay(1100)
        alpha.animateTo(0f, tween(350))
    }
    val half = with(LocalDensity.current) { 37.dp.toPx() }
    val yellow = Color(0xFFFFD60A)
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(74.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
            .border(1.5.dp, yellow, RoundedCornerShape(4.dp)),
    ) {
        Box(Modifier.align(Alignment.CenterStart).size(width = 6.dp, height = 1.5.dp).background(yellow))
        Box(Modifier.align(Alignment.CenterEnd).size(width = 6.dp, height = 1.5.dp).background(yellow))
    }
}

/** iOS zoomPill: the stops this lens can reach; the one in use is wider, yellow and has the "×". */
@Composable
private fun ZoomPill(stops: List<Float>, selected: Float, onPick: (Float) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { s ->
            val on = s == selected
            Box(
                Modifier.height(34.dp).size(width = if (on) 44.dp else 34.dp, height = 34.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = if (on) 0.35f else 0.22f)).clickable { onPick(s) }
                    .semantics { contentDescription = "Zoom ${StoryCameraRules.zoomLabel(s, false)}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(StoryCameraRules.zoomLabel(s, on), color = if (on) Color(0xFFFFD60A) else Color.White,
                    fontSize = if (on) 14.sp else 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** iOS lockButton: grows and brightens as the thumb slides towards it, pops yellow when it latches. */
@Composable
private fun LockButton(locked: Boolean, progress: Float, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (locked) 1.18f else 1f + 0.22f * progress,
        if (locked) spring(dampingRatio = 0.6f) else tween(100), label = "lock",
    )
    val yellow = Color(0xFFFFD60A)
    Box(
        Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border((1.5f + 1.5f * progress).dp, if (locked) yellow.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.35f + 0.55f * progress), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Keep recording without holding" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (locked) Icons.Filled.Lock else Icons.Outlined.Lock, contentDescription = null,
            tint = if (locked) yellow else Color.White, modifier = Modifier.size(20.dp))
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

/**
 * Tap = photo; hold 220 ms = record until the finger lifts (iOS shutter). While holding, the finger's
 * travel goes to [onHoldMove] in dp (right, up): the lock and the zoom. Once locked the shutter is a
 * stop button: a press on it during a recording goes straight to [onHoldEnd], which stops it.
 */
@Composable
private fun Shutter(
    recording: Boolean,
    locked: Boolean,
    isRecording: () -> Boolean,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (dxDp: Float, upDp: Float) -> Unit,
    onHoldEnd: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onHoldStart)
    val move by rememberUpdatedState(onHoldMove)
    val end by rememberUpdatedState(onHoldEnd)
    val busyNow by rememberUpdatedState(isRecording)
    val inner by animateDpAsState(if (!recording) 70.dp else if (locked) 34.dp else 62.dp, tween(200), label = "shutter")
    val corner by animateDpAsState(if (locked && recording) 8.dp else 36.dp, tween(200), label = "shutterCorner")
    Box(
        Modifier.size(84.dp).border(5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = if (recording) "Stop recording" else "Take photo, hold to record" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!busyNow()) {
                        val released = withTimeoutOrNull(StoryCameraRules.HOLD_MS) {
                            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                            true
                        }
                        if (released == true) { tap(); return@awaitEachGesture }
                        start()
                    }
                    // Follow this finger wherever it goes (it may leave the shutter for the lock).
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val d = c.position - down.position
                        move(d.x.toDp().value, -d.y.toDp().value)
                    }
                    end()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(if (recording) corner else 36.dp))
            .background(if (recording) Color(0xFFFF3B30) else Color.White))
    }
}
