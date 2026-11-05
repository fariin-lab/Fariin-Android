package com.fariin.app.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The phone's newest photos and videos for the attach sheet's grid (iOS AttachRecentsStrip reads
 * PhotoKit the same way). Owner 2026-11-04: our own grid, never the Google photo picker.
 */
object RecentMedia {

    data class Item(val uri: Uri, val isVideo: Boolean, val durationMs: Long, val dateAdded: Long,
                    val bucketId: Long = 0, val bucketName: String = "")

    /** One photo folder (iOS album list): its newest item is the cover. */
    data class Album(val id: Long, val name: String, val cover: Item, val count: Int)

    enum class Access { FULL, PARTIAL, NONE }

    /** What to ask for: images + video, and on Android 14+ the "selected photos" grant too. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(ctx: Context): Access {
        fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
            else -> Access.NONE
        }
    }

    /** The phone's folders, biggest first, as iOS lists them. Call off the main thread. */
    fun albums(ctx: Context): List<Album> =
        load(ctx, limit = 20_000).groupBy { it.bucketId }.map { (id, items) ->
            Album(id, items.first().bucketName.ifBlank { "Other" }, items.first(), items.size)
        }.sortedByDescending { it.count }

    /** Newest first, photos and videos together, at most [limit]; one folder when [bucket] is set. Off the main thread. */
    fun load(ctx: Context, limit: Int = 600, bucket: Long? = null): List<Item> {
        val out = ArrayList<Item>()
        fun read(base: Uri, video: Boolean) {
            val base0 = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.Images.ImageColumns.BUCKET_ID, MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            val cols = if (video) base0 + MediaStore.Video.VideoColumns.DURATION else base0
            val sel = bucket?.let { "${MediaStore.Images.ImageColumns.BUCKET_ID} = ?" }
            val args = bucket?.let { arrayOf(it.toString()) }
            runCatching {
                ctx.contentResolver.query(base, cols, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dur = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                    val bid = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
                    val bname = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext() && out.size < limit * 2) {
                        out += Item(ContentUris.withAppendedId(base, c.getLong(id)), video,
                            if (dur >= 0) c.getLong(dur) else 0L, c.getLong(added),
                            if (bid >= 0) c.getLong(bid) else 0L, if (bname >= 0) c.getString(bname) ?: "" else "")
                    }
                }
            }
        }
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out.sortedByDescending { it.dateAdded }.take(limit)
    }
}
