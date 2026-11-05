package com.fariin.app.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakeForeignTest {
    private val us = "com.fariin.app"
    private fun ok(scheme: String?, auth: String?) = ShareIntake.isForeignContent(scheme, auth, us)

    @Test fun otherAppsContentIsTaken() {
        assertTrue(ok("content", "com.google.android.apps.photos.contentprovider"))
        assertTrue(ok("content", "media"))
        assertTrue(ok("CONTENT", "com.android.providers.downloads.documents"))
    }

    @Test fun ourOwnProvidersAreRefused() {
        assertFalse(ok("content", "com.fariin.app.files"))
        assertFalse(ok("content", "com.fariin.app"))
        assertFalse(ok("content", "COM.FARIIN.APP.FILES"))
        assertFalse(ok("content", "0@com.fariin.app.files"))
        assertFalse(ok("content", "x;com.fariin.app.files"))
    }

    @Test fun filesAndOddInputAreRefused() {
        assertFalse(ok("file", null))
        assertFalse(ok("file", ""))
        assertFalse(ok(null, "media"))
        assertFalse(ok("content", null))
        assertFalse(ok("content", "0@"))
    }

    @Test fun lookalikeOfOursIsAnotherApp() {
        assertTrue(ok("content", "com.fariin.application.provider"))
    }
}
