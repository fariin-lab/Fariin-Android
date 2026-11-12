package com.fariin.app

import okio.Path.Companion.toOkioPath
import coil3.ImageLoader
import coil3.request.allowRgb565
import com.fariin.app.ui.photo.FariinPhotoFetcher
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
        StartClock.mark("app.start")
        // A launcher icon pick the app died before finishing leaves two icons; finish it.
        runCatching { com.fariin.app.ui.settings.AppIcons.heal(this) }
        // The picture loader, set ONCE before any screen draws. It was set by MainActivity's content,
        // so a call that woke the app straight into CallActivity made the loader first, and opening
        // the app then crashed ("singleton image loader has already been created") and dropped the
        // call (owner's Pixel 15:03, 2026-11-05).
        coil3.SingletonImageLoader.setSafe { ctx ->
                val lowRam = com.fariin.app.media.LowEnd.isLowRam(ctx)
                ImageLoader.Builder(ctx)
                    .components { add(FariinPhotoFetcher.Factory()) }
                    // Decoded pictures kept in memory: a smaller share of the app's memory on a
                    // low-RAM phone (Android Go, 1 GB), and 2 bytes a pixel there instead of 4.
                    .memoryCache {
                        coil3.memory.MemoryCache.Builder()
                            .maxSizePercent(ctx, com.fariin.app.media.LowEnd.memoryCachePercent(lowRam))
                            .build()
                    }
                    .let { if (lowRam) it.allowRgb565(true) else it }
                    // Faces and photos on disk, capped like iOS DiskImageCache (250 MB), wiped at sign-out.
                    .diskCache {
                        coil3.disk.DiskCache.Builder()
                            .directory(java.io.File(ctx.cacheDir, "image_cache").toOkioPath())
                            .maxSizeBytes(250L * 1024 * 1024)
                            .build()
                    }
                    .build()
        }
        StartClock.mark("coil")
        CoroutineSafety.install()   // an uncaught server refusal in a coroutine no longer closes the app
        // Not needed for the first frame (owner 2026-11-09: the app felt heavy to open; the reference
        // app keeps start-up to the minimum and does the rest after): the sent sound off the main
        // thread, and the reaction art a few seconds later so it does not compete with the first frames.
        Thread({ runCatching { com.fariin.app.media.ChatSounds.init(this) } }, "sounds-init").apply { isDaemon = true }.start()
        com.fariin.app.ui.thread.ReactionArt.init(this)
        // The settings files a chat reads as it opens, loaded off the main thread now: the first read
        // of each parsed its file on the main thread inside the chat's first frame (~7 ms each).
        // A moment after launch (started with it, it slowed the first frame by ~40 ms on the Pixel).
        android.os.Handler(mainLooper).postDelayed({
        Thread({ for (f in listOf(com.fariin.app.ui.settings.CHAT_PREFS, "fariin_privacy", "fariin_history_start")) runCatching {
            getSharedPreferences(f, MODE_PRIVATE).all }
            // Messages I deleted for me: read once here, not by the first chat that opens.
            runCatching { com.fariin.app.data.HiddenMessages.load(this) } }, "prefs-prefetch").apply { isDaemon = true }.start() }, PREFS_PREFETCH_DELAY_MS)
        StartClock.mark("prefs+reactionart")
        com.fariin.app.ui.thread.DecodedPictures.init(this)
        com.fariin.app.ui.thread.StickerLottie.init(this)
        android.os.Handler(mainLooper).postDelayed({ com.fariin.app.ui.thread.Emoji3DArt.preload(this) }, 4_000)
        StartClock.mark("stickers")
        com.fariin.app.data.ContactNames.init(this)
        com.fariin.app.data.MsgPolicy.init(this)     // Message Requests on/off, as of the last open
        com.fariin.app.data.AppFeatures.init(this)   // the admin panel's switches, as of the last open
        com.fariin.app.data.AppearancePrefs.applySplash(this)   // the next launch screen follows Night Mode
        StartClock.mark("features")
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // The benchmark build (release code, debug key, never shipped) may use the local backend too.
        // Asked only when the flag file is there: the package manager call is a binder round trip
        // on every cold start (~5-10 ms on the Pixel), and real phones never have the file.
        val flagFile = File(filesDir, LOCAL_FLAG).exists()
        val benchmark = flagFile && !debuggable && runCatching {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getBoolean("fariin.local_backend_allowed") == true
        }.getOrDefault(false)
        val local = (debuggable || benchmark) && flagFile
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
        StartClock.mark("firebase")
        if (!local) com.fariin.app.system.AppCheckSetup.install(debuggable || resources.getBoolean(R.bool.fariin_debug_appcheck))
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
        StartClock.mark("appcheck")
        // A call this phone was in when the app last died is ended for the other phone (CallManager).
        // Only when a call was really left open: touching CallManager loads the whole call stack,
        // ~46 ms of every cold start on the Pixel, almost always to find nothing.
        if (FirebaseAuth.getInstance().currentUser != null &&
            getSharedPreferences("fariin_call_live", MODE_PRIVATE).contains("callId")) com.fariin.app.call.CallManager.endAfterCrash(this)
        // Slow networks: a photo download gives up after 2 minutes (default 10) so the bubble shows
        // its "Try again" disc instead of a ring that never ends; uploads keep the 10 minute window.
        runCatching { FirebaseStorage.getInstance().maxDownloadRetryTimeMillis = 120_000 }
        StartClock.mark("storage")
        CrashReports.start(this, debuggable)
        // The phone's own copies (chat list, newest messages, drafts, the send queue) and the
        // app-wide front/back hooks (presence, reconnect).
        StartClock.mark("crash")
        com.fariin.app.local.LocalCache.init(this)
        // The saved chat list, read and opened now on its own thread (owner 2026-11-09: "close the app
        // and open, never comfortable"). The list asked for it only after its first frame was built,
        // and the launch logo waited ~140 ms more for the disk; now it is in memory by then.
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            Thread({
                runCatching { com.fariin.app.local.LocalCache.loadChats(uid) }
                runCatching { com.fariin.app.local.LocalCache.loadFollows(uid) }
                // The keys too (sodium load, Keystore unwrap): the first rows' previews need them.
                runCatching { com.fariin.app.data.CryptoHub.get(this).prewarm() }
            }, "chats-prefetch")
                .apply { priority = Thread.MAX_PRIORITY }.start()
        }
        StartClock.mark("localcache")
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
        StartClock.mark("drafts+queue")
        AppLifecycle.install(this)
        // "Waiting for network" / "Connecting" in the chat list title (iOS ConnectionStatus).
        com.fariin.app.data.ConnectionStatus.start(this)
        // Settings that the rest of the app reads: my privacy map (presence, read receipts, typing)
        // and my private block list follow whoever is signed in; App Lock and Screen Security
        // watch every activity.
        StartClock.mark("lifecycle+conn")
        com.fariin.app.data.PrivacySettings.init(this)
        com.fariin.app.data.Disappearing.init(this)
        FirebaseAuth.getInstance().addAuthStateListener { a ->
            val uid = a.currentUser?.uid
            // A Log Out kept its media and chats for its own return; a different account wipes them first.
            if (uid != null) com.fariin.app.device.SessionEnd.claimKept(this, uid)
            com.fariin.app.data.PrivacySettings.follow(uid)
            com.fariin.app.data.BlockList.follow(uid)
        }
        StartClock.mark("privacy+auth")
        com.fariin.app.security.AppLock.install(this)
        // Update campaigns: the last answer shows at once, then the server is asked (iOS AppUpdateGate).
        StartClock.mark("applock")
        if (!local) com.fariin.app.system.AppUpdateGate.start(this)
        // The home screen widget follows App Lock and Message Preview as they are switched.
        android.os.Handler(mainLooper).postDelayed({ com.fariin.app.system.FariinWidget.init(this) }, 1_500)
        StartClock.mark("app.end")
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

/** Start-up timing marks in logcat (tag FariinStart): ms since this process began. */
object StartClock {
    fun mark(step: String) {
        if (android.os.Build.VERSION.SDK_INT >= 24)
            Log.i("FariinStart", "$step ${android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()}")
    }
}

/** When the chat prefs are read ahead after launch: after the first frames, before a first tap. */
private const val PREFS_PREFETCH_DELAY_MS = 700L
