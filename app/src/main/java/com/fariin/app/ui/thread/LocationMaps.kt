package com.fariin.app.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fariin.app.data.CardRules
import com.fariin.app.data.LocationShare
import com.fariin.app.ui.components.FloatingBack
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Maps for Share Location: MapLibre Native drawing the free OpenFreeMap tiles (OpenStreetMap data).
 * No key and no Google account. The only servers that ever see a coordinate are the tile server
 * (which square of the map to draw) and the place search (PhotonPlaces); everything else stays on
 * the phone.
 */

/**
 * A live map in Compose. The MapView follows the screen's lifecycle (start, resume, pause, stop),
 * hands memory back when the phone runs low, and is destroyed with the composable. It draws into a
 * TextureView, so it can sit under Compose layers and change size without the flicker a
 * SurfaceView shows.
 */
@Composable
internal fun FariinMap(
    modifier: Modifier,
    dark: Boolean,
    initial: CameraPosition,
    onMap: (MapLibreMap) -> Unit = {},
    onStyle: (MapLibreMap, Style) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        val opts = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .camera(initial)
            .logoEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .attributionEnabled(true)
        MapView(ctx, opts).also { it.onCreate(null) }
    }
    MapLifecycle(mapView)
    val styleCb by rememberUpdatedState(onStyle)
    val mapCb by rememberUpdatedState(onMap)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            // The (i) for the map's sources, bottom start, small; our own credit line sits beside it.
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            val e = (6 * density).roundToInt()
            m.uiSettings.setAttributionMargins(e, e, e, e)
            m.uiSettings.setAttributionTintColor(android.graphics.Color.GRAY)
            map = m
            mapCb(m)
        }
    }
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(LocationShare.styleUrl(dark))) { s -> styleCb(m, s) }
    }
    Box(modifier) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        MapCredit(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 8.dp))
    }
}

/** The data licence's line, always on the map ("© OpenStreetMap contributors"). */
@Composable
internal fun MapCredit(modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Text(LocationShare.ATTRIBUTION, fontSize = 10.sp, color = c.label.copy(alpha = 0.75f), maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.bg.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun MapLifecycle(mv: MapView) {
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val ctx = LocalContext.current
    DisposableEffect(owner, mv) {
        // Tracked here so each MapView call is made once and in order, whatever the screen did.
        var started = false
        var resumed = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!started) { mv.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mv.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mv.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mv.onStop(); started = false }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        val memory = object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mv.onLowMemory() }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("old API") override fun onLowMemory() { mv.onLowMemory() }
        }
        ctx.applicationContext.registerComponentCallbacks(memory)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            ctx.applicationContext.unregisterComponentCallbacks(memory)
            if (resumed) mv.onPause()
            if (started) mv.onStop()
            mv.onDestroy()
        }
    }
}

/** The red pin drawn on maps: its tip is the bottom centre of the bitmap. */
internal object PinArt {
    fun pin(density: Float): Bitmap {
        val w = (34 * density).roundToInt()
        val h = (46 * density).roundToInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val r = w / 2f - 3 * density
        val cx = w / 2f
        val cy = r + 2 * density
        val head = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(cx - r * 0.62f, cy + r * 0.78f); lineTo(cx, h - 2 * density); lineTo(cx + r * 0.62f, cy + r * 0.78f); close()
        }
        head.op(tail, Path.Op.UNION)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF3B30.toInt()
            setShadowLayer(3 * density, 0f, density, 0x59000000)
        }
        cv.drawPath(head, fill)
        cv.drawCircle(cx, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        return b
    }

    /** Ground metres per screen dp at a zoom (MapLibre's 512-wide tiles). */
    fun metresPerDp(lat: Double, zoom: Double): Double = 78_271.517 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
}

/**
 * The card's small map: one still picture per place, rendered by MapLibre's snapshotter, never a
 * live map in a chat row (a map engine per bubble would cost memory and battery while scrolling).
 * Kept in memory and on disk by place, zoom, size and theme, so a map scrolled past twice is
 * rendered once and a restart does not fetch it again.
 */
internal object MapCards {
    private val mem = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Main thread only: one render per key, however many bubbles wait for it. */
    private val inflight = HashMap<String, Deferred<Bitmap?>>()
    /** Two renders at a time at most: each holds an offscreen map. */
    private val gate = Semaphore(2)
    private const val MAX_FILES = 300

    fun cached(key: String): Bitmap? = mem.get(key)

    fun key(lat: Double, lon: Double, approx: Boolean, dark: Boolean, w: Dp, h: Dp, density: Float) =
        LocationShare.cardKey(lat, lon, LocationShare.cardZoom(approx), dark, (w.value * density).roundToInt(), (h.value * density).roundToInt())

    suspend fun get(ctx: Context, lat: Double, lon: Double, approx: Boolean, dark: Boolean, w: Dp, h: Dp, density: Float): Bitmap? =
        withContext(Dispatchers.Main.immediate) {
            val key = key(lat, lon, approx, dark, w, h, density)
            mem.get(key)?.let { return@withContext it }
            // Lazy, so it is in the table before it can finish; a failed render is just no picture.
            val job = inflight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    runCatching { load(ctx.applicationContext, key, lat, lon, approx, dark, w, h, density) }.getOrNull()
                } finally { inflight.remove(key) }
            }.also { inflight[key] = it }
            job.await()
        }

    private suspend fun load(ctx: Context, key: String, lat: Double, lon: Double, approx: Boolean, dark: Boolean,
                             w: Dp, h: Dp, density: Float): Bitmap? {
        val dir = File(ctx.cacheDir, "map-cards")
        val file = File(dir, "$key.jpg")
        withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }?.let { mem.put(key, it); return it }
        return gate.withPermit {
            val shot = withTimeoutOrNull(25_000L) { snapshot(ctx, lat, lon, LocationShare.cardZoom(approx), dark, w, h, density) }
                ?: return@withPermit null
            val art = withContext(Dispatchers.Default) { decorate(shot, lat, approx, density) }
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, "$key.tmp")
                    FileOutputStream(tmp).use { art.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    tmp.renameTo(file)
                    val all = dir.listFiles().orEmpty()
                    if (all.size > MAX_FILES) all.sortedBy { f -> f.lastModified() }.take(all.size - MAX_FILES).forEach { f -> f.delete() }
                }
            }
            mem.put(key, art)
            art
        }
    }

    /** One render. The snapshotter is made and answers on the main thread; the drawing is on its own thread. */
    private suspend fun snapshot(ctx: Context, lat: Double, lon: Double, zoom: Double, dark: Boolean,
                                 w: Dp, h: Dp, density: Float): Bitmap? = suspendCancellableCoroutine { cont ->
        MapLibre.getInstance(ctx)
        val opts = MapSnapshotter.Options(w.value.roundToInt(), h.value.roundToInt())
            .withStyleBuilder(Style.Builder().fromUri(LocationShare.styleUrl(dark)))
            .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build())
            .withPixelRatio(density)
            .withLogo(false)
            .withAttribution(true)
        val s = MapSnapshotter(ctx, opts)
        cont.invokeOnCancellation { runCatching { s.cancel() } }
        s.start(object : MapSnapshotter.SnapshotReadyCallback {
            override fun onSnapshotReady(snapshot: MapSnapshot) { if (cont.isActive) cont.resume(snapshot.bitmap) }
        }, object : MapSnapshotter.ErrorHandler {
            override fun onError(error: String) { if (cont.isActive) cont.resume(null) }
        })
    }

    /** The pin on the spot (the picture's centre), or a soft circle for an approximate share. */
    private fun decorate(src: Bitmap, lat: Double, approx: Boolean, density: Float): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(b)
        val cx = b.width / 2f
        val cy = b.height / 2f
        if (approx) {
            val r = (500.0 / PinArt.metresPerDp(lat, LocationShare.cardZoom(true)) * density).toFloat()
            cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F7CF6 })
            cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA2F7CF6.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
            })
        } else {
            val pin = PinArt.pin(density)
            cv.drawBitmap(pin, cx - pin.width / 2f, cy - pin.height, null)
        }
        return b
    }
}

/**
 * The map on top of a location card. Its size is fixed by the caller, so the row's height is known
 * before the picture arrives; until then a quiet flat colour (never a spinner in a chat row).
 */
@Composable
internal fun LocationCardMap(loc: CardRules.Location, approx: Boolean, width: Dp, height: Dp) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val key = MapCards.key(loc.lat, loc.lon, approx, c.dark, width, height, density)
    var image by remember(key) { mutableStateOf(MapCards.cached(key)) }
    val shown by animateFloatAsState(if (image != null) 1f else 0f, tween(180), label = "map")
    LaunchedEffect(key) {
        if (image == null) image = MapCards.get(ctx, loc.lat, loc.lon, approx, c.dark, width, height, density)
    }
    Box(Modifier.size(width, height).background(c.label.copy(alpha = if (c.dark) 0.10f else 0.06f))) {
        image?.let {
            Image(it.asImageBitmap(), contentDescription = "Map preview", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(shown))
        }
    }
}

/**
 * Full-screen map of a shared place: the pin (or the soft circle of an approximate share), back,
 * and two actions, Directions (the phone's map and navigation apps, in a chooser) and Copy
 * coordinates.
 */
@Composable
internal fun LocationMapPage(loc: CardRules.Location, approx: Boolean, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val spot = LatLng(loc.lat, loc.lon)
    val zoom = if (approx) 12.5 else 16.0
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MapDialogWindow(c.dark)
        Box(Modifier.fillMaxSize().background(c.bg)) {
            FariinMap(
                Modifier.fillMaxSize(), c.dark,
                CameraPosition.Builder().target(spot).zoom(zoom).build(),
                onMap = { map = it },
                onStyle = { _, s -> markSpot(s, loc, approx, density) },
            )
            FloatingBack(onDismiss, Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp), circle = true)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                FloatingIcon(Icons.Filled.MyLocation, "Show the place", {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(spot, zoom), 400)
                }, Modifier.align(Alignment.End).padding(bottom = 10.dp), circle = true)
                Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                    .background(c.card).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c.red), contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_location), null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(CardRules.title(loc), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (approx) "Approximate area, about 1 km" else CardRules.coords(loc.lat, loc.lon),
                                fontSize = 13.sp, color = c.secondary, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PageButton("Directions", Icons.Filled.Directions, filled = true, Modifier.weight(1f)) { openDirections(ctx, loc) }
                        PageButton("Copy coordinates", Icons.Filled.ContentCopy, filled = false, Modifier.weight(1f)) { copyCoordinates(ctx, loc) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageButton(text: String, icon: ImageVector, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        modifier.height(46.dp).clip(CircleShape).background(if (filled) c.accent else c.label.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (filled) c.onAccent else c.label
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The pin as a map layer (it moves with the map), or a soft 1 km circle for an approximate share. */
private fun markSpot(s: Style, loc: CardRules.Location, approx: Boolean, density: Float) {
    runCatching {
        s.addSource(GeoJsonSource("fariin-spot", Point.fromLngLat(loc.lon, loc.lat)))
        if (approx) {
            // 500 m of ground at every zoom: the radius in dp doubles with each zoom step.
            val r0 = 500.0 / PinArt.metresPerDp(loc.lat, 0.0)
            s.addLayer(CircleLayer("fariin-spot-area", "fariin-spot").withProperties(
                PropertyFactory.circleRadius(Expression.interpolate(Expression.exponential(2), Expression.zoom(),
                    Expression.stop(0, r0), Expression.stop(22, r0 * 2.0.pow(22)))),
                PropertyFactory.circleColor("#2F7CF6"), PropertyFactory.circleOpacity(0.2f),
                PropertyFactory.circleStrokeColor("#2F7CF6"), PropertyFactory.circleStrokeWidth(1.5f),
            ))
        } else {
            s.addImage("fariin-pin", PinArt.pin(density))
            s.addLayer(SymbolLayer("fariin-spot-pin", "fariin-spot").withProperties(
                PropertyFactory.iconImage("fariin-pin"), PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ))
        }
    }
}

/**
 * Directions: every map and navigation app on the phone, in Android's chooser. The navigation
 * app's own turn-by-turn entry is offered too when one is installed. Nothing goes to a website.
 */
internal fun openDirections(ctx: Context, loc: CardRules.Location) {
    val pm = ctx.packageManager
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse(CardRules.geoUri(loc)))
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse(CardRules.navigationUri(loc)))
    val geoOk = geo.resolveActivity(pm) != null
    val navApp = nav.resolveActivity(pm)?.packageName
    if (!geoOk && navApp == null) {
        Snack.show("No maps app on this phone.", action = "Copy coordinates") { copyCoordinates(ctx, loc) }
        return
    }
    val chooser = Intent.createChooser(if (geoOk) geo else nav, "Directions")
    if (geoOk && navApp != null) {
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(LabeledIntent(Intent(nav).setPackage(navApp), navApp, "Start navigation", 0)))
    }
    runCatching { ctx.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Snack.show("No maps app on this phone.") }
}

internal fun copyCoordinates(ctx: Context, loc: CardRules.Location) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Coordinates", CardRules.coords(loc.lat, loc.lon)))
    // Android 13 and later show their own "Copied" panel.
    if (Build.VERSION.SDK_INT < 33) Snack.show("Coordinates copied")
}

/**
 * A full-screen map dialog: edge to edge, the bars clear, their icons dark on the light map and
 * light on the dark one; the keyboard lays over the page instead of squeezing the map.
 */
@Composable
internal fun MapDialogWindow(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION") w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION") run {
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
