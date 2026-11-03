package com.fariin.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.fariin.app.data.AuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.io.File

/**
 * DEBUG BUILDS ONLY: the local test backend (Fariin-Android/emulator: Firebase Auth, Firestore and
 * Storage emulators with fake users, project demo-fariin). Switched on by a flag file the developer
 * creates with
 *
 *     adb shell run-as com.fariin.app touch files/use_local_backend
 *
 * and off by deleting it. Set here, before anything touches Firebase, so the activity, the push
 * service and the call service all talk to the same backend. Push (FCM) has no emulator and is
 * skipped entirely (Push.register), so no token is ever made against the real project. 10.0.2.2 is the PC as seen from the
 * Android emulator. A release build never reads the flag.
 */
class FariinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.fariin.app.media.ChatSounds.init(this)
        com.fariin.app.data.ContactNames.init(this)
        com.fariin.app.data.AppFeatures.init(this)   // the admin panel's switches, as of the last open
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // The benchmark build (release code, debug key, never shipped) may use the local backend too.
        val benchmark = runCatching {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getBoolean("fariin.local_backend_allowed") == true
        }.getOrDefault(false)
        val local = (debuggable || benchmark) && File(filesDir, LOCAL_FLAG).exists()
        // Debug builds start Firebase here (the debug manifest removes FirebaseInitProvider), so a
        // local run can use the emulators' project id. Without it every read and write went to a
        // "kulan-2ef85" namespace inside the emulator, where no seeded user exists. Release builds
        // keep the provider, so the app is already there.
        if (FirebaseApp.getApps(this).isEmpty()) {
            if (local) {
                val real = FirebaseOptions.fromResource(this)
                if (real != null) FirebaseApp.initializeApp(this, FirebaseOptions.Builder(real)
                    .setProjectId(LOCAL_PROJECT).setStorageBucket("$LOCAL_PROJECT.appspot.com").build())
            } else {
                FirebaseApp.initializeApp(this)
            }
        }
        // App Check (iOS AppCheckSetup): every Firebase call carries a token proving it comes from
        // this app on a real phone. Not on the local backend, which has no App Check.
        if (!local) com.fariin.app.system.AppCheckSetup.install(debuggable)
        if (local) {
            FirebaseAuth.getInstance().useEmulator(HOST, 9099)
            FirebaseFirestore.getInstance().useEmulator(HOST, 8080)
            FirebaseStorage.getInstance().useEmulator(HOST, 9199)
            // Cloud Functions are NOT emulated. Point every callable at a local port anyway, so a
            // test run can never reach the real project's functions (they simply fail here).
            FirebaseFunctions.getInstance().useEmulator(HOST, 5001)
            FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).useEmulator(HOST, 5001)
            usingLocalBackend = true
            Log.i("FariinApp", "Using the LOCAL test backend at $HOST")
        }
        // Slow networks: a photo download gives up after 2 minutes (default 10) so the bubble shows
        // its "Try again" disc instead of a ring that never ends; uploads keep the 10 minute window.
        runCatching { FirebaseStorage.getInstance().maxDownloadRetryTimeMillis = 120_000 }
        CrashReports.start(this, debuggable)
        // The phone's own copies (chat list, newest messages, drafts, the send queue) and the
        // app-wide front/back hooks (presence, reconnect).
        com.fariin.app.local.LocalCache.init(this)
        com.fariin.app.local.Drafts.init(this)
        com.fariin.app.local.SendQueue.init(this)
        // The time zone rules and date formats load on first use, about 100 ms on a cheap phone,
        // and the first use was the opening chat's day separators. Loaded here off the main thread.
        Thread({
            runCatching {
                val now = System.currentTimeMillis().toDouble()
                com.fariin.app.data.ThreadLogic.dayLabel(now, now)
                com.fariin.app.data.ThreadLogic.timeLabel(now, false)
            }
        }, "warm-dates").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        AppLifecycle.install(this)
        // "Waiting for network" / "Connecting" in the chat list title (iOS ConnectionStatus).
        com.fariin.app.data.ConnectionStatus.start(this)
        // Settings that the rest of the app reads: my privacy map (presence, read receipts, typing)
        // and my private block list follow whoever is signed in; App Lock and Screen Security
        // watch every activity.
        com.fariin.app.data.PrivacySettings.init(this)
        com.fariin.app.data.Disappearing.init(this)
        FirebaseAuth.getInstance().addAuthStateListener { a ->
            val uid = a.currentUser?.uid
            com.fariin.app.data.PrivacySettings.follow(uid)
            com.fariin.app.data.BlockList.follow(uid)
        }
        com.fariin.app.security.AppLock.install(this)
        // The home screen widget follows App Lock and Message Preview as they are switched.
        com.fariin.app.system.FariinWidget.init(this)
    }

    companion object {
        const val LOCAL_FLAG = "use_local_backend"
        private const val HOST = "10.0.2.2"
        private const val LOCAL_PROJECT = "demo-fariin"
        /** True when this run talks to the local emulators, never the real project. */
        @Volatile var usingLocalBackend = false
            private set
    }
}
