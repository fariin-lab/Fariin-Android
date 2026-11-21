package com.fariin.app

import android.content.Context
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.security.MessageDigest
import java.util.UUID

/**
 * Crash reports through Firebase Crashlytics, as iOS (Telemetry.swift): collection on, the phone's
 * facts as keys (build, Android version, model, a random install id), and the account only as a
 * salted hash, never the uid itself. iOS has no opt-out switch, so neither does this.
 *
 * NO MESSAGE CONTENT: nothing here (or anywhere in the app) logs chat text, names or keys into
 * Crashlytics; a report holds the stack trace, these keys and Android's own device facts. Debug
 * builds and the local test backend never send (the debug manifest also turns collection off
 * before the app starts), so a test run cannot reach the real project's crash list.
 */
object CrashReports {
    fun start(c: Context, debuggable: Boolean) {
        val crash = try { FirebaseCrashlytics.getInstance() } catch (_: Exception) { return }
        val send = !debuggable && !FariinApp.usingLocalBackend
        crash.setCrashlyticsCollectionEnabled(send)
        if (!send) return
        crash.setCustomKey("android", "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        crash.setCustomKey("model", Build.MODEL ?: "")
        // A package manager call and a prefs file: off the main thread at app start.
        Thread({
            val info = try { c.packageManager.getPackageInfo(c.packageName, 0) } catch (_: Exception) { null }
            crash.setCustomKey("build", "${info?.versionName ?: "?"} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0})")
            runCatching { crash.setCustomKey("install", installId(c)) }
        }, "crash-keys").apply { isDaemon = true }.start()
        com.fariin.app.Fb.auth.addAuthStateListener { a -> crash.setUserId(hashUid(a.currentUser?.uid ?: "")) }
    }

    /**
     * A listener the server refused (iOS SessionRecovery.noteRefusal + Telemetry.event, 2026-11-20 F4):
     * a non-fatal report with its code and [where] ("chat:" + the last 6 of the cid, never the whole
     * id or anything said in it). Only while signed in: one refused on its way out at sign-out is not
     * an app error. Only refusals (permission denied, unauthenticated); a missing answer is not one.
     */
    fun refusal(error: Throwable?, where: String) {
        val e = error as? com.google.firebase.firestore.FirebaseFirestoreException ?: return
        if (com.fariin.app.Fb.auth.currentUser == null) return
        val code = e.code
        if (code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED &&
            code != com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED) return
        runCatching {
            val crash = FirebaseCrashlytics.getInstance()
            crash.setCustomKey("where", where)
            crash.recordException(RuntimeException("firestore_${code.value()} at $where"))
        }
    }

    /** iOS Telemetry.hashUid: the first 10 bytes of sha256("kulan.telemetry.v1:" + uid), hex. */
    fun hashUid(uid: String): String {
        if (uid.isEmpty()) return ""
        val d = MessageDigest.getInstance("SHA-256").digest(("kulan.telemetry.v1:$uid").toByteArray())
        return d.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun installId(c: Context): String {
        val p = c.getSharedPreferences("fariin_telemetry", Context.MODE_PRIVATE)
        p.getString("install", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("install", id).apply()
        return id
    }
}
