package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.TextFormat
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.theme.Fariin

/**
 * Format > Link (iOS f09ef437c, 2026-11-16): "Add Link" over the chosen words, or "Edit Link" when
 * they already are one (then "Remove Link" too). Done stays off until the address is one the wire
 * takes ([TextFormat.cleanUrl]); [onDone] gets the cleaned address, or null for Remove Link.
 */
@Composable
internal fun LinkDialog(words: String, current: String?, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = Fariin.colors
    var url by remember { mutableStateOf(current ?: "") }
    val clean = TextFormat.cleanUrl(url)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassAlert(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Add Link" else "Edit Link") },
        text = {
            Column {
                Text("“$words”", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = url, onValueChange = { url = it.take(2048) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                        autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .background(c.fieldFill, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box {
                            if (url.isEmpty()) Text("https://", fontSize = 17.sp, color = c.tertiary)
                            inner()
                        }
                    },
                )
                if (current != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton({ onDone(null) }) { Text("Remove Link", color = c.red) }
                }
            }
        },
        confirmButton = { TextButton({ clean?.let(onDone) }, enabled = clean != null) {
            Text("Done", color = if (clean != null) c.accent else c.tertiary)
        } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.accent) } },
        containerColor = c.card,
    )
}
