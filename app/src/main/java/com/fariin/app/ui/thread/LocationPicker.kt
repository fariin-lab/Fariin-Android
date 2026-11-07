package com.fariin.app.ui.thread

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fariin.app.data.CardRules
import com.fariin.app.data.LocationShare
import com.fariin.app.data.LocationShare.Source
import com.fariin.app.data.PhotonPlaces
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.SheetHandleStrip
import com.fariin.app.ui.components.FloatingSearchField
import com.fariin.app.ui.components.FloatingSegments
import com.fariin.app.ui.components.PermissionDenied
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import java.net.UnknownHostException
import kotlin.coroutines.resume

/**
 * Share Location (iOS LocationPickerSheet, "Select Location"): the map fills the top with a fixed
 * pin in its centre; dragging the map moves the spot under the pin, and that spot is what is sent.
 * Under the map: what is there (the phone's Geocoder, debounced), how sure it is ("Within 27 m",
 * updating as the fix improves), Exact or Approximate, and Send Location.
 *
 * The permission is asked here and only here, at the moment of use; after a "no" the map still
 * works (drag to a spot) and a line offers Settings. "Places In This Area" and the search ask
 * Photon (PhotonPlaces); when it fails the picker still sends the pin.
 */
@Composable
internal fun LocationPicker(onDismiss: () -> Unit, onSend: (lat: Double, lon: Double, label: String?) -> Unit) {
    // A sheet over the chat, as iOS (.sheet with its grabber): the map keeps every drag of its own,
    // so the sheet moves by the strip at its top; X, Back and a tap on the dim close it too.
    FariinSheetDialog(onDismiss, handle = true) { motion -> PickerPage({ motion.close() }, onSend) }
}

/** What the list under the header is showing: search answers or the places around the pin. */
private sealed interface Ask {
    data class Search(val q: String, val lat: Double?, val lon: Double?, val n: Int) : Ask
    data class Nearby(val lat: Double, val lon: Double, val n: Int) : Ask
}

@SuppressLint("MissingPermission")   // the map's blue dot is switched on only once access is held
@Composable
private fun PickerPage(onDismiss: () -> Unit, onSend: (lat: Double, lon: Double, label: String?) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var access by remember { mutableStateOf(LocAccess.of(ctx)) }
    var asked by remember { mutableStateOf(false) }
    var servicesOff by remember { mutableStateOf(false) }
    var tries by remember { mutableIntStateOf(0) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var source by remember { mutableStateOf(Source.MY_FIX) }
    var picked by remember { mutableStateOf<PhotonPlaces.Place?>(null) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val fixSource = remember { FixSource() }
    val area1km = remember { arrayOfNulls<Circle>(1) }
    // No Play Services (or too old): no map, but sharing where I am still works.
    val mapsOk = remember { MapsAvailable.ok(ctx) }
    var exact by remember { mutableStateOf(true) }
    // What the spot under the pin is called.
    var name by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    var area by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    // Search and nearby.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var results by remember { mutableStateOf<List<PhotonPlaces.Place>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var canRetry by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    val coarseOnly = access == LocAccess.APPROXIMATE
    val locked = LocationShare.mustBeApproximate(source, coarseOnly)
    val sendExact = exact && !locked
    val spot: LatLng? = when (source) {
        Source.MY_FIX -> fix?.let { LatLng(it.latitude, it.longitude) }
        Source.MAP -> center
        Source.PLACE -> picked?.let { LatLng(it.lat, it.lon) }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = LocAccess.of(ctx)
        if (access == LocAccess.NONE) PermissionDenied.explain(ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            "Fariin needs location access to show where you are. You can still drag the map to a spot.")
    }
    fun requestAccess() = askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    LaunchedEffect(Unit) { if (access == LocAccess.NONE) requestAccess() }

    // Back from Settings (permission or location switched on): look again.
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = LocAccess.of(ctx)
                if (now != access) access = now
                if (servicesOff) tries++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // The fix, kept up to date while the page is open, so the accuracy line improves as GPS locks on.
    DisposableEffect(access, tries) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (access == LocAccess.NONE) return@DisposableEffect onDispose {}
        servicesOff = Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val old = fix
                val age = old?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                if (LocationShare.takeNewFix(old?.takeIf { it.hasAccuracy() }?.accuracy, age, l.takeIf { it.hasAccuracy() }?.accuracy)) fix = l
            }
            @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        LocFix.lastKnown(lm, access == LocAccess.PRECISE)?.let { listener.onLocationChanged(it) }
        for (p in LocFix.providers(lm, access == LocAccess.PRECISE)) {
            runCatching { lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper()) }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }

    // Follow my fix while the pin is mine: the first one jumps there, later ones glide.
    LaunchedEffect(fix, source, map) {
        val m = map ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        fixSource.push(f)
        if (source != Source.MY_FIX) return@LaunchedEffect
        val target = LatLng(f.latitude, f.longitude)
        val cur = m.cameraPosition.target
        val zoom = if (coarseOnly) 14f else 17f
        when {
            m.cameraPosition.zoom < 5 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            LocationShare.distanceM(cur.latitude, cur.longitude, target.latitude, target.longitude) > 5 ->
                m.animateCamera(CameraUpdateFactory.newLatLng(target), 350, null)
        }
    }

    // The blue dot and its accuracy ring, drawn by the map from our own fixes (no second reader).
    LaunchedEffect(map, access) {
        val m = map ?: return@LaunchedEffect
        if (access == LocAccess.NONE) return@LaunchedEffect
        runCatching {
            m.setLocationSource(fixSource)
            m.isMyLocationEnabled = true
        }
    }

    // Approximate: the 1 km square that will travel, as a soft circle around the rounded spot.
    val areaKey = if (sendExact) null else spot?.let { LocationShare.roundApprox(it.latitude) to LocationShare.roundApprox(it.longitude) }
    LaunchedEffect(map, areaKey) {
        area1km[0]?.remove(); area1km[0] = null
        val m = map ?: return@LaunchedEffect
        val (lat, lon) = areaKey ?: return@LaunchedEffect
        area1km[0] = runCatching {
            m.addCircle(CircleOptions().center(LatLng(lat, lon)).radius(LocationShare.APPROX_RADIUS_M)
                .fillColor(0x332F7CF6).strokeColor(0xAA2F7CF6.toInt()).strokeWidth(1.5f * ctx.resources.displayMetrics.density))
        }.getOrNull()
    }

    // What is at the spot: once the map has settled (450 ms, as iOS), three tries with a growing
    // wait, then Photon; the old name is dropped at once so a new spot never carries an old name.
    val lookupKey = spot?.let { String.format(java.util.Locale.US, "%.4f,%.4f", it.latitude, it.longitude) }
    LaunchedEffect(lookupKey, source) {
        name = null; address = null
        if (source == Source.PLACE) {
            picked?.let { name = it.name; address = it.address.ifEmpty { null }; area = it.area }
            resolving = false
            return@LaunchedEffect
        }
        val s = spot ?: return@LaunchedEffect
        resolving = true
        delay(450)
        var done = false
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(attempt * 1_200L)
            val a = LocFix.reverse(ctx, s.latitude, s.longitude) ?: continue
            val n = CardRules.placeName(a.featureName, a.thoroughfare, a.subLocality, a.locality, a.subAdminArea, a.adminArea, a.countryName)
                ?: continue
            name = n
            address = (a.getAddressLine(0) ?: CardRules.addressLine(a.thoroughfare, a.locality, a.countryName)).takeIf { it.isNotBlank() && it != n }
            area = LocationShare.areaName(a.locality, a.subAdminArea, a.adminArea, a.countryName)
            done = true
            break
        }
        if (!done) runCatching {
            PhotonPlaces.parse(PhotonPlaces.fetch(PhotonPlaces.reverseUrl(s.latitude, s.longitude))).firstOrNull()
        }.getOrNull()?.let { p -> name = p.name; address = p.address.ifEmpty { null }; area = p.area }
        resolving = false
    }

    // Typing searches by itself after a short pause; each new word cancels the older request.
    LaunchedEffect(query, searching) {
        if (!searching) return@LaunchedEffect
        val q = query.trim()
        if (q.length < 2) { ask = null; results = emptyList(); note = null; return@LaunchedEffect }
        delay(400)
        val bias = spot ?: map?.cameraPosition?.target
        ask = Ask.Search(q, bias?.latitude, bias?.longitude, 0)
    }
    LaunchedEffect(ask) {
        val a = ask ?: run { loading = false; return@LaunchedEffect }
        loading = true; note = null; canRetry = false
        try {
            val url = when (a) {
                is Ask.Search -> PhotonPlaces.searchUrl(a.q, a.lat, a.lon)
                is Ask.Nearby -> PhotonPlaces.nearbyUrl(a.lat, a.lon)
            }
            val list = PhotonPlaces.parse(PhotonPlaces.fetch(url), namedOnly = a is Ask.Nearby)
            results = list
            if (list.isEmpty()) note = if (a is Ask.Search) "No results" else "No places found here"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            canRetry = true
            note = if (e is UnknownHostException) "No internet connection. Check your connection and try again."
            else "Place search isn't answering right now. You can still send the pin."
        } finally { loading = false }
    }
    fun retry() {
        ask = when (val a = ask) {
            is Ask.Search -> a.copy(n = a.n + 1)
            is Ask.Nearby -> a.copy(n = a.n + 1)
            null -> null
        }
    }
    fun closeList() { ask = null; results = emptyList(); note = null; canRetry = false }
    fun pick(p: PhotonPlaces.Place) {
        picked = p
        source = Source.PLACE
        searching = false; query = ""
        closeList()
        focus.clearFocus()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 17f), 450, null)
    }
    fun locateMe() {
        when {
            access == LocAccess.NONE && asked && PermissionDenied.isBlocked(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ->
                PermissionDenied.openAppSettings(ctx)
            access == LocAccess.NONE -> requestAccess()
            servicesOff -> ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
                source = Source.MY_FIX; picked = null
                fix?.let { f -> map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.latitude, f.longitude),
                    if (coarseOnly) 14f else 17f), 400, null) }
                tries++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        if (!mapsOk) Box(Modifier.weight(1f).fillMaxWidth()) {
            // No map on this phone: my own fix only, sent from the panel below.
            FloatingIcon(Icons.Filled.Close, "Close", onDismiss,
                Modifier.padding(start = 16.dp, top = SheetHandleStrip), circle = true)
            MapsMissingNote(Modifier.align(Alignment.Center))
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            val start = remember {
                val last = LocFix.lastKnownAny(ctx)
                CameraPosition.fromLatLngZoom(last ?: LatLng(20.0, 20.0), if (last != null) 16f else 1.5f)
            }
            FariinMap(Modifier.fillMaxSize(), c.dark, start) { m ->
                map = m
                m.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                        dragging = true
                        source = Source.MAP
                        picked = null
                        // The spot is wherever the map comes to rest, not where it was.
                        center = null
                    }
                }
                m.setOnCameraIdleListener {
                    dragging = false
                    center = m.cameraPosition.target
                }
            }
            CentrePin(dragging)
            // Below the sheet's grabber strip, which drags the sheet.
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = SheetHandleStrip, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingIcon(Icons.Filled.Close, "Close", onDismiss, circle = true)
                    Text("Location", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                        style = TextStyle(shadow = Shadow(c.bg, blurRadius = 8f)),
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    // Searching: "Cancel" in words. A second X beside the page's X and the field's clear X
                    // left three crosses on screen (Pixel, 2026-11-06).
                    if (searching) Box(Modifier.height(42.dp).floating(CircleShape)
                        .clickable { searching = false; query = ""; closeList(); focus.clearFocus() }
                        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    }
                    else FloatingIcon(Icons.Outlined.Search, "Search", { searching = true }, circle = true)
                }
                Spacer(Modifier.height(10.dp))
                if (searching) {
                    LaunchedEffect(Unit) { delay(150); runCatching { searchFocus.requestFocus() } }
                    FloatingSearchField(query, { query = it }, "Search by name or address",
                        Modifier.fillMaxWidth().shadow(10.dp, CircleShape).focusRequester(searchFocus), fill = c.card,
                        onSearch = { val q = query.trim(); if (q.isNotEmpty()) { val b = spot; ask = Ask.Search(q, b?.latitude, b?.longitude, 0) } })
                } else {
                    Box(Modifier.align(Alignment.CenterHorizontally).height(42.dp).floating(CircleShape)
                        .clickable {
                            val at = spot ?: map?.cameraPosition?.target
                            if (at != null) ask = Ask.Nearby(at.latitude, at.longitude, ((ask as? Ask.Nearby)?.n ?: 0) + 1)
                        }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text("Places In This Area", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    }
                }
                ResultsList(loading, results, note, canRetry, ::retry, ::pick)
            }
            FloatingIcon(Icons.Filled.MyLocation, "My location", ::locateMe,
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                tint = if (source == Source.MY_FIX && fix != null) c.blue else c.label, circle = true)
        }
        BottomPanel(
            access = access, asked = asked, servicesOff = servicesOff, coarseOnly = coarseOnly, locked = locked,
            source = source, fix = fix, spot = spot, name = name, address = address, area = area,
            resolving = resolving, sendExact = sendExact,
            sendWord = if (mapsOk) "Send Location" else "Send My Location",
            onExact = { exact = it },
            onPrecise = { requestAccess() },
            onSettings = {
                if (access == LocAccess.NONE) PermissionDenied.openAppSettings(ctx)
                else ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onSend = {
                val s = spot ?: return@BottomPanel
                val out = LocationShare.outgoing(s.latitude, s.longitude, sendExact, name, area)
                onSend(out.lat, out.lon, out.label)
                onDismiss()
            },
        )
    }
}

/**
 * The pin from the iOS picker: a blue disc holding a white pin, its point a small blue dot exactly
 * on the map's centre. While the map is dragged it lifts a little, and drops back when it settles.
 */
@Composable
private fun CentrePin(dragging: Boolean) {
    val c = Fariin.colors
    val lift by animateDpAsState(if (dragging) (-10).dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "lift")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The dot stays on the spot; the disc above it rises.
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.blue))
        Box(Modifier.offset(y = (-34).dp + lift).size(58.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.blue),
            contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_location), null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ResultsList(loading: Boolean, results: List<PhotonPlaces.Place>, note: String?, canRetry: Boolean,
                        onRetry: () -> Unit, onPick: (PhotonPlaces.Place) -> Unit) {
    val c = Fariin.colors
    val box = Modifier.padding(top = 10.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(c.card)
    when {
        loading -> Box(box.padding(16.dp), contentAlignment = Alignment.Center) {
            com.fariin.app.ui.components.LoadingLine("Searching…", fontSize = 15.sp)
        }
        results.isNotEmpty() -> LazyColumn(box.heightIn(max = 300.dp)) {
            items(results.take(15)) { p ->
                Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_location), null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.address.isNotEmpty()) Text(p.address, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        note != null -> Row(box.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(note, fontSize = 15.sp, color = c.secondary, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (canRetry) TextButton(onClick = onRetry) { Text("Retry", color = c.accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BottomPanel(
    access: LocAccess, asked: Boolean, servicesOff: Boolean, coarseOnly: Boolean, locked: Boolean,
    source: Source, fix: Location?, spot: LatLng?, name: String?, address: String?, area: String?,
    resolving: Boolean, sendExact: Boolean, sendWord: String,
    onExact: (Boolean) -> Unit, onPrecise: () -> Unit, onSettings: () -> Unit, onSend: () -> Unit,
) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().background(c.bgSecondary).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // A "no" or location switched off: say so, offer the way back; the map still works.
        val blocked = when {
            access == LocAccess.NONE && asked -> "Allow location access in Settings to share where you are. You can still drag the map." to "Open Settings"
            servicesOff -> "Turn on location to share where you are." to "Location Settings"
            else -> null
        }
        if (blocked != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(blocked.first, fontSize = 13.sp, color = c.secondary, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text(blocked.second, color = c.accent) }
        }
        // The spot under the pin.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(14.dp)) {
            val title = when {
                spot == null && source == Source.MY_FIX -> if (access == LocAccess.NONE) "Drag the map to a spot" else "Finding your location…"
                spot == null -> "Finding place…"
                !sendExact -> LocationShare.areaLabel(area)
                name != null -> name
                resolving -> "Finding place…"
                spot != null -> CardRules.coords(spot.latitude, spot.longitude)
                else -> "Location"
            }
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sendExact && address != null) Text(address, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val acc = fix?.takeIf { it.hasAccuracy() }?.accuracy
            val good = source == Source.MY_FIX && !coarseOnly && acc != null && acc <= 50f
            // "Within 6 m" under a 1 km area reads as a contradiction: only Exact shows how sure the fix is.
            // With location off there is no fix coming: no "Finding your location…" under "Drag the map".
            val noFixComing = access == LocAccess.NONE && fix == null && source == Source.MY_FIX
            if ((sendExact || locked) && !noFixComing) Text(LocationShare.pinNote(source, acc, coarseOnly, fix != null), fontSize = 13.sp,
                color = if (good && sendExact) c.blue else c.secondary, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            FloatingSegments(listOf("Exact", "Approximate"), if (sendExact) 0 else 1, { i -> if (!locked) onExact(i == 0) })
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LocationShare.privacyLine(sendExact, locked), fontSize = 13.sp, color = c.secondary, modifier = Modifier.weight(1f))
                if (locked) TextButton(onClick = onPrecise) { Text("Use precise location", color = c.accent) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val enabled = spot != null
        Column(
            Modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(if (enabled) c.accent else c.accent.copy(alpha = 0.5f))
                .clickable(enabled = enabled, onClick = onSend).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(sendWord, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            val sub = when {
                // Location off and no spot yet: nothing is being found, the person must drag.
                spot == null && access == LocAccess.NONE -> "Choose a spot on the map"
                spot == null -> "Finding place…"
                !sendExact -> LocationShare.areaLabel(area)
                name != null -> name
                resolving -> "Finding place…"
                else -> CardRules.coords(spot.latitude, spot.longitude)
            }
            Text(sub, fontSize = 13.sp, color = c.onAccent.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class LocAccess {
    NONE, APPROXIMATE, PRECISE;
    companion object {
        fun of(ctx: Context): LocAccess = when {
            granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> PRECISE
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) -> APPROXIMATE
            else -> NONE
        }
        private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }
}

/** The phone's own location services and Geocoder (no extra library, no key). */
private object LocFix {
    private const val FRESH_MS = 2 * 60_000L

    fun providers(lm: LocationManager, precise: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        if (precise) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /** A fix the phone already holds, if it is under two minutes old (age on the boot clock). */
    @SuppressLint("MissingPermission")
    fun lastKnown(lm: LocationManager, precise: Boolean): Location? =
        providers(lm, precise).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.takeIf { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 < FRESH_MS }

    /** Where to open the map: any last fix, however old (only the first frame; the live fix follows). */
    @SuppressLint("MissingPermission")
    fun lastKnownAny(ctx: Context): LatLng? {
        if (LocAccess.of(ctx) == LocAccess.NONE) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }?.let { LatLng(it.latitude, it.longitude) }
    }

    /** The address at a spot, or null (no answer, offline, no Geocoder on this phone). */
    @Suppress("DEPRECATION")
    suspend fun reverse(ctx: Context, lat: Double, lon: Double): Address? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(10_000L) {
            val g = Geocoder(ctx)
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { cont ->
                g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(list: MutableList<Address>) { if (cont.isActive) cont.resume(list.firstOrNull()) }
                    override fun onError(msg: String?) { if (cont.isActive) cont.resume(null) }
                })
            } else withContext(Dispatchers.IO) { runCatching { g.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull() }
        }
    }
}
