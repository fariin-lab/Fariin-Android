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
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val local = debuggable && File(filesDir, LOCAL_FLAG).exists()
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
