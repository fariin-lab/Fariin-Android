package com.fariin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

/*
 * The Android settings list (owner 2026-11-02, "Android wins"): a Material top app bar with the
 * up arrow, full-width rows with no cards, section headers in the brand blue, a value as the
 * row's second line, no chevrons. Same rows, words and order as iOS SettingsView.
 */

/** Kept for callers that still clip a block to a card (profile header buttons and the like). */
internal val CardShape = RoundedCornerShape(24.dp)

/** A pushed page's bar: up arrow, the title at the start, an optional action at the end. */
@Composable
internal fun PageBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().background(Fariin.colors.bg).statusBarsPadding()) {
        com.fariin.app.ui.components.FariinTopBar(title, onBack = onBack, actions = { if (action != null) action() })
    }
}

/** One section: an optional header above, an optional footer below, a thin divider after it. */
@Composable
internal fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header, style = MaterialTheme.typography.titleSmall, color = c.blue,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        Column(Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(
                footer, style = MaterialTheme.typography.bodySmall, color = c.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = c.separator.copy(alpha = 0.35f))
}

enum class Trailing { CHEVRON, OUT_LINK, NONE }

/**
 * A Material list row: 24dp icon in a 40dp slot, the title in bodyLarge and the [value] as the
 * supporting line under it (Android settings), ripple on tap. [trailing] CHEVRON draws nothing
 * (Android rows do not), OUT_LINK keeps the small arrow for pages that leave the app. [last] is
 * kept for the iOS call sites; Android rows carry no separators.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SettingsRow(
    title: String,
    icon: Painter? = null,
    value: String? = null,
    trailing: Trailing = Trailing.CHEVRON,
    titleColor: Color? = null,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                Icon(icon, contentDescription = null, tint = titleColor ?: c.secondary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor ?: c.label)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 2)
            }
        }
        if (trailing == Trailing.OUT_LINK) {
            Icon(Icons.Filled.NorthEast, contentDescription = null, tint = c.secondary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

@Composable
internal fun vec(v: ImageVector): Painter = rememberVectorPainter(v)
