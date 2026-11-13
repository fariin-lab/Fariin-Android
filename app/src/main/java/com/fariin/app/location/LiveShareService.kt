package com.fariin.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fariin.app.data.LiveLocation
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sends my live location while I share it (owner 2026-11-13). A foreground service of type
 * `location`, started while the app is in front, so it needs no background-location permission and
 * keeps going when the app is closed. Its ongoing notification says where I am sharing, with Stop.
 *
 * One phone fix feeds every chat I share in. A new spot goes out when I moved about 20 m (at most
 * every 15 s), and at least every 2 minutes while I stand still, so "Updated" stays fresh. Each
 * share ends by itself at its own `until`; the service stops when none is left.
 */
class LiveShareService : Service() {
    data class Share(val cid: String, val msgId: String, val untilMs: Long, val title: String)

    companion object {
        private const val CHANNEL = "live_location"
        private const val NOTE_ID = 7342
        private const val ACTION_STOP_ALL = "com.fariin.app.live.STOP_ALL"
        private const val MIN_MS = 15_000L
        private const val MAX_QUIET_MS = 120_000L
        private const val MIN_METRES = 20f
        private const val PREFS = "live_shares"

        private val _shares = MutableStateFlow<Map<String, Share>>(emptyMap())
        /** The chats I am sharing in right now (the card's Stop button and the sheet read this). */
        val shares: StateFlow<Map<String, Share>> = _shares

        /** Share in [cid] until [untilMs]; the service starts (or picks up the new chat). */
        fun begin(ctx: Context, share: Share) {
            _shares.value = _shares.value + (share.cid to share)
            save(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveShareService::class.java))
        }

        /** Stop in one chat; the service ends with the last one. [untilMs] ends a share this phone
         *  no longer holds (a reinstall, another session) from its card. */
        fun stop(ctx: Context, cid: String, untilMs: Long? = null) {
            val me = FirebaseAuth.getInstance().currentUser?.uid
            val was = _shares.value[cid] ?: untilMs?.let { Share(cid, "", it, "") }
            _shares.value = _shares.value - cid
            save(ctx)
            if (me != null && was != null) CoroutineScope(Dispatchers.IO).launch { LiveLocation.end(me, cid, was.untilMs) }
            ctx.startService(Intent(ctx, LiveShareService::class.java))
        }

        /** Stop everywhere (sign-out, account deletion, the notification's Stop). */
        fun stopAll(ctx: Context) {
            val me = FirebaseAuth.getInstance().currentUser?.uid
            val all = _shares.value.values.toList()
            _shares.value = emptyMap()
            save(ctx)
            if (me != null) CoroutineScope(Dispatchers.IO).launch { all.forEach { LiveLocation.end(me, it.cid, it.untilMs) } }
            ctx.stopService(Intent(ctx, LiveShareService::class.java))
        }

        /** After a restart of the app: the shares still running come back from the phone. */
        fun restore(ctx: Context) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val back = p.all.mapNotNull { (cid, v) ->
                val parts = (v as? String)?.split("|", limit = 3) ?: return@mapNotNull null
                val until = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                if (until <= now) null else cid to Share(cid, parts[0], until, parts.getOrNull(2) ?: "")
            }.toMap()
            _shares.value = back
            save(ctx)
            if (back.isNotEmpty()) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveShareService::class.java)) }
        }

        private fun save(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply {
                _shares.value.values.forEach { putString(it.cid, "${it.msgId}|${it.untilMs}|${it.title}") }
            }.apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lm: LocationManager? = null
    private var last: Location? = null
    private var lastSentAt = 0L
    private val tick = android.os.Handler(Looper.getMainLooper())

    private val listener = LocationListener { loc -> onFix(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) { stopAll(this); return START_NOT_STICKY }
        prune()
        if (_shares.value.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val fg = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTE_ID, note(), fg) }.onFailure { stopSelf(); return START_NOT_STICKY }
        listen()
        scheduleTick()
        return START_STICKY
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val ended = _shares.value.values.filter { it.untilMs <= now }
        if (ended.isEmpty()) return
        val me = FirebaseAuth.getInstance().currentUser?.uid
        _shares.value = _shares.value.filterValues { it.untilMs > now }
        save(this)
        if (me != null) scope.launch { ended.forEach { LiveLocation.end(me, it.cid, it.untilMs) } }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (lm != null) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopAll(this); return }
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && m.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else { if (fine) add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
        }.filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) runCatching { m.requestLocationUpdates(p, MIN_MS, MIN_METRES, listener, Looper.getMainLooper()) }
        providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { onFix(it) }
    }

    private fun onFix(loc: Location) {
        val prev = last
        last = loc
        val now = System.currentTimeMillis()
        val moved = prev == null || prev.distanceTo(loc) >= MIN_METRES
        if (!moved && now - lastSentAt < MAX_QUIET_MS) return
        if (now - lastSentAt < MIN_MS && prev != null) return
        send(loc)
    }

    private fun send(loc: Location) {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        lastSentAt = System.currentTimeMillis()
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val chats = _shares.value.values.toList()
        scope.launch { chats.forEach { sh -> runCatching { LiveLocation.put(this@LiveShareService, me, sh.cid, sh.untilMs, loc.latitude, loc.longitude, acc) } } }
    }

    /** Every 30 s: drop shares whose time is up, keep a standing-still sharer fresh, refresh the note. */
    private fun scheduleTick() {
        tick.removeCallbacksAndMessages(null)
        tick.postDelayed(object : Runnable {
            override fun run() {
                prune()
                if (_shares.value.isEmpty()) { stopSelf(); return }
                val l = last
                if (l != null && System.currentTimeMillis() - lastSentAt >= MAX_QUIET_MS) send(l)
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, note()) }
                tick.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you share your live location"
            })
        }
        val shares = _shares.value.values
        val text = when (shares.size) {
            0 -> "Stopping…"
            1 -> "Sharing live location with ${shares.first().title.ifEmpty { "a chat" }}"
            else -> "Sharing live location in ${shares.size} chats"
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveShareService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.fariin.app.R.drawable.ic_location)
            .setContentTitle("Fariin")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        runCatching { lm?.removeUpdates(listener) }
        lm = null
        super.onDestroy()
    }
}
