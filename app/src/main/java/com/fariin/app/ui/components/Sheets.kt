package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin

/*
 * The sheets' and menus' shared look, after the iOS action sheets: the sheet is the grey page,
 * its actions sit in one rounded card (no full-width strips or hard lines), menus are a rounded
 * card with the same hairline rim as the chat's floating controls.
 */

/** The fill a bottom sheet uses, so its cards stand off it as Settings cards do. */
val sheetFill: Color @Composable get() = Fariin.colors.bgSecondary

/** The corner of a sheet's top edge (iOS sheets round more than Material's 28dp). */
val SheetShape: Shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/** A menu's (DropdownMenu) corner. */
val MenuShape: Shape = RoundedCornerShape(16.dp)

/** A rounded card of rows inside a sheet. */
@Composable
fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp)).background(Fariin.colors.card),
        content = content,
    )
}

/** One action in a [SheetCard]: outline icon, title, an optional line under it, a thin inset line below. */
@Composable
fun SheetRow(
    title: String,
    icon: Painter?,
    tint: Color = Fariin.colors.label,
    supporting: (@Composable () -> Unit)? = null,
    last: Boolean = false,
    /** The admin panel's green "New" pill after the title ([isNew]). */
    newBadge: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            if (newBadge) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f, fill = false))
                NewPill(Modifier.padding(start = 8.dp))
            } else Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            supporting?.invoke()
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator,
        modifier = Modifier.padding(start = if (icon != null) 58.dp else 16.dp))
}

/** Rounded card colours for a DropdownMenu, passed as `shape`/`containerColor`/`border`. */
object FariinMenu {
    val shape: Shape get() = MenuShape
    val container: Color @Composable get() = Fariin.colors.card
    val border: androidx.compose.foundation.BorderStroke
        @Composable get() = androidx.compose.foundation.BorderStroke(0.5.dp, Fariin.colors.label.copy(alpha = 0.07f))
}
