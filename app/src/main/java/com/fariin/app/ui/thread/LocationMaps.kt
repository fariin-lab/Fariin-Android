package com.fariin.app.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fariin.app.R
import com.fariin.app.data.CardRules
import com.fariin.app.data.LocationShare
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.SheetHandleStrip
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.roundToInt

/*
 * Maps for Share Location: the map engine that comes with the phone's Play Services, as iOS uses
 * the system map. Only a thin client ships in the APK. The coordinates of a share reach the map
 * service (to draw that part of the map) and the place search (PhotonPlaces), nothing else.
 */

/** Play Services is there and new enough to draw maps. Without it the pages fall back to words. */
internal object MapsAvailable {
    fun ok(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
}

/** The dark map style in dark mode (res/raw/map_dark.json); the standard light map otherwise. */
internal fun GoogleMap.applyTheme(ctx: Context, dark: Boolean) {
    runCatching { setMapStyle(if (dark) MapStyleOptions.loadRawResourceStyle(ctx, R.raw.map_dark) else null) }
}

/**
 * A live map in Compose. The MapView follows the screen's lifecycle (start, resume, pause, stop),
 * hands memory back when the phone runs low, and is destroyed with the composable.
 */
@Composable
internal fun FariinMap(modifier: Modifier, dark: Boolean, initial: CameraPosition, onMap: (GoogleMap) -> Unit = {}) {
    val ctx = LocalContext.current
    val mapView = remember {
        val opts = GoogleMapOptions().camera(initial).compassEnabled(false).rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false).mapToolbarEnabled(false).zoomControlsEnabled(false)
        MapView(ctx, opts).also { it.onCreate(null) }
    }
    MapLifecycle(mapView)
    val mapCb by rememberUpdatedState(onMap)
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isMyLocationButtonEnabled = false
            m.uiSettings.isIndoorLevelPickerEnabled = false
            map = m
            mapCb(m)
        }
    }
    LaunchedEffect(map, dark) { map?.applyTheme(ctx, dark) }
    AndroidView({ mapView }, modifier)
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

/**
 * My own fixes as the map's location source: the map draws its blue dot and accuracy ring from
 * them, so there is one location reader on the page, not two.
 */
internal class FixSource : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var last: Location? = null
    override fun activate(l: LocationSource.OnLocationChangedListener) { listener = l; last?.let { l.onLocationChanged(it) } }
    override fun deactivate() { listener = null }
    fun push(l: Location) { last = l; listener?.onLocationChanged(l) }
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
}

private const val AREA_FILL = 0x332F7CF6
private const val AREA_LINE = 0xAA2F7CF6.toInt()

/** The pin on the spot, or the soft 1 km circle of an approximate share. */
internal fun GoogleMap.markSpot(spot: LatLng, approx: Boolean, density: Float) {
    runCatching {
        if (approx) addCircle(CircleOptions().center(spot).radius(LocationShare.APPROX_RADIUS_M)
            .fillColor(AREA_FILL).strokeColor(AREA_LINE).strokeWidth(1.5f * density))
        else addMarker(MarkerOptions().position(spot).anchor(0.5f, 1f)
            .icon(BitmapDescriptorFactory.fromBitmap(PinArt.pin(density))))
    }
}

/**
 * The map on top of a location card: a LITE map, a still picture the map engine draws once, with
 * no gestures and no running renderer, so a chat of many places stays light while scrolling. Its
 * size is fixed by the caller, so the row's height is known before the picture arrives. A lid over
 * it keeps every touch for the bubble (tap opens the full map, long-press the message menu).
 */
@Composable
internal fun LocationCardMap(loc: CardRules.Location, approx: Boolean, width: Dp, height: Dp, pin: Boolean = true) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val ok = remember { MapsAvailable.ok(ctx) }
    Box(Modifier.size(width, height).background(c.label.copy(alpha = if (c.dark) 0.10f else 0.06f)), contentAlignment = Alignment.Center) {
        if (ok) {
            val dark = c.dark
            AndroidView(
                factory = { vc ->
                    val spot = LatLng(loc.lat, loc.lon)
                    MapView(vc, GoogleMapOptions().liteMode(true).mapToolbarEnabled(false)
                        .camera(CameraPosition.fromLatLngZoom(spot, LocationShare.cardZoom(approx).toFloat()))).apply {
                        onCreate(null)
                        isClickable = false
                        getMapAsync { m ->
                            m.uiSettings.isMapToolbarEnabled = false
                            // A lite map opens the maps app on a tap by default; the bubble decides instead.
                            m.setOnMapClickListener { }
                            m.applyTheme(vc, dark)
                            if (pin) m.markSpot(spot, approx, density)
                        }
                    }
                },
                onRelease = { it.onDestroy() },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().pointerInput(Unit) {})
        } else {
            Icon(ImageVector.vectorResource(R.drawable.ic_location), "Map preview", tint = c.red, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * Full-screen map of a shared place: the pin (or the soft circle of an approximate share), back,
 * and two actions, Directions (the phone's map and navigation apps, in a chooser) and Copy.
 */
@Composable
internal fun LocationMapPage(loc: CardRules.Location, approx: Boolean, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val spot = LatLng(loc.lat, loc.lon)
    val zoom = if (approx) 13.5f else 17f
    val ok = remember { MapsAvailable.ok(ctx) }
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    // A sheet over the chat, like the picker: the map keeps its own drag, the strip at the top moves
    // the sheet; X, Back and a tap on the dim close it.
    FariinSheetDialog(onDismiss, handle = true) { motion ->
        Box(Modifier.fillMaxSize().background(c.bg)) {
            if (ok) FariinMap(Modifier.fillMaxSize(), c.dark, CameraPosition.fromLatLngZoom(spot, zoom)) { m ->
                map = m
                m.markSpot(spot, approx, density)
            } else MapsMissingNote(Modifier.align(Alignment.Center))
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", { motion.close() },
                Modifier.padding(start = 12.dp, top = SheetHandleStrip), circle = true)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (ok) FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_location_fill), "Show the place", {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(spot, zoom), 400, null)
                }, Modifier.align(Alignment.End).padding(bottom = 10.dp), circle = true)
                Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                    .background(c.card).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c.red), contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_location), null, tint = Color.White, modifier = Modifier.size(20.dp))
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
                        PageButton("Directions", ImageVector.vectorResource(R.drawable.ic_sf_directions), filled = true, Modifier.weight(1f)) { openDirections(ctx, loc) }
                        PageButton("Copy", ImageVector.vectorResource(R.drawable.ic_sf_doc_on_doc), filled = false, Modifier.weight(1f)) { copyCoordinates(ctx, loc) }
                    }
                }
            }
        }
    }
}

/** Shown where a map would be when this phone has no (or too old) Play Services. */
@Composable
internal fun MapsMissingNote(modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Text("Maps aren't available on this phone. Update Google Play services to see the map.",
        fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = modifier.padding(horizontal = 32.dp))
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
