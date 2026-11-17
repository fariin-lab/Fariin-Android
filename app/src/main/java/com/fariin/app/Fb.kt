package com.fariin.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * The app's one Firebase Auth and Firestore, looked up once. Each getInstance() call takes
 * FirebaseApp's global lock; with ~300 call sites, a background worker holding it while the main
 * thread asked made the main thread wait inside a chat's opening frame (5-14 ms on the cheap
 * emulator, monitor-contention trace 2026-11-17). Same instances as before; only the lookups go.
 * FariinApp.onCreate points them at the local emulators first when it uses them.
 */
object Fb {
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
}
