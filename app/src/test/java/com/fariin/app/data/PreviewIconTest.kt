package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewIconTest {
    @Test fun iconsAsIos() {
        assertEquals(ChatList.PreviewIcon.PIN, ChatList.previewIcon("Pinned a message"))
        assertEquals(ChatList.PreviewIcon.PIN, ChatList.previewIcon("Omar: Pinned a message"))
        assertEquals(ChatList.PreviewIcon.CONTACT, ChatList.previewIcon("Contact"))
        assertEquals(ChatList.PreviewIcon.LOCATION, ChatList.previewIcon("Carol: Location"))
        assertNull(ChatList.previewIcon("Pinned a message to the wall, Omar"))
        assertNull(ChatList.previewIcon("hello"))
    }
}
