package com.fariin.app.crypto

/**
 * J-5 (audit 2026-11-22): the rules for the identity key's automatic backup ([KeyBlockStore]).
 * Play services can be slow on a first start after a reinstall. A read that timed out or failed
 * used to count as "no backup", so the phone made a new key, contacts saw a key change, and the
 * new key then replaced the saved one: every older message stayed locked for good.
 *
 * - Only a read that worked and holds nothing for this account (or a phone with no Block Store at
 *   all) may lead to a new key. A timeout or an error means "try again", never "none".
 * - A saved key that differs from mine: the one the server has published for this account wins.
 *   Mine published: the stale backup is replaced. The backup's published: this phone takes it.
 *   Neither (or the server unknown): both stay, nothing is written. A key the person brought back
 *   on purpose (Backup PIN, as iOS adoptRestoredIdentity) always replaces it. iOS has no automatic
 *   key backup (its Keychain key is ThisDeviceOnly), so this rule is ours.
 * Pure and unit tested (KeyBackupRulesTest).
 */
object KeyBackupRules {
    /** How long a first-start read may take before it counts as "try again". */
    const val READ_TIMEOUT_MS = 8_000L
    /** After a "try again", calls in this window fail at once instead of waiting on Play services. */
    const val RETRY_AFTER_MS = 15_000L

    /** What one read of the backup answered for one account. */
    sealed class Read {
        /** An entry. [valid]: it decodes to a 32-byte secret and a 32-byte public key. */
        class Found(val sk: ByteArray, val pk: ByteArray, val made: Long, val valid: Boolean) : Read()
        /** The read worked and holds nothing for this account. */
        data object Empty : Read()
        /** This phone has no Block Store at all (no or broken Play services): no backup can exist. */
        data object NoService : Read()
        data object Failed : Read()
        data object TimedOut : Read()
    }

    enum class Restore { USE_BACKUP, MAKE_NEW, TRY_AGAIN }

    /** No key on this phone: what to do with the backup's answer. [pairOk]: the found pair belongs together. */
    fun restoreStep(read: Read, pairOk: Boolean): Restore = when (read) {
        is Read.Found -> if (read.valid && pairOk) Restore.USE_BACKUP else Restore.MAKE_NEW
        Read.Empty, Read.NoService -> Restore.MAKE_NEW
        Read.Failed, Read.TimedOut -> Restore.TRY_AGAIN
    }

    /** RESTORE_OTHER: the backup holds the key the server has published; this phone should take it. */
    enum class Write { WRITE, SKIP, KEEP_OTHER, RESTORE_OTHER }

    /** Saving my key: what the backup's current entry allows. [adopted]: the person chose this key.
     *  [published]: the public key the server holds for this account now (null = not known). */
    fun writeStep(existing: Read, pk: ByteArray, made: Long, adopted: Boolean, published: ByteArray? = null): Write = when (existing) {
        is Read.Found -> when {
            !existing.valid -> Write.WRITE
            existing.pk.contentEquals(pk) ->
                if (made == existing.made || made == 0L) Write.SKIP else Write.WRITE
            adopted -> Write.WRITE
            published != null && published.contentEquals(pk) -> Write.WRITE
            published != null && published.contentEquals(existing.pk) -> Write.RESTORE_OTHER
            else -> Write.KEEP_OTHER
        }
        Read.Empty -> Write.WRITE
        // Not known what is saved: write nothing now, the next start tries again.
        Read.NoService, Read.Failed, Read.TimedOut -> Write.SKIP
    }

    /** Play services status codes that mean this phone has no Block Store (not a passing failure). */
    fun noService(statusCode: Int): Boolean = statusCode in NO_SERVICE_CODES

    // CommonStatusCodes.API_NOT_CONNECTED, ConnectionResult.SERVICE_MISSING / SERVICE_INVALID.
    private val NO_SERVICE_CODES = setOf(17, 1, 9)
}
