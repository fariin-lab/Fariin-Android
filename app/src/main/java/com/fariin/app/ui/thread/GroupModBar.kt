package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin

/**
 * iOS groupModBar (ThreadView composerNotice): a group that is ended, suspended or disabled has no
 * composer; in its place, on the composer's own edges, a full-width glass card (26 corners) with the
 * notice centred in it, medium weight, secondary colour.
 */
@Composable
internal fun GroupModBar(text: String) {
    val c = Fariin.colors
    val rest = rememberComposerRestInsets()
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(rest)
            .padding(start = COMPOSER_SIDE, end = COMPOSER_SIDE, top = 8.dp, bottom = 8.dp)
            .floating(RoundedCornerShape(26.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp))
}
