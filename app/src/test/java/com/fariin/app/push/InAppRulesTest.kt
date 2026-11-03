package com.fariin.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppRulesTest {

    private fun spec(
        key: String = "a_me", opensChat: Boolean = true, channel: NotifChannel = NotifChannel.MESSAGES,
        title: String = "Amina", body: String = "Hello", plain: Boolean = false, isGroup: Boolean = false,
        senderPhoto: String? = "https://x/amina.jpg", groupPhoto: String? = null,
    ) = NotifDecision.Show(NotifSpec(key, opensChat, channel, title, body, body, plain, "a", "Amina", isGroup,
        if (isGroup) title else null, senderPhoto, groupPhoto, "m1", null))

    private val on = InAppPrefs()

    @Test fun skippedPushIsNothing() {
        assertEquals(InAppRoute.Nothing, InAppRules.route(NotifDecision.Skip("chat is open"), true, false, on))
        assertEquals(InAppRoute.Nothing, InAppRules.route(NotifDecision.Skip("chat is open"), false, false, on))
    }

    @Test fun behindIsTheSystemNotification() {
        assertEquals(InAppRoute.System, InAppRules.route(spec(), inFront = false, locked = false, prefs = on))
    }

    @Test fun inFrontChatMessageIsTheBannerWithSoundAndBuzz() {
        val r = InAppRules.route(spec(), true, false, on) as InAppRoute.InApp
        assertEquals(InAppBanner("a_me", "Amina", "Hello", "https://x/amina.jpg"), r.banner)
        assertTrue(r.sound); assertTrue(r.vibrate)
    }

    @Test fun inFrontNonChatAndCallsKeepTheSystemNotification() {
        assertEquals(InAppRoute.System, InAppRules.route(spec(key = "fariin", opensChat = false), true, false, on))
        assertEquals(InAppRoute.System, InAppRules.route(spec(channel = NotifChannel.CALLS), true, false, on))
    }

    @Test fun eachSwitchOnItsOwn() {
        val noPreview = InAppRules.route(spec(), true, false, InAppPrefs(preview = false)) as InAppRoute.InApp
        assertNull(noPreview.banner); assertTrue(noPreview.sound); assertTrue(noPreview.vibrate)
        val noSound = InAppRules.route(spec(), true, false, InAppPrefs(sound = false)) as InAppRoute.InApp
        assertEquals(false, noSound.sound); assertTrue(noSound.vibrate); assertTrue(noSound.banner != null)
        val noBuzz = InAppRules.route(spec(), true, false, InAppPrefs(vibrate = false)) as InAppRoute.InApp
        assertEquals(false, noBuzz.vibrate); assertTrue(noBuzz.sound)
        val allOff = InAppRules.route(spec(), true, false, InAppPrefs(false, false, false)) as InAppRoute.InApp
        assertNull(allOff.banner); assertEquals(false, allOff.sound); assertEquals(false, allOff.vibrate)
    }

    @Test fun appLockHidesTheBannerButNotTheSound() {
        val r = InAppRules.route(spec(), true, locked = true, prefs = on) as InAppRoute.InApp
        assertNull(r.banner); assertTrue(r.sound); assertTrue(r.vibrate)
    }

    @Test fun messagePreviewOffShowsThePushWordsAndTheLocalFace() {
        val r = InAppRules.route(spec(title = "Fariin", body = "New message", plain = true, senderPhoto = null),
            true, false, on, localPhoto = "https://x/local.jpg") as InAppRoute.InApp
        assertEquals(InAppBanner("a_me", "Fariin", "New message", "https://x/local.jpg"), r.banner)
    }

    @Test fun groupShowsTheGroupFace() {
        val r = InAppRules.route(spec(key = "g1", title = "Family", body = "Omar: hi", isGroup = true, groupPhoto = "https://x/g.jpg"),
            true, false, on) as InAppRoute.InApp
        assertEquals("https://x/g.jpg", r.banner?.photo)
        assertEquals("Omar: hi", r.banner?.body)
    }
}
