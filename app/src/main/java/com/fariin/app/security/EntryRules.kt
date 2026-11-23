package com.fariin.app.security

import com.fariin.app.channel.ChannelRules

/**
 * Pure rules for the ways into the app (links, notification taps, shortcuts, the widget) and what
 * App Lock lets through. No Android here: unit tested in EntryRulesTest.
 */
object EntryRules {
    /** A tapped link nudge counts for this long after the server marked it (and no longer). */
    const val NUDGE_MAX_AGE_MS = 10 * 60_000L

    /** %XX to its bytes, read as UTF-8. Null when a % has no two hex digits after it. No "+" to space. */
    fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 3 > s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    /**
     * kulan://wchat/<value> (iOS widgetChat): the chat to open, or null. Decoded first, so "a%2Fb"
     * is seen as "a/b" and refused, then checked as a document id, then only a chat this phone's
     * widget lists opens (none listed: nothing opens).
     */
    fun wchatCid(raw: String, listed: Set<String>): String? {
        val cid = percentDecode(raw) ?: return null
        if (!ChannelRules.safeId(cid)) return null
        return cid.takeIf { it in listed }
    }

    /**
     * The intent that started the activity is handled only on a fresh start. Not after a recreate
     * or a restore after the app was killed ([restored] = a saved state came back), and not when
     * Android relaunched it from Recents ([fromHistory]): that would open the same link, chat or
     * ring a second time.
     */
    fun takeLaunchIntent(restored: Boolean, fromHistory: Boolean): Boolean = !restored && !fromHistory

    /**
     * An activity stopped: is the app now out of sight? Never for a configuration change (dark
     * mode, a new size): the same screen comes straight back.
     */
    fun leftApp(started: Int, appStarted: Int, callInPip: Boolean, stoppedIsCall: Boolean, changingConfig: Boolean): Boolean =
        !changingConfig && (started == 0 || (appStarted == 0 && callInPip && !stoppedIsCall))

    /** Chat shortcuts (launcher long-press, share sheet) carry names and faces: none while App Lock is on. */
    fun chatShortcuts(appLockOn: Boolean): Boolean = !appLockOn

    /** A tapped call link starts the call lobby only once App Lock is passed (or is off). */
    fun mayStartLobby(appLockOn: Boolean, unlocked: Boolean): Boolean = !appLockOn || unlocked

    /**
     * A link nudge ("<name> wants to talk") is real only when the server marked it on my invite
     * (callLinks/<room>/invites/<me>, nudgedAt.<caller> in ms) within [NUDGE_MAX_AGE_MS].
     */
    fun nudgeVerified(nudgedAt: Map<*, *>?, callerUid: String, now: Long): Boolean {
        if (callerUid.isEmpty()) return false
        val at = (nudgedAt?.get(callerUid) as? Number)?.toLong() ?: return false
        // A minute of clock difference between the server and this phone is allowed.
        return now - at in -60_000L..NUDGE_MAX_AGE_MS
    }

    /**
     * The call screen over the phone's own lock screen: only mute, speaker, camera and hang up.
     * Add People, the link rows and the host tools wait until the phone is unlocked.
     */
    fun callExtrasShown(keyguardLocked: Boolean): Boolean = !keyguardLocked
}
