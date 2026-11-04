package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.size
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
    if (!CustomReaction.isCustom(key)) { Text(key, fontSize = textSize, modifier = modifier); return }
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
