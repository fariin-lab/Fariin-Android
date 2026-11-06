package com.fariin.app.data

/**
 * The text story's link card, for OUR links (iOS StoryTextLinkPreview.action, owner 2026-11-06).
 *
 * A channel's or group's link (the card's line reads "N followers" / "N members") gets a Join pill;
 * a person's link (the line reads "@handle") gets a Chat pill. In the story viewer such a link closes
 * the story and opens in the app (the channel or group page, or the chat with the person); every
 * other link still opens in the in-app browser.
 */
object StoryLinkCardRules {
    enum class Pill(val label: String) { JOIN("Join"), CHAT("Chat") }

    private val SIZE_LINE = Regex("^[0-9.,KMB]+ (members?|followers?)$")

    /** fariin.com/u/<handle> or a bare fariin.com/<name>: a person's (or a public group's) name. */
    private fun isNameLink(url: String): Boolean =
        QrRules.handleFrom(url) != null || QrRules.bareNameFrom(url) != null

    /** One of our person, group or channel links: these open in the app, not the browser. */
    fun opensInApp(url: String): Boolean =
        isNameLink(url) || com.fariin.app.ui.people.GroupInviteLink.code(url) != null

    /** The pill the card carries, or null for any other link. */
    fun pill(url: String, desc: String): Pill? {
        val d = desc.trim()
        return when {
            SIZE_LINE.matches(d) && opensInApp(url) -> Pill.JOIN
            d.startsWith("@") && isNameLink(url) -> Pill.CHAT
            else -> null
        }
    }
}
