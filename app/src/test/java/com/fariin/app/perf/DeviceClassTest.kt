package com.fariin.app.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClassTest {
    private val gb = 1024L * 1024 * 1024
    private fun specs(
        ram: Long = 8 * gb, cores: Int = 8, freq: Int? = 2800, heap: Int = 256, sdk: Int = 34, lowRam: Boolean = false,
    ) = DeviceSpecs(ram, cores, freq, heap, sdk, lowRam)

    @Test fun flagshipIsHigh() = assertEquals(PerfClass.HIGH, DeviceClass.classify(specs()))

    @Test fun lowRamFlagIsLow() = assertEquals(PerfClass.LOW, DeviceClass.classify(specs(lowRam = true)))

    @Test fun under2GbIsLow() {
        assertEquals(PerfClass.LOW, DeviceClass.classify(specs(ram = 1536L * 1024 * 1024)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(ram = 2 * gb)))
    }

    @Test fun twoCoresIsLow() = assertEquals(PerfClass.LOW, DeviceClass.classify(specs(cores = 2)))

    @Test fun slowQuadIsLowButSlowOctaIsAverage() {
        assertEquals(PerfClass.LOW, DeviceClass.classify(specs(cores = 4, freq = 1200)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(cores = 8, freq = 1200)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(cores = 4, freq = 1300)))
    }

    @Test fun averageBands() {
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(cores = 6)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(freq = 2055)))
        assertEquals(PerfClass.HIGH, DeviceClass.classify(specs(freq = 2056)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(ram = 3 * gb)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(heap = 192)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(sdk = 28)))
        assertEquals(PerfClass.LOW, DeviceClass.classify(specs(heap = 96)))
    }

    @Test fun unknownFrequencyDoesNotDecide() {
        assertEquals(PerfClass.HIGH, DeviceClass.classify(specs(freq = null)))
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(cores = 4, freq = null)))
    }

    @Test fun averageMhzFromKhzFiles() {
        assertEquals(1800, DeviceClass.averageMhz(listOf("1800000\n", "1800000", null, "garbage")))
        assertEquals(2000, DeviceClass.averageMhz(listOf("1000000", "3000000")))
        assertNull(DeviceClass.averageMhz(listOf(null, "", "0", "2")))
    }

    @Test fun lowDefaultsAreAllOff() {
        val d = PowerRules.defaults(PerfClass.LOW)
        assertFalse(d.blur || d.animations || d.autoplayVideos || d.autoplayGifs)
        assertTrue(d.followBatterySaver)
        val a = PowerRules.defaults(PerfClass.AVERAGE)
        assertTrue(a.animations && a.autoplayVideos && a.autoplayGifs)
        assertFalse(a.blur)   // blur starts on only on a high-end phone
        assertTrue(PowerRules.defaults(PerfClass.HIGH).blur)
    }

    @Test fun batterySaverTurnsEffectsOffOnlyWhenFollowed() {
        val on = PowerRules.defaults(PerfClass.HIGH)
        val saver = PowerState(on, batterySaverOn = true, deviceClass = PerfClass.HIGH)
        assertFalse(saver.blur || saver.autoplayVideos || saver.autoplayGifs)
        assertTrue(saver.animations)   // animations are always on (owner 2026-11-10, the switch is gone)
        val ignored = saver.copy(choices = on.copy(followBatterySaver = false))
        assertTrue(ignored.blur && ignored.animations && ignored.autoplayVideos && ignored.autoplayGifs)
    }

    @Test fun glassNeedsAndroid12NotLowAndBlur() {
        assertTrue(PowerRules.glass(31, PerfClass.AVERAGE, true))
        assertFalse(PowerRules.glass(30, PerfClass.HIGH, true))
        assertFalse(PowerRules.glass(34, PerfClass.LOW, true))
        assertFalse(PowerRules.glass(34, PerfClass.HIGH, false))
        val s = PowerState(PowerRules.defaults(PerfClass.HIGH), batterySaverOn = true, deviceClass = PerfClass.HIGH)
        assertFalse(s.glass(34))
    }
}
