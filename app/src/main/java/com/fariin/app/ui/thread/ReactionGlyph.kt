package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 */
object CustomReaction {
    private const val COUNT = 181
    fun isCustom(key: String?): Boolean =
        key != null && key.startsWith("ce_") && key.drop(3).toIntOrNull()?.let { it in 1..COUNT } == true
    /** What a screen reader says (iOS spoken). */
    fun spoken(key: String) = if (isCustom(key)) "Custom emoji" else key
}

/** A reaction as drawn on a chip or the reaction bar: the emoji, or a custom reaction playing. */
@Composable
fun ReactionGlyph(key: String, textSize: TextUnit, boxSize: Dp, modifier: Modifier = Modifier) {
    if (!CustomReaction.isCustom(key)) { Emoji3D(key, textSize, boxSize, modifier); return }
    val ctx = LocalContext.current
    val asset = "custom_reactions/$key.tgs"
    val json by produceState<String?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { GZIPInputStream(ctx.assets.open(asset)).bufferedReader().use { it.readText() } }.getOrNull()
        }
    }
    val j = json ?: return
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
