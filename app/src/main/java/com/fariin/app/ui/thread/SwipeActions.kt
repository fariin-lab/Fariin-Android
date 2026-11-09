package com.fariin.app.ui.thread

import android.content.Context
import com.fariin.app.R
import com.fariin.app.data.Message
import com.fariin.app.data.MessageRules

/**
 * Swipe Actions (iOS SwipeActionMenu / MultiSwipe): swipe a message left past the reply point and,
 * still holding, slide up or down to another action. Off by default, for everyone (owner 2026-11-09:
 * "all users Android and iOS"). Off still leaves the plain reply swipe.
 */
object SwipeActions {
    private const val PREFS = "fariin_swipe_actions"
    const val ON = "swipeActions.on"
    const val UP = "swipeActions.up"
    const val DOWN = "swipeActions.down"

    enum class Pick(val label: String) { COPY("Copy"), FORWARD("Forward"), SHARE("Share"), QUOTE("Quote"), NONE("None") }

    fun allowed(me: String): Boolean = me.isNotEmpty()
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun on(c: Context, me: String): Boolean = allowed(me) && prefs(c).getBoolean("$ON.$me", false)
    fun setOn(c: Context, me: String, v: Boolean) = prefs(c).edit().putBoolean("$ON.$me", v).apply()
    fun up(c: Context, me: String): Pick = pick(prefs(c).getString("$UP.$me", null), Pick.FORWARD)
    fun down(c: Context, me: String): Pick = pick(prefs(c).getString("$DOWN.$me", null), Pick.COPY)
    fun setUp(c: Context, me: String, p: Pick) = prefs(c).edit().putString("$UP.$me", p.name).apply()
    fun setDown(c: Context, me: String, p: Pick) = prefs(c).edit().putString("$DOWN.$me", p.name).apply()
    private fun pick(s: String?, d: Pick) = Pick.entries.firstOrNull { it.name == s } ?: d

    const val FOOTER_OFF = "Swipe a message left to reply, then slide up or down for more actions."
    const val FOOTER_ON = "Swipe a message left to reply. Keep holding and slide up or down for the actions above. " +
        "Photos and videos save instead of copying, and Quote works in channels only."

    /** One slot of the capsule: null run = dimmed and skipped. */
    class Slot(val icon: Int, val label: String, val run: (() -> Unit)?)

    /** No capsule on photos, albums and videos (iOS multiSwipeMenu nil): they keep the plain reply swipe. */
    fun hasMenu(m: Message): Boolean = m.mediaKind != Message.MediaKind.PHOTO && m.mediaKind != Message.MediaKind.VIDEO &&
        m.mediaKind != Message.MediaKind.ALBUM && !m.deleted && !m.isSystem

    /**
     * What [p] does for [m], with the long-press menu's own fallbacks (Copy on media saves), or a
     * dimmed slot when nothing applies.
     */
    fun slot(p: Pick, m: Message, restricted: Boolean, quoteOk: Boolean,
             copy: (String) -> Unit, save: () -> Unit, forward: () -> Unit, share: (String) -> Unit, quote: () -> Unit): Slot? {
        val card = com.fariin.app.data.CardRules.isCard(m.text)
        return when (p) {
            Pick.NONE -> null
            Pick.COPY -> when {
                restricted -> Slot(R.drawable.ic_sf_doc_on_doc, "Copy", null)
                canSaveToGallery(m) -> Slot(R.drawable.ic_save_down, "Save", save)
                MessageRules.canCopy(m) && !card -> Slot(R.drawable.ic_sf_doc_on_doc, "Copy") { copy(m.text) }
                else -> Slot(R.drawable.ic_sf_doc_on_doc, "Copy", null)
            }
            Pick.FORWARD -> Slot(R.drawable.ic_menu_forward, "Forward", if (!restricted && MessageRules.canForward(m)) forward else null)
            Pick.SHARE -> Slot(R.drawable.ic_sf_share, "Share",
                if (!restricted && !card && m.mediaKind == null && m.text.isNotBlank()) ({ share(m.text) }) else null)
            Pick.QUOTE -> Slot(R.drawable.ic_quote, "Quote", if (!restricted && quoteOk && MessageRules.canQuote(m)) quote else null)
        }
    }
}
