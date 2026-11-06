package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.MemberRestrict
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay

/**
 * iOS iAmMuted: an admin has muted me in this group (`sendText` restricted, its end still ahead).
 * Re-judged when the restriction runs out (iOS muteLiftTick), so the composer comes back on time.
 */
@Composable
internal fun rememberMutedInGroup(doc: Map<String, Any?>, me: String): Boolean {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = MemberRestrict.until(doc, me)
    LaunchedEffect(until) {
        now = System.currentTimeMillis()
        val wait = until - now
        if (wait > 0 && wait < 30L * 86_400_000) { delay(wait.toLong() + 500); now = System.currentTimeMillis() }
    }
    return doc.isNotEmpty() && MemberRestrict.isMuted(doc, me, now.toDouble())
}

/** iOS restrictedBar: "You're muted in this group" where the composer was. */
@Composable
internal fun MutedInGroupBar() {
    val c = Fariin.colors
    HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Icon(painterResource(com.fariin.app.R.drawable.ic_speaker_off), contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(MemberRestrict.MUTED_BAR, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary)
    }
}
