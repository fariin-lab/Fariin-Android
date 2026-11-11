package com.fariin.app.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.fariin.app.device.DeviceRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Push tokens and the notification permission: a port of Kulan-iOS/Kulan/Services/PushManager.swift
 * (`Push`: register, saveToken, unregister, needsExplainer, finishExplainer).
 *
 * WHERE THE TOKEN GOES. The server reads `users/{uid}/push/tokens.fcmTokens` (the old home on the
 * user doc is scrubbed of this token, as iOS does) and sends every message push there, so the
 * Android token sits in that same list. It is listed again under `androidFcmTokens`, the way iOS
 * lists its VoIP token under `groupRingTokens` / `voipCancelTokens`: a capability marker the server
 * does not read yet, so a later server change can send Android a data-only push (PROGRESS.md,
 * "Server changes needed"). The rules let the owner write any field of that doc.
 */
object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS = "fariin_push"
    /** Settings > Notifications > Show Notifications (iOS `notif.push`, absent = on). */
    const val PUSH_ON = "notif.push"
    private const val EXPLAINER_SEEN = "firstRun.notificationsExplainerSeen"
    private const val ASKED = "notif.systemAsked"
    const val ANDROID_FIELD = "androidFcmTokens"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pushOn(c: Context): Boolean = prefs(c).getBoolean(PUSH_ON, true)

    fun permitted(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Should the first-run notifications page show? Only on a phone that was never asked, with the
     * switch on, until the page has been answered once (Push.needsExplainer). Before Android 13 there
     * is no question to ask, so never.
     */
    fun needsExplainer(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && pushOn(c) && !prefs(c).getBoolean(EXPLAINER_SEEN, false) && !permitted(c)

    /** The page was answered (Continue or Not now). The system question itself is asked by the page. */
    fun markExplainerSeen(c: Context) { prefs(c).edit().putBoolean(EXPLAINER_SEEN, true).apply() }

    /** Android's notifications question was shown once on this install. */
    fun markAsked(c: Context) { prefs(c).edit().putBoolean(ASKED, true).apply() }

    /**
     * Android will not ask again: asked before, still off, and no reason to show its dialog (the
     * person said no for good). Then the only way on is Fariin's notification settings.
     */
    fun askBlocked(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && !permitted(c) && prefs(c).getBoolean(ASKED, false) &&
            com.fariin.app.ui.components.PermissionDenied.isBlocked(c, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * After every signed-in launch and sign-in: save the token we hold for whoever is signed in now.
     * Show Notifications off survives a log out (the wipe clears the local switch, so a sign-in with
     * none asks the account's `notifPush`).
     */
    fun register(c: Context) {
        val app = c.applicationContext
        if (com.fariin.app.FariinApp.usingLocalBackend) return   // no FCM emulator: never touch the real project
        scope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            if (!prefs(app).contains(PUSH_ON)) {
                val off = try {
                    FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
                        .getBoolean("notifPush") == false
                } catch (_: Exception) { false }
                if (off && !prefs(app).contains(PUSH_ON)) {
                    prefs(app).edit().putBoolean(PUSH_ON, false).apply()
                    unregister(app)
                    return@launch
                }
            }
            if (!pushOn(app)) return@launch
            // A token a Log Out retired goes first, so it is never saved under the new account. While
            // it is still waiting (offline, no answer), nothing is saved: the old account's pushes must
            // not reach this one. Tried a few times; the next app start tries again.
            var retired = false
            for (attempt in 0 until 5) {
                if (kotlinx.coroutines.withTimeoutOrNull(10_000) { retirePending(app) } == true) { retired = true; break }
                delay(15_000)
            }
            if (!retired) return@launch
            val token = try { FirebaseMessaging.getInstance().token.await() } catch (_: Exception) { null }
            if (!token.isNullOrEmpty()) saveToken(token, uid)
        }
    }

    /** A new or rotated token (FirebaseMessagingService.onNewToken). */
    fun onNewToken(c: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (com.fariin.app.FariinApp.usingLocalBackend) return
        if (!pushOn(c)) return
        // A retired token still waiting to be deleted: register() saves the new one once it is gone.
        if (prefs(c.applicationContext).getBoolean(RETIRE, false)) return
        DeviceRegistry.init(c)
        saveToken(token, uid)
    }

    private fun saveToken(token: String, uid: String) {
        val user = FirebaseFirestore.getInstance().collection("users").document(uid)
        user.collection("push").document("tokens").set(
            mapOf("fcmTokens" to FieldValue.arrayUnion(token), ANDROID_FIELD to FieldValue.arrayUnion(token)),
            SetOptions.merge(),
        )
        // Only OUR token comes off the old world-readable home.
        user.update("fcmTokens", FieldValue.arrayRemove(token))
        DeviceRegistry.recordFCMToken(token)
    }

    /**
     * Stop push to this phone: its token off both homes, BEFORE Auth signs out (the writes need it),
     * retried, as iOS `unregister`. Without it a signed-out phone keeps getting the account's banners.
     */
    /** True once the token is off the account; false when it may still be listed there. */
    suspend fun unregister(c: Context): Boolean {
        if (com.fariin.app.FariinApp.usingLocalBackend) return true
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val token = try { FirebaseMessaging.getInstance().token.await() } catch (_: Exception) { null } ?: return false
        val db = FirebaseFirestore.getInstance()
        val doc = db.collection("users").document(uid)
        repeat(3) { attempt ->
            try {
                val batch = db.batch()
                batch.update(doc, mapOf("fcmTokens" to FieldValue.arrayRemove(token)))
                batch.set(doc.collection("push").document("tokens"),
                    mapOf("fcmTokens" to FieldValue.arrayRemove(token), ANDROID_FIELD to FieldValue.arrayRemove(token)),
                    SetOptions.merge())
                batch.commit().await()
                return true
            } catch (_: Exception) {
                if (attempt == 2) return false
                delay(400)
            }
        }
        return false
    }

    private const val RETIRE = "token.retire"
    private val retireLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Log Out could not take this phone's token off the account (offline, or no answer in time):
     * the token itself is deleted, so the old account's pushes stop reaching this phone. Remembered
     * until it works; the next sign-in finishes it first and then saves a new token.
     */
    fun retireToken(c: Context) {
        if (com.fariin.app.FariinApp.usingLocalBackend) return
        val app = c.applicationContext
        prefs(app).edit().putBoolean(RETIRE, true).apply()
        scope.launch { retirePending(app) }
    }

    /** True when no retired token is left waiting. */
    private suspend fun retirePending(app: Context): Boolean = retireLock.withLock {
        if (!prefs(app).getBoolean(RETIRE, false)) return@withLock true
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            prefs(app).edit().remove(RETIRE).apply()
            true
        } catch (_: Exception) { false }
    }

    /** Sign-out wipes the local switch and the explainer stays answered (iOS SessionWipe keeps
     *  the first-run flags, clears the account's settings). */
    fun clearAccount(c: Context) { prefs(c).edit().remove(PUSH_ON).apply() }
}
