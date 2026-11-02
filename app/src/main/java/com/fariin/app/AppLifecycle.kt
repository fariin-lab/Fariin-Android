package com.fariin.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.Presence
import com.fariin.app.data.ThreadRepository
import com.fariin.app.local.Drafts
import com.fariin.app.media.ChatMedia
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole app going to the front or the back (not one screen), after the iOS scenePhase hooks:
 * - front: presence "online", Firestore's network on, every open chat checks its listeners
 *   (iOS reattachIfDead) and the send queue goes again;
 * - back: presence "last seen now", drafts written out.
 * The phone coming back online also drains the send queue and checks the open chats.
 */
object AppLifecycle : DefaultLifecycleObserver {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var inFront = false
        private set

    fun install(a: Application) {
        app = a
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        try {
            a.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { reconnect() }
            })
        } catch (_: Exception) {}
        // Opened media over the iOS budget goes, oldest first.
        scope.launch { runCatching { ChatMedia.trim(a) } }
    }

    override fun onStart(owner: LifecycleOwner) {
        inFront = true
        if (FirebaseAuth.getInstance().currentUser == null) return
        // Notifications of disappearing messages that ran out while the app was away.
        runCatching { com.fariin.app.push.NotificationPoster.pruneExpired(app) }
        scope.launch { Presence.set(online = true, context = app) }
        try { FirebaseFirestore.getInstance().enableNetwork() } catch (_: Exception) {}
        reconnect()
    }

    override fun onStop(owner: LifecycleOwner) {
        inFront = false
        Drafts.flush()
        if (FirebaseAuth.getInstance().currentUser == null) return
        scope.launch { Presence.set(online = false, context = app) }
    }

    /** Sign-in just finished (the app was already in front when it did). */
    fun signedIn() {
        if (inFront) scope.launch { Presence.set(online = true, context = app) }
        reconnect()
    }

    fun reconnect() {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!::app.isInitialized) return
        ThreadRepository.recoverOpen()
        ThreadRepository.drainQueue(me, CryptoHub.get(app), scope)
    }
}
