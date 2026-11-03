package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Glow's pure rules against Kulan-iOS GlowModels, GlowNotificationsView, GlowProfileView, GlowIntroSheet. */
class GlowRulesTest {
    @Test fun countShortMatchesTheBadge() {
        assertEquals("0", GlowRules.countShort(0))
        assertEquals("999", GlowRules.countShort(999))
        assertEquals("1.0K", GlowRules.countShort(1_000))
        // The iOS code keeps one decimal only under 10K, so his "25.6K" comment draws as "25K".
        assertEquals("25K", GlowRules.countShort(25_600))
        assertEquals("2.6K", GlowRules.countShort(2_560))
        assertEquals("9.9K", GlowRules.countShort(9_949))
        assertEquals("10K", GlowRules.countShort(10_999))
        assertEquals("999K", GlowRules.countShort(999_999))
        assertEquals("1.5M", GlowRules.countShort(1_500_000))
        assertEquals("12M", GlowRules.countShort(12_400_000))
    }

    @Test fun introNamesThePerson() {
        assertEquals("Ana's", GlowRules.introName(" Ana "))
        assertEquals("their", GlowRules.introName("  "))
        assertTrue(GlowRules.introBody("Ana").startsWith("A Glow lets you see Ana's stories without being in each other's chats. "))
        assertTrue(GlowRules.introBody("").contains("see their stories"))
    }

    @Test fun statsCardLines() {
        assertEquals("See who glowed you", GlowRules.byLine(emptyList()))
        assertEquals("See who glowed you", GlowRules.byLine(listOf("", "")))
        assertEquals("by Ana, Bo", GlowRules.byLine(listOf("Ana", "Bo", "Cy")))
        assertEquals("1.2K Glowers  ·  3 Glowing", GlowRules.statsLine(1_200, 3))
    }

    @Test fun rowLinesAndSearch() {
        val p = GlowPerson("u", "Ana Lee", "ana")
        assertEquals("@ana", GlowRules.rowTitle(p))
        assertEquals("Ana Lee", GlowRules.rowSubtitle(p))
        val noHandle = GlowPerson("u", "Ana Lee")
        assertEquals("Ana Lee", GlowRules.rowTitle(noHandle))
        assertNull(GlowRules.rowSubtitle(noHandle))
        assertTrue(GlowRules.matches(p, "  LEE "))
        assertTrue(GlowRules.matches(p, "an"))
        assertTrue(GlowRules.matches(p, ""))
        assertTrue(!GlowRules.matches(p, "bo"))
    }

    @Test fun stampFollowsHisRules() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_788_000_000_000L   // 2026-08-29
        val day = 86_400_000L
        assertEquals("now", GlowRules.stampText(now - 30_000, now, utc, Locale.US))
        assertEquals("today", GlowRules.stampText(now - 3_600_000, now, utc, Locale.US))
        assertEquals("3d", GlowRules.stampText(now - 3 * day, now, utc, Locale.US))
        assertEquals("1w", GlowRules.stampText(now - 7 * day, now, utc, Locale.US))
        assertEquals("4w", GlowRules.stampText(now - 34 * day, now, utc, Locale.US))
        assertEquals("16 Jul", GlowRules.stampText(now - 44 * day, now, utc, Locale.US))
        assertEquals("16 Jul 2025", GlowRules.stampText(now - 409 * day, now, utc, Locale.US))
    }

    @Test fun partialPeopleIsStillAnAnswer() {
        val a = GlowPerson("a", "A")
        assertEquals(GlowLoad.Loaded(listOf(a)), GlowRules.resolved(listOf("a", "b"), listOf(a, null)))
        assertEquals(GlowLoad.Failed, GlowRules.resolved(listOf("a"), listOf(null)))
        assertEquals(GlowLoad.Loaded(emptyList<GlowPerson>()), GlowRules.resolved(emptyList(), emptyList()))
    }

    @Test fun expiredCardsAreDroppedOnRead() {
        fun card(id: String, exp: Long) = GlowStoryCard(GlowPerson(id, id), PostedStory(id, "", createdAtMs = 0, expiresAtMs = exp))
        assertEquals(listOf("b"), GlowRules.liveCards(listOf(card("a", 10), card("b", 30)), 20)?.map { it.person.id })
        assertNull(GlowRules.liveCards(listOf(card("a", 10)), 20))
    }

    @Test fun cardGeometry() {
        assertEquals(44f, GlowRules.cardAvatar(168f))
        assertEquals(44f, GlowRules.cardAvatar(0f))
        assertEquals(46f, GlowRules.cardAvatar(175f))
        assertEquals(16f, GlowRules.nameInset(34f))
        assertEquals(10f, GlowRules.nameInset(12f))
        assertEquals(2f, GlowRules.ringWidth(44f, seen = false))
        assertEquals(1.32f, GlowRules.ringWidth(44f, seen = true), 0.001f)
    }

    @Test fun relationshipIsEitherDirection() {
        val s = GlowState(glowers = setOf("a", "b"), glowing = setOf("b", "c"), hasLoaded = true)
        assertEquals(setOf("a", "b", "c"), s.relationship)
        assertTrue(s.known)
        assertTrue(!s.copy(hasFailed = true).known)
    }
}
