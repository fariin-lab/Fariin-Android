package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardPhotoTest {
    @Test fun aProfileCardIsSentWithNoImage() {
        assertFalse(ProfileCardPhoto.senderCarriesImage(true))
        assertTrue(ProfileCardPhoto.senderCarriesImage(false))
    }

    @Test fun anOldCardWithAnImageShowsOnlyTheProfileReadPhoto() {
        assertEquals("fariin-photo://profiles/b.jpg", ProfileCardPhoto.shown(true, "https://x/thumb.jpg", "fariin-photo://profiles/b.jpg", false))
        assertNull(ProfileCardPhoto.shown(true, "https://x/thumb.jpg", null, false))
        assertNull(ProfileCardPhoto.shown(true, "https://x/thumb.jpg", "", false))
    }

    @Test fun noPhotoWhenIBlockedThem() {
        assertNull(ProfileCardPhoto.shown(true, null, "fariin-photo://profiles/b.jpg", true))
    }

    @Test fun otherCardsKeepTheirPicture() {
        assertEquals("https://x/a.jpg", ProfileCardPhoto.shown(false, "https://x/a.jpg", null, false))
    }
}
