package com.fariin.app.system

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The phone is nearly full (iOS LowStorage): nothing downloads then, so the person is told why and
 * what to do instead of being left with photos that never open. The same rules as iOS: warn below 5%
 * of the phone (at most 2 GB), once in 3 days, from the chat list and on coming back; below 400 MB
 * downloads stop, and a tapped download brings the sheet up at once (a row of taps is one sheet).
 */
object LowStorage {
    private const val CRITICAL = 400L * 1_000_000
    private const val WARN_CAP = 2L * 1_000_000_000
    private const val COOLDOWN_MS = 3L * 86_400_000
    private const val PREFS = "fariin_storage"
    private var lastForcedAt = 0L
    /** Bytes to clear while the sheet is up; null = no sheet. */
    val need = MutableStateFlow<Long?>(null)

    private fun space(c: Context): Pair<Long, Long>? = runCatching {
        val s = StatFs(c.filesDir.absolutePath)
        s.availableBytes to s.totalBytes
    }.getOrNull()?.takeIf { it.second > 0 }

    private fun warnBelow(total: Long) = minOf((total * 0.05).toLong(), WARN_CAP)

    /** A download of [size] bytes (null when unknown) should not start. */
    fun tooFullToDownload(c: Context, size: Long? = null): Boolean {
        val (free, _) = space(c) ?: return false
        return free < CRITICAL + (size ?: 0)
    }

    /** The 3-day warning: from the chat list, and when the app comes back to the front. */
    fun checkAndWarn(c: Context) {
        val (free, total) = space(c) ?: return
        if (free >= warnBelow(total)) return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("lastWarningAt", 0) <= COOLDOWN_MS) return
        p.edit().putLong("lastWarningAt", System.currentTimeMillis()).apply()
        need.value = warnBelow(total)
    }

    /** A download that could not start because the phone is full. */
    fun warnForDownload(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastForcedAt < 60_000) return
        val (_, total) = space(c) ?: return
        lastForcedAt = now
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("lastWarningAt", now).apply()
        need.value = warnBelow(total)
    }
}

/** iOS LowStorageSheet, in Android's words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LowStorageSheet() {
    val need by LowStorage.need.collectAsState()
    val n = need ?: return
    val c = Fariin.colors
    val needText = if (n >= 1_000_000_000) "%.1f GB".format(n / 1e9) else "${n / 1_000_000} MB"
    ModalBottomSheet(onDismissRequest = { LowStorage.need.value = null }, containerColor = sheetFill, shape = SheetShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your Phone Is Almost Full", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Photos, videos and voice messages can't download until there is more room, and a full phone can lose data. Clear at least $needText.",
                fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            listOf("In Settings, open Storage, and remove apps you no longer use",
                "Delete large videos from your gallery and from your chats",
                "Remove downloaded music, podcasts or films").forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(t, fontSize = 15.sp, color = c.label)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent).clickable { LowStorage.need.value = null },
                contentAlignment = Alignment.Center) {
                Text("OK", color = c.onAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
