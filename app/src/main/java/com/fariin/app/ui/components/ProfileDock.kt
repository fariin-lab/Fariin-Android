package com.fariin.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The page's photo DOCKS into the bar as the page scrolls (owner 2026-11-11, channel page, beside
 * the reference app's channel info): instead of melting away it shrinks and slides up to sit small
 * next to the back button, and the name with its second line comes in beside it, left-aligned.
 * Shares [ProfileMeltState] with the melt: [profileMeltSource] on the page's photo, this overlay
 * once over the page, [profileMeltBarGround] and [ProfileDockTitle] in the bar.
 */
/** Where the docked photo sits: its left edge from the screen's left, and its side. */
private val DOCK_LEFT = 58.dp
internal val DOCK_SIDE = 36.dp
private val BAR_CENTER = 28.dp   // the bar's middle under the status bar (PageBar is 56 dp)

@Composable
fun ProfileDockOverlay(m: ProfileMeltState, photoSize: Dp, squircle: Boolean = false, nameSpace: Dp = 104.dp, photo: @Composable () -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val topInset = remember(view) { (view.rootWindowInsets?.systemWindowInsetTop ?: 0).toFloat() }
    val target = with(density) { Offset(DOCK_LEFT.toPx() + DOCK_SIDE.toPx() / 2f, topInset + BAR_CENTER.toPx()) }
    // The bar's ground and title use the same point, so they arrive as the photo lands.
    m.targetY = target.y
    val travel by remember(target) {
        derivedStateOf { if (m.restCenter.isSpecified) (m.restCenter.y - target.y).coerceAtLeast(1f) else 1f }
    }
    val showing by remember { derivedStateOf { m.restCenter.isSpecified && m.scroll.value > 0 } }
    // Let go part way and it settles docked or out, never left half way. Docked goes on past the
    // photo by [nameSpace], so the page's own big name is under the bar too (it showed twice).
    val extra = with(density) { nameSpace.toPx() }
    LaunchedEffect(m.scroll, travel) {
        snapshotFlow { m.scroll.isScrollInProgress }.collect { moving ->
            val v = m.scroll.value
            val end = travel + extra
            if (!moving && m.restCenter.isSpecified && v > 0 && v < end) {
                m.scroll.animateScrollTo(if (v < travel * 0.5f) 0 else end.toInt() + 1,
                    androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f))
            }
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { m.origin = it.positionInWindow() }) {
        if (!showing) return@Box
        val shape = if (squircle) RoundedCornerShape(photoSize * 0.27f) else CircleShape
        Box(Modifier.size(photoSize)
            .graphicsLayer {
                val sidePx = photoSize.toPx()
                val p = (m.scroll.value / travel).coerceIn(0f, 1f)
                // A soft start and landing on the way up.
                val e = p * p * (3f - 2f * p)
                val rest = m.restCenter - m.origin
                // Across: from the PAGE's left edge, not the screen's. As the page slides out on back,
                // its origin moves right; a screen spot left the photo behind on the chat's header
                // (owner's video 2026-11-11: two photos there).
                val goal = Offset(target.x, target.y - m.origin.y)
                val cx = rest.x + (goal.x - rest.x) * e
                val cy = rest.y + (goal.y - rest.y) * e
                val s = 1f + (DOCK_SIDE.toPx() / sidePx - 1f) * e
                transformOrigin = TransformOrigin.Center
                translationX = cx - sidePx / 2f
                translationY = cy - sidePx / 2f
                scaleX = s; scaleY = s
            }
            .clip(shape)) { photo() }
    }
}

/** The name and its second line beside the docked photo, fading in as it lands. First in the bar's Box. */
@Composable
fun BoxScope.ProfileDockTitle(m: ProfileMeltState, title: String, subtitle: String?, endSpace: Dp = 120.dp) {
    val c = com.fariin.app.ui.theme.Fariin.colors
    Box(Modifier.matchParentSize().statusBarsPadding()
        .padding(start = DOCK_LEFT + DOCK_SIDE + 10.dp, end = endSpace)
        .graphicsLayer { alpha = m.collapsed(-16.dp.toPx(), 16.dp.toPx()) },
        contentAlignment = Alignment.CenterStart) {
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, fontSize = 13.sp, color = c.secondary, maxLines = 1)
        }
    }
}
