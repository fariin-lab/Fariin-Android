package com.fariin.app.media

import com.fariin.app.data.Message

/**
 * The rest of a run of voice notes (iOS VoiceNotePlayer.followOn): when one note ends, the next one
 * sent straight after it plays by itself, also after the chat was left. The open chat publishes its
 * messages here; [VoicePlayer] asks for the next note when one finishes.
 *
 * A run is what was sent one after another: a line the app wrote about the chat (a system notice) does
 * not end it, anything else that is not a voice note does, and so does a one-time note (playing it by
 * itself would spend its single listen on someone who never chose to open it).
 */
object VoiceRun {
    @Volatile private var cid: String? = null
    @Volatile private var messages: List<Message> = emptyList()
    /** The chat's media loader, kept with its messages so the next note can be fetched after leaving. */
    @Volatile var media: ChatMedia? = null
        private set

    fun publish(cid: String, messages: List<Message>, media: ChatMedia) {
        this.cid = cid
        this.messages = messages
        this.media = media
    }

    /** The voice note right after [rowId] in [cid], or null when the run ends there. */
    fun nextAfter(cid: String, rowId: String): Message? {
        if (cid != this.cid) return null
        val list = messages
        val i = list.indexOfFirst { it.rowId == rowId }
        if (i < 0) return null
        for (m in list.subList(i + 1, list.size)) {
            if (m.isSystem) continue
            if (m.deleted || m.mediaKind != Message.MediaKind.VOICE || m.viewOnce) return null
            return m
        }
        return null
    }
}
