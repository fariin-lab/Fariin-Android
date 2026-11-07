package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.NewBadges
import com.fariin.app.ui.theme.Fariin

/**
 * The spot ids whose "New" pill shows now ([NewBadges], ids from NewBadgeSpots). Provided once at
 * the root by FariinTheme, so every tagged spot reads the same set: one listener, one timer.
 */
val LocalNewBadges = compositionLocalOf<Set<String>> { emptySet() }

/** True while the admin panel's "New" pill is on for [spot] (a NewBadgeSpots constant). */
@Composable
fun isNew(spot: String?): Boolean = spot != null && spot in LocalNewBadges.current

/** Feeds [LocalNewBadges] for [content]; FariinTheme calls this at the root. */
@Composable
fun ProvideNewBadges(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNewBadges provides rememberNewBadges(), content = content)
}

/**
 * The spot ids whose "New" pill shows now. Live from config/features; one timer to the soonest end
 * takes a pill down on time while the app stays open (no polling).
 */
@Composable
fun rememberNewBadges(): Set<String> {
    val ends by NewBadges.ends.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ends) {
        while (true) {
            now = System.currentTimeMillis()
            val next = NewBadges.nextEnd(ends, now) ?: break
            kotlinx.coroutines.delay((next - now).coerceAtLeast(1L))
        }
    }
    return remember(ends, now) { NewBadges.active(ends, now) }
}

private val newGreen: Color @Composable get() = if (Fariin.colors.dark) Color(0xFF30D158) else Color(0xFF34C759)

/** The green "New" capsule after a label (iOS: systemGreen text on a ~16% green fill, 13 pt semibold). */
@Composable
fun NewPill(modifier: Modifier = Modifier) {
    val green = newGreen
    Text("New", color = green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        softWrap = false,
        modifier = modifier.clip(RoundedCornerShape(50)).background(green.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp))
}

/**
 * The small "New" for a tile or an icon with no room beside its label: a solid green capsule laid
 * over the top end corner, so the tile's own layout does not move. Call inside the tile's Box.
 */
@Composable
fun BoxScope.NewCornerPill(show: Boolean, modifier: Modifier = Modifier) {
    if (!show) return
    Text("New", color = Color.White, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false,
        modifier = modifier.align(Alignment.TopEnd)
            // Takes no room: the pill reports a zero size, so the Box keeps its child's size. It is
            // drawn over the glyph's top end corner, 4dp past its edge and 2dp above it, which stays
            // inside a round tool button's or a rounded tile's clip.
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints())
                layout(0, 0) { p.place(-p.width + 4.dp.roundToPx(), -2.dp.roundToPx()) }
            }
            .clip(RoundedCornerShape(50)).background(newGreen)
            .padding(horizontal = 5.dp, vertical = 1.dp))
}

/** Wraps [content] (a tile or icon) and lays [NewCornerPill] over its corner when [spot] is on. */
@Composable
fun NewCorner(spot: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val on = isNew(spot)
    Box(modifier) {
        content()
        NewCornerPill(on)
    }
}
