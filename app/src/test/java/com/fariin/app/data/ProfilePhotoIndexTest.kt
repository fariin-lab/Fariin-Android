package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS Conversation.photoUrl: the newer of the chat's mirror and the person's own photo. */
class ProfilePhotoIndexTest {
    private val old = "https://x/o/avatars%2Fu%2Fa.jpg?alt=media&v=1000"
    private val new = "https://x/o/avatars%2Fu%2Fb.jpg?alt=media&v=2000"

    @Test fun stampReadsV() {
        assertEquals(2000.0, ProfilePhotoIndex.stamp(new), 0.0)
        assertEquals(0.0, ProfilePhotoIndex.stamp("https://x/a.jpg"), 0.0)
        assertEquals(0.0, ProfilePhotoIndex.stamp("https://x/a.jpg?alt=media&v=abc"), 0.0)
    }

    @Test fun newerWins() {
        assertEquals(new, ProfilePhotoIndex.newer(old, new))
        assertEquals(new, ProfilePhotoIndex.newer(new, old))
        assertEquals(old, ProfilePhotoIndex.newer(old, null))
        assertEquals(new, ProfilePhotoIndex.newer(null, new))
        assertNull(ProfilePhotoIndex.newer(null, null))
        // Same stamp: the mirror stays.
        assertEquals(old, ProfilePhotoIndex.newer(old, old.replace("a.jpg", "c.jpg")))
    }

    @Test fun audienceIsRespected() {
        ProfilePhotoIndex.record("u1", mapOf("name" to "A", "photoUrl" to new, "privacy" to mapOf("photo" to "contacts")))
        assertNull(ProfilePhotoIndex.photo("u1", contact = false))
        assertEquals(new, ProfilePhotoIndex.photo("u1", contact = true))
        ProfilePhotoIndex.record("u2", mapOf("name" to "B", "photoUrl" to new, "privacy" to mapOf("photo" to "nobody")))
        assertNull(ProfilePhotoIndex.photo("u2", contact = true))
        // A partial read (no name) is not a record.
        ProfilePhotoIndex.record("u3", mapOf("photoUrl" to new))
        assertNull(ProfilePhotoIndex.photo("u3", contact = true))
    }
}
