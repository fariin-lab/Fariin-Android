package com.fariin.app.data

/**
 * Which photo links a notification or the widget may download (J-44). Only this project's own
 * storage buckets: a member can put any string in a group's photos map, and a photo on their own
 * server would tell them each reader's IP and the time of every notification. https on port 443,
 * no user info, and the host or the path pinned to our bucket (as iOS).
 */
object NotifPhotoHost {
    private const val PROJECT = "kulan-2ef85"
    private val BUCKETS = setOf("$PROJECT.firebasestorage.app", "$PROJECT.appspot.com")

    fun allowed(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (u.scheme?.lowercase() != "https") return false
        if (u.rawUserInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        val host = u.host?.lowercase() ?: return false
        val path = u.rawPath ?: ""
        return when (host) {
            // https://firebasestorage.googleapis.com/v0/b/<bucket>/o/...
            "firebasestorage.googleapis.com" -> BUCKETS.any { path.startsWith("/v0/b/$it/o/") }
            // https://storage.googleapis.com/<bucket>/...
            "storage.googleapis.com" -> BUCKETS.any { path.startsWith("/$it/") }
            else -> host in BUCKETS
        }
    }
}
