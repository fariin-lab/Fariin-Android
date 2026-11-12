package com.fariin.app.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The person's own Google Drive, app folder only (drive.appdata: Fariin sees nothing else in it, and
 * the person sees only its size). Plain REST with the access token Google's authorization gives.
 */
object Drive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    sealed class Auth {
        class Token(val value: String) : Auth()
        /** Google must ask the person first: launch this from a screen. */
        class Ask(val intent: PendingIntent) : Auth()
        object Failed : Auth()
    }

    /** Silent when already allowed; [Auth.Ask] the first time (Google's own screen). */
    suspend fun authorize(ctx: Context): Auth = runCatching {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val r = Identity.getAuthorizationClient(ctx).authorize(req).await()
        when {
            r.hasResolution() -> r.pendingIntent?.let { Auth.Ask(it) } ?: Auth.Failed
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Failed
        }
    }.getOrDefault(Auth.Failed)

    /** The token from the result of Google's screen (after [Auth.Ask]). */
    fun tokenFrom(activity: Activity, data: android.content.Intent?): String? = runCatching {
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    /** [uid] = the Fariin account the file belongs to (several accounts can share one Google Drive). */
    class DriveFile(val id: String, val name: String, val set: String?, val size: Long, val uid: String? = null)

    class DriveError(val code: Int, message: String) : Exception(message)

    private suspend fun call(token: String, method: String, url: String, body: ByteArray? = null,
                             contentType: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; contentType?.let { c.setRequestProperty("Content-Type", it) }; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw DriveError(code, bytes.toString(Charsets.UTF_8).take(300))
        bytes
    }

    /** The Google account the token belongs to (Drive's about.user). */
    suspend fun accountEmail(token: String): String? = runCatching {
        JSONObject(call(token, "GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)").toString(Charsets.UTF_8))
            .optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
    }.getOrNull()

    /** Every file of the app folder, with its backup set (appProperties.set). */
    suspend fun list(token: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000" +
                "&fields=" + URLEncoder.encode("nextPageToken,files(id,name,size,appProperties)", "UTF-8") +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val o = JSONObject(call(token, "GET", url).toString(Charsets.UTF_8))
            val files = o.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                val props = f.optJSONObject("appProperties")
                out.add(DriveFile(f.getString("id"), f.getString("name"), props?.optString("set"), f.optLong("size"),
                    props?.optString("uid")?.ifEmpty { null }))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    suspend fun upload(token: String, name: String, set: String, bytes: ByteArray, uid: String): String {
        // Drive's one-request upload takes 5 MB at most: anything bigger (a video) goes the resumable way.
        if (bytes.size > 1024 * 1024) return resumable(token, name, set, bytes, uid)
        val boundary = "fariin" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("set", set).put("uid", uid)).toString()
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val r = call(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", body,
            "multipart/related; boundary=$boundary")
        return JSONObject(r.toString(Charsets.UTF_8)).getString("id")
    }

    /** Drive's resumable upload: open a session, then send the bytes in 8 MB pieces. */
    private suspend fun resumable(token: String, name: String, set: String, bytes: ByteArray, uid: String): String = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("set", set).put("uid", uid)).toString().toByteArray()
        val open = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id").openConnection() as HttpURLConnection
        open.requestMethod = "POST"; open.doOutput = true; open.connectTimeout = 20_000; open.readTimeout = 60_000
        open.setRequestProperty("Authorization", "Bearer $token")
        open.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        open.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
        open.setRequestProperty("X-Upload-Content-Length", bytes.size.toString())
        open.outputStream.use { it.write(meta) }
        if (open.responseCode !in 200..299) throw DriveError(open.responseCode, open.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)?.take(300) ?: "")
        val session = open.getHeaderField("Location") ?: throw DriveError(500, "no upload session")
        open.disconnect()
        val piece = 8 * 1024 * 1024
        var at = 0
        while (true) {
            val end = minOf(at + piece, bytes.size)
            val c = URL(session).openConnection() as HttpURLConnection
            c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(end - at)
            c.setRequestProperty("Content-Range", "bytes $at-${end - 1}/${bytes.size}")
            c.outputStream.use { it.write(bytes, at, end - at) }
            val code = c.responseCode
            if (code == 200 || code == 201) {
                val id = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).getString("id")
                c.disconnect(); return@withContext id
            }
            if (code != 308) { val e = c.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)?.take(300) ?: ""; c.disconnect(); throw DriveError(code, e) }
            // 308: Drive says how much it has; continue from there.
            at = c.getHeaderField("Range")?.substringAfterLast('-')?.toIntOrNull()?.plus(1) ?: end
            c.disconnect()
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    suspend fun download(token: String, id: String): ByteArray =
        call(token, "GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media")

    suspend fun delete(token: String, id: String) {
        runCatching { call(token, "DELETE", "https://www.googleapis.com/drive/v3/files/$id") }
    }
}
