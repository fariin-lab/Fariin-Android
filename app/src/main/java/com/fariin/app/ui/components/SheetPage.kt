package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin

/**
 * A page opened from a profile or group page as an iOS sheet (`.sheet` with a cancel X and a
 * confirm tick, owner 2026-11-08: "a sheet that comes from down to up, not a full page"). Draw it
 * inside [FariinSheetDialog]: the bar (X, title, [onDone] tick) pulls the sheet, the body scrolls
 * and a pull down at its top pulls the sheet too. [onDone] null: no tick (a page that saves as you go).
 */
@Composable
fun SheetPage(
    motion: SheetMotion,
    title: String,
    onDone: (() -> Unit)? = null,
    doneEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fariin.colors
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        Box(Modifier.fillMaxWidth().sheetDrag(motion).padding(top = 8.dp).height(64.dp).padding(horizontal = 16.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Close") { motion.close() },
                contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = c.label) }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
            if (onDone != null) Box(Modifier.align(Alignment.CenterEnd).size(44.dp).floating(CircleShape)
                .clickable(enabled = doneEnabled, onClickLabel = "Done") { onDone() }, contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Done",
                    tint = if (doneEnabled) c.label else c.label.copy(alpha = 0.35f))
            }
        }
        Column(Modifier.fillMaxSize().sheetDrag(motion, atTop = { scroll.value == 0 }).verticalScroll(scroll).imePadding(), content = content)
    }
}
