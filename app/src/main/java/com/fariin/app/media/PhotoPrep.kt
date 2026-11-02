package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * A picked photo made ready to send, the way iOS sendJPEG / inlineThumb / BlurHash.encode do it:
 * rebuilt from pixels (so the camera's metadata, GPS included, never leaves the phone), the long
 * side at most 1280 (standard quality), JPEG 0.6, stepped down by quarters while it is over 1.5 MB.
 * ImageDecoder applies the EXIF rotation, as UIImage does.
 */
class PhotoPrep(val jpeg: ByteArray, val width: Int, val height: Int, val blurhash: String?, val thumbB64: String?) {
    companion object {
        fun fromUri(context: Context, uri: Uri, q: MediaRules.PhotoQuality = MediaRules.PhotoQuality.STANDARD): PhotoPrep? {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return prepare(q) { side -> decode(src, side) }
        }

        fun fromBytes(bytes: ByteArray, q: MediaRules.PhotoQuality = MediaRules.PhotoQuality.STANDARD): PhotoPrep? {
            val src = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            return prepare(q) { side -> decode(src, side) }
        }

        private fun prepare(q: MediaRules.PhotoQuality, decodeAt: (Int) -> Bitmap?): PhotoPrep? {
            var out: ByteArray? = null
            var bmp: Bitmap? = null
            for (side in MediaRules.sideLadder(q)) {
                bmp?.recycle()
                bmp = decodeAt(side) ?: return null
                out = jpeg(bmp, q.jpegQuality)
                if (out.size <= q.maxBytes) break
            }
            val b = bmp ?: return null
            val bytes = out ?: return null
            val prep = PhotoPrep(bytes, b.width, b.height, blurOf(b), thumbOf(b))
            b.recycle()
            return prep
        }

        private fun decode(src: ImageDecoder.Source, side: Int): Bitmap? = runCatching {
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val (w, h) = MediaRules.fit(info.size.width, info.size.height, side)
                dec.setTargetSize(w, h)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                dec.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            }
        }.getOrNull()?.let { flatten(it) }

        /** No alpha in a JPEG: a transparent PNG goes on white, not black. */
        private fun flatten(b: Bitmap): Bitmap {
            if (!b.hasAlpha()) return b
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
            b.recycle()
            return out
        }

        private fun jpeg(b: Bitmap, quality: Int): ByteArray =
            ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        private fun scaled(b: Bitmap, side: Int): Bitmap {
            val (w, h) = MediaRules.fit(b.width, b.height, side)
            return Bitmap.createScaledBitmap(b, w, h, true)
        }

        private fun blurOf(b: Bitmap): String? {
            val s = scaled(b, MediaRules.BLUR_SIDE)
            val px = IntArray(s.width * s.height)
            s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
            return BlurHash.encode(px, s.width, s.height)
        }

        private fun thumbOf(b: Bitmap): String? {
            val s = scaled(b, MediaRules.THUMB_SIDE)
            return Base64.encodeToString(jpeg(s, MediaRules.THUMB_QUALITY), Base64.NO_WRAP)
        }

        /** Bounds-only read, to size a bubble from a local file. */
        fun size(bytes: ByteArray): Pair<Int, Int>? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }
}
