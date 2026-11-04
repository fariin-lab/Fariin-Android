package com.fariin.app.ui.stories

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A story photo as iOS draws it on the story page and on the viewers carousel (StoryCardImage):
 * a picture at least as tall as the card fills it edge to edge; a shorter one is shown whole over a
 * gradient of its own top and bottom colours (iOS edgeColours), so the card is always the whole
 * rounded card and never a picture in a black frame. Both places use this, so a card that slides
 * from the row into the page keeps its look.
 */
@Composable
internal fun StoryPhoto(url: String?, modifier: Modifier = Modifier, onLoaded: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var aspect by remember(url) { mutableStateOf(0f) }          // height / width, 0 until known
    var canvas by remember(url) { mutableStateOf<Pair<Color, Color>?>(canvasCache[url]) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cardAspect = if (constraints.maxWidth > 0) constraints.maxHeight.toFloat() / constraints.maxWidth else 16f / 9f
        val fills = aspect > 0f && aspect >= cardAspect - 0.02f
        canvas?.let { (top, bottom) ->
            if (!fills) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
        }
        AsyncImage(url, contentDescription = null,
            contentScale = if (fills) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { s ->
                val img = s.result.image
                if (img.width > 0) aspect = img.height.toFloat() / img.width
                if (canvas == null && url != null) scope.launch {
                    val c = withContext(Dispatchers.Default) {
                        runCatching {
                            val b = img.toBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                            val (t, bt) = RepostCard.edgeColours(b)
                            Color(t) to Color(bt)
                        }.getOrNull()
                    }
                    if (c != null) { canvasCache[url] = c; canvas = c }
                }
                onLoaded()
            })
    }
}

/** Edge colours already worked out, so a card that recomposes elsewhere has its canvas at once. */
private val canvasCache = object : LinkedHashMap<String, Pair<Color, Color>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Color, Color>>?) = size > 200
}
