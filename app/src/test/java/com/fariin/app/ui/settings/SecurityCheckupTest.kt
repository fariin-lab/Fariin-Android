package com.fariin.app.ui.settings

import com.fariin.app.ui.settings.SecurityCheckupRules.Device
import com.fariin.app.ui.settings.SecurityCheckupRules.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security Checkup rules against Kulan-iOS SecurityCheckupView.swift: what shows done, to do, or unchecked, in which order. */
class SecurityCheckupTest {
    private fun ids(l: List<SecurityCheckupRules.Entry>) = l.map { it.id }

    @Test fun orderAndGroups() {
        val e = SecurityCheckupRules.entries(Status.Missing, Status.Done("Set"), Status.Failed, Status.Missing, 0)
        assertEquals(listOf("passkey", "password", "devices", "chatKey"), ids(e))
        val g = SecurityCheckupRules.groups(e)
        // To do keeps the iOS order; a check with no answer is never a tick and never "missing".
        assertEquals(listOf("passkey", "chatKey"), ids(g.missing))
        assertEquals(listOf("devices"), ids(g.failed))
        assertEquals(listOf("password"), ids(g.rest))
        assertEquals("Set up", g.restHeader)
    }

    @Test fun restHeader() {
        val all = SecurityCheckupRules.entries(Status.Done("a"), Status.Done("b"), Status.Done("c"), Status.Done("d"), 0)
        assertEquals("Everything is set up", SecurityCheckupRules.groups(all).restHeader)
        val one = SecurityCheckupRules.entries(Status.Done("a"), Status.Checking, Status.Done("c"), Status.Done("d"), 0)
        assertEquals("Checking your account…", SecurityCheckupRules.groups(one).restHeader)
        // Something to do: "Set up", not "Everything is set up".
        val failed = SecurityCheckupRules.entries(Status.Done("a"), Status.Failed, Status.Done("c"), Status.Done("d"), 0)
        assertEquals("Set up", SecurityCheckupRules.groups(failed).restHeader)
    }

    @Test fun titles() {
        val e = SecurityCheckupRules.entries(Status.Missing, Status.Missing, Status.Missing, Status.Missing, 1)
        assertEquals(listOf("Create a passkey", "Set a password", "Review 1 new device", "Set a Chat Key"), e.map { it.missingTitle })
        assertEquals("Review 3 new devices", SecurityCheckupRules.entries(Status.Checking, Status.Checking, Status.Missing, Status.Checking, 3)[2].missingTitle)
        assertEquals("Password", SecurityCheckupRules.entries(Status.Checking, Status.Done("Set"), Status.Checking, Status.Checking, 0)[1].missingTitle)
        assertTrue(e.none { it.why.contains("—") })
    }

    @Test fun lines() {
        assertEquals("1 passkey · added 2 Nov 2026", SecurityCheckupRules.passkeyLine(1, "2 Nov 2026"))
        assertEquals("2 passkeys", SecurityCheckupRules.passkeyLine(2, null))
        assertEquals("Only this device", SecurityCheckupRules.devicesLine(1, null))
        assertEquals("3 devices · reviewed 2 days ago", SecurityCheckupRules.devicesLine(3, "2 days ago"))
        assertEquals("Checking your account against the server…", SecurityCheckupRules.checkedLine(null, 1000, ""))
        assertEquals("We checked your account against the server just now.", SecurityCheckupRules.checkedLine(1000, 50_000, "x"))
        assertEquals("We checked your account against the server 5 minutes ago.", SecurityCheckupRules.checkedLine(0, 300_000, "5 minutes ago"))
        assertEquals("Signed in 3 days ago", SecurityCheckupRules.deviceDetail("", "3 days ago"))
        assertEquals("Kampala, Uganda · signed in", SecurityCheckupRules.deviceDetail("Kampala, Uganda", null))
    }

    @Test fun password() {
        assertEquals(Status.Missing, SecurityCheckupRules.passwordStatus(listOf("google.com" to "a@b.com")))
        assertEquals(Status.Done("Set"), SecurityCheckupRules.passwordStatus(listOf("google.com" to "a@b.com", "password" to "a@b.com")))
        // A password on a relay address cannot be typed: no password.
        assertEquals(Status.Missing, SecurityCheckupRules.passwordStatus(listOf("password" to "x@privaterelay.appleid.com")))
    }

    @Test fun unreviewedByDevice() {
        val old = Device("a", "Pixel", "", 1_000)
        val new = Device("b", "iPhone", "", 5_000)
        val noDate = Device("c", "iPad", "", null)
        // Never reviewed: every other device is new.
        assertEquals(listOf("a", "b", "c"), SecurityCheckupRules.unreviewed(listOf(old, new, noDate), null, emptySet()).map { it.id })
        // Reviewed at 2000: only later sign-ins, plus records with no date.
        assertEquals(listOf("b", "c"), SecurityCheckupRules.unreviewed(listOf(old, new, noDate), 2_000, emptySet()).map { it.id })
        // A confirmed id is reviewed whatever its date says.
        assertEquals(listOf("b"), SecurityCheckupRules.unreviewed(listOf(old, new, noDate), 2_000, setOf("c")).map { it.id })
    }
}
