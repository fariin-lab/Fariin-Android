package com.fariin.app.data

/**
 * Which copies in the `outgoing` folder may go (H-106). A send's plain and sealed copies sit there
 * until it lands; nothing deleted them after. A file goes once it is older than [SAFE_AGE_MS] and
 * no outbox (photo, voice, video, album, file, song, playlist) of any account on this phone still
 * names it. A send still in flight is always younger than a day.
 */
object OutgoingSweepRules {
    const val SAFE_AGE_MS = 24L * 60 * 60 * 1000

    /** The outbox stores' raw JSON as one text to look names up in ("\/" read back as "/"). */
    fun heldText(raws: Collection<String?>): String = raws.filterNotNull().joinToString("\n") { it.replace("\\/", "/") }

    fun deletable(name: String, lastModifiedMs: Long, nowMs: Long, held: String): Boolean =
        name.isNotEmpty() && nowMs - lastModifiedMs > SAFE_AGE_MS && !held.contains("/$name\"")
}
