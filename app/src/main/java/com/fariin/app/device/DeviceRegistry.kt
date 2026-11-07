package com.fariin.app.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Every phone signed in to this account, one record each: a port of
 * Kulan-iOS/Kulan/Services/DeviceRegistry.swift (this-device half; the Devices list page is TODO).
 *
 * `users/{uid}/devices/{deviceId}`, the iOS fields (see [DeviceRecord]). ONE PHONE PER ACCOUNT: the
 * server's `enforceDeviceLimit` removes every OLDER record when a new one is created, with its push
 * tokens, and leaves a note at `users/{uid}/deviceReplaced/{deviceId}`. This phone sees its record
 * gone (the watcher, or the next launch's server read) and signs out with the iOS notice.
 *
 * Only the SERVER's word that the record is gone counts: an answer from the local cache (offline)
 * reports a never-seen document as missing, which would sign people out for being on a plane.
 */
object DeviceRegistry {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _revoked = MutableStateFlow<DeviceRecord.Reason?>(null)
    /** Set when the server removed this phone's record. MainActivity performs the sign-out. */
    val revoked: StateFlow<DeviceRecord.Reason?> = _revoked.asStateFlow()
    /** The account [revoked] is about: MainActivity signs out only if it is still the signed-in one. */
    @Volatile var revokedUid: String? = null
        private set

    @Volatile private var registered = false
    @Volatile private var watcher: ListenerRegistration? = null
    @Volatile private var heartbeat: Job? = null
    @Volatile private var lastTouch = 0L
    private val pendingTokens = HashMap<String, Any>()

    private lateinit var app: Context
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val uid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    private const val PREFS = "fariin_device"
    private const val INSTALL_ID = "device.installId"
    private fun registeredKey(uid: String) = "device.registered.$uid"
    private const val HEARTBEAT_MS = 300_000L

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * This install's id. iOS keeps its first id in the Keychain so a reinstall comes back as the
     * same record (one phone, one record, 2026-10-14). The Android equivalent that also survives a
     * reinstall is ANDROID_ID (per app signing key, per user). Saved on first use, so it never
     * changes under a running install; a random id only if the system gives none.
     */
    @SuppressLint("HardwareIds")
    fun thisDeviceId(context: Context): String {
        init(context)
        prefs().getString(INSTALL_ID, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val android = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }   // the old emulator constant
        val id = android ?: UUID.randomUUID().toString()
        prefs().edit().putString(INSTALL_ID, id).apply()
        return id
    }

    private fun devices(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("devices")

    private fun hadRegistered(uid: String) = prefs().getBoolean(registeredKey(uid), false)

    private suspend fun noteExists(uid: String, coll: String, id: String): Boolean =
        BootWatch.capped(BootWatch.SERVER_READ_MS) {
            db.collection("users").document(uid).collection(coll).document(id).get(Source.SERVER).await().exists()
        } ?: false

    /** A late answer for an account that has since logged out must not end the next one's session. */
    private fun stillSignedIn(uid: String) = this.uid == uid

    private suspend fun revoke(uid: String, id: String) {
        val reason = reasonFor(uid, id)
        if (!stillSignedIn(uid)) { android.util.Log.w(BootWatch.TAG, "removed-device answer for an old session dropped"); return }
        revokedUid = uid
        _revoked.value = reason
    }

    private suspend fun reasonFor(uid: String, id: String) = DeviceRecord.reason(
        replaced = noteExists(uid, "deviceReplaced", id),
        refusedForLimit = noteExists(uid, "deviceRefusals", id),
    )

    // ------------------------------------------------------------------ this device

    /** Record this phone and watch for a remote sign-out. Safe on every launch and sign-in. */
    fun start(context: Context) {
        init(context)
        scope.launch { register() }
    }

    private suspend fun register() {
        val uid = uid ?: return
        val id = thisDeviceId(app)
        val ref = devices(uid).document(id)
        val data = HashMap<String, Any>(DeviceRecord.baseFields(
            model = Build.MODEL ?: "",
            hardware = DeviceRecord.hardware(Build.MANUFACTURER ?: "", Build.DEVICE ?: ""),
            os = DeviceRecord.os(Build.VERSION.RELEASE ?: ""),
            appVersion = appVersion(),
        ))
        data["lastSeenAt"] = FieldValue.serverTimestamp()

        var exists: Boolean? = null
        try {
            val server = ref.get(Source.SERVER).await()
            if (!stillSignedIn(uid)) return
            when (DeviceRecord.check(server.exists(), hadRegistered(uid))) {
                DeviceRecord.Check.REVOKED -> { revoke(uid, id); return }
                DeviceRecord.Check.NEW_RECORD -> exists = false
                DeviceRecord.Check.EXISTING -> exists = true
            }
        } catch (_: Exception) {
            // Offline: the cached copy, then write as before (iOS does the same).
            exists = try { ref.get().await().exists() } catch (_: Exception) { null }
        }
        if (exists != true) {
            // The rules accept a new record only with the server's createdAt, and authTime equal to
            // the token's own auth_time (so it cannot be backdated).
            data["createdAt"] = FieldValue.serverTimestamp()
            try {
                val t = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()
                val at = t?.authTimestamp ?: 0L
                if (at > 0) data["authTime"] = at
            } catch (_: Exception) {}
        }
        synchronized(pendingTokens) { data.putAll(pendingTokens); pendingTokens.clear() }
        try {
            ref.set(data, SetOptions.merge()).await()
            // Offline this write answers only when the phone is back, maybe after a log out.
            if (!stillSignedIn(uid)) return
            prefs().edit().putBoolean(registeredKey(uid), true).apply()
            registered = true
            val late = synchronized(pendingTokens) { HashMap(pendingTokens).also { pendingTokens.clear() } }
            if (late.isNotEmpty()) writeTokenFields(late)
            watchThisDevice()
            startHeartbeat()
            // "Location: city" on the device sheet: the server works it out from this request's
            // address. The phone sends no position and asks for no permission.
            try {
                FirebaseFunctions.getInstance("me-central1").getHttpsCallable("recordDeviceLocation")
                    .call(mapOf("deviceId" to id)).await()
            } catch (_: Exception) {}
        } catch (_: Exception) {
            // A failed write must NOT arm the watcher: it would see no record and sign out a phone
            // that simply never managed to register. Keep the token for the next try.
            registered = false
            data["fcmToken"]?.let { tok -> synchronized(pendingTokens) { pendingTokens.putIfAbsent("fcmToken", tok) } }
        }
    }

    private fun appVersion(): String = try {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        DeviceRecord.appVersion(p.versionName, code)
    } catch (_: Exception) { "?" }

    /** Keep the push token on the record, so a remote sign-out strips exactly this phone's token. */
    fun recordFCMToken(token: String) = writeTokenFields(mapOf("fcmToken" to token))

    /** Tokens that arrive before the record exists wait and ride on the registration write: a
     *  token write landing first would create the record without createdAt, which the rules refuse. */
    private fun writeTokenFields(fields: Map<String, Any>) {
        if (!::app.isInitialized) return
        val uid = uid ?: return
        if (!registered) { synchronized(pendingTokens) { pendingTokens.putAll(fields) }; return }
        val data = HashMap(fields)
        data["lastSeenAt"] = FieldValue.serverTimestamp()
        devices(uid).document(thisDeviceId(app)).set(data, SetOptions.merge())
    }

    /** Watch our own record. If the SERVER says it is gone, another sign-in removed it. */
    private fun watchThisDevice() {
        val uid = uid ?: return
        if (watcher != null) return
        val id = thisDeviceId(app)
        watcher = devices(uid).document(id).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (!registered) return@addSnapshotListener
            if (snap == null && error != null) {
                // An error ends the listener for good; drop it so the next start watches again.
                watcher?.remove(); watcher = null
                return@addSnapshotListener
            }
            if (snap == null || snap.metadata.isFromCache || snap.exists()) return@addSnapshotListener
            scope.launch { revoke(uid, id) }
        }
    }

    // ------------------------------------------------------------------ heartbeat

    /** `lastSeenAt` means "last active": every 5 minutes while the app is in front (the server's
     *  inactivity sweep and the list's "Active now" read it). */
    fun startHeartbeat() {
        if (heartbeat != null || !::app.isInitialized) return
        heartbeat = scope.launch {
            while (isActive) {
                touch()
                delay(HEARTBEAT_MS)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeat?.cancel(); heartbeat = null
    }

    private suspend fun touch() {
        val uid = uid ?: return
        if (!registered) return
        val now = System.currentTimeMillis()
        if (now - lastTouch < 60_000) return
        lastTouch = now
        try {
            devices(uid).document(thisDeviceId(app))
                .set(mapOf("lastSeenAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the crypto hook

    /**
     * Crypto.swift:320-331: a phone a newer sign-in replaced publishes nothing. It runs this at its
     * next launch before the registry notices, and would take the account's key back from the phone
     * now in use. Registered before (the marker) and no record on the server = on its way out.
     */
    suspend fun mayPublish(context: Context, uid: String): Boolean {
        init(context)
        val id = thisDeviceId(app)
        if (id.isEmpty() || !hadRegistered(uid)) return true
        return try {
            devices(uid).document(id).get(Source.SERVER).await().exists()
        } catch (_: Exception) { true }   // could not ask: as iOS, publish as before
    }

    // ------------------------------------------------------------------ sign-out

    fun stopWatching() {
        watcher?.remove(); watcher = null
        registered = false
        stopHeartbeat()
    }

    /** Normal sign-out on this phone: drop our record (and the marker) BEFORE Auth signs out,
     *  while we still have permission. */
    suspend fun removeThisDevice() {
        stopWatching()
        if (!::app.isInitialized) return
        val uid = uid ?: return
        prefs().edit().remove(registeredKey(uid)).apply()
        try { devices(uid).document(thisDeviceId(app)).delete().await() } catch (_: Exception) {}
    }

    /** Another phone took over: the marker goes (the record is already gone). */
    fun forgetAfterRevoke() {
        stopWatching()
        val uid = uid
        if (uid != null && ::app.isInitialized) prefs().edit().remove(registeredKey(uid)).apply()
    }

    fun clearRevoked() { _revoked.value = null; revokedUid = null }
}
