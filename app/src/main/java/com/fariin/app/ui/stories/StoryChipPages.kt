package com.fariin.app.ui.stories

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fariin.app.R
import com.fariin.app.data.PhotonPlaces
import com.fariin.app.data.StoryChips
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The three colours a Location, Link or Time chip can wear (iOS StoryChipStyle): white, black,
 * blue, one tap apart, no picker. The pin and the chain take the accent blue; the clock keeps the ink.
 */
enum class StoryChipStyle(val bg: Int, val ink: Int, val accent: Int) {
    WHITE(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF0A84FF.toInt()),
    BLACK(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF64D2FF.toInt()),
    BLUE(0xFF0A84FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt());

    val next get() = entries[(ordinal + 1) % entries.size]
}

/** What a chip shows at its head (iOS chipGlyph). */
enum class StoryChipSymbol(val icon: Int, val accent: Boolean, val sizePt: Float) {
    PLACE(R.drawable.ic_story_place_pin, true, 20f),
    LINK(R.drawable.ic_sf_link, true, 18f),
    TIME(R.drawable.ic_sf_clock, false, 18f),
}

/**
 * The chip as a picture (iOS stickerChip, baked by ImageRenderer): a capsule, the glyph, the words
 * in 18 pt bold, 18 x 11 pt padding, 7 pt apart. Drawn at [PX_PER_UNIT] pixels per edit unit
 * (edit space is 3 units per point), so it stays sharp when pinched up.
 */
object StoryChipArt {
    const val PX_PER_UNIT = 2f
    private const val PX_PER_PT = 3f * PX_PER_UNIT

    fun render(ctx: Context, symbol: StoryChipSymbol, text: String, style: StoryChipStyle): Bitmap {
        val s = PX_PER_PT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f * s
            color = style.ink
        }
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val glyph = symbol.sizePt * s
        val textW = paint.measureText(text)
        val contentH = maxOf(lineH, glyph)
        val w = (18f * s * 2 + glyph + 7f * s + textW).toInt().coerceAtLeast(1)
        val h = (11f * s * 2 + contentH).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.bg }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        ContextCompat.getDrawable(ctx, symbol.icon)?.mutate()?.let { d ->
            d.setTint(if (symbol.accent) style.accent else style.ink)
            val gx = 18f * s
            val gy = (h - glyph) / 2f
            d.setBounds(gx.toInt(), gy.toInt(), (gx + glyph).toInt(), (gy + glyph).toInt())
            d.draw(c)
        }
        val tx = 18f * s + glyph + 7f * s
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(text, tx, baseline, paint)
        return bmp
    }
}

/** The pushed page's header inside the tray (iOS TrayPushHeader): a round back chevron and the title. */
@Composable
private fun TrayPushHeader(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_back_arrow), contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(title, Modifier.align(Alignment.Center), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** iOS's plain rounded field (tertiarySystemFill in a capsule, no glass), 52 tall. */
@Composable
private fun CapsuleField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
                         keyboard: KeyboardOptions = KeyboardOptions.Default, actions: KeyboardActions = KeyboardActions.Default) {
    Box(modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(Color(0x3D767680)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.4f), fontSize = 17.sp)
        BasicTextField(value, onChange, singleLine = true, keyboardOptions = keyboard, keyboardActions = actions,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth())
    }
}

/** iOS StoryLinkSticker.nameMaxChars: the badge is one line, so the name stops at 48. */
private const val LINK_NAME_MAX = 48

/**
 * Link (iOS LinkStickerScreen): "Enter link", an optional "Link name", and Add link, nothing else.
 * The keyboard comes up once the page has landed. The badge says the name if given, else the site.
 */
@Composable
fun StoryLinkPage(onBack: () -> Unit, onDone: (url: String, label: String) -> Unit) {
    BackHandler(onBack = onBack)
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val urlFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val resolved = remember(text) { if (text.trim().length >= 4) StoryChips.link(text) else null }
    fun add() {
        val (host, url) = resolved ?: return
        val n = name.trim()
        onDone(url, if (n.isEmpty()) host else n.uppercase())
    }
    LaunchedEffect(Unit) { delay(280); runCatching { urlFocus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        TrayPushHeader("Link", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CapsuleField(text, { text = it }, "Enter link", Modifier.focusRequester(urlFocus),
                KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                KeyboardActions(onNext = { runCatching { nameFocus.requestFocus() } }))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapsuleField(name, { name = it.take(LINK_NAME_MAX) }, "Link name (optional)", Modifier.focusRequester(nameFocus),
                    KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done), KeyboardActions(onDone = { add() }))
                Text("Leave this empty and the badge shows the site's name.", Modifier.padding(horizontal = 16.dp),
                    color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp)
            }
            val ok = resolved != null
            Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape)
                .background(if (ok) Color.White else Color.White.copy(alpha = 0.12f))
                .clickable(enabled = ok) { add() }, contentAlignment = Alignment.Center) {
                Text("Add link", color = if (ok) Color.Black else Color.White.copy(alpha = 0.4f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Location (iOS PlaceStickerScreen): a "Search location" field and, the moment it opens, the places
 * around you (1.5 km). The phone's last fix is used first, so the list fills at once. Typing (2+
 * letters) searches nearby first, then wider. Saying no to location leaves the search working.
 */
@Composable
fun StoryPlacePage(onBack: () -> Unit, onDone: (name: String, lat: Double, lon: Double) -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var here by remember { mutableStateOf<Location?>(null) }
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    var nearby by remember { mutableStateOf<List<PhotonPlaces.Place>>(emptyList()) }
    var results by remember { mutableStateOf<List<PhotonPlaces.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasLocation(ctx) }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    LaunchedEffect(granted) { if (granted) here = lastFix(ctx) ?: currentFix(ctx) }
    LaunchedEffect(here) {
        val at = here ?: return@LaunchedEffect
        if (nearby.isNotEmpty()) return@LaunchedEffect
        busy = true
        nearby = runCatching { PhotonPlaces.parse(PhotonPlaces.fetch(PhotonPlaces.nearbyUrl(at.latitude, at.longitude, radiusKm = 1.5)), namedOnly = true) }
            .getOrElse { if (it is CancellationException) throw it; emptyList() }
        busy = false
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        busy = true
        val at = here
        results = runCatching {
            val near = if (at != null) PhotonPlaces.parse(PhotonPlaces.fetch(PhotonPlaces.searchUrl(q, at.latitude, at.longitude, localKm = 30.0))) else emptyList()
            if (near.size >= 8) near
            else (near + PhotonPlaces.parse(PhotonPlaces.fetch(PhotonPlaces.searchUrl(q, at?.latitude, at?.longitude))))
                .distinctBy { it.name + "|" + it.address }
        }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        busy = false
    }
    val shown = if (query.trim().length >= 2) results else nearby
    Column(Modifier.fillMaxSize()) {
        TrayPushHeader("Location", onBack)
        CapsuleField(query, { query = it }, "Search location", Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.name + "|" + it.lat + "|" + it.lon }) { p ->
                    // The whole row answers the finger, not just the words (owner, 2026-08-23 on iOS).
                    Column(Modifier.fillMaxWidth().clickable { onDone(p.name, p.lat, p.lon) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = p.address.ifEmpty { p.area.orEmpty() }
                        if (sub.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (busy && shown.isEmpty()) com.fariin.app.ui.components.FariinSpinner(size = 28.dp, stroke = 3.dp, color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        }
    }
}

private fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Any fix the phone already holds: for "what is near me", a minute-old answer is the same answer. */
@SuppressLint("MissingPermission")
private fun lastFix(ctx: Context): Location? {
    if (!hasLocation(ctx)) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.elapsedRealtimeNanos }
}

/** A fresh coarse fix (wifi and towers answer this without waiting for GPS), or null after 10 s. */
@SuppressLint("MissingPermission")
private suspend fun currentFix(ctx: Context): Location? {
    if (!hasLocation(ctx) || Build.VERSION.SDK_INT < 30) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
    return withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { cont ->
            val cancel = android.os.CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching {
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(ctx)) { if (cont.isActive) cont.resume(it) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}

