package com.fariin.app.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.fariin.app.R
import com.fariin.app.data.MyProfile
import com.fariin.app.data.QrRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.min

/*
 * Settings > Share profile and Invite Friends (iOS MyQRView = GroupLinkQRSheet, ScanQRView,
 * InviteShare): my code on a coloured ground with the ten colour choices, Share / Save / Copy link /
 * Scan, and the scanner with its target, flash, a photo of a code and My code. Share goes through
 * the Android share sheet; the camera is asked for when the scanner opens.
 */

internal const val PAGE_SCAN = "qr:scan"

/** The My QR sheet is up; kept here so the scanner's My code can bring it back. */
internal val myQrOpen = mutableStateOf(false)

/** iOS QRTheme: the ground's four corners, the code's ink (or two inks for a multi-colour code). */
private class QrTheme(val corners: List<Long>, val ink: Long, val inks: List<Long>? = null, val dot: Long) {
    companion object {
        private fun plain(top: Long, bottom: Long, ink: Long, dot: Long) = QrTheme(listOf(top, top, bottom, bottom), ink, null, dot)
        private fun multi(c: List<Long>, inks: List<Long>) = QrTheme(c, inks[0], inks, c[0])
        val all = listOf(
            plain(0x5AA2FF, 0x1D3FB0, 0x1C40B0, 0x2C6BED),
            plain(0xFFB36B, 0xB8432A, 0xA83D21, 0xF0703C),
            plain(0x5FD39A, 0x137A4A, 0x0F6B40, 0x21A366),
            plain(0xC48BE8, 0x5B2585, 0x5C2685, 0x8E44AD),
            plain(0x4A4A50, 0x111114, 0x141417, 0x222226),
            multi(listOf(0xF3DC47, 0xF2CD46, 0xE54F41, 0xE44040), listOf(0xB4410C, 0x7F1D1D)),
            multi(listOf(0xE4384B, 0xF53844, 0x42378F, 0x533788), listOf(0xA61B34, 0x33287A)),
            multi(listOf(0x0093E9, 0x0093E9, 0x80D0C7, 0x80D0C7), listOf(0x005A8F, 0x1C6B62)),
            multi(listOf(0xDC7DE6, 0xED82E6, 0x3753E6, 0x4858E6), listOf(0x8E1C93, 0x1B2C9A)),
            multi(listOf(0x65CDAC, 0x65CDAC, 0x0A995A, 0x0A995A), listOf(0x1C6E53, 0x064F2E)),
        )
    }
}

private fun argb(hex: Long): Int = (0xFF000000 or hex).toInt()

/** The 2 x 2 mesh: the four corners blended bilinearly into a small picture that is stretched. */
private val grounds = HashMap<Int, Bitmap>()
private fun ground(i: Int): Bitmap = synchronized(grounds) {
    grounds.getOrPut(i) {
        val c = QrTheme.all[i].corners
        val n = 32
        val px = IntArray(n * n)
        fun ch(v: Long, sh: Int) = ((v shr sh) and 0xFF).toFloat()
        for (y in 0 until n) for (x in 0 until n) {
            val u = x / (n - 1f); val v = y / (n - 1f)
            fun at(sh: Int) = (ch(c[0], sh) * (1 - u) + ch(c[1], sh) * u) * (1 - v) + (ch(c[2], sh) * (1 - u) + ch(c[3], sh) * u) * v
            px[y * n + x] = (0xFF shl 24) or (at(16).toInt() shl 16) or (at(8).toInt() shl 8) or at(0).toInt()
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
}

/**
 * iOS qrImage, drawn: round dots at 0.86 of a module, the three finder squares as rounded rings
 * around a rounded centre, the middle left clear for the mark. A two-ink code runs its inks corner
 * to corner (the iOS screen-blended gradient over black dots gives the same colours).
 */
private fun drawQr(cv: android.graphics.Canvas, m: Array<BooleanArray>, left: Float, top: Float, side: Float, t: QrTheme) {
    val n = m.size
    if (n == 0) return
    val s = side / n
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val inks = t.inks
    if (inks != null) paint.shader = LinearGradient(left, top, left + side, top + side, argb(inks[0]), argb(inks[1]), Shader.TileMode.CLAMP)
    else paint.color = argb(t.ink)
    val clear = QrRules.clearRange(n, 0.22f)
    val d = s * 0.86f; val inset = (s - d) / 2
    for (y in 0 until n) for (x in 0 until n) {
        if (!m[y][x] || QrRules.inEye(x, y, n)) continue
        if (x in clear && y in clear) continue
        cv.drawOval(left + x * s + inset, top + y * s + inset, left + x * s + inset + d, top + y * s + inset + d, paint)
    }
    for ((ex, ey) in QrRules.eyes(n)) {
        val outer = RectF(left + ex * s, top + ey * s, left + (ex + 7) * s, top + (ey + 7) * s)
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRoundRect(outer, s * 2.2f, s * 2.2f, Path.Direction.CW)
            addRoundRect(RectF(outer.left + s, outer.top + s, outer.right - s, outer.bottom - s), s * 1.4f, s * 1.4f, Path.Direction.CW)
        }
        cv.drawPath(ring, paint)
        cv.drawRoundRect(RectF(outer.left + 2 * s, outer.top + 2 * s, outer.right - 2 * s, outer.bottom - 2 * s), s, s, paint)
    }
}

/** iOS QRCentreMark: the app mark in a circle, its black turned into the code's colour (screen blend). */
private fun drawMark(cv: android.graphics.Canvas, mark: Bitmap?, cx: Float, cy: Float, size: Float, tint: Long) {
    val r = size / 2
    val save = cv.saveLayer(cx - r, cy - r, cx + r, cy + r, null)
    val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
    cv.clipPath(clip)
    cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(tint) })
    if (mark != null) cv.drawBitmap(mark, null, RectF(cx - r, cy - r, cx + r, cy + r),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
    cv.restoreToCount(save)
}

private fun markBitmap(c: Context): Bitmap? = runCatching { BitmapFactory.decodeResource(c.resources, R.drawable.qr_centre_mark) }.getOrNull()

/** The code with its mark, as a Compose square. */
@Composable
private fun QrCodeView(link: String, theme: QrTheme, modifier: Modifier) {
    val ctx = LocalContext.current
    val m = remember(link) { runCatching { QrRules.matrix(link) }.getOrNull() }
    val mark = remember { markBitmap(ctx) }
    Canvas(modifier.aspectRatio(1f)) {
        val cv = drawContext.canvas.nativeCanvas
        if (m != null) {
            drawQr(cv, m, 0f, 0f, size.width, theme)
            drawMark(cv, mark, size.width / 2, size.height / 2, size.width * 46f / 250f, theme.inks?.get(0) ?: theme.ink)
        }
    }
}

/** iOS GroupQRCardFace: white card, the photo over its top edge, name, @username, the code, the hint. */
@Composable
private fun CardFace(title: String, subtitle: String, link: String, photoUrl: String?, theme: QrTheme, hint: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(top = 46.dp).shadow(20.dp, RoundedCornerShape(34.dp), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(RoundedCornerShape(34.dp)).background(Color.White)
            .padding(top = 58.dp, bottom = 22.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 2, textAlign = TextAlign.Center)
            Text(subtitle, fontSize = 15.sp, color = Color.Black.copy(alpha = 0.5f))
            QrCodeView(link, theme, Modifier.padding(top = 14.dp).widthIn(max = 250.dp).fillMaxWidth())
            Text(hint, fontSize = 13.sp, color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.padding(top = 10.dp))
        }
        Box(Modifier.align(Alignment.TopCenter).size(102.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Avatar(photoUrl, 92.dp)
        }
    }
}

/** iOS PosterActionIcon on the dark glass: a 50 circle and its name under it, in white. */
@Composable
private fun PosterAction(painter: Painter, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painter, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(label, fontSize = 13.sp, color = Color.White, maxLines = 1)
    }
}

/** A short notice in a capsule, gone after 1.6 s (iOS flash). */
@Composable
private fun Flash(text: String?, modifier: Modifier) {
    // Owner 2026-11-10: it sat on the QR code's logo and just blinked on. Now it drops in at the top,
    // clear of the code and of Android's own copy preview (bottom left), and floats away again.
    var shown by remember { mutableStateOf("") }
    if (text != null) shown = text
    androidx.compose.animation.AnimatedVisibility(visible = text != null, modifier = modifier,
        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f)) { -it } +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { -it / 2 } +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))) {
        Text(shown, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fariin.colors.label,
            modifier = Modifier.floating(CircleShape).padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** iOS MyQRView: my name, @username and profile link on the poster; Scan opens the scanner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyQrSheet(profile: MyProfile?, onClose: () -> Unit, onScan: () -> Unit) {
    val handle = profile?.handle.orEmpty()
    val name = profile?.name.orEmpty()
    QrPosterSheet(title = name.ifEmpty { handle }, subtitle = if (handle.isEmpty()) " " else "@$handle",
        link = if (handle.isEmpty()) QrRules.LINK_HOST else QrRules.userLink(handle), photoUrl = profile?.photoUrl,
        hint = "Scan with the camera to chat", onClose = onClose, onScan = onScan,
        shareText = QrRules.INVITE_SENTENCE + " " + QrRules.inviteLink(handle))
}

/** iOS GroupLinkQRSheet for a group: "Group · N members" under the name, "to join" under the code, no Scan. */
@Composable
internal fun ChannelQrSheet(title: String, link: String, photoUrl: String?, followers: Int, onClose: () -> Unit) {
    QrPosterSheet(title = title, subtitle = "Channel · " + followers + (if (followers == 1) " follower" else " followers"), link = link,
        photoUrl = photoUrl, hint = "Scan with the camera to follow", onClose = onClose, onScan = null,
        shareText = "Follow $title on Fariin: $link")
}

@Composable
internal fun GroupQrSheet(title: String, link: String, photoUrl: String?, memberCount: Int, onClose: () -> Unit) {
    QrPosterSheet(title = title, subtitle = "Group · $memberCount member${if (memberCount == 1) "" else "s"}", link = link,
        photoUrl = photoUrl, hint = "Scan with the camera to join", onClose = onClose, onScan = null,
        shareText = "Join $title on Fariin: $link")
}

/** The poster sheet both codes use (iOS GroupLinkQRSheet): the coloured ground, the card, the colours, the actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrPosterSheet(title: String, subtitle: String, link: String, photoUrl: String?, hint: String,
                          onClose: () -> Unit, onScan: (() -> Unit)?, shareText: String = link) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("groupQR.theme", 0).coerceIn(0, QrTheme.all.size - 1)) }
    val theme = QrTheme.all[themeIndex]
    var toast by remember { mutableStateOf<String?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var dip by remember { mutableStateOf(false) }
    val dipScale by androidx.compose.animation.core.animateFloatAsState(if (dip) 0.94f else 1f,
        if (dip) tween(120) else androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 250f), label = "dip")
    fun flash(t: String) { toast = t; toastSeq++ }
    LaunchedEffect(toastSeq) { if (toast != null) { delay(1600); toast = null } }
    fun close() { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }

    suspend fun card(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val photo = photoUrl?.takeIf { it.isNotEmpty() }?.let { QrPoster.loadPhoto(ctx, it) }
            val bmp = QrPoster.render(ctx, themeIndex, title, subtitle, hint, link, photo)
            File(ctx.cacheDir, "open").apply { mkdirs() }.let { File(it, "fariin-qr.png") }.also { f ->
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onFailure { android.util.Log.w("QrPoster", "card", it) }.getOrNull()
    }
    fun doSave() {
        saving = true
        scope.launch {
            val f = card()
            val ok = f != null && withContext(Dispatchers.IO) {
                // The saver writes a .jpg name and type, so give it a JPEG copy.
                val jpg = File(f.parentFile, "fariin-qr.jpg")
                BitmapFactory.decodeFile(f.path)?.let { b -> jpg.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                com.fariin.app.media.GallerySaver.save(ctx, jpg, false)
            }
            saving = false
            flash(if (ok) "Saved to Photos" else "Could not save")
        }
    }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave() else flash("Photos access is off")
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, dragHandle = null,
        shape = com.fariin.app.ui.components.SheetShape, containerColor = Color.Transparent,
        // Its own painted ground runs to the bottom edge, under the gesture bar; left inset, the page
        // behind showed through the strip there (owner 2026-11-04 screenshot).
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) }) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp).clip(com.fariin.app.ui.components.SheetShape)) {
            val g = ground(themeIndex).asImageBitmap()
            Canvas(Modifier.fillMaxSize()) {
                drawImage(g, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            }
            // iOS: the card floats in the free space (Spacer at least 56 above, 24 below), the
            // colours and actions sit at the bottom.
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)).clickable { close() },
                        contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.weight(1f).heightIn(min = 10.dp))
                CardFace(title, subtitle, link, photoUrl, theme, hint, Modifier.padding(horizontal = 28.dp).scale(dipScale))
                Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    QrTheme.all.forEachIndexed { i, t ->
                        val sel = i == themeIndex
                        val fill = if (t.inks != null) Brush.linearGradient(listOf(Color(argb(t.corners[0])), Color(argb(t.corners[3]))))
                        else Brush.linearGradient(listOf(Color(argb(t.dot)), Color(argb(t.dot))))
                        Box(Modifier.padding(4.dp).size(28.dp).scale(if (sel) 1.12f else 1f).clip(CircleShape).background(fill)
                            .border(if (sel) 3.dp else 1.5.dp, Color.White, CircleShape)
                            .clickable {
                                if (i != themeIndex) {
                                    themeIndex = i; prefs.edit().putInt("groupQR.theme", i).apply()
                                    scope.launch { dip = true; delay(120); dip = false }
                                }
                            })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 16.dp)) {
                    PosterAction(painterResource(R.drawable.ic_link_share), "Share", Modifier.weight(1f)) {
                        // The invite line with the link, not the picture (owner 2026-11-13): a link
                        // opens in one tap; the picture is Save's.
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                            .putExtra(Intent.EXTRA_TITLE, title)
                        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                    }
                    PosterAction(rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_save_down)), if (saving) "Saving…" else "Save", Modifier.weight(1f), enabled = !saving) {
                        if (com.fariin.app.media.GallerySaver.needsPermission(ctx)) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else doSave()
                    }
                    PosterAction(rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy)), "Copy link", Modifier.weight(1f)) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Fariin link", link))
                        flash("Link copied")
                    }
                    if (onScan != null) PosterAction(rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_qrcode_viewfinder)), "Scan", Modifier.weight(1f)) {
                        scope.launch { sheet.hide() }.invokeOnCompletion { onClose(); onScan() }
                    }
                }
            }
            Flash(toast, Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        }
    }
}

/** The saved / shared picture (iOS renderCard: the ground and the face at 390 x 600, 3x). */
private object QrPoster {
    suspend fun loadPhoto(c: Context, url: String): Bitmap? = runCatching {
        // A software bitmap: the poster is drawn on a plain Canvas, which cannot read a hardware one.
        val req = coil3.request.ImageRequest.Builder(c).data(url).size(300).build()
        val r = coil3.SingletonImageLoader.get(c).execute(req)
        (r as? coil3.request.SuccessResult)?.image?.let { img -> (img as? coil3.BitmapImage)?.bitmap }
            ?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    fun render(c: Context, themeIndex: Int, title: String, subtitle: String, hint: String, link: String, photo: Bitmap?): Bitmap {
        val k = 3f
        val w = 390 * k; val h = 600 * k
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val t = QrTheme.all[themeIndex]
        cv.drawBitmap(ground(themeIndex), null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val cardW = (390 - 64) * k
        val left = 32 * k
        val qr = min(250 * k, cardW - 44 * k)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 22 * k; isFakeBoldText = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, (cardW - 44 * k).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build()
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt(); textSize = 15 * k; textAlign = Paint.Align.CENTER }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt(); textSize = 13 * k; textAlign = Paint.Align.CENTER }
        val faceH = 58 * k + titleLayout.height + 4 * k + 20 * k + 14 * k + qr + 10 * k + 17 * k + 22 * k
        val top = 40 * k + ((h - 40 * k) - faceH) / 2
        val rect = RectF(left, top, left + cardW, top + faceH)
        cv.drawRoundRect(RectF(rect.left, rect.top + 12 * k, rect.right, rect.bottom + 12 * k), 34 * k, 34 * k,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000; maskFilter = android.graphics.BlurMaskFilter(20 * k, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        cv.drawRoundRect(rect, 34 * k, 34 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val cx = w / 2
        var y = top + 58 * k
        cv.save(); cv.translate(left + 22 * k, y); titleLayout.draw(cv); cv.restore()
        y += titleLayout.height + 4 * k
        cv.drawText(subtitle, cx, y + 15 * k, sub)
        y += 20 * k + 14 * k
        QrRules.runCatching { matrix(link) }.getOrNull()?.let { drawQr(cv, it, cx - qr / 2, y, qr, t) }
        drawMark(cv, markBitmap(c), cx, y + qr / 2, qr * 46f / 250f, t.inks?.get(0) ?: t.ink)
        y += qr + 10 * k
        cv.drawText(hint, cx, y + 13 * k, small)
        // The photo over the card's top edge, ringed in white.
        val r = 46 * k; val ay = top
        cv.drawCircle(cx, ay, r + 5 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val save = cv.save()
        cv.clipPath(Path().apply { addCircle(cx, ay, r, Path.Direction.CW) })
        if (photo != null) {
            val s = maxOf(2 * r / photo.width, 2 * r / photo.height)
            val pw = photo.width * s; val ph = photo.height * s
            cv.drawBitmap(photo, null, RectF(cx - pw / 2, ay - ph / 2, cx + pw / 2, ay + ph / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            cv.drawColor(0xFFD9DEE6.toInt())
            val letter = title.trimStart('@').take(1).uppercase()
            cv.drawText(letter, cx, ay + 12 * k, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); textSize = 32 * k; textAlign = Paint.Align.CENTER })
        }
        cv.restoreToCount(save)
        return out
    }
}

/** iOS InviteShare.present: the Android share sheet with the sentence and my link. */
internal fun shareInvite(c: Context, handle: String?) {
    val text = QrRules.INVITE_SENTENCE + " " + QrRules.inviteLink(handle)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_TITLE, QrRules.INVITE_TITLE)
    runCatching { c.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * iOS ScanQRView, full screen: the camera with the target, X and the flash at the top, the hint,
 * then a photo of a code, My code. A Fariin user code opens the chat with that person.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
internal fun ScanQrPage(onMyCode: () -> Unit, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    var handling by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var noCodeInPhoto by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasCamera = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!hasCamera) ask.launch(Manifest.permission.CAMERA) }
    DisposableEffect(owner) {
        // Back from the system settings with the camera turned on.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && granted()) { hasCamera = true; denied = false } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun resolve(code: String) {
        if (handling) return
        noCodeInPhoto = false
        val h = QrRules.handleFrom(code)
        if (h == null) {
            notFound = true; offline = false
            handling = true
            scope.launch { delay(1000); handling = false }   // re-arm after a second (iOS rearmScanner)
            return
        }
        handling = true
        scope.launch {
            val found = runCatching { people.searchPeople(h) }
            val p = found.getOrNull()?.firstOrNull { it.handle.equals(h, ignoreCase = true) }
            val cid = p?.let { runCatching { people.startChat(it) }.getOrNull() }
            if (cid != null) { onDone(); people.chat(cid) }
            else { notFound = true; offline = found.isFailure; delay(1000); handling = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            notFound = false; noCodeInPhoto = false
            val code = withContext(Dispatchers.Default) {
                com.fariin.app.media.BitmapDecode.fromUri(ctx, uri, 1600)?.let { b ->
                    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    QrScan.decode(RGBLuminanceSource(b.width, b.height, px))
                }
            }
            if (code == null) noCodeInPhoto = true else { handling = false; resolve(code) }
        }
    }

    run {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera) CameraFeed(owner, onCamera = { camera = it; hasTorch = it.cameraInfo.hasFlashUnit() }) { code ->
                if (!handling) scope.launch { resolve(code) }
            }
            ScanTarget(locked = handling && !notFound)
            if (denied && !hasCamera) CameraOffCard(ctx)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    GlassCircle(rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_xmark)), "Close", onClose)
                    Spacer(Modifier.weight(1f))
                    if (hasTorch) GlassCircle(rememberVectorPainter(if (torch) ImageVector.vectorResource(R.drawable.ic_sf_bolt_fill) else ImageVector.vectorResource(R.drawable.ic_sf_bolt_slash_fill)),
                        if (torch) "Turn off flash" else "Turn on flash") {
                        torch = !torch; camera?.cameraControl?.enableTorch(torch)
                    }
                }
                Spacer(Modifier.weight(1f))
                val hint = when {
                    denied && !hasCamera -> "Or pick a photo of a code"
                    noCodeInPhoto -> "No QR code in that picture"
                    notFound -> if (offline) "Check your connection" else "No Fariin user found"
                    else -> "Point at a Fariin QR code"
                }
                Text(hint, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fariin.colors.label,
                    modifier = Modifier.align(Alignment.CenterHorizontally).floating(CircleShape).padding(horizontal = 16.dp, vertical = 10.dp))
                Spacer(Modifier.height(40.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassCircle(rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album)), "Choose a photo") {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.floating(CircleShape).clickable(onClick = onMyCode).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_qr), contentDescription = null, tint = Fariin.colors.label, modifier = Modifier.size(20.dp))
                        Text("My code", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label)
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(46.dp))
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { camera?.cameraControl?.enableTorch(false) } }
}

@Composable
private fun GlassCircle(painter: Painter, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).floating(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painter, contentDescription = label, tint = Fariin.colors.label, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CameraOffCard(ctx: Context) {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text("Camera access is off", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text("Fariin needs the camera to scan a QR code. Turn it on in Settings, or pick a photo of a code with the button below.",
            fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
        Text("Open Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.Black,
            modifier = Modifier.padding(top = 4.dp).height(48.dp).clip(CircleShape).background(Color.White).clickable {
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(horizontal = 28.dp, vertical = 13.dp))
    }
}

/** iOS ScanTarget: the dim with a rounded window, four corner arms that breathe until a code locks. */
@Composable
private fun ScanTarget(locked: Boolean) {
    val pulse = rememberInfiniteTransition(label = "scan")
    val breath by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath")
    Canvas(Modifier.fillMaxSize()) {
        val side = min(size.width * 0.72f, size.height * 0.42f)
        val tl = Offset((size.width - side) / 2, (size.height - side) / 2)
        val win = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + side, tl.y + side, CornerRadius(14.dp.toPx())))
        }
        clipPath(win, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.38f)) }
        val b = if (locked) 0f else breath
        val s = 1f - 0.04f * b
        val a = 1f - 0.55f * b
        val sd = side * s
        val o = Offset(tl.x + (side - sd) / 2, tl.y + (side - sd) / 2)
        val arm = 34.dp.toPx(); val r = 14.dp.toPx()
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(o.x, o.y + arm); lineTo(o.x, o.y + r); quadraticTo(o.x, o.y, o.x + r, o.y); lineTo(o.x + arm, o.y)
            moveTo(o.x + sd - arm, o.y); lineTo(o.x + sd - r, o.y); quadraticTo(o.x + sd, o.y, o.x + sd, o.y + r); lineTo(o.x + sd, o.y + arm)
            moveTo(o.x + sd, o.y + sd - arm); lineTo(o.x + sd, o.y + sd - r); quadraticTo(o.x + sd, o.y + sd, o.x + sd - r, o.y + sd); lineTo(o.x + sd - arm, o.y + sd)
            moveTo(o.x + arm, o.y + sd); lineTo(o.x + r, o.y + sd); quadraticTo(o.x, o.y + sd, o.x, o.y + sd - r); lineTo(o.x, o.y + sd - arm)
        }
        drawPath(p, if (locked) Color.Green else Color.White.copy(alpha = a), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The camera: a preview and a frame reader that hands every QR code it reads to [onCode]. */
@Composable
internal fun CameraFeed(owner: androidx.lifecycle.LifecycleOwner, onCamera: (androidx.camera.core.Camera) -> Unit, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val worker = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // A TextureView preview: this sits in its own dialog window, where a SurfaceView can stay black.
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    LaunchedEffect(Unit) {
        val p = suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(ctx))
        } ?: return@LaunchedEffect
        provider = p
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(worker) { image ->
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val data = ByteArray(buf.remaining()).also { buf.get(it) }
                val src = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                QrScan.decode(src)?.let { code -> ContextCompat.getMainExecutor(ctx).execute { onCode(code) } }
            } catch (_: Exception) {
            } finally { image.close() }
        }
        runCatching {
            p.unbindAll()
            onCamera(p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
        }
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); worker.shutdown() } }
    AndroidView({ view }, Modifier.fillMaxSize())
}

private object QrScan {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun decode(src: LuminanceSource): String? = runCatching {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).text
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
