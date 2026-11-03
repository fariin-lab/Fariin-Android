package com.fariin.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PollRulesTest {
    private fun json(marker: String) =
        JSONObject(String(Base64.getDecoder().decode(marker.removePrefix(PollRules.MARKER))))

    private val plain = PollCard("p1", "Lunch?", listOf("Pizza", "Soup", "Salad"), multiple = false)
    private val multi = plain.copy(multiple = true)
    private val quiz = plain.copy(correct = listOf(1))

    @Test fun markerRoundTripsAllFields() {
        val m = PollRules.markerText("abc", "Where?", listOf("Here", "There"), multiple = true,
            hideVoters = true, correct = listOf(0), endsAt = 1_700_000_000_123.0)
        assertTrue(m.startsWith("fariin-poll:"))
        val p = PollRules.parse(m)!!
        assertEquals("abc", p.id); assertEquals("Where?", p.question); assertEquals(listOf("Here", "There"), p.options)
        assertTrue(p.multiple); assertTrue(p.hideVoters); assertEquals(listOf(0), p.correct)
        assertEquals(1_700_000_000_123.0, p.endsAt!!, 0.0)
    }

    @Test fun wireShapeMatchesIos() {
        // iOS pollMarkerText: id, q, opts, multi always; anon / quiz / ends only when set.
        val o = json(PollRules.markerText("x", "Q", listOf("a", "b"), multiple = false))
        assertEquals(setOf("id", "q", "opts", "multi"), o.keys().asSequence().toSet())
        val full = json(PollRules.markerText("x", "Q", listOf("a", "b"), false, true, listOf(1), 5000.0))
        assertEquals(true, full.get("anon")); assertEquals(1, full.getJSONArray("quiz").getInt(0)); assertEquals(5000L, full.getLong("ends"))
    }

    @Test fun parsesAnIosMarker() {
        // Built as iOS JSONSerialization + base64EncodedString would.
        val raw = """{"id":"demo-trip-2","q":"What time do we leave? 🚗","opts":["7 AM","9 AM"],"multi":false}"""
        val p = PollRules.parse("fariin-poll:" + Base64.getEncoder().encodeToString(raw.toByteArray()))!!
        assertEquals("What time do we leave? 🚗", p.question); assertFalse(p.isQuiz); assertNull(p.endsAt)
    }

    @Test fun rejectsBadMarkers() {
        assertNull(PollRules.parse("hello"))
        assertNull(PollRules.parse("fariin-poll:!!notbase64"))
        val one = Base64.getEncoder().encodeToString("""{"id":"a","q":"Q","opts":["only"]}""".toByteArray())
        assertNull(PollRules.parse("fariin-poll:$one"))   // fewer than two options
        val noId = Base64.getEncoder().encodeToString("""{"q":"Q","opts":["a","b"]}""".toByteArray())
        assertNull(PollRules.parse("fariin-poll:$noId"))
        val badQuiz = Base64.getEncoder().encodeToString("""{"id":"a","q":"Q","opts":["a","b"],"quiz":[5,-1,1],"ends":0}""".toByteArray())
        val p = PollRules.parse("fariin-poll:$badQuiz")!!
        assertEquals(listOf(1), p.correct); assertNull(p.endsAt)
    }

    @Test fun clearFieldsOnlyForPolls() {
        assertEquals(emptyMap<String, Any>(), PollRules.clearFields("just words"))
        assertEquals(mapOf("pollMulti" to false), PollRules.clearFields(PollRules.markerText("a", "Q", listOf("x", "y"), false)))
        assertEquals(mapOf("pollMulti" to true, "pollEndsAt" to 9000L),
            PollRules.clearFields(PollRules.markerText("a", "Q", listOf("x", "y"), true, endsAt = 9000.0)))
    }

    @Test fun voteDocShape() {
        val d = PollRules.voteDoc(listOf(2, 0), "TS")
        assertEquals(setOf("options", "at"), d.keys)
        assertEquals(listOf(0, 2), d["options"])
        assertEquals(listOf(1, 3), PollRules.readOptions(listOf(1L, 3L)))
        assertEquals(emptyList<Int>(), PollRules.readOptions(null))
    }

    @Test fun singleChoiceReplacesAndRetracts() {
        assertEquals(listOf(1), PollRules.toggle(plain, emptyList(), 1, true))
        assertEquals(listOf(2), PollRules.toggle(plain, listOf(1), 2, true))
        assertEquals(emptyList<Int>(), PollRules.toggle(plain, listOf(1), 1, true))
    }

    @Test fun multipleToggles() {
        assertEquals(listOf(0, 2), PollRules.toggle(multi, listOf(2), 0, true))
        assertEquals(listOf(2), PollRules.toggle(multi, listOf(0, 2), 0, true))
        assertEquals(emptyList<Int>(), PollRules.toggle(multi, listOf(2), 2, true))
    }

    @Test fun closedAndQuizLock() {
        assertNull(PollRules.toggle(plain, emptyList(), 0, open = false))
        assertNull(PollRules.toggle(plain, emptyList(), 7, true))
        assertEquals(listOf(0), PollRules.toggle(quiz, emptyList(), 0, true))
        assertNull(PollRules.toggle(quiz, listOf(0), 1, true))   // a quiz answer is final
    }

    @Test fun openRules() {
        assertTrue(PollRules.isOpen(plain, false, 1.0))
        assertFalse(PollRules.isOpen(plain, true, 1.0))
        val timed = plain.copy(endsAt = 100.0)
        assertTrue(PollRules.isOpen(timed, false, 99.0))
        assertFalse(PollRules.isOpen(timed, false, 100.0))
        assertTrue(PollRules.canStop("me", "me", true))
        assertFalse(PollRules.canStop("bob", "me", true))
        assertFalse(PollRules.canStop("me", "me", false))
        assertFalse(PollRules.canStop("", "", true))
    }

    @Test fun talliesAndWhoVoted() {
        val v = mapOf("a" to listOf(0), "b" to listOf(0, 2), "c" to listOf(2), "d" to listOf(1))
        assertEquals(2, PollRules.count(v, 0)); assertEquals(1, PollRules.count(v, 1))
        assertEquals("50%", PollRules.percent(v, 0)); assertEquals("25%", PollRules.percent(v, 1))
        assertEquals(listOf("b", "c"), PollRules.voters(v, 2))
        assertEquals("0%", PollRules.percent(emptyMap(), 0))
        assertEquals(0.0, PollRules.fraction(emptyMap(), 0), 0.0)
    }

    @Test fun resultsAndQuizReveal() {
        assertFalse(PollRules.showResults(emptyList(), open = true))
        assertTrue(PollRules.showResults(listOf(1), open = true))
        assertTrue(PollRules.showResults(emptyList(), open = false))
        assertEquals(PollRules.QuizMark.NONE, PollRules.quizMark(quiz, emptyList(), true, 1))
        assertEquals(PollRules.QuizMark.RIGHT, PollRules.quizMark(quiz, listOf(0), true, 1))
        assertEquals(PollRules.QuizMark.WRONG, PollRules.quizMark(quiz, listOf(0), true, 0))
        assertEquals(PollRules.QuizMark.NONE, PollRules.quizMark(quiz, listOf(0), true, 2))
        assertEquals(PollRules.QuizMark.RIGHT, PollRules.quizMark(quiz, emptyList(), false, 1))
        assertEquals(PollRules.QuizMark.NONE, PollRules.quizMark(plain, listOf(0), true, 0))
    }

    @Test fun words() {
        assertEquals("Select one", PollRules.subtitle(plain))
        assertEquals("Select one or more", PollRules.subtitle(multi))
        assertEquals("Quiz · Select one", PollRules.subtitle(quiz))
        assertEquals("No votes yet", PollRules.footer(0, true))
        assertEquals("1 vote · View results", PollRules.footer(1, true))
        assertEquals("3 votes · View results", PollRules.footer(3, true))
        assertEquals("Poll ended", PollRules.footer(0, false))
        assertEquals("Poll ended · 2 votes", PollRules.footer(2, false))
        assertEquals("2 votes · ends in 3 hours", PollRules.resultsStatus(2, true, "in 3 hours"))
        assertEquals("Poll ended · 1 vote", PollRules.resultsStatus(1, false, "in 3 hours"))
        assertEquals("📊 Poll", PollRules.PREVIEW)
        assertEquals("📊 Lunch?", PollRules.safeText(plain))
        for (s in listOf(PollRules.footer(2, true), PollRules.subtitle(quiz), PollRules.resultsStatus(1, true, "in 1 day")))
            assertFalse(s.contains('—'))
    }

    @Test fun messageCarriesPollAndClosed() {
        val marker = PollRules.markerText("a", "Q", listOf("x", "y"), false)
        val m = Message.fromFirestore("m1", mapOf("authorId" to "u", "text" to marker, "pollClosed" to true,
            "pollMulti" to false, "pollCounts" to mapOf("0" to 3L, "1" to 0L), "pollVoters" to 3L), "group1") { raw, _ -> raw }
        assertEquals("Q", m.poll?.question)
        assertTrue(m.pollClosed)
        assertEquals(mapOf(0 to 3), m.pollCounts); assertEquals(3, m.pollVoters)
        assertEquals("📊 Q", m.bodyText)
        assertNull(m.copy(deleted = true).poll)
    }

    @Test fun composerRules() {
        assertFalse(PollRules.canSend("", listOf("a", "b"), false, listOf(false, false)))
        assertFalse(PollRules.canSend("Q", listOf("a", " "), false, listOf(false, false)))
        assertTrue(PollRules.canSend("Q", listOf("a", "b", ""), false, listOf(false, false, false)))
        assertFalse(PollRules.canSend("Q", listOf("a", "b"), true, listOf(false, false)))
        assertTrue(PollRules.canSend("Q", listOf("a", "b"), true, listOf(false, true)))
        // Blank options dropped; the quiz index follows the kept list; a quiz is never multiple.
        val m = PollRules.compose("id", " Q ", listOf("a", "", "c", ""), multiple = true, hideVoters = false,
            quiz = true, right = listOf(false, false, true, false), ends = PollRules.EndChoice.HOUR, nowMs = 1000.0)
        val p = PollRules.parse(m)!!
        assertEquals("Q", p.question); assertEquals(listOf("a", "c"), p.options); assertEquals(listOf(1), p.correct)
        assertFalse(p.multiple); assertEquals(3_601_000.0, p.endsAt!!, 0.0)
    }

    @Test fun nextOptionAppearsByItself() {
        assertEquals(listOf("a", "b", ""), PollRules.tidyOptions(listOf("a", "b")))
        assertEquals(listOf("a", ""), PollRules.tidyOptions(listOf("a", "", "")))
        assertEquals(listOf("", ""), PollRules.tidyOptions(listOf("", "")))
        assertEquals(10, PollRules.tidyOptions(List(10) { "x" }).size)
    }

    @Test fun channelVotesFromServerCounts() {
        // Three voters on three options (single choice): three placeholders, one option each.
        val v = PollRules.channelVotes(mapOf(0 to 1, 1 to 1, 2 to 1), 3, 0.0, emptyList(), 0.0, "me")
        assertEquals(3, v.size)
        assertEquals(1, PollRules.count(v, 0)); assertEquals("33%", PollRules.percent(v, 0))
        assertTrue(v.keys.all { it.startsWith(PollRules.ANON_PREFIX) })
        // My vote already counted by the server: taken off, then added under my uid (no double count).
        val mine = PollRules.channelVotes(mapOf(0 to 2), 2, tallyAt = 200.0, mine = listOf(0), mineAt = 100.0, me = "me")
        assertEquals(2, mine.size); assertEquals(listOf(0), mine["me"])
        // Not counted yet: simply added.
        val fresh = PollRules.channelVotes(mapOf(0 to 2), 2, tallyAt = 50.0, mine = listOf(1), mineAt = 100.0, me = "me")
        assertEquals(3, fresh.size); assertEquals(2, PollRules.count(fresh, 0))
    }
}
