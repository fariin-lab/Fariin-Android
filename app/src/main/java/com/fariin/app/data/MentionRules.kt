package com.fariin.app.data

/**
 * Group @mentions, as iOS ThreadView ("@mentions (groups)"): the "@word" typed just before the
 * cursor opens a list of members, a pick puts "@Name " in its place, and at send the uids whose
 * "@Name" is still in the text go out in the clear as `mentions` (the words stay sealed). The
 * server reads that list for the "NAME mentioned you" push, even in a muted group.
 */
object MentionRules {
    /** iOS mentionPicker: at most five rows. */
    const val MAX_ROWS = 5

    private val token = Regex("@[^\\s@]*$")

    /** The words before the cursor, the cursor clamped to the text. */
    fun head(text: String, caret: Int): String = text.substring(0, caret.coerceIn(0, text.length))

    /** iOS mentionQuery: what follows the "@" being typed just before the cursor, or null. */
    fun query(head: String): String? = token.find(head)?.value?.drop(1)

    data class Person(val uid: String, val name: String)

    /** iOS mentionCandidates: members but me, with a name, whose name holds the query (any case). */
    fun candidates(members: List<String>, me: String, names: Map<String, String>, query: String): List<Person> {
        val q = query.lowercase()
        return members.filter { it != me }.mapNotNull { uid ->
            val n = names[uid] ?: ""
            if (n.isEmpty()) null else if (q.isEmpty() || n.lowercase().contains(q)) Person(uid, n) else null
        }
    }

    /**
     * iOS insertMention: the "@query" before the cursor becomes "@Name", with a space after it
     * unless the text after the cursor starts with one. Returns the new text and the cursor
     * (just after the name), or null when there is no "@" to replace.
     */
    fun insert(text: String, caret: Int, name: String): Pair<String, Int>? {
        val h = head(text, caret)
        val tail = text.substring(h.length)
        val m = token.find(h) ?: return null
        val put = if (tail.firstOrNull()?.isWhitespace() == true) "@$name" else "@$name "
        val newHead = h.substring(0, m.range.first) + put
        return (newHead + tail) to newHead.length
    }

    /** iOS mentionMap: name to the uids picked under it, the latest last. */
    fun addPick(picks: Map<String, List<String>>, name: String, uid: String): Map<String, List<String>> =
        picks + (name to ((picks[name] ?: emptyList()) - uid + uid))

    /**
     * iOS resolveMentions: the uids whose "@Name" is really in the text. Longest names first, each
     * match taken out of the text so a shorter name cannot match inside it, and a match must not
     * run on into a letter or digit ("@Sam" inside "@Samir" is not Sam).
     */
    fun resolve(text: String, picks: Map<String, List<String>>): List<String> {
        var rest = text
        val uids = mutableListOf<String>()
        for ((name, ids) in picks.entries.sortedByDescending { it.key.length }) {
            val tok = "@$name"
            var found = 0
            var from = 0
            while (true) {
                val at = rest.indexOf(tok, from)
                if (at < 0) break
                val end = at + tok.length
                if (end < rest.length && rest[end].isLetterOrDigit()) {
                    from = end
                } else {
                    found++
                    rest = rest.substring(0, at) + " " + rest.substring(end)
                    from = 0
                }
            }
            // As many people as the name is still in the text, the latest picks first.
            for (uid in ids.takeLast(found)) if (uid !in uids) uids.add(uid)
        }
        return uids
    }

    /** iOS send: only people still in the group are tagged. */
    fun forSend(text: String, picks: Map<String, List<String>>, members: List<String>): List<String> =
        resolve(text, picks).filter { it in members }

    /**
     * iOS BubbleText: where each "@Name" of the message's mentions sits in its text, drawn
     * semibold. A name not known here makes a bare "@", which is skipped.
     */
    fun ranges(text: String, mentions: List<String>, names: Map<String, String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (uid in mentions) {
            val tok = "@" + (names[uid] ?: "")
            if (tok.length <= 1) continue
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(tok, from)
                if (at < 0) break
                out.add(at until at + tok.length)
                from = at + tok.length
            }
        }
        return out
    }
}
