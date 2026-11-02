package com.fariin.app.call

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration

/**
 * Listens for calls ringing me while the app process is alive (iOS observeIncoming). A ring that
 * arrives while the app is closed needs a data push from the server (see PROGRESS.md, server
 * changes): FariinMessagingService then calls [onCallPush], which attaches this listener.
 */
object IncomingCallWatcher {
    private var reg: ListenerRegistration? = null
    private var uid: String? = null

    fun start(context: Context, me: String) {
        if (uid == me && reg != null) return
        stop()
        uid = me
        val app = context.applicationContext
        CallManager.init(app)
        CallManager.warm()
        reg = CallSignaling().listenIncoming(me) { docs, created -> CallManager.onRingingDocs(app, docs, created) }
    }

    fun stop() { reg?.remove(); reg = null; uid = null }

    /** A call push (data `kind == "call"`): make sure the ring listener is up; it does the rest. */
    fun onCallPush(context: Context, me: String) = start(context, me)
}
