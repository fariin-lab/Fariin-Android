package com.fariin.app.data

import com.fariin.app.channel.ChannelRules

/**
 * Channel Settings > Reactions > Selected Emoji, many at once (iOS 148c67ef, owner 2026-11-06:
 * "the x gets tapped by mistake; adding one by one is too slow"). The emoji sheet stays open and a
 * tap turns an emoji on or off in the list; the page saves once, when the sheet closes, and only if
 * something changed. The last one cannot be turned off here: none at all is "No Reactions".
 */
object ReactionPicks {
    const val KEEP_ONE = "Keep at least one. To turn reactions off, choose No Reactions."

    /** The list after a tap, and a line to show when the tap was refused (the list is then unchanged). */
    data class Toggle(val list: List<String>, val note: String?)

    fun isPicked(list: List<String>, e: String): Boolean = ChannelRules.containsEmoji(list, e)

    fun toggle(list: List<String>, e: String, max: Int = ChannelReactions.MAX): Toggle {
        val i = list.indexOfFirst { ChannelRules.sameEmoji(it, e) }
        return when {
            i >= 0 && list.size <= 1 -> Toggle(list, KEEP_ONE)
            i >= 0 -> Toggle(list.filterIndexed { k, _ -> k != i }, null)
            list.size >= max -> Toggle(list, "Up to $max reactions.")
            else -> Toggle(list + e, null)
        }
    }

    /** The sheet's title while picking: "6 of 12". */
    fun title(n: Int, max: Int = ChannelReactions.MAX) = "$n of $max"

    /** Whether closing the sheet has anything to save. */
    fun changed(mode: String, list: List<String>, savedMode: String, savedList: List<String>): Boolean =
        mode != savedMode || list != savedList

    /** Under the bar: there are no x badges any more, so it says what a tap does. */
    fun footer(list: List<String>): String =
        if (list.isEmpty()) "Pick at least one, or reactions will be off." else "Tap to choose reactions, up to ${ChannelReactions.MAX}"
}
