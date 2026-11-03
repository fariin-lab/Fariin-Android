package com.fariin.app.call

import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRoutingTest {
    private val phoneOnly = RouteState(AudioRoute.EARPIECE, setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER))

    @Test fun speakerTapTogglesWithoutAccessories() {
        assertEquals(CallRouting.Tap.Switch(AudioRoute.SPEAKER), CallRouting.speakerTap(phoneOnly))
        assertEquals(CallRouting.Tap.Switch(AudioRoute.EARPIECE), CallRouting.speakerTap(phoneOnly.copy(current = AudioRoute.SPEAKER)))
    }

    @Test fun speakerTapOpensPickerWithBluetoothOrHeadset() {
        val bt = phoneOnly.copy(available = phoneOnly.available + AudioRoute.BLUETOOTH)
        assertEquals(CallRouting.Tap.Picker, CallRouting.speakerTap(bt))
        val wired = RouteState(AudioRoute.HEADSET, setOf(AudioRoute.HEADSET, AudioRoute.SPEAKER))
        assertEquals(CallRouting.Tap.Picker, CallRouting.speakerTap(wired))
    }

    @Test fun pickerListOrderAndHeadsetHidesPhone() {
        val all = RouteState(AudioRoute.BLUETOOTH, setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER, AudioRoute.BLUETOOTH))
        assertEquals(listOf(AudioRoute.BLUETOOTH, AudioRoute.EARPIECE, AudioRoute.SPEAKER), CallRouting.pickerList(all))
        val wired = RouteState(AudioRoute.HEADSET, setOf(AudioRoute.EARPIECE, AudioRoute.HEADSET, AudioRoute.SPEAKER))
        assertEquals(listOf(AudioRoute.HEADSET, AudioRoute.SPEAKER), CallRouting.pickerList(wired))
        assertEquals(AudioRoute.HEADSET, CallRouting.quietRoute(wired))
    }

    @Test fun labels() {
        assertEquals("Phone", CallRouting.label(AudioRoute.EARPIECE, null))
        assertEquals("Bluetooth", CallRouting.label(AudioRoute.BLUETOOTH, " "))
        assertEquals("Car Kit", CallRouting.label(AudioRoute.BLUETOOTH, "Car Kit"))
        assertFalse(CallRouting.speakerLit(phoneOnly))
        assertTrue(CallRouting.speakerLit(phoneOnly.copy(current = AudioRoute.BLUETOOTH)))
    }

    @Test fun telecomMaskRoundTrip() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_BLUETOOTH
        val s = CallRouting.fromMask(mask, CallAudioState.ROUTE_BLUETOOTH, "Buds")
        assertEquals(AudioRoute.BLUETOOTH, s.current)
        // The speaker is always offered, even when the mask leaves it out.
        assertEquals(setOf(AudioRoute.EARPIECE, AudioRoute.BLUETOOTH, AudioRoute.SPEAKER), s.available)
        for (r in AudioRoute.entries) assertEquals(r, CallRouting.fromTelecom(CallRouting.toTelecom(r)))
    }

    @Test fun startRouteVideoOnSpeakerUnlessAccessory() {
        assertEquals(AudioRoute.SPEAKER, CallRouting.startRoute(true, phoneOnly))
        assertEquals(AudioRoute.EARPIECE, CallRouting.startRoute(false, phoneOnly))
        assertEquals(AudioRoute.BLUETOOTH, CallRouting.startRoute(true, phoneOnly.copy(available = phoneOnly.available + AudioRoute.BLUETOOTH)))
        assertEquals(AudioRoute.HEADSET, CallRouting.startRoute(true, phoneOnly.copy(available = phoneOnly.available + AudioRoute.HEADSET)))
    }

    @Test fun proximityOnlyAtTheEar() {
        assertTrue(CallRouting.proximityWanted(CallPhase.ACTIVE, cameraOn = false, route = AudioRoute.EARPIECE))
        assertFalse(CallRouting.proximityWanted(CallPhase.OUTGOING, false, AudioRoute.EARPIECE))   // not while ringing out
        assertFalse(CallRouting.proximityWanted(CallPhase.INCOMING, false, AudioRoute.EARPIECE))
        assertFalse(CallRouting.proximityWanted(CallPhase.ACTIVE, false, AudioRoute.SPEAKER))
        assertFalse(CallRouting.proximityWanted(CallPhase.ACTIVE, true, AudioRoute.EARPIECE))
        assertFalse(CallRouting.proximityWanted(CallPhase.ENDED, false, AudioRoute.EARPIECE))
    }

    @Test fun disconnectCauses() {
        assertEquals(DisconnectCause.REJECTED, CallRouting.disconnectCode(EndReason.DECLINED, byUser = true, isCaller = false, wasIncomingRing = true))
        assertEquals(DisconnectCause.MISSED, CallRouting.disconnectCode(EndReason.MISSED, false, false, true))
        assertEquals(DisconnectCause.ANSWERED_ELSEWHERE, CallRouting.disconnectCode(EndReason.HANGUP, false, false, true))
        assertEquals(DisconnectCause.BUSY, CallRouting.disconnectCode(EndReason.BUSY, false, false, true))
        assertEquals(DisconnectCause.CANCELED, CallRouting.disconnectCode(EndReason.MISSED, true, true, false))
        assertEquals(DisconnectCause.LOCAL, CallRouting.disconnectCode(EndReason.HANGUP, true, true, false))
        assertEquals(DisconnectCause.REMOTE, CallRouting.disconnectCode(EndReason.HANGUP, false, true, false))
        assertEquals(DisconnectCause.ERROR, CallRouting.disconnectCode(EndReason.FAILED, false, true, false))
    }

    @Test fun pipOnlyForLiveVideo() {
        assertTrue(CallRouting.pipWanted(CallPhase.ACTIVE, cameraOn = false, remoteCameraOn = true))
        assertTrue(CallRouting.pipWanted(CallPhase.RECONNECTING, true, false))
        assertTrue(CallRouting.pipWanted(CallPhase.ACTIVE, false, false))
        assertTrue(CallRouting.pipWanted(CallPhase.OUTGOING, true, true))
        assertFalse(CallRouting.pipWanted(CallPhase.INCOMING, true, true))
        assertFalse(CallRouting.pipWanted(CallPhase.ENDED, true, true))
        assertTrue(CallRouting.pipVideo(cameraOn = false, remoteCameraOn = true))
        assertFalse(CallRouting.pipVideo(cameraOn = false, remoteCameraOn = false))
    }
}

class GroupCallStartTest {
    @Test fun whoMayStart() {
        val conv = mapOf("type" to "group", "users" to listOf("a", "b"), "admins" to listOf("a"))
        assertTrue(GroupCallWire.mayStart(conv, "b"))
        assertFalse(GroupCallWire.mayStart(conv, "z"))
        assertFalse(GroupCallWire.mayStart(conv + ("type" to "dm"), "a"))
        val adminsOnly = conv + ("onlyAdminsCall" to true)
        assertTrue(GroupCallWire.mayStart(adminsOnly, "a"))
        assertFalse(GroupCallWire.mayStart(adminsOnly, "b"))
        assertFalse(GroupCallWire.mayStart(null, "a"))
    }

    @Test fun startDocHasExactlyTheRulesKeys() {
        val d = GroupCallWire.startDoc("me", video = true, title = "Family", recordId = "gcall_X", stamp = "TS")
        assertEquals(setOf("active", "startedBy", "video", "title", "startedAt", "recordId"), d.keys)
        assertEquals(true, d["active"]); assertEquals("me", d["startedBy"]); assertEquals("TS", d["startedAt"])
        assertEquals("gcall_X", GroupCallWire.newRecordId("X"))
    }

    @Test fun recordAndMarker() {
        val r = GroupCallWire.recordFields("me", false, "TS")
        assertEquals("call", r["type"]); assertEquals("ongoing", r["callOutcome"]); assertEquals("me", r["callerUid"])
        assertEquals(false, r["callVideo"]); assertEquals("", r["text"])
        assertEquals("📞 Call", GroupCallWire.convMarker("me", false, "TS")["lastMessage"])
        assertEquals("📹 Video call", GroupCallWire.convMarker("me", true, "TS")["lastMessage"])
    }

    @Test fun closeBodyAndRecordClose() {
        assertEquals(mapOf("cid" to "g", "answered" to true, "recordId" to "r"), GroupCallWire.closeBody("g", true, "r"))
        assertEquals(mapOf("cid" to "g", "answered" to false), GroupCallWire.closeBody("g", false, null))
        assertEquals(mapOf("callOutcome" to "answered", "callDuration" to 90), GroupCallWire.recordClose(true, 1_000, 91_000))
        assertEquals(mapOf("callOutcome" to "missed"), GroupCallWire.recordClose(false, 1_000, 91_000))
        assertNull(GroupCallWire.recordClose(true, null, 91_000))
    }
}
