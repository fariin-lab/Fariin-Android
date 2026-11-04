package com.fariin.app.data

import com.fariin.app.crypto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Story mentions in the chat (iOS StoryMentions). After a story has posted, every @name in its
 * caption that belongs to somebody who can SEE the story and already has a chat with me gets one
 * message there: an ordinary sealed text ("Mentioned in a story", what the list and a notification
 * show) carrying the plain `storyMention` marker the chat draws as a card. Never my own name, never
 * somebody I blocked, never a first message or a new chat. Its id is fixed per story and person, so
 * a retried post rewrites the same message instead of sending a second.
 */
object StoryMentions {
    const val PREVIEW_TEXT = "Mentioned in a story"
    private val handleRe = Regex("@([A-Za-z][A-Za-z0-9_.]{0,29})")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The lowercased @names in a caption (pure). */
    fun handles(caption: String): Set<String> =
        if (!caption.contains('@')) emptySet() else handleRe.findAll(caption).map { it.groupValues[1].lowercase() }.toSet()

    fun send(storyId: String, caption: String, thumbUrl: String, recipients: Set<String>, me: String) {
        val names = handles(caption)
        if (names.isEmpty() || storyId.isEmpty() || me.isEmpty()) return
        scope.launch {
            val people = FirestorePeople(me, CryptoHub.crypto)
            val sent = HashSet<String>()
            for (h in names) {
                val u = runCatching { people.byHandle(h) }.getOrNull() ?: continue
                if (u.uid == me || u.uid in sent || u.uid !in recipients) continue
                if (BlockList.snapshot.entries.containsKey(u.uid)) continue
                val cid = Wire.directCid(me, u.uid)
                val known = CryptoHub.listedConversation(cid)?.let { !it.isGroup && (it.startedBy.isEmpty() || it.accepted) } ?: false
                if (!known) continue
                sent += u.uid
                runCatching {
                    ThreadRepository(cid, me, CryptoHub.crypto, scope)
                        .sendStoryMention(PREVIEW_TEXT, "storymention-$storyId-${u.uid}", Message.StoryMentionRef(storyId, thumbUrl))
                }.onFailure { android.util.Log.w("StoryMentions", "send failed", it) }
            }
        }
    }
}
