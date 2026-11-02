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

    /**
     * After every signed-in launch and sign-in: save the token we hold for whoever is signed in now.
     * Show Notifications off survives a log out (the wipe clears the local switch, so a sign-in with
     * none asks the account's `notifPush`).
     */
    fun register(c: Context) {
        val app = c.applicationContext
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
            val token = try { FirebaseMessaging.getInstance().token.await() } catch (_: Exception) { null }
            if (!token.isNullOrEmpty()) saveToken(token, uid)
        }
    }

    /** A new or rotated token (FirebaseMessagingService.onNewToken). */
    fun onNewToken(c: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!pushOn(c)) return
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
    suspend fun unregister(c: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val token = try { FirebaseMessaging.getInstance().token.await() } catch (_: Exception) { null } ?: return
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
                return
            } catch (_: Exception) {
                if (attempt == 2) return
                delay(400)
            }
        }
    }

    /** Sign-out wipes the local switch and the explainer stays answered (iOS SessionWipe keeps
     *  the first-run flags, clears the account's settings). */
    fun clearAccount(c: Context) { prefs(c).edit().remove(PUSH_ON).apply() }
}
