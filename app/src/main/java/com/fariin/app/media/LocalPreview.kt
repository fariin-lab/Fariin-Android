package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build

/**
 * The picked file itself, drawn in my own bubble from its first frame (iOS adds the pending photo
 * or album with its local pictures at once): the shape read cheaply from the file (bounds only,
 * EXIF turn applied; a video's width, height and rotation tag), and a small decoded copy kept in
 * memory so the bubble and its echo from the server draw the same pixels without a gap.
 *
 * Everything here reads the disk: call it off the main thread.
 */
object LocalPreview {
    /** Long side of the copy drawn in a lone photo or video bubble (a 350 dp bubble on a 2.75x screen). */
    const val LONE_SIDE = 960
    /** Long side of the copy drawn in one album tile. */
    const val TILE_SIDE = 640

    data class Shape(val width: Int, val height: Int, val duration: Double? = null)

    /** Width and height as the picture shows (EXIF turn applied), or null when unreadable. */
    fun photoShape(c: Context, uri: Uri): Shape? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0 && o.outHeight > 0) {
            val tag = runCatching {
                c.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            val (deg, _) = BitmapDecode.orientation(tag)
            turned(o.outWidth, o.outHeight, deg)
        } else {
            // A format BitmapFactory cannot read on this phone (HEIF on an older one): a tiny
            // decode through the same path the send uses gives the true shape, turn included.
            BitmapDecode.fromUri(c, uri, 96)?.let { b -> Shape(b.width, b.height).also { b.recycle() } }
        }
    }.getOrNull()

    /** A video's shown width and height (rotation tag applied) and its length in seconds. */
    fun videoShape(c: Context, uri: Uri): Shape? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
            if (w <= 0 || h <= 0) null else turned(w, h, rot).copy(duration = ms?.let { it / 1000.0 })
        } finally { r.release() }
    }.getOrNull()

    /** Pure rule, tested: a quarter or three-quarter turn swaps the sides. */
    fun turned(w: Int, h: Int, degrees: Int): Shape =
        if (((degrees % 360) + 360) % 360 == 90 || ((degrees % 360) + 360) % 360 == 270) Shape(h, w) else Shape(w, h)

    // ---- the decoded copies

    private var cache: android.util.LruCache<String, Bitmap>? = null

    private fun cache(c: Context): android.util.LruCache<String, Bitmap> = cache ?: synchronized(this) {
        cache ?: object : android.util.LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / if (LowEnd.isLowRam(c)) 32 else 16).toInt().coerceAtLeast(4 * 1024 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }.also { cache = it }
    }

    private fun key(uri: String, side: Int) = "$uri|$side"

    /** The copy already in memory, or null. Safe on the main thread. */
    fun cached(c: Context, uri: String, side: Int): Bitmap? = cache(c).get(key(uri, side))

    /** The copy of [uri] at [side] (long side), decoded once and kept. Off the main thread. */
    fun load(c: Context, uri: String, isVideo: Boolean, side: Int): Bitmap? {
        cached(c, uri, side)?.let { return it }
        val k = key(uri, side)
        // The send and the bubble may ask at once: one decode, the other waits for it.
        synchronized(locks.getOrPut(k) { Any() }) {
            try {
                cached(c, uri, side)?.let { return it }
                val u = Uri.parse(uri)
                val b = runCatching { if (isVideo) videoFrame(c, u, side) else BitmapDecode.fromUri(c, u, side) }.getOrNull() ?: return null
                cache(c).put(k, b)
                return b
            } finally { locks.remove(k) }
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun videoFrame(c: Context, uri: Uri, side: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val raw = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
            } else r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val bmp = raw ?: return null
            if (maxOf(bmp.width, bmp.height) <= side) return bmp
            val (w, h) = MediaRules.fit(bmp.width, bmp.height, side)
            return Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() }
        } finally { r.release() }
    }
}
