package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedAccountTest {
    @Test fun serverSaysGoneIsDeleted() = assertTrue(DeletedAccount.fromProfile(exists = false, fromCache = false, was = false))

    @Test fun profileThereIsNotDeleted() {
        assertFalse(DeletedAccount.fromProfile(exists = true, fromCache = true, was = true))
        assertFalse(DeletedAccount.fromProfile(exists = true, fromCache = false, was = true))
    }

    @Test fun coldCacheMissKeepsTheLastAnswer() {
        assertFalse(DeletedAccount.fromProfile(exists = false, fromCache = true, was = false))
        assertTrue(DeletedAccount.fromProfile(exists = false, fromCache = true, was = true))
    }

    @Test fun barWordsMatchIos() = assertEquals("This person is no longer on Fariin", DeletedAccount.BAR)
}
