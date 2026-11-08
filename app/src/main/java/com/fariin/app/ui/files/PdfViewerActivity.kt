package com.fariin.app.ui.files

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.media.FileOpener
import com.fariin.app.ui.components.FariinSheet
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.SheetMotion
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fariin's own PDF reader (iOS PDFViewerSheet): a received .pdf opens here instead of in another app.
 * The pages scroll top to bottom and pinch to zoom; the bar holds close, the file's name and share;
 * the page you are on floats at the bottom. A locked (password) or damaged file says so and offers
 * the phone's own apps, which can ask for the password.
 *
 * Pages are drawn by the system's PdfRenderer, one at a time on a background thread (it allows only
 * one open page), at the screen's width, and only for the pages in view.
 */
class PdfViewerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"

        /** True for a file this reader takes (by its name, as the bubble knows it). */
        fun handles(fileName: String): Boolean = fileName.lowercase().endsWith(".pdf")

        fun open(c: Context, plain: File, fileName: String) {
            c.startActivity(Intent(c, PdfViewerActivity::class.java)
                .putExtra(EXTRA_PATH, plain.absolutePath).putExtra(EXTRA_NAME, fileName)
                .addFlags(if (c is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // White status icons over the dimmed screen behind the sheet, as iOS while a sheet is up.
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: File(path).name
        // A sheet over the chat (owner, 2026-11-06): it slides up, the chat stays dimmed at the top,
        // and it closes by X, Back, a tap on the dim, the bar dragged down, or the first page pulled down.
        setContent {
            FariinTheme(bars = false) {   // the sheet keeps its white status icons (enableEdgeToEdge above)
                val motion = rememberSheetMotion { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
                BackHandler { motion.close() }
                FariinSheet(motion) { PdfReader(File(path), name, motion) }
            }
        }
    }
}

/** What opening the file came to. */
private sealed interface Opened {
    data object Loading : Opened
    data class Ready(val renderer: PdfRenderer, val fd: ParcelFileDescriptor, val ratios: List<Float>) : Opened
    data object Locked : Opened
    data object Broken : Opened
}

@Composable
private fun PdfReader(file: File, name: String, motion: SheetMotion) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    var opened by remember { mutableStateOf<Opened>(Opened.Loading) }
    val gate = remember { Mutex() }   // PdfRenderer has one open page at a time

    LaunchedEffect(file) {
        var made: Opened.Ready? = null
        try {
            opened = withContext(Dispatchers.IO) {
                try {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = try { PdfRenderer(fd) } catch (e: Exception) { runCatching { fd.close() }; throw e }
                    // Each page's height for its width, read once, so the list knows every page's size
                    // before any is drawn (no jumping while scrolling).
                    val ratios = try {
                        (0 until r.pageCount).map { i -> r.openPage(i).use { p -> p.height.toFloat() / p.width.coerceAtLeast(1) } }
                    } catch (e: Exception) { runCatching { r.close(); fd.close() }; throw e }
                    Opened.Ready(r, fd, ratios).also { made = it }
                } catch (_: SecurityException) {
                    Opened.Locked   // a password-protected file: the system renderer cannot open it
                } catch (_: Exception) {
                    Opened.Broken
                }
            }
        } finally {
            // Closed before a long file finished opening: nothing shows it, so let it go here.
            made?.let { m -> if (opened !== m) closeLater(m, gate) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { (opened as? Opened.Ready)?.let { closeLater(it, gate) } }
    }

    fun openElsewhere() {
        val i = FileOpener.intentFor(ctx, file, name)
        if (i != null) runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }

    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        when (val o = opened) {
            Opened.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.FariinSpinner()
            }
            Opened.Locked -> Notice("This PDF is locked with a password.", "Open it in another app to enter the password.",
                "Open in Another App") { openElsewhere() }
            Opened.Broken -> Notice("Couldn't open the file.", "It may be damaged, or in a format this phone can't open.",
                "Open in Another App") { openElsewhere() }
            is Opened.Ready -> Pages(o, gate, motion)
        }
        // The bar: close, the name, share (iOS: close · filename · share).
        // On the page's grey, so the white circles never sit on a white page (pages scroll under it).
        // The sheet sits under the status bar, so the bar starts at the sheet's rounded top; a drag on
        // it moves the sheet (a tap still reaches the buttons).
        Row(Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.97f)).sheetDrag(motion)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", onClick = { motion.close() })
            Spacer(Modifier.width(10.dp))
            Text(name, fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.card.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 8.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.width(10.dp))
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_link_share), "Share", onClick = {
                val i = FileOpener.intentFor(ctx, file, name) ?: return@FloatingIcon
                val send = Intent(Intent.ACTION_SEND).setType(FileOpener.mimeOf(name))
                    .putExtra(Intent.EXTRA_STREAM, i.data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
            })
        }
    }
}

/**
 * Closes the renderer once no page is being drawn: a page still rendering on the background thread
 * when the sheet goes would otherwise meet a renderer closed under it (a native crash) or make
 * close() refuse, leaving the file open.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun closeLater(o: Opened.Ready, gate: Mutex) {
    GlobalScope.launch(Dispatchers.IO) {
        gate.withLock { runCatching { o.renderer.close() }; runCatching { o.fd.close() } }
    }
}

@Composable
private fun Pages(o: Opened.Ready, gate: Mutex, motion: SheetMotion) {
    val c = Fariin.colors
    val list = rememberLazyListState()
    var widthPx by remember { mutableStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val page by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    Box(Modifier.fillMaxSize().onSizeChanged { widthPx = it.width }
        // One finger pulling down on the first page's top moves the sheet instead (as the browser).
        .sheetDrag(motion, atTop = { !list.canScrollBackward })
        // Two fingers zoom (1x to 4x) and move the zoomed page; one finger still scrolls the list.
        .pointerInput(Unit) {
            detectTransformGestures(panZoomLock = false) { _, p, z, _ ->
                val next = (zoom * z).coerceIn(1f, 4f)
                zoom = next
                pan = if (next == 1f) Offset.Zero else Offset(
                    (pan.x + p.x).coerceIn(-size.width * (next - 1) / 2, size.width * (next - 1) / 2), pan.y)
            }
        }) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = pan.x
        }, verticalArrangement = Arrangement.spacedBy(10.dp),
            // The first page starts below the bar (62 dp), the last above the page count.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 70.dp,
                bottom = 72.dp, start = 10.dp, end = 10.dp)) {
            items(o.ratios.size) { i -> PageImage(o.renderer, i, o.ratios[i], widthPx, gate) }
        }
        // The page in view, floating at the bottom (iOS page indicator).
        if (o.ratios.isNotEmpty()) Text("$page of ${o.ratios.size}", fontSize = 13.sp, color = c.label,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)
                .clip(RoundedCornerShape(14.dp)).background(c.card.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun PageImage(renderer: PdfRenderer, index: Int, ratio: Float, widthPx: Int, gate: Mutex) {
    var bmp by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        // Drawn at twice the screen's width (at most 2400 px) so a pinch to 2x stays sharp.
        val w = (widthPx * 2).coerceAtMost(2400)
        val h = (w * ratio).toInt().coerceIn(1, 6000)
        bmp = withContext(Dispatchers.IO) {
            gate.withLock {
                runCatching {
                    renderer.openPage(index).use { p ->
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(AColor.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    DisposableEffect(index, widthPx) { onDispose { bmp = null } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio.coerceAtLeast(0.05f)).clip(RoundedCornerShape(4.dp)).background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center)
        Text(body, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action, color = c.accent) }
    }
}
