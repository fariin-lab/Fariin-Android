package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
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
    /** False: always the plain bar (a host that is sometimes a sheet). */
    enabled: Boolean = true,
    /** The bar is frosted: the rows show blurred through it (its own background must be clear). */
    frosted: Boolean = false,
    /** No bar at all: the buttons float over the page (iOS info pages). */
    clear: Boolean = false,
    bar: @Composable () -> Unit,
    content: @Composable (top: Dp) -> Unit,
) {
    val glass = com.fariin.app.perf.rememberPowerState().glass()
    val liquid = enabled && glass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
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
        val haze = remember { dev.chrisbanes.haze.HazeState() }
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(page).then(if (frosted) Modifier.hazeSource(haze) else Modifier)) {
            CompositionLocalProvider(LocalLiquidBackdrop provides null) {
                content(with(density) { barPx.toDp() })
            }
            // The glass must see what the eye sees: the bar's solid page colour and its fade, not the
            // rows hidden under them (the back circle showed ghost letters from under the bar,
            // Pixel 2026-11-06).
            if (!frosted && !clear) Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(with(density) { barPx.toDp() }).background(page))
        }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth().then(if (frosted) Modifier.frostedTopBar(haze, page, true) else Modifier)
                .onSizeChanged { barPx = it.height }) {
                CompositionLocalProvider(
                    LocalLiquidBackdrop provides backdrop,
                    LocalGlassWindow provides LocalView.current,
                    LocalGlassFrost provides frost,
                    LocalGlassBar provides true,
                    LocalGlassBlur provides frosted,
                    LocalFrostedBar provides (frosted || clear),
                ) { bar() }
            }
            // No line under the bar (owner 2026-11-08).
        }
    }
}

/**
 * [GlassBarPage] for a page laid out as a column (bar, then the body): the body is a [Column], so
 * `weight` and the like keep working. Pad the scroll's top INSIDE it by `top`; a body part that
 * stays put (a search field, a spinner) starts with `Spacer(Modifier.height(top))` instead.
 */
@Composable
fun GlassColumnPage(
    under: Boolean,
    modifier: Modifier = Modifier,
    page: Color = Fariin.colors.bgSecondary,
    barEstimate: Dp = 64.dp,
    enabled: Boolean = true,
    /** The body's own colour where it is not [page]; the bar and its glass stay on [page]. */
    body: Color? = null,
    bar: @Composable () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(top: Dp) -> Unit,
) = GlassBarPage(under, modifier, page, barEstimate, enabled, bar = bar) { top ->
    Column(Modifier.fillMaxSize().then(if (body != null) Modifier.background(body) else Modifier)) { content(top) }
}

/** True once a scrolled page's rows are under its bar (the fade under a [GlassBarPage] bar). */
@Composable
fun underBar(scroll: androidx.compose.foundation.ScrollState): Boolean {
    val u by remember(scroll) { androidx.compose.runtime.derivedStateOf { scroll.value > 0 } }
    return u
}

/** The same for a lazy list. */
@Composable
fun underBar(list: androidx.compose.foundation.lazy.LazyListState): Boolean {
    val u by remember(list) { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    return u
}

/** A plain Material icon or text button in a bar: in a glass circle on a [GlassBarPage] bar, as the floating ones. */
@Composable
fun barGlass(): Modifier = if (glassBar()) Modifier.size(44.dp).floating() else Modifier

/**
 * A pushed page with a [FloatingTopBar] (the status bar inside the bar, in [page] colour) over a
 * column body, its back and actions glass as on [GlassColumnPage]. The body gets the bar's height.
 */
@Composable
fun FloatingBarPage(
    title: String,
    onBack: (() -> Unit)?,
    under: Boolean = false,
    modifier: Modifier = Modifier,
    page: Color = Fariin.colors.bg,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(top: Dp) -> Unit,
) = GlassColumnPage(under, modifier.background(page), page = page, bar = {
    Box(Modifier.fillMaxWidth().background(page).statusBarsPadding()) {
        FloatingTopBar(title, onBack = onBack, subtitle = subtitle, container = page, actions = actions)
    }
}, content = content)
