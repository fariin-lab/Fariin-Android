package com.fariin.app.ui.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStepBackTest {
    @Test fun aStepStaysOpenWhileItsOwnPageIsUp() {
        assertFalse(GroupStepBack.closesToChat("add", adding = true, editing = false, pages = emptyList()))
        assertFalse(GroupStepBack.closesToChat("edit", adding = false, editing = true, pages = emptyList()))
        assertFalse(GroupStepBack.closesToChat("link", false, false, listOf(GroupPage.Links)))
        assertFalse(GroupStepBack.closesToChat("link", false, false, listOf(GroupPage.Links, GroupPage.LinkEdit(null))))
    }

    @Test fun closingTheStepGoesToTheChat() {
        assertTrue(GroupStepBack.closesToChat("add", false, false, emptyList()))
        assertTrue(GroupStepBack.closesToChat("edit", false, false, emptyList()))
        assertTrue(GroupStepBack.closesToChat("link", false, false, emptyList()))
    }

    @Test fun openedFromTheHeaderNothingGoesToTheChat() {
        // Group Info -> Group Settings -> back: the stack empties, Group Info stays.
        assertFalse(GroupStepBack.closesToChat(null, false, false, emptyList()))
        assertFalse(GroupStepBack.closesToChat(null, false, false, listOf(GroupPage.Settings)))
        assertFalse(GroupStepBack.closesToChat("other", false, false, emptyList()))
    }

    @Test fun theStackOutlivesATripAway() {
        val stack = listOf(GroupPage.Settings, GroupPage.Members, GroupPage.Rights("u1"), GroupPage.Stats("A: B"), GroupPage.Storage("G"))
        assertEquals(stack, GroupPage.restore(GroupPage.save(stack)))
        // An open link editor is a moment: the saved stack stops below it.
        assertEquals(listOf(GroupPage.Links), GroupPage.restore(GroupPage.save(listOf(GroupPage.Links, GroupPage.LinkEdit(null), GroupPage.Members))))
        assertEquals(listOf(GroupPage.Settings), GroupPage.restore(listOf("settings", "nonsense")))
    }
}
