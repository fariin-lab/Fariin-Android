package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * A pushed page whose content runs under its bar, so the bar's floating buttons (the back circle,
 * Edit, Done) are real liquid glass as in the chat (owner 2026-11-06: "the back button in the chat
 * has liquid glass, but the one in Settings doesn't"). The same build as the chat screen: the
 * content is the glass's backdrop, the bar is laid over it in the page colour, and only the glass
 * buttons show the rows passing under them, bent at the rim. No blur pass, so a scrolled frame
 * costs about what the chat's does.
 *
 * [content] gets the bar's height: pad the top INSIDE the scroll by it, so the page opens with
 * nothing hidden and the rows then scroll under the bar. [under] is true once they do; a short
 * fade under the bar then softens the cut.
 *
 * Without glass (Power Saving, Android 12 and older) it is the plain bar over the page, as before,
 * and [content] gets 0.
 */
@Composable
fun GlassBarPage(
    under: Boolean,
    modifier: Modifier = Modifier,
    page: Color = Fariin.colors.bgSecondary,
    /** The bar's height under the status bar before it is measured (64 dp, a plain page bar). */
    barEstimate: Dp = 64.dp,
    bar: @Composable () -> Unit,
    content: @Composable (top: Dp) -> Unit,
) {
    val glass = com.fariin.app.perf.rememberPowerState().glass()
    val liquid = glass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    if (!liquid) {
        Column(modifier.fillMaxSize()) { bar(); content(0.dp) }
        return
    }
    val c = Fariin.colors
    val density = LocalDensity.current
    val backdrop = rememberLayerBackdrop()
    // Started at the usual bar height so the first frame is already padded.
    val estimate = WindowInsets.statusBars.getTop(density) + with(density) { barEstimate.roundToPx() }
    var barPx by remember { mutableIntStateOf(estimate) }
    val fade = rememberEdgeAlpha(under)
    // On the grey Settings page the chat's grey frost would be the page's own colour: the card white
    // there, as the solid circle was. A white page keeps the chat's frost.
    val frost = if (c.dark || page == c.bg) null else c.card
    Box(modifier.fillMaxSize()) {
        // Nothing inside the content may draw glass from the content itself: a layer that holds its
        // own reader is an endless render tree (the chat's crash, 2026-11-06).
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(page)) {
            CompositionLocalProvider(LocalLiquidBackdrop provides null) {
                content(with(density) { barPx.toDp() })
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth().onSizeChanged { barPx = it.height }) {
                CompositionLocalProvider(
                    LocalLiquidBackdrop provides backdrop,
                    LocalGlassWindow provides LocalView.current,
                    LocalGlassFrost provides frost,
                    LocalGlassBar provides true,
                ) { bar() }
            }
            Box(Modifier.fillMaxWidth().height(20.dp).graphicsLayer { alpha = fade.value }
                .background(Brush.verticalGradient(listOf(page, page.copy(alpha = 0f)))))
        }
    }
}
