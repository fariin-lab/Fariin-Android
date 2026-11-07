package com.fariin.app.ui.thread

import androidx.compose.runtime.mutableStateMapOf
import com.fariin.app.data.Message

/**
 * A DOUBLE TAP'S PILL SHOWS AT ONCE (iOS b6256acc, owner 2026-11-07: "react badge appearing late" in
 * chats and groups; channels were already instant). The reaction is sealed and written first (a
 * group asks for its members), so the pill waited for the write's echo. Here my pick is drawn the
 * moment the second tap lands and held until the message itself says the same, or for a few
 * seconds at most (a refused write then shows what the server kept).
 */
internal object ReactNow {
    private const val HOLD_MS = 8_000L

    /** Message id to my wanted emoji ("" = none). */
    private val wanted = mutableStateMapOf<String, String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** My reaction as shown: the one on its way first, else the message's own. */
    fun mine(m: Message, me: String): String? = wanted[m.id]?.ifEmpty { null } ?: if (m.id in wanted) null else m.reactions[me]

    /** [emoji] null = mine taken off. */
    fun set(m: Message, emoji: String?) {
        val v = emoji ?: ""
        wanted[m.id] = v
        main.postDelayed({ if (wanted[m.id] == v) wanted.remove(m.id) }, HOLD_MS)
    }

    /** The message with my pick on its way laid over it; the same message once it agrees. */
    fun shown(m: Message, me: String): Message {
        val w = wanted[m.id] ?: return m
        val now = m.reactions[me] ?: ""
        if (now == w) return m
        return m.copy(reactions = if (w.isEmpty()) m.reactions - me else m.reactions + (me to w))
    }
}
