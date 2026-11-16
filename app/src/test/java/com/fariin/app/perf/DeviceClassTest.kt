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
        soc: List<String> = emptyList(),
    ) = DeviceSpecs(ram, cores, freq, heap, sdk, lowRam, soc)

    // A chip's real numbers plus its names, the way read() would fill them in.
    private fun phone(cores: Int, freq: Int, ram: Long, vararg soc: String) =
        DeviceClass.classify(specs(ram = ram, cores = cores, freq = freq, soc = soc.toList()))

    @Test fun nubiaV80DesignUnisocT7280IsLow() {
        // 2x A75 2.2 GHz + 6x A55 1.8 GHz averages about 1900 MHz, so the numbers alone said AVERAGE.
        assertEquals(PerfClass.AVERAGE, DeviceClass.classify(specs(cores = 8, freq = 1900)))
        assertEquals(PerfClass.LOW, phone(8, 1900, 8 * gb, "Unisoc T7280"))
        assertEquals(PerfClass.LOW, DeviceClass.forSoc("Spreadtrum", "T620"))
        assertEquals(PerfClass.LOW, DeviceClass.forSoc("Unisoc", "ums9230"))
    }

    @Test fun galaxyA05AndA05sAreLow() {
        assertEquals(PerfClass.LOW, phone(8, 1900, 4 * gb, "Mediatek MT6769Z"))   // A05, Helio G85
        assertEquals(PerfClass.LOW, DeviceClass.forSoc("Mediatek", "MT6769V/CZ"))
        assertEquals(PerfClass.LOW, phone(8, 2100, 4 * gb, "QTI SM6225"))         // A05s, Snapdragon 680
    }

    @Test fun galaxyA10Exynos7884IsLowFromCpuinfo() {
        // Android 11 at most, so no SOC_MODEL: the name comes from cpuinfo and the board.
        val hw = DeviceClass.cpuinfoHardware("processor\t: 7\nHardware\t: Samsung EXYNOS7884B\n")
        assertEquals("Samsung EXYNOS7884B", hw)
        assertEquals(PerfClass.LOW, phone(8, 1450, 2 * gb, hw!!, "universal7884B"))
    }

    @Test fun pixel5Sm7250StaysAverage() {
        // Snapdragon 765G: must keep its glass and the full chat slide.
        assertNull(DeviceClass.forSoc("QTI", "SM7250"))
        assertEquals(PerfClass.AVERAGE, phone(8, 2000, 8 * gb, "QTI SM7250", "lito", "redfin"))
        assertTrue(PowerRules.glass(34, PerfClass.AVERAGE, true))
    }

    @Test fun snapdragon8Gen2IsHigh() {
        assertNull(DeviceClass.forSoc("QTI", "SM8550"))
        assertEquals(PerfClass.HIGH, phone(8, 2700, 12 * gb, "QTI SM8550", "kalama"))
    }

    @Test fun socMatchNeedsDigitBoundaries() {
        assertNull(DeviceClass.forSoc("QTI", "SM62250"))
        assertNull(DeviceClass.forSoc("Mediatek", "MT67691"))
        assertNull(DeviceClass.forSoc(null, null))
        assertNull(DeviceClass.forSoc("", ""))
        assertNull(DeviceClass.cpuinfoHardware("processor : 0\nBogoMIPS : 52.00\n"))
        assertEquals(PerfClass.LOW, DeviceClass.forSoc(null, "Qualcomm Technologies, Inc SDM450"))
    }

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
