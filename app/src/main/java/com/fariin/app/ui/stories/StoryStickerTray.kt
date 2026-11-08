package com.fariin.app.ui.stories

import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.fariin.app.data.Giphy
import com.fariin.app.data.NewBadgeSpots
import com.fariin.app.data.StickerGifRules
import com.fariin.app.perf.PowerSaving
import com.fariin.app.ui.components.NewCornerPill
import com.fariin.app.ui.components.isNew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The story stickers (iOS StoryStickerSheet + BuiltInStickers + StickerRecents): our own pack that
 * ships in the app, the sticker search of the GIF service the chat already uses, and this phone's
 * recently used. A chosen sticker is placed on the card as its first frame, which is what posts.
 */
object StoryStickers {
    private const val PREFS = "fariin_story_stickers"
    private const val KEY = "story.sticker.recents"
    private const val CAP = 24
    private const val ASSET = "file:///android_asset/"

    /** iOS BuiltInStickers.all, in its order (the order on screen). */
    private val BUILT_IN = listOf("new_story_blue", "new_story_red", "love_burst", "love_heart", "heart_walk", "yay", "coming_soon")
    val builtIn: List<StickerGifRules.Gif> = BUILT_IN.map {
        StickerGifRules.Gif("fariin.$it", "${ASSET}story_stickers/sticker_$it.png", 512.0, 512.0)
    }

    fun recents(context: Context): List<StickerGifRules.Gif> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").ifEmpty { return@mapNotNull null }
                StickerGifRules.Gif("recent-$url", url, o.optDouble("w", 200.0), o.optDouble("h", 200.0))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** iOS StickerRecents.note: to the front, once, at most 24. */
    fun note(context: Context, g: StickerGifRules.Gif) {
        val kept = recents(context).filter { it.url != g.url }
        val a = org.json.JSONArray()
        a.put(org.json.JSONObject().put("url", g.url).put("w", g.width).put("h", g.height))
        kept.take(CAP - 1).forEach { a.put(org.json.JSONObject().put("url", it.url).put("w", it.width).put("h", it.height)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** The sticker's first frame as a picture, at most 600 px a side; null when it cannot be had. */
    suspend fun bitmap(context: Context, g: StickerGifRules.Gif): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = if (g.url.startsWith(ASSET)) context.assets.open(g.url.removePrefix(ASSET)).use { it.readBytes() }
            else {
                val c = java.net.URL(g.url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
            }
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val k = 600f / maxOf(b.width, b.height)
            if (k >= 1f) b else Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
                .also { if (it !== b) b.recycle() }
        } catch (_: Exception) { null }
    }
}

/** One tab along the tray's bottom (iOS StickerTab): recent, our pack, then the searched ones. */
private sealed interface StickerTab {
    data object Recent : StickerTab
    data object BuiltIn : StickerTab
    data class Term(val icon: Int, val query: String) : StickerTab
}

/** iOS StickerTab.all: trending (an empty query) leads the searched tabs. */
private val TABS: List<StickerTab> = listOf(
    StickerTab.Recent,
    StickerTab.BuiltIn,
    StickerTab.Term(R.drawable.ic_sf_flame_fill, ""),
    StickerTab.Term(R.drawable.ic_sf_face_smiling, "laughing"),
    StickerTab.Term(R.drawable.ic_sf_heart_fill, "love"),
    StickerTab.Term(R.drawable.ic_sf_party, "party"),
    StickerTab.Term(R.drawable.ic_sf_hand_wave, "hello"),
    StickerTab.Term(R.drawable.ic_sf_star_fill, "thank you"),
    StickerTab.Term(R.drawable.ic_sf_moon_fill, "tired"),
    StickerTab.Term(R.drawable.ic_sf_sparkles, "sparkle"),
    StickerTab.Term(R.drawable.ic_sf_hand_thumbsup_fill, "yes"),
    StickerTab.Term(R.drawable.ic_sf_drop_fill, "crying"),
)

/** Trending, kept for the process like iOS trendingCache. */
private var trendingCache: List<StickerGifRules.Gif> = emptyList()

/**
 * The tray (iOS StoryStickerSheet in StoryTraySheet): black, edge to edge, 80% of the screen. A
 * search field, the Location / Link / Time pills, the grid of stickers four across, and the tabs.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoryStickerTray(
    onClose: () -> Unit, onSticker: (StickerGifRules.Gif) -> Unit,
    onTime: () -> Unit, onLink: () -> Unit, onPlace: () -> Unit,
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf<StickerTab>(StickerTab.BuiltIn) }
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<StickerGifRules.Gif>>(StoryStickers.builtIn) }
    var loading by remember { mutableStateOf(false) }
    val searching = query.isNotBlank()
    val play = PowerSaving.state.collectAsState().value.autoplayGifs
    LaunchedEffect(tab, query) {
        val q = query.trim()
        if (q.isNotEmpty()) {
            delay(300)   // iOS waits 0.3 s after the last letter
            loading = true
            list = Giphy.search(ctx, q, stickers = true)
            loading = false
            return@LaunchedEffect
        }
        when (val t = tab) {
            StickerTab.Recent -> { loading = false; list = StoryStickers.recents(ctx) }
            StickerTab.BuiltIn -> { loading = false; list = StoryStickers.builtIn }
            is StickerTab.Term -> {
                val trending = t.query.isEmpty()
                if (trending && trendingCache.isNotEmpty()) { list = trendingCache; return@LaunchedEffect }
                list = emptyList()
                loading = true
                val found = Giphy.search(ctx, t.query, stickers = true)
                loading = false
                if (trending && found.isNotEmpty()) trendingCache = found
                list = found
            }
        }
    }
    fun pick(g: StickerGifRules.Gif) {
        StoryStickers.note(ctx, g)
        onSticker(g)
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            // The search field (iOS: a 40 tall capsule).
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    BasicTextField(query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth())
                }
                if (searching) Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark_circle_fill), contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp).clickable { query = "" })
            }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!searching) item(span = { GridItemSpan(maxLineSpan) }) {
                    // iOS: the pills wrap and each row is centred.
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StickerPill("📍", "Location", Color(0xFFFF453A), NewBadgeSpots.STORY_STICKERS_LOCATION, onPlace)
                        StickerPill("🔗", "Link", Color(0xFF0A84FF), NewBadgeSpots.STORY_STICKERS_LINK, onLink)
                        StickerPill("🕒", "Time", Color(0xFFFF9F0A), NewBadgeSpots.STORY_STICKERS_TIME, onTime)
                    }
                }
                if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading -> com.fariin.app.ui.components.FariinSpinner(size = 28.dp, stroke = 3.dp, color = Color.White)
                            searching -> Text("No stickers found.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                            tab == StickerTab.Recent -> Text("Stickers you use will show up here.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                    }
                }
                items(list) { g ->
                    val request = remember(g.url, play) {
                        ImageRequest.Builder(ctx).data(g.url).apply {
                            if (play) decoderFactory(if (Build.VERSION.SDK_INT >= 28) coil3.gif.AnimatedImageDecoder.Factory() else coil3.gif.GifDecoder.Factory())
                        }.build()
                    }
                    AsyncImage(request, contentDescription = "Sticker", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { pick(g) })
                }
            }
            LazyRow(Modifier.fillMaxWidth().height(56.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                items(TABS) { t ->
                    val on = tab == t && !searching
                    Box(Modifier.width(52.dp).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { query = ""; tab = t }, contentAlignment = Alignment.Center) {
                        when (t) {
                            StickerTab.Recent -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_clock_arrow), contentDescription = "Recent", tint = Color.White, modifier = Modifier.size(22.dp))
                            // Our pack's tab is its first sticker, as iOS draws it.
                            StickerTab.BuiltIn -> AsyncImage(StoryStickers.builtIn.first().url, contentDescription = "Fariin stickers",
                                contentScale = ContentScale.Fit, modifier = Modifier.size(27.dp))
                            is StickerTab.Term -> Icon(ImageVector.vectorResource(t.icon), contentDescription = t.query.ifEmpty { "Trending" }, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** iOS actionPill: the symbol in its colour, the word in white, on a capsule. */
@Composable
private fun StickerPill(glyph: String, title: String, tint: Color, badge: String, onClick: () -> Unit) {
    Box {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, fontSize = 16.sp, color = tint)
            Spacer(Modifier.width(6.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        NewCornerPill(isNew(badge))
    }
}
