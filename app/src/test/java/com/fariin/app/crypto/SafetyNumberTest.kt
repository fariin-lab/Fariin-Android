package com.fariin.app.crypto

import com.fariin.app.crypto.TestSodium.alicePk
import com.fariin.app.crypto.TestSodium.bobPk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyNumberTest {
    @Test fun matchesIndependentRebuild() {
        // Computed by a Python rebuild of SafetyNumber.swift (hashlib SHA-512, same loop).
        val n = SafetyNumber.compute("alice", alicePk, "bob", bobPk)
        assertEquals("536849810547811644534881182794916909104096606339950857979340", n)
    }

    @Test fun symmetricAndShaped() {
        val a = SafetyNumber.compute("alice", alicePk, "bob", bobPk)
        assertEquals(a, SafetyNumber.compute("bob", bobPk, "alice", alicePk))
        assertEquals(60, a.length)
        assertEquals(12, SafetyNumber.grouped(a).size)
        assertNotEquals(a, SafetyNumber.compute("alice", alicePk, "bob", TestSodium.nacl.boxKeyPair().publicKey))
    }

    @Test fun qr() {
        val a = SafetyNumber.compute("alice", alicePk, "bob", bobPk)
        assertEquals("fariinverify:v1:$a", SafetyNumber.qrPayload(a))
        assertEquals(a, SafetyNumber.numberFromQR(SafetyNumber.qrPayload(a)))
        assertNull(SafetyNumber.numberFromQR("fariinverify:v2:$a"))
        assertNull(SafetyNumber.numberFromQR("fariinverify:v1:123"))
    }
}
