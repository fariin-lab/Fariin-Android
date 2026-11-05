package com.fariin.app.ui.settings

import com.fariin.app.ui.components.listPress
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
import androidx.compose.foundation.layout.widthIn
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
 * The Android settings list: a Material top app bar with the up arrow, a value as the row's
 * second line, no chevrons. Each section is a rounded card on the grey page (owner 2026-11-02:
 * keep the rounded look of iOS; the big Android messengers use cards too). Same rows, words and
 * order as iOS SettingsView.
 */

/** Kept for callers that still clip a block to a card (profile header buttons and the like). */
internal val CardShape = RoundedCornerShape(24.dp)

/** A pushed page's bar: up arrow, the title at the start, an optional action at the end. */
@Composable
internal fun PageBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().background(Fariin.colors.bgSecondary).statusBarsPadding()) {
        com.fariin.app.ui.components.FariinTopBar(title, onBack = onBack, container = Fariin.colors.bgSecondary, actions = { if (action != null) action() })
    }
}

/** One section: an optional header above, its rows in one rounded card, an optional footer below. */
@Composable
internal fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    /** Something small at the end of the header line (the Stories page's + New). */
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                header, style = MaterialTheme.typography.titleSmall, color = c.secondary,
                modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            )
            if (headerAction != null) Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) { headerAction() }
        }
        Column(Modifier.fillMaxWidth().clip(SectionShape).background(c.card), content = content)
        if (footer != null) {
            Text(
                footer, style = MaterialTheme.typography.bodySmall, color = c.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

private val SectionShape = RoundedCornerShape(20.dp)

enum class Trailing { CHEVRON, OUT_LINK, NONE }

/**
 * A Material list row: 24dp icon in a 40dp slot, the title in bodyLarge and the [value] as the
 * value beside the title as iOS (a long one under it), ripple on tap. [trailing] CHEVRON draws the
 * iOS chevron on a tappable row (owner 2026-11-04), OUT_LINK keeps the small arrow for pages that leave the app. A thin line
 * under every row but the [last] of its card, starting at the title (iOS).
 */
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
            .then(if (onClick != null) Modifier.listPress(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                // iOS SettingsRowLabel: the icon is the text's colour, not grey (owner 2026-11-04: grey looked dead).
                Icon(icon, contentDescription = null, tint = titleColor ?: c.label, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        // iOS (owner 2026-11-04, Storage and Data side by side): a short value sits on the right of
        // the title's line before the chevron; a long one (a sentence) stays under the title.
        val side = value != null && value.length <= SIDE_VALUE_MAX
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor ?: c.label)
            if (value != null && !side) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 2)
            }
        }
        if (side) Text(value!!, style = MaterialTheme.typography.bodyLarge, color = c.secondary, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 200.dp))
        if (trailing == Trailing.CHEVRON && onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.secondary.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 4.dp).size(24.dp))
        }
        if (trailing == Trailing.OUT_LINK) {
            Icon(Icons.Filled.NorthEast, contentDescription = null, tint = c.secondary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
    // Full width, edge to edge of the card (owner 2026-11-04).
    // No line between rows (owner 2026-11-04: "just leave the rounded way, remove the lines").
    if (!last && SHOW_ROW_LINES) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
}

/** The longest value drawn beside the title (iOS); longer ones go under it. */
private const val SIDE_VALUE_MAX = 24

/** Lines between settings rows; off by the owner's word 2026-11-04 (flip to bring them back). */
internal const val SHOW_ROW_LINES = false

@Composable
internal fun vec(v: ImageVector): Painter = rememberVectorPainter(v)
