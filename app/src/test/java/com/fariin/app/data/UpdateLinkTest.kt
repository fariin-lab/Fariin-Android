package com.fariin.app.data

import com.fariin.app.system.UpdateLink
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateLinkTest {
    private val play = "https://play.google.com/store/apps/details?id=com.fariin.app"

    @Test fun appleOrEmptyLinksOpenPlay() {
        assertEquals(play, UpdateLink.forAndroid("", "com.fariin.app"))
        assertEquals(play, UpdateLink.forAndroid("https://apps.apple.com/app/id123", "com.fariin.app"))
        assertEquals(play, UpdateLink.forAndroid("https://itunes.apple.com/app/id123", "com.fariin.app"))
        assertEquals(play, UpdateLink.forAndroid("itms-apps://apps.apple.com/app/id123", "com.fariin.app"))
    }

    @Test fun aCampaignsOwnAndroidLinkIsKept() {
        val own = "https://play.google.com/store/apps/details?id=com.fariin.app&referrer=x"
        assertEquals(own, UpdateLink.forAndroid(own, "com.fariin.app"))
        assertEquals("https://fariin.com/android", UpdateLink.forAndroid("https://fariin.com/android", "com.fariin.app"))
    }
}
