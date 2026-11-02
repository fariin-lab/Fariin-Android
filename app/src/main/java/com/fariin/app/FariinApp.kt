package com.fariin.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
 * service and the call service all talk to the same backend. 10.0.2.2 is the PC as seen from the
 * Android emulator. A release build never reads the flag.
 */
class FariinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && File(filesDir, LOCAL_FLAG).exists()) {
            FirebaseAuth.getInstance().useEmulator(HOST, 9099)
            FirebaseFirestore.getInstance().useEmulator(HOST, 8080)
            FirebaseStorage.getInstance().useEmulator(HOST, 9199)
            usingLocalBackend = true
            Log.i("FariinApp", "Using the LOCAL test backend at $HOST")
        }
    }

    companion object {
        const val LOCAL_FLAG = "use_local_backend"
        private const val HOST = "10.0.2.2"
        /** True when this run talks to the local emulators, never the real project. */
        @Volatile var usingLocalBackend = false
            private set
    }
}
