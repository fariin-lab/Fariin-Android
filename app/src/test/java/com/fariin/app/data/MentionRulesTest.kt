package com.fariin.app.data

import com.fariin.app.local.SendQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionRulesTest {
    @Test fun queryIsTheAtWordBeforeTheCursor() {
        assertEquals("", MentionRules.query("hi @"))
        assertEquals("al", MentionRules.query("hi @al"))
        assertEquals("al", MentionRules.query(MentionRules.head("hi @al there", 6)))
        assertNull(MentionRules.query("hi @al "))
        assertNull(MentionRules.query("no at sign"))
        assertEquals("", MentionRules.query("a@@"))
        assertEquals("hi", MentionRules.head("hi", 99))
    }

    @Test fun candidatesSkipMeAndNamelessAndMatchAnyCase() {
        val names = mapOf("me" to "Dave", "a" to "Alice", "b" to "Bob", "c" to "")
        val all = MentionRules.candidates(listOf("me", "a", "b", "c"), "me", names, "")
        assertEquals(listOf("a", "b"), all.map { it.uid })
        assertEquals(listOf("a"), MentionRules.candidates(listOf("me", "a", "b"), "me", names, "LI").map { it.uid })
        assertEquals(emptyList<MentionRules.Person>(), MentionRules.candidates(listOf("a"), "me", names, "zz"))
    }

    @Test fun insertReplacesAtTheCursor() {
        assertEquals("hi @Alice " to 10, MentionRules.insert("hi @al", 6, "Alice"))
        // A space already after the cursor: none added.
        assertEquals("@Alice there" to 6, MentionRules.insert("@a there", 2, "Alice"))
        assertNull(MentionRules.insert("plain", 5, "Alice"))
    }

    @Test fun resolveKeepsOnlyNamesStillInTheText() {
        var p = MentionRules.addPick(emptyMap(), "Sam", "s1")
        p = MentionRules.addPick(p, "Samir", "s2")
        assertEquals(listOf("s2"), MentionRules.resolve("hey @Samir", p))
        assertEquals(listOf("s2", "s1"), MentionRules.resolve("@Samir and @Sam", p))
        assertEquals(emptyList<String>(), MentionRules.resolve("no names", p))
        // Same name picked for two people, once in the text: the latest pick.
        val twice = MentionRules.addPick(MentionRules.addPick(emptyMap(), "Ali", "x"), "Ali", "y")
        assertEquals(listOf("y"), MentionRules.resolve("@Ali hi", twice))
        // Gone from the group before Send: not tagged.
        assertEquals(emptyList<String>(), MentionRules.forSend("@Sam", p, listOf("s2")))
    }

    @Test fun storedAndReadAsIos() {
        val e = SendQueue.Entry("C1", "g1", "@Alice hi", 1.0, mentions = listOf("a"))
        assertEquals(listOf(e), SendQueue.decode(SendQueue.encode(listOf(e))))
        val m = Message.fromFirestore("m1", mapOf("authorId" to "b", "text" to "@Alice hi", "mentions" to listOf("a")), "g1") { r, _ -> r }
        assertEquals(listOf("a"), m.mentions)
        assertEquals(listOf(0 until 6), MentionRules.ranges(m.text, m.mentions, mapOf("a" to "Alice")))
        assertEquals(emptyList<IntRange>(), MentionRules.ranges(m.text, m.mentions, emptyMap()))
    }
}
