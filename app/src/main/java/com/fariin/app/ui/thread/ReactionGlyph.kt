package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/**
 * iOS CustomReaction: "ce_1" to "ce_181" are animated reactions shipped as .tgs (gzipped Lottie),
 * offered only by channels that @v set them on; everyone else's reaction is a plain emoji.
 * "cu_" + 10 characters are the ones uploaded in the admin panel ([UploadedEmoji]), played the same way.
 */
object CustomReaction {
    private const val COUNT = 181
    val builtIn: List<String> = (1..COUNT).map { "ce_$it" }
    /** The built-in ones, then the uploaded ones, newest last (iOS CustomReaction.all). Built-in ones
     *  the admin panel hid are left out; a channel that already uses one keeps showing it. */
    fun all(): List<String> = com.fariin.app.data.UploadedEmoji.offered(builtIn, com.fariin.app.data.UploadedEmoji.hidden.value) +
        com.fariin.app.data.UploadedEmoji.items.value.map { "cu_" + it.id }
    fun isCustom(key: String?): Boolean = com.fariin.app.data.UploadedEmoji.isKey(key) ||
        (key != null && key.startsWith("ce_") && key.drop(3).toIntOrNull()?.let { it in 1..COUNT } == true)
    /** A key that looks like a custom one ("ce_" or "cu_") but is not known: never drawn as raw text. */
    fun looksCustom(key: String): Boolean = key.startsWith("ce_") || key.startsWith("cu_")
    /** What a screen reader says (iOS spoken). */
    fun spoken(key: String) = if (isCustom(key) || looksCustom(key)) "Custom emoji" else key
    /** An uploaded one taken off the admin list: not drawn, not counted, not offered. */
    fun isRetired(key: String?): Boolean = com.fariin.app.data.UploadedEmoji.isRetired(key)
}

/** [CustomReaction.isRetired] that redraws its caller when the admin list changes. */
@Composable
fun rememberRetired(): (String) -> Boolean {
    val items by com.fariin.app.data.UploadedEmoji.items.collectAsState()
    val known by com.fariin.app.data.UploadedEmoji.known.collectAsState()
    return remember(items, known) { { key: String -> known && com.fariin.app.data.UploadedEmoji.retired(key, items) } }
}

/** A reaction as drawn on a chip or the reaction bar: the emoji, or a custom reaction playing. */
@Composable
fun ReactionGlyph(key: String, textSize: TextUnit, boxSize: Dp, modifier: Modifier = Modifier) {
    if (!CustomReaction.isCustom(key)) {
        // An unknown "ce_"/"cu_" key (a newer build's, or one taken off the list): an empty box, not the raw key.
        if (CustomReaction.looksCustom(key)) Box(modifier.size(boxSize)) else Emoji3D(key, textSize, boxSize, modifier)
        return
    }
    val ctx = LocalContext.current
    val uploaded = com.fariin.app.data.UploadedEmoji.isKey(key)
    // An uploaded one may still be downloading: drawn when its file lands.
    val landed by com.fariin.app.data.UploadedEmoji.landed.collectAsState()
    val asset = if (uploaded) "uploaded/$key" else "custom_reactions/$key.tgs"
    val json by produceState<String?>(null, asset, if (uploaded) landed else 0) {
        value = withContext(Dispatchers.IO) {
            if (uploaded) com.fariin.app.data.UploadedEmoji.json(key)
            else runCatching { GZIPInputStream(ctx.assets.open(asset)).bufferedReader().use { it.readText() } }.getOrNull()
        }
    }
    val j = json ?: run { Box(modifier.size(boxSize)); return }
    val comp by rememberLottieComposition(LottieCompositionSpec.JsonString(j), cacheKey = asset)
    LottieAnimation(comp, iterations = LottieConstants.IterateForever,
        modifier = modifier.size(boxSize).semantics { contentDescription = "Custom emoji" })
}

/**
 * A big emoji in the 3D style (owner 2026-11-04: iOS's emoji look better than Android's): Microsoft's
 * Fluent 3D art (MIT, via @lobehub/fluent-emoji-3d 1.1.0) for the reaction emoji bundled in
 * assets/emoji3d/<codepoints without FE0F>.webp; any other emoji stays the phone's own text glyph.
 * Only where emoji are big (reactions, pickers); emoji inside typed text keep the keyboard's look.
 */
@Composable
fun Emoji3D(emoji: String, textSize: TextUnit, boxSize: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val name = remember(emoji) { Emoji3DArt.key(emoji) }
    if (!Emoji3DArt.has(ctx, name)) { Text(emoji, fontSize = textSize, modifier = modifier); return }
    val bmp by produceState(Emoji3DArt.cached(name), name) {
        if (value == null) value = withContext(Dispatchers.IO) { Emoji3DArt.load(ctx, name) }
    }
    val b = bmp
    if (b == null) Box(modifier.size(boxSize))
    else androidx.compose.foundation.Image(b, emoji, modifier = modifier.size(boxSize))
}

object Emoji3DArt {
    private var names: Set<String>? = null
    private val bitmaps = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48)

    fun key(emoji: String): String = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }

    fun has(ctx: android.content.Context, name: String): Boolean {
        val n = names ?: (runCatching { ctx.assets.list("emoji3d")?.map { it.removeSuffix(".webp") }?.toSet() }.getOrNull() ?: emptySet())
            .also { names = it }
        return name in n
    }

    fun cached(name: String): androidx.compose.ui.graphics.ImageBitmap? = bitmaps.get(name)

    /** All the art decoded once, off the main thread, at app start: the long-press bar drew an
     *  empty capsule for ~10 frames while its six were read the first time (owner's video). */
    fun preload(ctx: android.content.Context) {
        Thread({
            runCatching { ctx.assets.list("emoji3d")?.forEach { f -> val n = f.removeSuffix(".webp"); if (bitmaps.get(n) == null) load(ctx, n) } }
        }, "emoji3d-preload").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun load(ctx: android.content.Context, name: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        ctx.assets.open("emoji3d/$name.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
            ?.asImageBitmap()?.also { bitmaps.put(name, it) }
    }.getOrNull()
}
