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
        val info = try { c.packageManager.getPackageInfo(c.packageName, 0) } catch (_: Exception) { null }
        crash.setCustomKey("build", "${info?.versionName ?: "?"} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0})")
        crash.setCustomKey("android", "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        crash.setCustomKey("model", Build.MODEL ?: "")
        crash.setCustomKey("install", installId(c))
        FirebaseAuth.getInstance().addAuthStateListener { a -> crash.setUserId(hashUid(a.currentUser?.uid ?: "")) }
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
