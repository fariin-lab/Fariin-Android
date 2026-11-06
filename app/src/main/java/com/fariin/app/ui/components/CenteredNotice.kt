package com.fariin.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin

/**
 * A centered "Couldn't load ..." with its plain reason and one floating capsule action, the same
 * look as the chat list's empty states. Used for a failed load (action "Try Again") and for an
 * empty list with one next step (no [reason] then).
 */
@Composable
fun CenteredNotice(title: String, reason: String? = null, action: String? = null, onAction: () -> Unit = {},
                   modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (reason != null) c.label else c.secondary,
            textAlign = TextAlign.Center)
        if (reason != null) {
            Spacer(Modifier.height(6.dp))
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = c.secondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(44.dp).floating(CircleShape).clickable(onClick = onAction).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        }
    }
}
