package com.fariin.app.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Upload progress by clientId (iOS MediaSend progress), 0..1, for the ring on my own bubble. */
object UploadProgress {
    private val _map = MutableStateFlow<Map<String, Float>>(emptyMap())
    val map: StateFlow<Map<String, Float>> = _map.asStateFlow()
    fun set(clientId: String, f: Float) = _map.update { it + (clientId to f.coerceIn(0f, 1f)) }
    fun done(clientId: String) = _map.update { it - clientId }
}

/**
 * View-once photos this phone has opened (iOS ViewedOnce): once opened, the bubble says "Viewed"
 * and can never be opened here again, the sender's own copy too. Bounded at 500 ids.
 */
object ViewedOnce {
    private const val PREFS = "fariin_media"
    private const val KEY = "viewedOnceMessageIds"
    @Volatile private var mirror: LinkedHashSet<String>? = null
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    private fun load(c: Context): LinkedHashSet<String> = mirror ?: synchronized(this) {
        mirror ?: LinkedHashSet(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")!!.split(',').filter { it.isNotEmpty() },
        ).also { mirror = it }
    }

    fun contains(c: Context, id: String): Boolean = id in load(c)

    @Synchronized fun mark(c: Context, id: String) {
        val s = load(c)
        if (!s.add(id)) return
        while (s.size > 500) s.remove(s.first())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s.joinToString(",")).apply()
        _epoch.value++
    }
}

/**
 * Auto-download (iOS AutoDownloadPrefs): per kind "always" (Wi-Fi and Cellular), "wifi" or
 * "never". Defaults as iOS: photos and audio always, videos and documents on Wi-Fi.
 * The Settings > Storage and Data page writes these: prefs file `fariin_media`, keys
 * `autodl.photos`, `autodl.videos`, `autodl.audio`, `autodl.documents`.
 */
object AutoDownload {
    enum class Kind(val key: String, val default: String) {
        PHOTOS("photos", "always"), VIDEOS("videos", "wifi"), AUDIO("audio", "always"), DOCUMENTS("documents", "wifi")
    }
    const val PREFS = "fariin_media"
    /** Files over 200 MB never download by themselves (iOS maxAutoBytes). */
    const val MAX_AUTO_BYTES = 200L * 1024 * 1024

    fun policy(c: Context, k: Kind): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("autodl.${k.key}", k.default) ?: k.default

    /** Pure rule, tested: may this kind download by itself now? */
    fun allowed(policy: String, onWifi: Boolean): Boolean = when (policy) {
        "always" -> true
        "wifi" -> onWifi
        else -> false
    }

    fun allowedNow(c: Context, k: Kind): Boolean = allowed(policy(c, k), onWifi(c))

    private fun onWifi(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}

/** Saving to the phone's gallery through MediaStore (no storage permission on Android 10+). */
object GallerySaver {
    /** Android 8 and 9 still need the storage permission to write into Pictures. */
    fun needsPermission(c: Context): Boolean = Build.VERSION.SDK_INT < 29 &&
        c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED

    const val PERMISSION_NOTICE = "Allow Fariin to use storage to save"

    /** Copies [file] into Pictures/Fariin or Movies/Fariin. True when it landed. */
    fun save(c: Context, file: File, isVideo: Boolean): Boolean = runCatching {
        val name = "Fariin_${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".jpg"
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Fariin")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                // Before Android 10 the file's place is a real path under Pictures or Movies.
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "Fariin")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = c.contentResolver.insert(collection, values) ?: return false
        c.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)

    /** Saves a photo, a video or every tile of an album; the words for the notice. */
    suspend fun saveMessage(c: Context, media: ChatMedia?, m: com.fariin.app.data.Message): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val media = media ?: return@withContext "Couldn't save"
            if (needsPermission(c)) return@withContext PERMISSION_NOTICE
            val items: List<Triple<String, com.fariin.app.crypto.EncMeta?, Boolean>> = when (m.mediaKind) {
                com.fariin.app.data.Message.MediaKind.PHOTO -> listOfNotNull((m.localPath ?: m.imageUrl)?.let { Triple(it, m.enc, false) })
                com.fariin.app.data.Message.MediaKind.VIDEO -> listOfNotNull(m.videoUrl?.let { Triple(it, m.enc, true) })
                com.fariin.app.data.Message.MediaKind.ALBUM -> m.album.map {
                    if (it.videoUrl != null) Triple(it.videoUrl, it.videoEnc, true) else Triple(it.imageUrl, it.enc, false)
                }
                else -> emptyList()
            }
            var saved = 0
            for ((url, enc, video) in items) {
                val f = media.file(url, enc) ?: continue
                if (save(c, f, video)) saved++
            }
            when {
                saved == 0 -> "Couldn't save"
                saved == items.size -> "Saved to Photos"
                else -> "Saved $saved of ${items.size}"
            }
        }
}

/** Opening a received document in another app: the plain copy goes to cache/open, shared by FileProvider. */
object FileOpener {
    fun intentFor(c: Context, plain: File, fileName: String): Intent? = runCatching {
        val dir = File(c.cacheDir, "open").also { it.mkdirs() }
        val out = File(dir, MediaRules.safeFileName(fileName))
        plain.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", out)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    fun mimeOf(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}

/**
 * The server burn for a view-once photo (iOS ViewedOnce.burnImage -> `consumeOnceImage`,
 * me-central1): deletes the file and strips the message. Safe to call twice; the function refuses
 * the author and groups. Not on the local test backend (functions are not emulated there).
 */
object OnceBurn {
    fun burn(cid: String, messageId: String) {
        runCatching {
            com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable("consumeOnceImage").call(mapOf("cid" to cid, "messageId" to messageId))
        }
    }
}
