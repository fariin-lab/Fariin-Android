package com.fariin.app.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The user's switches on Settings > Power Saving. True = the effect is allowed. */
data class PowerChoices(
    val blur: Boolean,
    val animations: Boolean,
    val autoplayVideos: Boolean,
    val autoplayGifs: Boolean,
    val followBatterySaver: Boolean,
)

/**
 * The one state the UI reads. The switches as saved, plus whether the phone's Battery Saver is on;
 * the effective values turn every effect off while Battery Saver is on and the user asked for that.
 */
data class PowerState(
    val choices: PowerChoices,
    val batterySaverOn: Boolean,
    val deviceClass: PerfClass,
) {
    private val saving get() = choices.followBatterySaver && batterySaverOn
    val blur get() = choices.blur && !saving
    val animations get() = choices.animations && !saving
    val autoplayVideos get() = choices.autoplayVideos && !saving
    val autoplayGifs get() = choices.autoplayGifs && !saving
    /** Real glass (background blur) only on Android 12+, a phone that is not LOW, and blur allowed. */
    fun glass(sdk: Int = Build.VERSION.SDK_INT) = PowerRules.glass(sdk, deviceClass, blur)
    /**
     * A LOW phone draws the cheap way: no soft shadows under floating buttons and bars (the phone
     * recomputes each one on every frame while a list moves under it), and drawings that never
     * change kept as a picture. Every other phone keeps the full look.
     */
    val cheapDrawing get() = deviceClass == PerfClass.LOW
}

/** Pure rules, unit tested. */
object PowerRules {
    fun defaults(cls: PerfClass): PowerChoices {
        val on = cls != PerfClass.LOW
        return PowerChoices(blur = on, animations = on, autoplayVideos = on, autoplayGifs = on, followBatterySaver = true)
    }
    fun glass(sdk: Int, cls: PerfClass, blurAllowed: Boolean) = sdk >= 31 && cls != PerfClass.LOW && blurAllowed
}

object PowerSaving {
    private const val PREFS = "fariin_power"
    const val K_BLUR = "power.blur"
    const val K_ANIM = "power.animations"
    const val K_VIDEO = "power.autoplayVideos"
    const val K_GIFS = "power.autoplayGifs"
    const val K_FOLLOW = "power.followBatterySaver"

    private val _state = MutableStateFlow(PowerState(PowerRules.defaults(PerfClass.AVERAGE), false, PerfClass.AVERAGE))
    val state: StateFlow<PowerState> = _state
    @Volatile private var started = false

    /** Safe to call more than once. Reads the class, the switches, and starts watching Battery Saver. */
    fun init(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val app = context.applicationContext
            val cls = DeviceClass.get(app)
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            _state.value = PowerState(load(app, cls), pm.isPowerSaveMode, cls)
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    _state.value = _state.value.copy(batterySaverOn = pm.isPowerSaveMode)
                }
            }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun load(c: Context, cls: PerfClass): PowerChoices {
        val d = PowerRules.defaults(cls)
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PowerChoices(
            blur = p.getBoolean(K_BLUR, d.blur),
            animations = p.getBoolean(K_ANIM, d.animations),
            autoplayVideos = p.getBoolean(K_VIDEO, d.autoplayVideos),
            autoplayGifs = p.getBoolean(K_GIFS, d.autoplayGifs),
            followBatterySaver = p.getBoolean(K_FOLLOW, d.followBatterySaver),
        )
    }

    fun set(context: Context, key: String, value: Boolean) {
        init(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
        val ch = _state.value.choices
        _state.value = _state.value.copy(choices = when (key) {
            K_BLUR -> ch.copy(blur = value)
            K_ANIM -> ch.copy(animations = value)
            K_VIDEO -> ch.copy(autoplayVideos = value)
            K_GIFS -> ch.copy(autoplayGifs = value)
            K_FOLLOW -> ch.copy(followBatterySaver = value)
            else -> ch
        })
    }
}

/** The Power Saving state for a composable; recomposes when a switch or Battery Saver changes. */
@Composable
fun rememberPowerState(): PowerState {
    // Measured here too, not only by the tab shell: a screen shown before the shell (a chat opened
    // straight away) read the defaults for an average phone, so a LOW phone drew everything.
    // A no-op once started.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember { PowerSaving.init(context) }
    val s by PowerSaving.state.collectAsState()
    return s
}
