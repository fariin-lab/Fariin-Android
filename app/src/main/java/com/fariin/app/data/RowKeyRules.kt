package com.fariin.app.data

/**
 * Which key a message row uses on screen (audit H-56). My own send keys by its clientId, so the
 * pending copy and its server echo stay one row. Anyone else's message keys by its doc id: a member
 * who reuses another message's clientId can then never replace that message on my screen.
 */
object RowKeyRules {
    /** [me] null (not signed in yet, unit tests): the old rule, clientId first. */
    fun rowId(id: String, clientId: String?, authorId: String, me: String?): String =
        if (clientId != null && (me == null || authorId == me)) clientId else id

    /** A server doc found by clientId is the echo of my send only when I wrote it. */
    fun isMyEcho(docAuthorId: String?, me: String): Boolean = docAuthorId == me
}
