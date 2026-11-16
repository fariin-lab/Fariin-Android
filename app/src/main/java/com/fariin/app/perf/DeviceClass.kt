package com.fariin.app.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * How strong this phone is, measured once on the first launch and kept in prefs, so the app can
 * pick cheaper drawing (no glass blur, no autoplay) on a weak phone without guessing every time.
 *
 * Three classes. The thresholds are our own, chosen from what is known to stutter:
 *  - LOW: the system calls it a low-RAM phone, or under 2 GB of RAM, or 2 cores or fewer, or
 *    4 cores or fewer running at 1.25 GHz or less, or an app heap of 96 MB or less.
 *  - AVERAGE: fewer than 8 cores, or a top speed of 2.05 GHz or less, or under 4 GB of RAM, or an
 *    app heap of 192 MB or less, or Android 9 and older.
 *  - HIGH: everything else.
 *
 * On top of that, a chip on our list of known weak chips is LOW whatever its core count says. The
 * reference app does the same: many cheap phones have 8 slow cores, lots of RAM and a tiny GPU, so
 * the numbers above call them AVERAGE and they get glass blur they cannot draw. A nubia V80 Design
 * (Unisoc T7280, one-core Mali-G57) lagged opening chats this way (tester, 2026-11-15).
 */
enum class PerfClass { LOW, AVERAGE, HIGH }

/** What we read from the phone. [avgMaxFreqMhz] is null when the kernel does not expose it. */
data class DeviceSpecs(
    val totalRamBytes: Long,
    val cores: Int,
    val avgMaxFreqMhz: Int?,
    val memoryClassMb: Int,
    val sdk: Int,
    val lowRamDevice: Boolean,
    /** Names of the chip: Build.SOC_MODEL on Android 12+, the cpuinfo "Hardware" line, the board. */
    val socNames: List<String> = emptyList(),
)

object DeviceClass {
    private const val PREFS = "fariin_perf"
    private const val KEY = "class"
    private const val KEY_VERSION = "classVersion"
    /** Bump when the rules change, so phones are measured again once. */
    private const val RULES_VERSION = 2
    private const val GB = 1024L * 1024 * 1024

    @Volatile private var cached: PerfClass? = null

    /**
     * Known weak chips, written the way the phone reports them, lower case with only letters and
     * digits kept. Most have a one or two core Mali-G52/G57, a PowerVR GE8320 or an Adreno 5xx/610
     * to 619 GPU. Unisoc renamed its chips in 2023 (T606 is now T7200, T620 is now T7280), so both
     * names are here, plus the platform codes the phone may report instead (ums9230, ums512).
     */
    private val LOW_SOCS = listOf(
        // Unisoc / Spreadtrum
        "sc9863a", "sc9863", "ums312", "ums512", "ums9230",
        "t310", "t606", "t610", "t612", "t616", "t618", "t620", "t700",
        "t7200", "t7225", "t7250", "t7280",
        // MediaTek: MT6739, Helio A22/P22/P35, G70/G80/G85/G88, G96, G99, Dimensity 700
        "mt6739", "mt6761", "mt6762", "mt6765", "mt6768", "mt6769", "mt6781", "mt6789", "mt6833",
        // Qualcomm: Snapdragon 429/439/450/625/632, 460, 480, 662, 665, 680, 4 Gen 1, 4 Gen 2
        "sdm429", "sdm439", "sdm450", "sdm632", "msm8953",
        "sm4250", "sm4350", "sm6115", "sm6125", "sm6225", "sm4375", "sm4450",
        // Samsung: Exynos 850 (s5e3830) and Exynos 7884/7904 (Galaxy A10, A20, A30)
        "s5e3830", "exynos850", "exynos7884", "exynos7904",
    )

    /** Lower case, letters and digits only, so "UMS9230", "ums9230" and "MT6769V/CZ" compare alike. */
    private fun normalise(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * LOW when the chip is on [LOW_SOCS], else null (the other rules decide). A name matches when it
     * holds a list entry with no digit right before or after it, so "mt6769z" and "Qualcomm
     * Technologies, Inc SDM450" match, but "sm62250" or "mt67691" do not. Pure, unit tested.
     */
    fun forSoc(socManufacturer: String?, socModel: String?): PerfClass? {
        val name = normalise("${socManufacturer.orEmpty()} ${socModel.orEmpty()}")
        if (name.isEmpty()) return null
        val hit = LOW_SOCS.any { key ->
            var i = name.indexOf(key)
            var found = false
            while (i >= 0 && !found) {
                val before = name.getOrNull(i - 1)
                val after = name.getOrNull(i + key.length)
                found = (before == null || !before.isDigit()) && (after == null || !after.isDigit())
                i = name.indexOf(key, i + 1)
            }
            found
        }
        return if (hit) PerfClass.LOW else null
    }

    /** The "Hardware" line of /proc/cpuinfo's text, or null when there is none. */
    fun cpuinfoHardware(cpuinfo: String?): String? = cpuinfo?.lineSequence()
        ?.firstOrNull { it.trimStart().startsWith("Hardware", ignoreCase = true) && it.contains(':') }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** Pure rule, unit tested. */
    fun classify(s: DeviceSpecs): PerfClass {
        if (s.socNames.any { forSoc(null, it) == PerfClass.LOW }) return PerfClass.LOW
        val freq = s.avgMaxFreqMhz
        val low = s.lowRamDevice ||
            (s.totalRamBytes in 1 until 2 * GB) ||
            (s.cores in 1..2) ||
            (freq != null && freq <= 1250 && s.cores <= 4) ||
            (s.memoryClassMb in 1..96)
        if (low) return PerfClass.LOW
        val average = s.cores < 8 ||
            (freq != null && freq <= 2055) ||
            (s.totalRamBytes in 1 until 4 * GB) ||
            (s.memoryClassMb in 1..192) ||
            s.sdk < 29
        return if (average) PerfClass.AVERAGE else PerfClass.HIGH
    }

    /** Average of the cores' top speeds in MHz from cpufreq files' contents in kHz; null when none parse. */
    fun averageMhz(khzValues: List<String?>): Int? {
        // Under 200 MHz is not a real top speed (some emulators and kernels write junk such as "2").
        val mhz = khzValues.mapNotNull { it?.trim()?.toLongOrNull() }.map { it / 1000 }.filter { it >= 200 }
        return if (mhz.isEmpty()) null else (mhz.sum() / mhz.size).toInt()
    }

    /** The class if it was already measured this run (no context needed); null before. */
    fun peek(): PerfClass? = cached

    /** The saved class, measuring once when there is none. Cheap after the first call. */
    fun get(context: Context): PerfClass {
        cached?.let { return it }
        // The Pixel test build only: a file the developer writes over adb makes this phone act as a
        // weaker class, to measure what a cheap phone gets (owner 2026-11-10, a friend's Nubia lagged).
        if (context.resources.getBoolean(com.fariin.app.R.bool.fariin_debug_appcheck)) {
            val f = java.io.File(context.getExternalFilesDir(null), "perf_class")
            val forced = runCatching { if (f.exists()) PerfClass.valueOf(f.readText().trim()) else null }.getOrNull()
            if (forced != null) { cached = forced; return forced }
        }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString(KEY, null)?.let { runCatching { PerfClass.valueOf(it) }.getOrNull() }
        if (saved != null && p.getInt(KEY_VERSION, 0) == RULES_VERSION) {
            cached = saved; return saved
        }
        val cls = classify(read(context))
        p.edit().putString(KEY, cls.name).putInt(KEY_VERSION, RULES_VERSION).apply()
        cached = cls
        return cls
    }

    fun read(context: Context): DeviceSpecs {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText() }.getOrNull()
        }
        // The chip's name. Android 12+ reports it directly; older phones only through cpuinfo or the
        // board name. There is no GPU check: reading the GPU name needs a GL context, which nothing
        // in the app makes at start, and making one just for this would slow the first launch.
        val socNames = buildList {
            if (Build.VERSION.SDK_INT >= 31) add("${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            runCatching { cpuinfoHardware(File("/proc/cpuinfo").readText()) }.getOrNull()?.let { add(it) }
            add(Build.HARDWARE.orEmpty())
            add(Build.BOARD.orEmpty())
        }.filter { it.isNotBlank() }
        return DeviceSpecs(
            totalRamBytes = mem.totalMem,
            cores = cores,
            avgMaxFreqMhz = averageMhz(freqs),
            memoryClassMb = am.memoryClass,
            sdk = Build.VERSION.SDK_INT,
            lowRamDevice = am.isLowRamDevice,
            socNames = socNames,
        )
    }
}
