package com.fariin.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every per-account store the sign-out wipe clears, by name (SessionEnd.wipeSteps). A step taken out
 * of the list fails here. A NEW per-account store gets a step in SessionEnd.wipeSteps and its name
 * below, or the next account on this phone can see it. Not in this list on purpose: the server steps
 * (calls, presence, device record, push token) run before Auth in signOutSteps; BlockList and
 * PrivacySettings follow the signed-in uid (FariinApp); PlusStore clears when another uid starts;
 * the saved Log In cards stay so an account can sign back in. Nothing here runs a step.
 */
class SessionEndWipeTest {
    private val expected = listOf(
        "activity pin",
        "notification store",
        "notifications, badge, shortcuts",
        "widget",
        "last seen lines",
        "photo outbox",
        "media origin",
        "profile photo index",
        "played voice",
        "push account",
        "official push topics",
        "local cache",
        "message store summaries",
        "hidden last line",
        "drafts",
        "voice drafts",
        "send queue",
        "delivery cursors",
        "safety key log",
        "group page previews",
        "explore cache",
        "call links",
        "sticker favorites",
        "gate memory",
        "live group calls",
        "story privacy",
        "passkeys",
        "chat media",
        "temp copies",
        "image cache",
        "decoded pictures",
        "chat search",
        "shared media",
        "verified people",
        "contact names",
        "search recents",
        "send effects",
        "avatar videos",
        "shared wallpapers",
        "chat folders",
        "plus billing",
        "official channel",
        "official stories",
        "channel stories",
        "thread seed",
        "clear cuts",
        "former groups",
        "quote store",
        "chats now",
        "bubble styles",
        "story limits",
        "chat key lockout",
        "voice player",
        "live location",
        "story outbox",
        "profile photo history",
        "glow cache",
        "chat looks",
        "story mutes",
        "disappearing default",
        "calls seen",
        "auto save media",
        "at rest key",
    )

    private fun names() = SessionEnd.wipeSteps.map { it.name }

    @Test fun everyStoreIsWiped() {
        assertEquals("missing: " + (expected - names().toSet()) + ", not listed here: " + (names() - expected.toSet()),
            expected.toSet(), names().toSet())
    }

    @Test fun eachStoreOnce() {
        assertEquals(names().size, names().toSet().size)
        assertEquals(expected.size, names().size)
    }

    @Test fun keyGoesLastAfterTheListItSeals() {
        // The at-rest key is dropped only after the chat list it seals has been handled.
        assertEquals("at rest key", names().last())
        assertEquals(true, names().indexOf("local cache") < names().indexOf("at rest key"))
    }
}
