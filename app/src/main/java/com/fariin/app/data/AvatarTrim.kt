package com.fariin.app.data

/**
 * K-127: the notification face folder (NotifStore, filesDir/NotifAvatars) keeps the newest-used
 * photos only: at most [MAX_FILES] and about [MAX_BYTES]; the ones used longest ago go first.
 */
object AvatarTrim {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_FILES = 400

    data class Item(val name: String, val bytes: Long, val usedAtMs: Long)

    /** The names to delete so the rest fit, oldest use first. */
    fun toDelete(items: List<Item>, maxBytes: Long = MAX_BYTES, maxFiles: Int = MAX_FILES): List<String> {
        val newestFirst = items.sortedByDescending { it.usedAtMs }
        var total = 0L
        val out = ArrayList<String>()
        newestFirst.forEachIndexed { i, it ->
            total += it.bytes
            if (i >= maxFiles || total > maxBytes) out += it.name
        }
        return out
    }
}
