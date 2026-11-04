package com.fariin.app.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * App update campaigns, the phone's half (iOS AppUpdateGate): the server's `appUpdateCheck` judges
 * this phone (build, version, Android version, device, account) and answers none / optional /
 * mandatory. Asked at launch, on every return to the front and every 10 minutes while open.
 *
 * The last good answer is kept and shown at once on the next launch, so a mandatory one cannot be
 * skipped by closing the app or going offline; a failed check keeps it; reaching the minimum build
 * lifts it at once. Only an answer the server marks "android" counts: build numbers differ between
 * platforms, and an older server that does not know Android must never block this app.
 */
object AppUpdateGate {
    data class Decision(val action: String, val campaignId: String, val title: String, val message: String,
                        val minBuild: Int, val storeUrl: String)

    private const val PREFS = "fariin_update"
    private const val NOT_NOW_MS = 3L * 24 * 3600 * 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var build = 0
    private var version = ""
    /** What applies right now (null = nothing to show). */
    val shown = MutableStateFlow<Decision?>(null)
    private var decision: Decision? = null

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()?.let {
            build = if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
            version = it.versionName ?: ""
        }
        decision = load(app)
        present(app)
        scope.launch { while (true) { check(app); delay(600_000) } }
    }

    /** On a return to the front (FariinApp's lifecycle). */
    fun onForeground(c: Context) { if (started) scope.launch { check(c.applicationContext) } }

    private suspend fun check(app: Context) {
        val data = mapOf("build" to build, "version" to version, "android" to android.os.Build.VERSION.RELEASE,
            "deviceId" to runCatching { com.fariin.app.device.DeviceRegistry.thisDeviceId(app) }.getOrDefault(""), "platform" to "android")
        val res = withTimeoutOrNull(10_000) {
            runCatching { FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("appUpdateCheck").call(data).await().getData() }.getOrNull()
        } as? Map<*, *> ?: return
        if (res["platform"] != "android") return
        val next = sane(Decision(res["action"] as? String ?: "none", res["campaignId"] as? String ?: "", res["title"] as? String ?: "",
            res["message"] as? String ?: "", (res["minBuild"] as? Number)?.toInt() ?: 0, res["storeUrl"] as? String ?: ""))
        decision = next
        save(app, next)
        present(app)
    }

    /** Nil for "none" and for anything that does not make sense, so broken data never locks anyone out. */
    private fun sane(d: Decision): Decision? =
        d.takeIf { (it.action == "mandatory" || it.action == "optional") && it.minBuild > 0 && it.title.isNotEmpty() }

    private fun present(app: Context) {
        val d = decision?.takeIf { build < it.minBuild }
        shown.value = when {
            d == null -> null
            d.action == "optional" && System.currentTimeMillis() - prefs(app).getLong("notNow.${d.campaignId}", 0) < NOT_NOW_MS -> null
            else -> d
        }
    }

    fun notNow(c: Context) {
        val d = shown.value ?: return
        prefs(c).edit().putLong("notNow.${d.campaignId}", System.currentTimeMillis()).apply()
        shown.value = null
    }

    fun openStore(c: Context, d: Decision) {
        val url = d.storeUrl.ifEmpty { "https://play.google.com/store/apps/details?id=${c.packageName}" }
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun save(c: Context, d: Decision?) {
        val e = prefs(c).edit()
        if (d == null) e.remove("decision") else e.putString("decision", JSONObject().put("action", d.action).put("campaignId", d.campaignId)
            .put("title", d.title).put("message", d.message).put("minBuild", d.minBuild).put("storeUrl", d.storeUrl).toString())
        e.apply()
    }
    private fun load(c: Context): Decision? = runCatching {
        val o = JSONObject(prefs(c).getString("decision", null) ?: return null)
        sane(Decision(o.getString("action"), o.optString("campaignId"), o.optString("title"), o.optString("message"),
            o.optInt("minBuild"), o.optString("storeUrl")))
    }.getOrNull()
}

/**
 * Over everything (iOS: its own window above the app). Mandatory: a page that covers the app with
 * one Update button and no way past it. Optional: a dialog with Update and Not Now (3 days).
 */
@Composable
fun AppUpdateLayer() {
    val d by AppUpdateGate.shown.collectAsState()
    val ctx = LocalContext.current
    val u = d ?: return
    val c = Fariin.colors
    if (u.action == "mandatory") {
        androidx.activity.compose.BackHandler {}
        Box(Modifier.fillMaxSize().background(c.bg).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(u.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                if (u.message.isNotEmpty()) Text(u.message, fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
                Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(c.blue).clickable { AppUpdateGate.openStore(ctx, u) },
                    contentAlignment = Alignment.Center) {
                    Text("Update", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else AlertDialog(onDismissRequest = { AppUpdateGate.notNow(ctx) },
        title = { Text(u.title) }, text = { if (u.message.isNotEmpty()) Text(u.message) },
        confirmButton = { TextButton(onClick = { AppUpdateGate.openStore(ctx, u) }) { Text("Update") } },
        dismissButton = { TextButton(onClick = { AppUpdateGate.notNow(ctx) }) { Text("Not Now") } })
}
