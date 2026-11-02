package com.fariin.app.ui.photo

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import okio.Buffer

/**
 * Profile photos are named `fariin-photo://profiles/<uid>.jpg?v=<ms>` and fetched here with the
 * signed-in Storage SDK, so `storage.rules` (`canSeePhoto`) decides per viewer on every download.
 * A port of Kulan-iOS/Kulan/Services/ProfilePhotoURLProtocol.swift.
 *
 * Why not a download URL: its token skips the storage rules, so a photo set to "No One" would
 * still show. `v` changes whenever the photo or its audience changes, so a new name misses every
 * cache and the rules are asked again.
 *
 * A refusal or a missing file throws, and the avatar shows the grey placeholder, the same as for
 * a person with no photo.
 *
 * TODO: keep a disk copy (iOS DiskImageCache); for now only Coil's memory cache holds them.
 */
class FariinPhotoFetcher(
    private val path: String,
    private val options: Options,
    private val url: String? = null,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = FirebaseStorage.getInstance().reference.child(path).getBytes(MAX_BYTES).await()
        // A copy for the notifications (iOS NotifShared.saveAvatarSoon): the face of a person this
        // account was allowed to see, under the photo's own name.
        url?.let { com.fariin.app.push.NotifStore.saveAvatarSoon(options.context, it, bytes) }
        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = storagePath(data) ?: return null
            return FariinPhotoFetcher(path, options, data.toString())
        }
    }

    companion object {
        const val SCHEME = "fariin-photo"
        private const val MAX_BYTES = 12L * 1024 * 1024

        /** `fariin-photo://profiles/abc.jpg?v=1` to `profiles/abc.jpg`. Null for anything else. */
        fun storagePath(uri: Uri): String? {
            if (uri.scheme != SCHEME) return null
            val host = uri.authority?.takeIf { it.isNotEmpty() } ?: return null
            val path = host + (uri.path ?: "")
            if (!path.startsWith("profiles/") || path.contains("..")) return null
            return path
        }
    }
}
