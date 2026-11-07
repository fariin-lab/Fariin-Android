package com.fariin.app.call

import com.fariin.app.ui.calls.CallTarget
import com.fariin.app.ui.calls.NewCallRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewCallPickTest {
    private fun person(uid: String, name: String = uid.uppercase(), photo: String? = null) =
        CallTarget("me_$uid", name, photo, uid, null, 0.0)

    @Test fun tapPicksAndUnpicksInOrder() {
        var picked = emptyList<String>()
        picked = NewCallRules.toggle(picked, "a")
        picked = NewCallRules.toggle(picked, "b")
        picked = NewCallRules.toggle(picked, "c")
        assertEquals(listOf("a", "b", "c"), picked)
        picked = NewCallRules.toggle(picked, "b")
        assertEquals(listOf("a", "c"), picked)
        picked = NewCallRules.toggle(picked, "b")
        assertEquals(listOf("a", "c", "b"), picked)       // back on at the end
        assertEquals(picked, NewCallRules.toggle(picked, ""))   // a row with no uid does nothing
    }

    @Test fun capStopsNewPicksButStillUnpicks() {
        val full = (1..NewCallRules.MAX_PICK).map { "u$it" }
        assertEquals(31, NewCallRules.MAX_PICK)
        assertEquals(full, NewCallRules.toggle(full, "extra"))
        assertEquals(full - "u3", NewCallRules.toggle(full, "u3"))
        assertEquals(listOf("a"), NewCallRules.toggle(listOf("a"), "b", max = 1))
    }

    @Test fun chosenFollowsTheListAndPickOrder() {
        val people = listOf(person("a"), person("b"), person("c"))
        assertEquals(listOf("c", "a"), NewCallRules.chosen(people, listOf("c", "a")).map { it.uid })
        // Someone who left the list (blocked, chat deleted) drops out of the strip.
        assertEquals(listOf("a"), NewCallRules.chosen(people, listOf("gone", "a", "a")).map { it.uid })
    }

    @Test fun planIsNothingOneOrMany() {
        assertEquals(NewCallRules.Plan.None, NewCallRules.plan(emptyList()))
        val a = person("a", "Amina", "p")
        assertEquals(NewCallRules.Plan.One(a), NewCallRules.plan(listOf(a)))
        val many = NewCallRules.plan(listOf(a, person("b", "Bilan")))
        assertTrue(many is NewCallRules.Plan.Many)
        assertEquals(listOf(CallPerson("a", "Amina", "p"), CallPerson("b", "Bilan", null)), (many as NewCallRules.Plan.Many).people)
    }

    @Test fun adhocTitleAsIos() {
        assertEquals("", GroupCallWire.adhocTitle(listOf(" ", "")))
        assertEquals("Amina", GroupCallWire.adhocTitle(listOf("Amina")))
        assertEquals("Amina & Bilan", GroupCallWire.adhocTitle(listOf("Amina", "Bilan")))
        assertEquals("Amina, Bilan & 1 other", GroupCallWire.adhocTitle(listOf("Amina", "Bilan", "Cali")))
        assertEquals("Amina, Bilan & 2 others", GroupCallWire.adhocTitle(listOf("Amina", "Bilan", "Cali", "Dahir")))
    }

    @Test fun adhocIdsAndBodies() {
        val id = GroupCallWire.newAdhocId("ABC-DEF")
        assertEquals("adhoc_abc-def", id)
        assertTrue(GroupCallWire.isAdhoc(id))
        assertFalse(GroupCallWire.isAdhoc("group1"))
        assertFalse(GroupCallWire.isAdhoc(null))
        assertEquals(mapOf("roomId" to id, "servers" to listOf("own")), GroupCallWire.adhocTokenRequest(id))
        assertEquals(mapOf("roomId" to id), GroupCallWire.adhocCloseBody(id))
        assertEquals(mapOf("kind" to "adhoc", "id" to id), GroupCallAdmin.room(id, null))
        assertEquals(mapOf("kind" to "group", "id" to "g1"), GroupCallAdmin.room("g1", null))
    }

    @Test fun adhocOthersDropMeAndRepeats() {
        val p = listOf(CallPerson("me", "Me", null), CallPerson("a", "A", null), CallPerson("a", "A", null), CallPerson("", "?", null), CallPerson("b", "B", null))
        assertEquals(listOf("a", "b"), GroupCallWire.adhocOthers("me", p).map { it.uid })
        val many = (1..40).map { CallPerson("u$it", "U$it", null) }
        assertEquals(31, GroupCallWire.adhocOthers("me", many).size)
    }

    @Test fun adhocStartDocHasOnlyTheRulesKeys() {
        val me = CallPerson("me", "Me", "pm")
        val doc = GroupCallWire.adhocStartDoc(me, listOf(CallPerson("a", "Amina", null), CallPerson("b", "Bilan", "pb")), true, "STAMP")
        val allowed = setOf("kind", "active", "startedBy", "video", "title", "members", "names", "photos", "joined", "startedAt", "endedAt", "invitedAt")
        assertTrue(allowed.containsAll(doc.keys))
        assertEquals("adhoc", doc["kind"])
        assertEquals(true, doc["active"])
        assertEquals("me", doc["startedBy"])
        assertEquals(listOf("me", "a", "b"), doc["members"])
        assertEquals(mapOf("me" to "Me", "a" to "Amina", "b" to "Bilan"), doc["names"])
        assertEquals(mapOf("me" to "pm", "b" to "pb"), doc["photos"])
        assertEquals(emptyList<String>(), doc["joined"])
        assertEquals("Me, Amina & 1 other", doc["title"])
        assertEquals("STAMP", doc["startedAt"])
    }

    @Test fun adhocInviteFieldsOneWrite() {
        val f = GroupCallWire.adhocInviteFields(listOf(CallPerson("c", "Cali", "pc"), CallPerson("d", "Dahir", null)), "T", "UNION", "STAMP")
        assertEquals(mapOf("members" to "UNION", "title" to "T", "names.c" to "Cali", "photos.c" to "pc", "invitedAt.c" to "STAMP",
            "names.d" to "Dahir", "invitedAt.d" to "STAMP"), f)
    }
}
