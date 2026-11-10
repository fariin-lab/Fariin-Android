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
)

object DeviceClass {
    private const val PREFS = "fariin_perf"
    private const val KEY = "class"
    private const val KEY_VERSION = "classVersion"
    /** Bump when the rules change, so phones are measured again once. */
    private const val RULES_VERSION = 1
    private const val GB = 1024L * 1024 * 1024

    @Volatile private var cached: PerfClass? = null

    /** Pure rule, unit tested. */
    fun classify(s: DeviceSpecs): PerfClass {
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
        return DeviceSpecs(
            totalRamBytes = mem.totalMem,
            cores = cores,
            avgMaxFreqMhz = averageMhz(freqs),
            memoryClassMb = am.memoryClass,
            sdk = Build.VERSION.SDK_INT,
            lowRamDevice = am.isLowRamDevice,
        )
    }
}
