package com.fariin.app.data

import android.content.Context
import java.io.File

/**
 * The profile photos this account has used, newest first, on this phone only (iOS
 * ProfilePhotoHistory): Edit Profile offers them back under Recents. Ten at most; one folder per
 * account; wiped at sign-out so the next person on this phone never sees the last one's pictures.
 */
object ProfilePhotoHistory {
    const val LIMIT = 10
    private fun dir(c: Context, uid: String) = File(c.filesDir, "profile_history/$uid")

    /** After the upload has landed, so a failed one never enters the history. */
    fun record(c: Context, uid: String, jpeg: ByteArray) {
        if (uid.isEmpty() || jpeg.isEmpty()) return
        val d = dir(c, uid).apply { mkdirs() }
        runCatching { File(d, "${System.currentTimeMillis()}.jpg").writeBytes(jpeg) }
        files(c, uid).drop(LIMIT).forEach { it.delete() }
    }

    fun files(c: Context, uid: String): List<File> =
        dir(c, uid).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedByDescending { it.name }

    fun wipe(c: Context) { File(c.filesDir, "profile_history").deleteRecursively() }
}
