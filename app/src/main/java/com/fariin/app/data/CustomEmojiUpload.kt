package com.fariin.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Adding custom emoji from this phone (iOS UploadedEmoji.upload, owner 2026-11-06): the chooser
 * account picks .tgs files in the Custom tab, they go to `addCustomEmoji` (functions-groups, the same
 * checks and list as the admin panel) 20 at a time, and new ones come back through the live
 * `config/features.customEmoji` listener ([AppFeatures] -> [UploadedEmoji]), so nothing here adds
 * them to the grid by hand.
 *   addCustomEmoji {files: [{name, data: base64}]} -> {added: [{id, url}]}
 */
object CustomEmojiUpload {
    /** The one account allowed to choose and add custom emoji (iOS CustomReaction.chooserUid). */
    const val CHOOSER_UID = "SVf0zxDVZzO8oLbGvM150QWgIWd2"
    /** Files per call (iOS stride 20). */
    const val BATCH = 20
    /** Bigger files are skipped before sending: the server refuses anything past its own cap anyway. */
    const val MAX_BYTES = UploadedEmoji.MAX_BYTES

    data class Picked(val name: String, val bytes: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _busy = MutableStateFlow(false)
    /** True while files are going up: the "+" shows a spinner, even if the sheet was closed and opened again. */
    val busy: StateFlow<Boolean> = _busy

    /** Starts an upload that outlives the sheet; [done] gets the line to show. Ignored while one is running. */
    fun start(ctx: Context, uris: List<Uri>, done: (String) -> Unit) {
        if (uris.isEmpty() || _busy.value) return
        _busy.value = true
        val app = ctx.applicationContext
        scope.launch {
            val line = try { upload(app, uris) } finally { _busy.value = false }
            done(line)
        }
    }

    fun canUpload(uid: String?): Boolean = uid == CHOOSER_UID

    /** The files in calls of at most [BATCH], in the order picked. */
    fun <T> batches(files: List<T>): List<List<T>> = files.chunked(BATCH)

    fun payload(files: List<Picked>): Map<String, Any> =
        mapOf("files" to files.map { mapOf("name" to it.name, "data" to Base64.getEncoder().encodeToString(it.bytes)) })

    /** How many the server says it added (falls back to how many were sent if it does not say). */
    fun addedCount(reply: Any?, sent: Int): Int =
        ((reply as? Map<*, *>)?.get("added") as? List<*>)?.size ?: sent

    fun addedText(n: Int): String = if (n == 1) "1 emoji added" else "$n emoji added"

    fun tooBigText(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names[0]} is too big (64 KB at most)"
        else -> "${names.size} files are too big (64 KB at most)"
    }

    /**
     * The one line shown when it is done. A refusal is the server's own words (they name the file),
     * after whatever went in before it; skipped big files are said at the end.
     */
    fun resultText(added: Int, error: String?, tooBig: List<String>): String {
        val parts = buildList {
            if (error != null) {
                if (added > 0) add(addedText(added))
                add(error)
            } else if (added > 0 || tooBig.isEmpty()) add(addedText(added))
            if (tooBig.isNotEmpty()) add(tooBigText(tooBig))
        }
        return parts.joinToString(". ")
    }

    /** The server's message for a refusal; a generic line when it is only a connection or internal failure. */
    fun errorText(e: Exception): String {
        val f = e as? FirebaseFunctionsException
        val msg = f?.message?.trim().orEmpty()
        val plain = f != null && msg.isNotEmpty() && f.code !in setOf(
            FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, FirebaseFunctionsException.Code.UNKNOWN)
        return if (plain) msg else "Couldn't add the emoji. Check your connection and try again."
    }

    /**
     * Reads the picked files off the main thread, sends them 20 at a time and returns the line to show.
     * Stops at the first refusal, as iOS does.
     */
    suspend fun upload(ctx: Context, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val files = ArrayList<Picked>()
        val tooBig = ArrayList<String>()
        var unreadable = 0
        for (u in uris) {
            val name = displayName(ctx, u)
            val size = sizeOf(ctx, u)
            if (size != null && size > MAX_BYTES) { tooBig.add(name); continue }
            val bytes = runCatching {
                // One byte past the cap at most, so a file that lied about its size is not read whole.
                ctx.contentResolver.openInputStream(u)?.use { it.readNBytesCompat(MAX_BYTES + 1) }
            }.getOrNull()
            when {
                bytes == null -> unreadable++
                bytes.size > MAX_BYTES -> tooBig.add(name)
                else -> files.add(Picked(name, bytes))
            }
        }
        if (files.isEmpty()) {
            return@withContext if (tooBig.isNotEmpty()) tooBigText(tooBig) else "Couldn't read those files."
        }
        val fn = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
        var added = 0
        var error: String? = null
        for (part in batches(files)) {
            try {
                val r = fn.getHttpsCallable("addCustomEmoji").withTimeout(60, TimeUnit.SECONDS)
                    .call(payload(part)).await().getData()
                added += addedCount(r, part.size)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = errorText(e); break
            }
        }
        if (unreadable > 0 && error == null && tooBig.isEmpty() && added == 0) "Couldn't read those files."
        else resultText(added, error, tooBig)
    }

    private fun displayName(ctx: Context, u: Uri): String = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (u.lastPathSegment ?: "file")

    private fun sizeOf(ctx: Context, u: Uri): Long? = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var left = n
        while (left > 0) {
            val k = read(buf, 0, minOf(buf.size, left))
            if (k < 0) break
            out.write(buf, 0, k); left -= k
        }
        return out.toByteArray()
    }
}
