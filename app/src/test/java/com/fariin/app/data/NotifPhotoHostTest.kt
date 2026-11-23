package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifPhotoHostTest {
    @Test fun ourBucketsPass() {
        assertTrue(NotifPhotoHost.allowed("https://firebasestorage.googleapis.com/v0/b/kulan-2ef85.firebasestorage.app/o/p%2Fa.jpg?alt=media"))
        assertTrue(NotifPhotoHost.allowed("https://firebasestorage.googleapis.com:443/v0/b/kulan-2ef85.appspot.com/o/a.jpg"))
        assertTrue(NotifPhotoHost.allowed("https://storage.googleapis.com/kulan-2ef85.appspot.com/a.jpg"))
        assertTrue(NotifPhotoHost.allowed("https://kulan-2ef85.firebasestorage.app/a.jpg"))
    }

    @Test fun otherAppsAndBucketsFail() {
        assertFalse(NotifPhotoHost.allowed("https://evil.appspot.com/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://x.firebasestorage.app/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://firebasestorage.googleapis.com/v0/b/other.appspot.com/o/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://storage.googleapis.com/other-bucket/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://storage.googleapis.com/kulan-2ef85.appspot.com.evil/a.jpg"))
    }

    @Test fun schemePortAndUserInfoFail() {
        assertFalse(NotifPhotoHost.allowed("http://kulan-2ef85.firebasestorage.app/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://kulan-2ef85.firebasestorage.app:8443/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("https://me@kulan-2ef85.firebasestorage.app/a.jpg"))
        assertFalse(NotifPhotoHost.allowed("not a url"))
    }
}
