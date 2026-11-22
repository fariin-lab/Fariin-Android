package com.fariin.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadMatchTest {
    @Test fun storedObjectWithOurMd5IsOurUpload() {
        // MD5 of "hello" is 5d41402abc4b2a76b9719d911017c592, base64 XUFAKrxLKna5cZ2REBfFkg==.
        assertTrue(MediaRules.sameUpload("XUFAKrxLKna5cZ2REBfFkg==", "hello".toByteArray()))
    }

    @Test fun aFailedSongOrPlaylistIsResentByItselfButNotAForward() {
        assertTrue(MediaRules.autoRetries(failed = true, hasMedia = true, forwarded = false, canResend = true, triedAlready = false))
        assertFalse(MediaRules.autoRetries(failed = true, hasMedia = true, forwarded = true, canResend = true, triedAlready = false))
        assertFalse(MediaRules.autoRetries(failed = true, hasMedia = true, forwarded = false, canResend = false, triedAlready = false))
        assertFalse(MediaRules.autoRetries(failed = true, hasMedia = true, forwarded = false, canResend = true, triedAlready = true))
        assertFalse(MediaRules.autoRetries(failed = false, hasMedia = true, forwarded = false, canResend = true, triedAlready = false))
        assertFalse(MediaRules.autoRetries(failed = true, hasMedia = false, forwarded = false, canResend = true, triedAlready = false))
    }

    @Test fun anotherObjectOrNoHashIsNot() {
        assertFalse(MediaRules.sameUpload("XUFAKrxLKna5cZ2REBfFkg==", "hellO".toByteArray()))
        assertFalse(MediaRules.sameUpload(null, "hello".toByteArray()))
        assertFalse(MediaRules.sameUpload("", ByteArray(0)))
    }
}
