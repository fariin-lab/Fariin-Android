package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * One way to turn a picked photo into a bitmap no larger than needed, on every Android the app
 * runs on (8.0 and up). Android 9+ uses ImageDecoder (EXIF rotation and HEIF built in); Android 8
 * uses BitmapFactory with a power-of-two sample size, then turns the pixels by the EXIF tag. Both
 * decode straight to the target size, so a 12 MP camera photo never sits in memory at full size
 * (48 MB of pixels would sink a 1 GB phone).
 */
object BitmapDecode {

    /** Largest power of two that keeps the long side at or above [target] (1 when no target). */
    fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        if (target <= 0 || w <= 0 || h <= 0) return 1
        val long = maxOf(w, h)
        var s = 1
        while (long / (s * 2) >= target) s *= 2
        return s
    }

    /** EXIF orientation tag to (degrees, mirror). Unknown or normal = (0, false). */
    fun orientation(tag: Int): Pair<Int, Boolean> = when (tag) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
        ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
        ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
        ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
        ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
        else -> 0 to false
    }

    fun fromUri(c: Context, uri: Uri, maxSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) modern(ImageDecoder.createSource(c.contentResolver, uri), maxSide)
        else legacy({ c.contentResolver.openInputStream(uri) }, maxSide)

    fun fromBytes(bytes: ByteArray, maxSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) modern(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), maxSide)
        else legacy({ bytes.inputStream() }, maxSide)

    @RequiresApi(28)
    private fun modern(src: ImageDecoder.Source, maxSide: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            if (maxSide > 0) {
                val (w, h) = MediaRules.fit(info.size.width, info.size.height, maxSide)
                dec.setTargetSize(w, h)
            }
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun legacy(open: () -> InputStream?, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val tag = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide) }
        var b = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (maxSide > 0 && maxOf(b.width, b.height) > maxSide) {
            val (w, h) = MediaRules.fit(b.width, b.height, maxSide)
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            if (s !== b) b.recycle()
            b = s
        }
        val (deg, mirror) = orientation(tag)
        if (deg == 0 && !mirror) return b
        val m = Matrix().apply { if (mirror) postScale(-1f, 1f); postRotate(deg.toFloat()) }
        val turned = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (turned !== b) b.recycle()
        turned
    }.getOrNull()
}
