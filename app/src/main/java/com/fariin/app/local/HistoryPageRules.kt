package com.fariin.app.local

/**
 * The history pull's page order (audit L-62): newest first by (createdAt, doc id), the order
 * Firestore's startAfter(document) follows. Paging by createdAt alone skipped every message that
 * shared the page edge's millisecond, and the mark then vouched past them.
 */
object HistoryPageRules {
    data class Key(val ms: Long, val id: String)

    /** Newest first: a later time first, then a larger id first (descending order on both). */
    val newestFirst: Comparator<Key> = compareByDescending<Key> { it.ms }.thenByDescending { it.id }

    /**
     * Is [k] in the page after the one that ended at [last]? With no last row (a fresh start, or a
     * resume after a kill from the saved note) the bound is inclusive: every row at or before
     * [fromMs]. Rows read twice are filed twice, which does nothing.
     */
    fun inNextPage(k: Key, last: Key?, fromMs: Long): Boolean =
        if (last != null) newestFirst.compare(k, last) > 0 else k.ms <= fromMs
}
