package com.fariin.app.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fariin.app.ui.components.FariinSheet
import com.fariin.app.ui.components.SheetMotion
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme

/**
 * Fariin's own browser (owner 2026-11-06: the iOS app has one, Android only had a plain Custom Tab).
 * The same layout as iOS InAppBrowser: the page fills the screen; a close circle top-left; one bottom
 * row of a back circle, an address capsule (menu, the site, reload; a loading fill sweeps across it)
 * and a compass circle that opens the phone's own browser. The page stays hidden until it is 10 %
 * loaded, then fades in, so it never opens on a half-drawn white flash.
 *
 * Safety, as iOS: no script bridge (no addJavascriptInterface), no file or content access; only
 * http(s) loads in place, other links (tel:, mailto:, maps, app links) go to the system; a page asking
 * for a new window gets it in this one; downloads go to the phone's browser.
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"

        /** Opens [url] in Fariin's browser; anything that is not http(s) goes to the system. */
        fun open(context: Context, url: String) {
            val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
            context.startActivity(Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, uri.toString())
                .addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // White status icons over the dimmed screen behind the sheet, as iOS while a sheet is up.
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        setContent {
            FariinTheme {
                BrowserSheet(onClosed = { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) }) { motion ->
                    Browser(start, motion)
                }
            }
        }
    }
}

/**
 * The browser as a sheet over the chat, as iOS presents it (owner, 2026-11-06): it slides up from the
 * bottom with rounded top corners, the chat stays dimmed behind it at the top, and it closes by its X,
 * by Back, by a tap on the dimmed chat, or by pulling the page down at its top. People can see they
 * never left the app. The page pulls the sheet down (pullsSheet), so no separate handle is needed.
 */
@Composable
private fun BrowserSheet(onClosed: () -> Unit, content: @Composable (SheetMotion) -> Unit) {
    val motion = rememberSheetMotion(onClosed)
    FariinSheet(motion) { content(motion) }
}

/**
 * The page drives the sheet too: a pull down while the page is at its top moves the sheet instead of
 * the page (iOS), with the speed measured for the release. Any other touch is the page's own.
 */
@SuppressLint("ClickableViewAccessibility")
private fun WebView.pullsSheet(motion: SheetMotion) {
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    var startY = 0f
    var lastY = 0f
    var pulling = false
    var tracker: android.view.VelocityTracker? = null
    setOnTouchListener { v, e ->
        val raw = android.view.MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        try {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; lastY = e.rawY; pulling = false
                    tracker?.recycle(); tracker = android.view.VelocityTracker.obtain().also { it.addMovement(raw) }
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(raw)
                    if (!pulling && e.pointerCount == 1 && !v.canScrollVertically(-1) && e.rawY - startY > slop) {
                        pulling = true
                        lastY = e.rawY
                        // The page lets go of this touch (no scroll, no tap).
                        android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                            .also { v.onTouchEvent(it); it.recycle() }
                    }
                    if (pulling) { motion.drag(e.rawY - lastY); lastY = e.rawY; true } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(raw)
                    val was = pulling
                    if (was) {
                        tracker?.computeCurrentVelocity(1000)
                        motion.release(tracker?.yVelocity ?: 0f)
                    }
                    pulling = false
                    tracker?.recycle(); tracker = null
                    was
                }
                else -> pulling
            }
        } finally { raw.recycle() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Browser(start: String, motion: SheetMotion) {
    val onClose: () -> Unit = { motion.close() }
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(start) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val pageAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "pageIn")

    fun refresh(w: WebView) { canBack = w.canGoBack(); canForward = w.canGoForward(); url = w.url ?: url }

    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else onClose() }

    Box(Modifier.fillMaxSize().drawBehind { drawRect(c.bg) }) {
        AndroidView(
            // As iOS InAppBrowser (owner, 2026-11-06): the page fills the screen under the status bar;
            // the close circle and the bottom row float over it.
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha },
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)   // a "new window" opens in this one
                    // Muted autoplay, as the iOS browser: a video page otherwise stays black.
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Android flashes the page's scrollbar on every load: a grey bar ran down the
                    // sheet's edge while it slid up (frame by frame, 2026-11-06). No scrollbars.
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    // The phone's Chrome name without the "; wv" web-view tag: some sites serve a
                    // web view a cut-down page with no video (iOS's browser reads as plain Safari).
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv)", ")").replace(Regex("""Version/\S+\s"""), "")
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val s = request.url.scheme?.lowercase()
                            if (s == "http" || s == "https") return false
                            // A page's own jump to an app link (fb://, intent://) happened without a
                            // tap and threw people out to another browser (owner, 2026-11-06): only a
                            // tap leaves, only to a real app, else the page's web fallback loads here.
                            val fallback = BrowserLinks.leave(context, request.url.toString(), request.hasGesture())
                            if (fallback != null) view.loadUrl(fallback)
                            return true
                        }
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { loading = true; refresh(view) }
                        override fun onPageFinished(view: WebView, u: String?) { loading = false; shown = true; refresh(view) }
                        override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) { refresh(view) }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                            if (newProgress >= 10) shown = true
                        }
                    }
                    setDownloadListener { dl, _, _, _, _ ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl))) }
                    }
                    pullsSheet(motion)
                    loadUrl(start)
                    web = this
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )

        // Close, top-left.
        Box(Modifier.padding(start = 18.dp, top = 14.dp).size(44.dp).floating(CircleShape)
            .clickable(onClickLabel = "Close") { onClose() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Close", tint = c.label, modifier = Modifier.size(22.dp))
        }

        // The bottom row: back, the address capsule, open in the phone's browser.
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).floating(CircleShape).clickable(enabled = canBack) { web?.goBack() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (canBack) c.label else c.label.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(48.dp).floating(RoundedCornerShape(24.dp))) {
                // The loading fill sweeps across behind the address, as on iOS.
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).drawBehind {
                    if (loading && progress < 1f) drawRoundRect(c.label.copy(alpha = 0.10f),
                        size = Size(size.width * progress.coerceIn(0.05f, 1f), size.height), cornerRadius = CornerRadius(0f))
                })
                Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Menu, "Menu", tint = c.label, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Forward") }, enabled = canForward,
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                                onClick = { menu = false; web?.goForward() })
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                                menu = false
                                runCatching {
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, url), null))
                                }
                            })
                            DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = {
                                menu = false
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("link", url))
                            })
                        }
                    }
                    Text(hostOf(url), fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { val w = web ?: return@clickable; if (loading) w.stopLoading() else w.reload() },
                        contentAlignment = Alignment.Center) {
                        Icon(if (loading) Icons.Filled.Stop else Icons.Filled.Refresh, if (loading) "Stop" else "Reload",
                            tint = c.label, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(48.dp).floating(CircleShape).clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Explore, "Open in browser", tint = c.label, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The site's name for the capsule: the host without "www.". */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()?.ifEmpty { null } ?: url
