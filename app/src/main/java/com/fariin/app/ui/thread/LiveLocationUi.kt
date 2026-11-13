package com.fariin.app.ui.thread

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import androidx.compose.animation.core.animateFloat
import coil3.toBitmap
import com.fariin.app.data.CardRules
import com.fariin.app.data.LiveLocation
import com.fariin.app.location.LiveShareService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.components.GlassAlertAction
import com.fariin.app.ui.components.SheetHandleStrip
import androidx.compose.ui.graphics.graphicsLayer
import com.fariin.app.ui.theme.Fariin
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everyone's spots in one chat, listened to while a card or the map is on screen. */
@Composable
internal fun rememberLiveSpots(cid: String): List<LiveLocation.Spot> {
    val ctx = LocalContext.current
    var spots by remember(cid) { mutableStateOf<List<LiveLocation.Spot>>(emptyList()) }
    DisposableEffect(cid) {
        val reg = runCatching { LiveLocation.listen(ctx, cid) { spots = it } }.getOrNull()
        onDispose { reg?.remove() }
    }
    return spots
}

/** A clock that ticks every 20 s, so "Live until" turns into "ended" and "Updated" ages without data. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    return now
}

private fun clockWords(ctx: Context, ms: Long): String =
    android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))

/**
 * The live card in the chat (plan 2026-11-13): the map with the sharer's round photo on their spot,
 * "Live until 5:50 AM" with the live mark, and the button: mine = Stop Sharing, theirs = View Live
 * Location. Ended (time up or stopped): "Live location ended", the map faded, no button.
 */
@Composable
internal fun LiveBody(live: CardRules.Live, authorId: String, photo: String?, isMe: Boolean, ui: ThreadUi, width: Dp,
                      onSend: (String) -> Unit, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val now = rememberNow()
    val spots = rememberLiveSpots(ui.cid)
    // One doc per sharer per chat, so it is this card's only while it is this share: matched by the
    // sender and the end time (iOS gives the doc its own id and sets until to "now" on Stop).
    val doc = spots.firstOrNull { it.uid == authorId }
    val same = doc != null && kotlin.math.abs(doc.untilMs - live.untilMs) < 2_000
    val running = live.untilMs > now && when {
        doc == null -> true                          // not loaded yet, or never written
        doc.ended -> false                           // stopped (here, or on the iPhone)
        same -> true
        else -> doc.untilMs < live.untilMs           // a newer share has taken the doc: this one is over
    }
    val spot = doc?.takeIf { running && !it.lat.isNaN() }?.let { CardRules.Location(it.lat, it.lon, null) }
        ?: CardRules.Location(live.lat, live.lon, null)
    val ink = if (isMe) Color.White else c.label
    val sub = if (isMe) Color.White.copy(alpha = 0.8f) else c.secondary
    var open by remember { mutableStateOf(false) }
    var askStop by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = running) { open = true }) {
            // Ended: the same full map, no fade (owner 2026-11-13: the faded soft blue looked wrong).
            LocationCardMap(spot, false, width, 150.dp, pin = false)
            // Our own mark: the photo in a white ring, a soft green ring breathing out of it while live.
            if (running) LivePulse(Modifier.align(Alignment.Center), 52.dp)
            Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.White).padding(3.dp)) {
                Avatar(photo, 46.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Live · 52 min left" while it runs; "Live · ended 5:31 AM" after (our words). No status
            // dot before it (owner 2026-11-13: the green/red dot looked cheap).
            Text(if (running) "Live · ${leftWords(live.untilMs - now)}" else "Live · ended ${clockWords(ctx, minOf(live.untilMs, doc?.updatedMs?.takeIf { doc.ended && it > 0 } ?: live.untilMs))}",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (running) ink else sub, modifier = Modifier.weight(1f), maxLines = 1)
            meta()
        }
        live.note?.let { Text(it, fontSize = 15.sp, color = ink, modifier = Modifier.padding(top = 4.dp)) }
        if (running) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).height(40.dp).clip(CircleShape)
                .background(if (isMe) Color.White.copy(alpha = 0.22f) else c.label.copy(alpha = 0.08f))
                .clickable { if (isMe) askStop = true else open = true }, contentAlignment = Alignment.Center) {
                Text(if (isMe) "Stop" else "Open Live Map", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isMe) Color.White else c.accent)
            }
        }
    }
    if (askStop) {
        // Sharing in more than one chat: this one, or everywhere (plan 2026-11-13).
        val many = LiveShareService.shares.value.size > 1
        GlassAlert(onDismissRequest = { askStop = false }, title = "Stop sharing live location?",
            actions = buildList {
                if (many) {
                    add(GlassAlertAction("Stop in This Chat", com.fariin.app.ui.components.GlassAlertStyle.Red) { LiveShareService.stop(ctx, ui.cid, live.untilMs) })
                    add(GlassAlertAction("Stop Everywhere", com.fariin.app.ui.components.GlassAlertStyle.Red) { LiveShareService.stopAll(ctx) })
                } else add(GlassAlertAction("Stop", com.fariin.app.ui.components.GlassAlertStyle.Red) { LiveShareService.stop(ctx, ui.cid, live.untilMs) })
                add(GlassAlertAction("Cancel") {})
            })
    }
    if (open) LiveMapPage(ui, onSend) { open = false }
}

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

/**
 * The live map (plan 2026-11-13): everyone sharing in this chat as their round photo, moving; a row
 * per person with "Updated just now"; my own Share / Stop; Map or Satellite in ⋯.
 */
@Composable
internal fun LiveMapPage(ui: ThreadUi, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val now = rememberNow()
    val spots = rememberLiveSpots(ui.cid).filter { it.live(now) && !it.lat.isNaN() }
    val mine by LiveShareService.shares.collectAsState()
    val iShare = ui.cid in mine
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val markers = remember { HashMap<String, Marker>() }
    var fitted by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun name(uid: String) = if (uid == ui.me) "You" else ui.names[uid] ?: ui.title
    // Markers follow the spots: moved, added, or taken away.
    LaunchedEffect(map, spots) {
        val m = map ?: return@LaunchedEffect
        val seen = spots.map { it.uid }.toSet()
        markers.keys.filter { it !in seen }.forEach { markers.remove(it)?.remove() }
        for (s in spots) {
            val at = LatLng(s.lat, s.lon)
            val have = markers[s.uid]
            if (have != null) have.position = at
            else {
                val art = LiveMarkerArt.load(ctx, ui.photos[s.uid], density)
                m.addMarker(MarkerOptions().position(at).anchor(0.5f, 0.5f).title(name(s.uid))
                    .icon(BitmapDescriptorFactory.fromBitmap(art)))?.let { markers[s.uid] = it }
            }
        }
        if (!fitted && spots.isNotEmpty()) {
            fitted = true
            if (spots.size == 1) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(spots[0].lat, spots[0].lon), 16f))
            else {
                val b = LatLngBounds.builder(); spots.forEach { b.include(LatLng(it.lat, it.lon)) }
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (80 * density).toInt())) }
            }
        }
    }
    FariinSheetDialog(onDismiss, handle = true) { motion ->
        Box(Modifier.fillMaxSize().background(c.bg)) {
            val first = spots.firstOrNull()
            val start = CameraPosition.fromLatLngZoom(if (first != null) LatLng(first.lat, first.lon) else LatLng(0.0, 0.0), if (first != null) 16f else 1f)
            FariinMap(Modifier.fillMaxSize(), c.dark, start) { m -> map = m }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = SheetHandleStrip), verticalAlignment = Alignment.CenterVertically) {
                FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", { motion.close() }, circle = true)
                Spacer(Modifier.weight(1f))
                Box {
                    FloatingIcon(ImageVector.vectorResource(R.drawable.ic_more_ios), "Map type", { menu = true }, circle = true)
                    com.fariin.app.ui.components.GlassDropdown(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
                        DropdownMenuItem(text = { Text("Map", color = c.label) }, onClick = { menu = false; map?.mapType = GoogleMap.MAP_TYPE_NORMAL })
                        DropdownMenuItem(text = { Text("Satellite", color = c.label) }, onClick = { menu = false; map?.mapType = GoogleMap.MAP_TYPE_HYBRID })
                        DropdownMenuItem(text = { Text("Terrain", color = c.label) }, onClick = { menu = false; map?.mapType = GoogleMap.MAP_TYPE_TERRAIN })
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                    .background(c.card).padding(vertical = 6.dp)) {
                    // My own share first: start it here, or stop it.
                    Row(Modifier.fillMaxWidth().clickable {
                        if (iShare) LiveShareService.stop(ctx, ui.cid) else share = true
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_location), null, tint = if (iShare) c.red else c.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(if (iShare) "Stop sharing mine" else "Share mine live too", fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, color = if (iShare) c.red else c.accent)
                    }
                    if (spots.isEmpty()) Text("No one is sharing right now.", fontSize = 14.sp, color = c.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    for (s in spots) Row(Modifier.fillMaxWidth().clickable {
                        scope.launch { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 16f), 400, null) }
                    }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(ui.photos[s.uid], 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name(s.uid), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(LiveLocation.updatedWords(s.updatedMs, now) + " · " + leftWords(s.untilMs - now), fontSize = 13.sp, color = c.secondary)
                        }
                    }
                }
            }
        }
    }
    if (share) LiveSharePage(ui, onDismiss = { share = false }, onSend = onSend)
}

/**
 * Share Live Location: the one-time explanation, then how long (15 minutes / 1 hour / 8 hours), an
 * optional comment, Send. Sending puts the card in the chat and starts [LiveShareService].
 */
@Composable
internal fun LiveSharePage(ui: ThreadUi, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("live_location", Context.MODE_PRIVATE) }
    var explained by remember { mutableStateOf(prefs.getBoolean("explained", false)) }
    var length by remember { mutableLongStateOf(LiveLocation.DEFAULT_LENGTH) }
    var note by remember { mutableStateOf("") }
    val fix = remember { LiveFix.last(ctx) }
    if (!explained) {
        GlassAlert(onDismissRequest = onDismiss, title = "Share your live location?",
            message = "Your spot will keep updating in this chat as you move, for as long as you choose, even when Fariin is closed. Stop it any time.",
            actions = listOf(GlassAlertAction("Cancel") { onDismiss() },
                GlassAlertAction("Continue", com.fariin.app.ui.components.GlassAlertStyle.Bold) {
                    prefs.edit().putBoolean("explained", true).apply(); explained = true }), dismissOnAction = false)
        return
    }
    FariinSheetDialog(onDismiss, handle = true) { motion ->
        Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val at = fix ?: LatLng(0.0, 0.0)
                FariinMap(Modifier.fillMaxSize(), c.dark, CameraPosition.fromLatLngZoom(at, if (fix != null) 16f else 1f))
                if (fix != null) {
                    LivePulse(Modifier.align(Alignment.Center), 56.dp)
                    Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.White).padding(3.dp)) {
                        Avatar(ui.photos[ui.me], 50.dp)
                    }
                }
                FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", { motion.close() },
                    Modifier.padding(start = 12.dp, top = SheetHandleStrip), circle = true)
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Text("Share your location live", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text("For how long?", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(12.dp))
                // Three tiles, each with a little clock ring filled to its length (ours): a quarter, a full
                // hour, a full ring marked 8h. The chosen one in the accent; the end time under them.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveLocation.LENGTHS.forEachIndexed { i, (ms, words) ->
                        val on = ms == length
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) c.accent else c.card)
                            .clickable { length = ms }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val ink = if (on) c.onAccent else c.label
                            androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
                                val st = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawCircle(ink.copy(alpha = 0.22f), style = st)
                                drawArc(ink, -90f, if (i == 0) 90f else 360f, false, style = st)
                                if (i == 2) drawCircle(ink, radius = 3.dp.toPx())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(words.replace("minutes", "min"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                        }
                    }
                }
                Text("Until " + clockWords(ctx, System.currentTimeMillis() + length), fontSize = 13.sp, color = c.secondary,
                    modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(48.dp).clip(CircleShape).background(c.card).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (note.isEmpty()) Text("Add a note", fontSize = 16.sp, color = c.secondary)
                        BasicTextField(note, { note = it.take(200) }, singleLine = true,
                            textStyle = TextStyle(fontSize = 16.sp, color = c.label), cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(if (fix != null) c.accent else c.accent.copy(alpha = 0.4f))
                    .clickable(enabled = fix != null) {
                            val f = fix ?: return@clickable
                            val until = System.currentTimeMillis() + length
                            val text = CardRules.liveText(f.latitude, f.longitude, until, note.trim().ifEmpty { null })
                            onSend(text)
                            LiveShareService.begin(ctx, LiveShareService.Share(ui.cid, LiveLocation.idFor(until), until, ui.title))
                            motion.close()
                        }, contentAlignment = Alignment.Center) {
                    Text("Start Sharing", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                }
                if (fix == null) Text("Finding your location… Turn on location for Fariin to share it.", fontSize = 13.sp,
                    color = c.secondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}


/** The round photo marker on the live map: white ring, the photo (or the plain face) inside. */
internal object LiveMarkerArt {
    private val cache = HashMap<String, android.graphics.Bitmap>()

    /** The photo through the app's image loader (as Avatar draws it), then [art]. */
    suspend fun load(ctx: Context, url: String?, density: Float): android.graphics.Bitmap {
        val key = url ?: ""
        cache[key]?.let { return it }
        val face = url?.takeIf { it.isNotEmpty() }?.let { u ->
            runCatching {
                val r = coil3.SingletonImageLoader.get(ctx).execute(coil3.request.ImageRequest.Builder(ctx).data(u)
                    .size((52 * density).toInt()).build())
                // A software copy: the marker is drawn on a Canvas, which refuses hardware bitmaps (crash 2026-11-13).
                (r as? coil3.request.SuccessResult)?.image?.toBitmap()?.let { b ->
                    if (b.config == android.graphics.Bitmap.Config.HARDWARE) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else b
                }
            }.getOrNull()
        }
        return art(face, density).also { if (face != null) cache[key] = it }
    }

    private fun art(face: android.graphics.Bitmap?, density: Float): android.graphics.Bitmap {
        val size = (52 * density).toInt()
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        val inner = size - (6 * density).toInt()
        val off = (size - inner) / 2f
        if (face != null) {
            val shader = android.graphics.BitmapShader(android.graphics.Bitmap.createScaledBitmap(face, inner, inner, true),
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            val m = android.graphics.Matrix().apply { setTranslate(off, off) }
            shader.setLocalMatrix(m)
            val fp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            cv.drawCircle(size / 2f, size / 2f, inner / 2f, fp)
        } else {
            p.color = 0xFF2F7CF6.toInt()
            cv.drawCircle(size / 2f, size / 2f, inner / 2f, p)
        }
        return out
    }
}

/** The phone's last fix, for the share page's first spot (the service takes over from there). */
internal object LiveFix {
    @android.annotation.SuppressLint("MissingPermission")
    fun last(ctx: Context): LatLng? {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return listOf(android.location.LocationManager.FUSED_PROVIDER, android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { LatLng(it.latitude, it.longitude) }
    }
}


/** The live mark's green (our own), the ring and the dot. */
private val LiveGreen = Color(0xFF34C759)

/** "52 min left", "1 h 20 min left", "under a minute left". */
private fun leftWords(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return when {
        m < 1 -> "under a minute left"
        m < 60 -> "$m min left"
        m % 60 == 0 -> "${m / 60} h left"
        else -> "${m / 60} h ${m % 60} min left"
    }
}


/** The breathing green ring behind a live photo (card, share page): 1.6 s, out to 1.6x, fading. */
@Composable
internal fun LivePulse(modifier: Modifier, size: Dp) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "livePulse")
    val t by pulse.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "liveT")
    Box(modifier.size(size).graphicsLayer { scaleX = 1f + t * 0.6f; scaleY = scaleX; alpha = (1f - t) * 0.5f }
        .clip(CircleShape).background(LiveGreen))
}
