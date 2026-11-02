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
 * The iOS inset-grouped list, drawn by hand: grey page, rounded cards 16 from the edges, 20
 * between sections, rows with 13 above and below the text (SettingsRowLabel), the separator
 * starting under the text, a chevron on rows that open a page.
 */

internal val CardShape = RoundedCornerShape(24.dp)

/** A pushed page's bar: back arrow on the left, the title centred, an optional action. */
@Composable
internal fun PageBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label)
            }
        }
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
            modifier = Modifier.align(Alignment.Center),
        )
        if (action != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { action() }
    }
}

/** One card, with an optional header above and footer below, as a SwiftUI Section. */
@Composable
internal fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(CardShape).background(c.card), content = content)
        if (footer != null) {
            Text(
                footer, fontSize = 13.sp, color = c.secondary, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
}

enum class Trailing { CHEVRON, OUT_LINK, NONE }

/**
 * A row in a card. [icon] is drawn 24 by 24 with 16 to the text, as SettingsRowLabel. [last]
 * leaves the separator off the bottom row.
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
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Text(title, fontSize = 17.sp, color = titleColor ?: c.label, modifier = Modifier.weight(1f).padding(vertical = 13.dp))
            if (value != null) {
                Text(value, fontSize = 17.sp, color = c.secondary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            when (trailing) {
                Trailing.CHEVRON -> Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(22.dp),
                )
                Trailing.OUT_LINK -> Icon(
                    Icons.Filled.NorthEast, contentDescription = null,
                    tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
                Trailing.NONE -> Unit
            }
        }
        if (!last) {
            HorizontalDivider(
                thickness = 0.5.dp, color = c.separator,
                modifier = Modifier.padding(start = if (icon != null) 56.dp else 16.dp),
            )
        }
    }
}

@Composable
internal fun vec(v: ImageVector): Painter = rememberVectorPainter(v)
