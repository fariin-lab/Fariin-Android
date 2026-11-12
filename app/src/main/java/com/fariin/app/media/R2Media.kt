package com.fariin.app.media

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chat media in Cloudflare R2 (same as iOS 9bbac4e65): a media url field holding
 * "r2:chat/{cid}/{file}" is fetched through the mediaSign callable (me-central1), which checks the
 * caller belongs to the chat and returns a presigned URL for 15 minutes. Reading works whatever the
 * switch says, so each app reads the other's; uploading to R2 waits for config/features.r2Media.
 */
object R2Media {
    /**
     * R2 holds group and channel media only (owner 2026-11-12). A 1:1 chat's id has a "_" in it:
     * its end-to-end encrypted media stays on Storage until delivered (30 days at most), and the
     * server refuses an R2 "put" for it anyway.
     */
    fun allowed(path: String): Boolean =
        path.startsWith("chat/") && !path.removePrefix("chat/").substringBefore('/').contains('_')

    const val PREFIX = "r2:"
    fun isR2(url: String?) = url?.startsWith(PREFIX) == true
    fun keyOf(url: String) = url.removePrefix(PREFIX)
    fun link(key: String) = PREFIX + key

    private fun fn() = FirebaseFunctions.getInstance("me-central1").getHttpsCallable("mediaSign")

    private suspend fun sign(op: String, key: String, size: Long? = null, contentType: String? = null): Pair<String, String?>? = runCatching {
        val data = hashMapOf<String, Any>("op" to op, "key" to key)
        size?.let { data["size"] = it }; contentType?.let { data["contentType"] = it }
        val r = fn().call(data).await().getData() as? Map<*, *> ?: return null
        val url = r["url"] as? String ?: return null
        url to ((r["headers"] as? Map<*, *>)?.get("Content-Type") as? String)
    }.getOrNull()

    /** The object's bytes (capped), or null. [progress] gets (done, total). */
    suspend fun get(url: String, max: Long, progress: (Long, Long?) -> Unit = { _, _ -> }): ByteArray? {
        val (signed, _) = sign("get", keyOf(url)) ?: return null
        return withContext(Dispatchers.IO) {
            val c = URL(signed).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            try {
                if (c.responseCode != 200) return@withContext null
                val total = c.contentLengthLong.takeIf { it > 0 }
                if (total != null && total > max) return@withContext null
                val out = java.io.ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) return@withContext null
                        progress(out.size().toLong(), total)
                    }
                }
                out.toByteArray()
            } finally { c.disconnect() }
        }
    }

    /** Uploads [bytes] to chat/{cid}/{file}; returns the "r2:" link, or null. Create-only. */
    suspend fun put(key: String, bytes: ByteArray, contentType: String = "application/octet-stream",
                    onProgress: ((Float) -> Unit)? = null): String? {
        val (signed, ct) = sign("put", key, bytes.size.toLong(), contentType) ?: return null
        return withContext(Dispatchers.IO) {
            val c = URL(signed).openConnection() as HttpURLConnection
            c.requestMethod = "PUT"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("Content-Type", ct ?: contentType)
            try {
                // Written in pieces so the bubble's ring fills as the bytes go (iOS didSendBodyData).
                c.outputStream.use { out ->
                    var at = 0
                    while (at < bytes.size) {
                        val n = minOf(64 * 1024, bytes.size - at)
                        out.write(bytes, at, n); at += n
                        onProgress?.invoke(at.toFloat() / bytes.size)
                    }
                }
                if (c.responseCode in 200..299) link(key) else null
            } finally { c.disconnect() }
        }
    }

    /** Delete-for-everyone: the author removes the object (the server checks the author). */
    suspend fun delete(url: String) { runCatching { fn().call(hashMapOf("op" to "delete", "key" to keyOf(url))).await() } }
}
