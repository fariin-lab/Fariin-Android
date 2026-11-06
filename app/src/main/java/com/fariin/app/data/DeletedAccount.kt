package com.fariin.app.data

/**
 * iOS ThreadRepository.otherAccountDeleted (decision D15): a 1:1 whose other person deleted their
 * account. Their profile document is gone once the purge runs; inside the grace period it still
 * exists, so the chat stays open until then.
 */
object DeletedAccount {
    /** iOS deletedAccountBar: the words in the composer's place. */
    const val BAR = "This person is no longer on Fariin"

    /**
     * What one snapshot of their profile says. A missing document counts only when the server said
     * so: a cold cache also reports "does not exist" on its first snapshot, which is not a deletion.
     * In that case the answer we had stays.
     */
    fun fromProfile(exists: Boolean, fromCache: Boolean, was: Boolean): Boolean =
        if (exists || !fromCache) !exists else was
}
