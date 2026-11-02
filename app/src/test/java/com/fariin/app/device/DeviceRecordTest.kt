package com.fariin.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRecordTest {
    @Test fun fieldsMatchTheIosNames() {
        val f = DeviceRecord.baseFields("Pixel 8", DeviceRecord.hardware("Google", "husky"),
            DeviceRecord.os("16"), DeviceRecord.appVersion("1.0", 1))
        assertEquals(setOf("model", "hardware", "os", "appVersion", "platform"), f.keys)
        assertEquals("Pixel 8", f["model"]); assertEquals("Google,husky", f["hardware"])
        assertEquals("Android 16", f["os"]); assertEquals("1.0 (1)", f["appVersion"]); assertEquals("android", f["platform"])
        assertEquals("Android phone", DeviceRecord.baseFields("", "", "", "")["model"])
    }

    @Test fun hardwareNeverReadsAsTheIosSimulator() {
        assertFalse(DeviceRecord.hardware("", "x86_64") == "x86_64")
        assertFalse(DeviceRecord.hardware("arm64", "") == "arm64")
    }

    @Test fun registrationCheck() {
        assertEquals(DeviceRecord.Check.REVOKED, DeviceRecord.check(existsOnServer = false, hadRegistered = true))
        assertEquals(DeviceRecord.Check.NEW_RECORD, DeviceRecord.check(existsOnServer = false, hadRegistered = false))
        assertEquals(DeviceRecord.Check.EXISTING, DeviceRecord.check(existsOnServer = true, hadRegistered = true))
    }

    @Test fun noticeWordingIsTheIosOne() {
        val r = DeviceRecord.reason(replaced = true, refusedForLimit = false)
        assertEquals(DeviceRecord.Reason.NEW_PHONE, r)
        assertEquals("Logged in on another phone", DeviceRecord.noticeTitle(r))
        assertEquals("Your account was logged in on another phone, so it was logged out here. Fariin works on one phone at a time. To use it here again, log in.",
            DeviceRecord.noticeMessage(r))
        assertEquals(DeviceRecord.Reason.DEVICE_LIMIT, DeviceRecord.reason(false, true))
        assertEquals("This device was logged out from another device.",
            DeviceRecord.noticeMessage(DeviceRecord.reason(false, false)))
        for (x in DeviceRecord.Reason.values()) assertFalse(DeviceRecord.noticeMessage(x).contains('—'))
        assertTrue(DeviceRecord.noticeMessage(DeviceRecord.Reason.DEVICE_LIMIT).contains("5 devices"))
    }
}
