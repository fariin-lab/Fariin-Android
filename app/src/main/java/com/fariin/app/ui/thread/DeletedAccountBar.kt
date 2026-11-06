package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.DeletedAccount
import com.fariin.app.ui.theme.Fariin

/**
 * iOS deletedAccountBar: the composer is gone and a solid capsule takes its place, the text
 * colour behind (black in light mode, white in dark) and the page colour for the words.
 */
@Composable
internal fun DeletedAccountBar() {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
        .height(48.dp).clip(CircleShape).background(c.label), contentAlignment = Alignment.Center) {
        Text(DeletedAccount.BAR, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.bg,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
