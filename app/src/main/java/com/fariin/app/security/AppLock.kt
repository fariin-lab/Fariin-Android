package com.fariin.app.security

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager

/**
 * Privacy & Security > App Lock and Screen Security, after Kulan-iOS AppLockPage
 * (`appLockEnabled`, `appLockDelay` 0 / 60 / 300 / 3600 s, `screenSecurity`), kept on this phone
 * only as iOS keeps them in UserDefaults.
 *
 * App Lock asks for the phone's own screen lock (fingerprint, face or PIN through BiometricPrompt,
 * LockActivity) when Fariin comes back after the delay, and on a cold start. Screen Security sets
 * FLAG_SECURE on every window: no screenshots, a blank card in Recents. App Lock turns it on too
 * (iOS shows the switch forced on while App Lock is on). The call screens are never locked, so an
 * incoming call can be answered.
 */
object AppLock {
    private const val PREFS = "fariin_security"
    const val ENABLED = "appLockEnabled"
    const val DELAY = "appLockDelay"
    const val SCREEN = "screenSecurity"
    val delays = listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 3600 to "After 1 hour")

    private lateinit var app: Application
    private var started = 0
    /** Started activities that are not the call screen (LockActivity counts: it is the app). */
    private var appStarted = 0
    /** The app went out of sight since the last check (Home, or the call shrank into its window). */
    private var pending = false
    /** The call screen is in its picture-in-picture window, so it no longer shows the app. */
    private var callInPip = false
    private var backgroundAt = -1L          // elapsedRealtime when the last activity stopped; -1 = cold
    @Volatile var unlocked = false
        private set

    private fun prefs(c: Context = app) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context = app) = prefs(c).getBoolean(ENABLED, false)
    fun delaySeconds(c: Context = app) = prefs(c).getInt(DELAY, 0)
    fun screenSecurity(c: Context = app) = prefs(c).getBoolean(SCREEN, false)
    /** What FLAG_SECURE follows. */
    fun secureWindows(c: Context = app) = screenSecurity(c) || enabled(c)

    fun delayLabel(s: Int) = delays.firstOrNull { it.first == s }?.second ?: "Immediately"

    /** The phone has a PIN, pattern or password (App Lock needs one). */
    fun deviceSecure(c: Context): Boolean =
        (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(ENABLED, on).apply()
        if (on) unlocked = true     // turned on just now, by the person holding the phone
        reapply()
    }
    fun setDelay(c: Context, s: Int) = prefs(c).edit().putInt(DELAY, s).apply()
    fun setScreenSecurity(c: Context, on: Boolean) { prefs(c).edit().putBoolean(SCREEN, on).apply(); reapply() }

    /** Pure: should the lock show when the app comes to the front? */
    fun shouldLock(enabled: Boolean, unlocked: Boolean, backgroundAt: Long, now: Long, delaySeconds: Int): Boolean {
        if (!enabled) return false
        if (backgroundAt < 0) return !unlocked                 // cold start
        return now - backgroundAt >= delaySeconds * 1000L
    }

    fun onUnlocked() { unlocked = true }

    private val live = mutableSetOf<Activity>()
    private fun reapply() { live.forEach { apply(it) } }
    /** Windows a screen asked to keep secure (a restricted chat), counted per window. While a
     *  window holds any, a resume never clears its FLAG_SECURE. */
    private val secureHolds = HashMap<android.view.Window, Int>()

    fun holdSecure(w: android.view.Window) {
        secureHolds[w] = (secureHolds[w] ?: 0) + 1
        w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun releaseSecure(c: Context, w: android.view.Window) {
        val n = (secureHolds[w] ?: 0) - 1
        if (n > 0) secureHolds[w] = n else secureHolds.remove(w)
        if (n <= 0 && !secureWindows(c)) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun apply(a: Activity) {
        if (secureWindows(a) || (secureHolds[a.window] ?: 0) > 0) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun callScreen(a: Activity) = a !is LockActivity && a.javaClass.simpleName.contains("Call")
    private fun exempt(a: Activity) = a is LockActivity || callScreen(a)

    private fun hidden() { backgroundAt = SystemClock.elapsedRealtime(); pending = true }

    /**
     * The call screen entered or left picture-in-picture. [byButton]: its own minimize, inside the
     * app; any other way in (Home, Recents) means the person left Fariin, so the next return asks.
     */
    fun onCallPip(inPip: Boolean, byButton: Boolean) {
        callInPip = inPip
        if (inPip && !byButton && appStarted == 0) hidden()
    }

    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { live.add(a); apply(a) }
            override fun onActivityStarted(a: Activity) {
                started++
                if (!callScreen(a)) appStarted++
                if (exempt(a)) return
                // Checked on the first app screen after the app was out of sight, even when a call
                // in picture-in-picture (or a call that woke the app) kept an activity started.
                val cold = backgroundAt < 0 && !unlocked
                if (!pending && !cold) return
                pending = false
                if (live.any { it is LockActivity && !it.isFinishing }) return
                val now = SystemClock.elapsedRealtime()
                if (shouldLock(enabled(), unlocked, backgroundAt, now, delaySeconds())) {
                    unlocked = false
                    a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
            override fun onActivityResumed(a: Activity) { apply(a) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (!callScreen(a)) appStarted = (appStarted - 1).coerceAtLeast(0)
                // Out of sight: nothing started, or only the call's picture-in-picture window left.
                if (started == 0 || (appStarted == 0 && callInPip && !callScreen(a))) hidden()
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { live.remove(a); secureHolds.remove(a.window) }
        })
    }
}
