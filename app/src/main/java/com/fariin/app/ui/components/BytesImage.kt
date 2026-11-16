package com.fariin.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.fariin.app.media.BitmapDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture held as bytes (a staged avatar, a story going up, a link card), decoded off the main
 * thread and only as large as the box it fills. Decoding a camera JPEG at full size inside
 * composition froze cheap phones for a frame or more and held 48 MB for a 64 dp circle.
 */
object BytesImage {
    /** Decodes [bytes] so its SHORT side is at least [sidePx] (enough to fill a [sidePx] square, cropped). */
    fun decode(bytes: ByteArray, sidePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val short = minOf(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = BitmapDecode.sampleSizeFor(short, short, sidePx) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

/** [BytesImage.decode] for a box of [side], on Dispatchers.Default; null until it is ready. */
@Composable
fun rememberBytesImage(bytes: ByteArray?, side: Dp): ImageBitmap? {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val image by produceState<ImageBitmap?>(null, bytes, px) {
        value = if (bytes == null) null
        else withContext(Dispatchers.Default) { BytesImage.decode(bytes, px)?.asImageBitmap() }
    }
    return image
}
