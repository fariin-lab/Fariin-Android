package com.fariin.app.ui.thread

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.data.CardRules
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.components.floating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The Location page (iOS LocationPickerSheet, without the map: a map needs a keyed service). The
 * permission is asked here and only here, when the person chose Location; Android's own dialog
 * offers precise or approximate. "Send My Current Location" names the spot it found (a reverse
 * lookup, three tries) or shows its coordinates; an approximate fix is sent as "Approximate
 * location", never under a street it only landed near (iOS fix-all #210). The search field asks
 * the phone's Geocoder (no key) and a result is sent under its name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationSheet(onDismiss: () -> Unit, onSend: (lat: Double, lon: Double, label: String?) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var access by remember { mutableStateOf(LocAccess.of(ctx)) }
    var asked by remember { mutableStateOf(false) }
    var tries by remember { mutableIntStateOf(0) }
    var fix by remember { mutableStateOf<android.location.Location?>(null) }
    var locating by remember { mutableStateOf(false) }
    var servicesOff by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Address>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchSeq by remember { mutableIntStateOf(0) }
    val canSearch = remember { Geocoder.isPresent() }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = LocAccess.of(ctx)
    }
    LaunchedEffect(Unit) {
        if (access == LocAccess.NONE) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // A fix once access is there (and again after "Use precise location" upgrades it).
    LaunchedEffect(access, tries) {
        if (access == LocAccess.NONE) return@LaunchedEffect
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        servicesOff = Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled
        if (servicesOff) return@LaunchedEffect
        locating = true
        val got = LocFix.current(ctx, access == LocAccess.PRECISE)
        locating = false
        fix = got
        if (got != null && access == LocAccess.PRECISE) {
            resolving = true
            placeName = null
            // LocationPickerSheet.resolveName: up to three tries with a growing wait.
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(attempt * 1_200L)
                val a = LocFix.reverse(ctx, got.latitude, got.longitude)
                val n = a?.let { CardRules.placeName(it.featureName, it.thoroughfare, it.subLocality, it.locality,
                    it.subAdminArea, it.adminArea, it.countryName) }
                if (n != null) { placeName = n; break }
            }
            resolving = false
        }
    }
    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        val seq = ++searchSeq
        searchNote = null
        searching = true
        scope.launch {
            val found = LocFix.search(ctx, q)
            if (seq != searchSeq) return@launch   // a newer search owns the list now
            searching = false
            results = found ?: emptyList()
            searchNote = when {
                found == null -> "No internet connection. Check your connection and try again."
                found.isEmpty() -> "No results"
                else -> null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            Text("Location", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            when {
                access == LocAccess.NONE && asked -> Blocked(
                    "Allow location access in Settings to share where you are.", "Open Settings",
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }
                servicesOff -> Blocked("Turn on location to share where you are.", "Location Settings") {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }
                access != LocAccess.NONE -> {
                    val approximate = access == LocAccess.APPROXIMATE
                    val f = fix
                    val subtitle = when {
                        f == null && locating -> "Finding your location…"
                        f == null -> "Couldn't find your location. Try again."
                        approximate -> "Approximate location"
                        placeName != null -> placeName!!
                        resolving -> "Finding place…"
                        else -> CardRules.coords(f.latitude, f.longitude)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
                            .background(c.card)
                            .clickable(enabled = f != null || !locating) {
                                if (f == null) { access = LocAccess.of(ctx); tries++; return@clickable }
                                onSend(f.latitude, f.longitude, if (approximate) "Approximate location" else placeName)
                                onDismiss()
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                            if (locating) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            else Icon(Icons.Filled.MyLocation, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Send My Current Location", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                            Text(subtitle, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (approximate) {
                        TextButton(onClick = {
                            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, modifier = Modifier.padding(start = 8.dp)) { Text("Use precise location", color = c.accent) }
                    }
                }
                else -> Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
            if (canSearch) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.fieldFill)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    if (query.isEmpty()) Text("Search by name or address", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(query, { query = it; searchSeq++; searchNote = null; searching = false },
                        singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                when {
                    searching -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                    searchNote != null -> Text(searchNote!!, fontSize = 15.sp, color = c.secondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    else -> if (results.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                        items(results.take(6)) { a ->
                            val name = CardRules.placeName(a.featureName, a.thoroughfare, a.subLocality, a.locality,
                                a.subAdminArea, a.adminArea, a.countryName) ?: query.trim()
                            val line = a.getAddressLine(0) ?: CardRules.addressLine(a.thoroughfare, a.locality, a.countryName)
                            Row(
                                Modifier.fillMaxWidth().clickable { onSend(a.latitude, a.longitude, name); onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(c.red), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Place, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (line.isNotEmpty() && line != name) Text(line, fontSize = 13.sp, color = c.secondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Blocked(text: String, button: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        TextButton(onClick = onClick) { Text(button, color = c.accent) }
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

/** One fix and the Geocoder, on the phone's own location services (no extra library, no key). */
private object LocFix {
    private const val FRESH_MS = 2 * 60_000L
    private const val WAIT_MS = 12_000L

    @Suppress("MissingPermission")   // only called once LocAccess says a permission is held
    suspend fun current(ctx: Context, precise: Boolean): android.location.Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // Age on the boot clock: a fix's wall time is whatever the provider said (an emulator's can be hours off).
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.elapsedRealtimeNanos }
        if (last != null && (android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000 < FRESH_MS) return last
        for (p in providers) {
            val got = withTimeoutOrNull(WAIT_MS) { one(lm, p) }
            if (got != null) return got
        }
        return last
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun one(lm: LocationManager, provider: String): android.location.Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val cancel = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, java.util.concurrent.Executors.newSingleThreadExecutor()) { l ->
                        if (cont.isActive) cont.resume(l)
                    }
                } else {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) { if (cont.isActive) cont.resume(l) }
                        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) { if (cont.isActive) cont.resume(null) }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                }
            } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
        }

    /** The address at a spot, or null (no answer, offline, no Geocoder). */
    @Suppress("DEPRECATION")
    suspend fun reverse(ctx: Context, lat: Double, lon: Double): Address? = withTimeoutOrNull(10_000L) {
        val g = Geocoder(ctx)
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { cont ->
            g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(list: MutableList<Address>) { if (cont.isActive) cont.resume(list.firstOrNull()) }
                override fun onError(msg: String?) { if (cont.isActive) cont.resume(null) }
            })
        } else withContext(Dispatchers.IO) { runCatching { g.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull() }
    }

    /** Places for a name or address; null when the lookup itself failed (usually no connection). */
    @Suppress("DEPRECATION")
    suspend fun search(ctx: Context, q: String): List<Address>? = withTimeoutOrNull(15_000L) {
        val g = Geocoder(ctx)
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<List<Address>?> { cont ->
            g.getFromLocationName(q, 6, object : Geocoder.GeocodeListener {
                override fun onGeocode(list: MutableList<Address>) { if (cont.isActive) cont.resume(list.filter { it.hasLatitude() && it.hasLongitude() }) }
                override fun onError(msg: String?) { if (cont.isActive) cont.resume(null) }
            })
        } else withContext(Dispatchers.IO) {
            runCatching { g.getFromLocationName(q, 6)?.filter { it.hasLatitude() && it.hasLongitude() } ?: emptyList() }.getOrNull()
        }
    }
}

/**
 * Share a contact: one of the people I have a 1:1 chat with (iOS sends their card from the
 * profile's Share with page; the attach sheet's own Contact tile was retired there with phone
 * numbers). Tap one, then Send. Their own profile name travels, never a name I saved for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactPickSheet(me: String, cid: String, onDismiss: () -> Unit, onSend: (CardRules.Contact) -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    val people = remember { CryptoHub.listedAll().filter { pickable(it, me, cid) }.sortedBy { it.displayName(me).lowercase() } }
    val shown = people.filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text("Share Contact", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) Text("Search", fontSize = 17.sp, color = c.secondary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (people.isEmpty()) {
                Text("People you chat with show up here.", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                items(shown, key = { it.id }) { conv ->
                    val on = conv.id == chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (on) null else conv.id }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(conv.displayPhoto(me), 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(conv.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) c.blue else Color.Transparent)
                                .border(1.5.dp, if (on) c.blue else c.secondary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            val pick = people.firstOrNull { it.id == chosen }
            Box(
                Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
                    .floating(CircleShape, fill = if (pick == null) c.secondary.copy(alpha = 0.3f) else c.blue)
                    .clickable(enabled = pick != null) { pick?.let { onSend(cardFor(it, me)) }; onDismiss() },
                contentAlignment = Alignment.Center,
            ) { Text("Send", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A person I have a 1:1 with: not a group or channel, not a deleted account, not this chat's own person. */
private fun pickable(conv: Conversation, me: String, cid: String): Boolean {
    if (conv.isGroup || conv.isChannel || conv.id == cid) return false
    val other = conv.otherUid(me)
    return other.isNotEmpty() && other != me && !conv.isDeletedAccount(other)
}

private fun cardFor(conv: Conversation, me: String): CardRules.Contact {
    val other = conv.otherUid(me)
    return CardRules.Contact(other, conv.names[other]?.takeIf { it.isNotBlank() } ?: conv.name(me), conv.photoUrl(me))
}
