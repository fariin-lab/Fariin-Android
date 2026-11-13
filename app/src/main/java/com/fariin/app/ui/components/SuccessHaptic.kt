package com.fariin.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The iOS "success" haptic (UINotificationFeedbackGenerator .success): two quick taps, the second
 * stronger, so a person feels that something was done (owner 2026-11-12: the view's CONFIRM
 * constant is so soft on the Pixel that he only felt it with the ringer on silent). Played on the
 * vibrator itself, the same with the sound on or off; the phone's "Vibration" switch still wins.
 */
object SuccessHaptic {
    private fun vibrator(context: Context): Vibrator? {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        return v?.takeIf { it.hasVibrator() }
    }

    /** One soft tap: "that did something" (owner 2026-11-13, following a channel). */
    fun soft(context: Context) {
        val v = vibrator(context) ?: return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                    v.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f).compose())
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                else -> v.vibrate(VibrationEffect.createOneShot(20, 120))
            }
        }
    }

    fun play(context: Context) {
        val v = vibrator(context) ?: return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                    v.vibrate(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 70)
                        .compose())
                Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                else -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 35), -1))
            }
        }
    }
}
