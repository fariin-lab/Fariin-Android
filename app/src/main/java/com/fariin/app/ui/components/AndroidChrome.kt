package com.fariin.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.flow.MutableSharedFlow

/*
 * The Android pieces every screen shares: the Material 3 top app bar (title at the start, up
 * arrow, actions at the end) and one app-wide snackbar in place of iOS toasts and banners.
 */

/**
 * A Material 3 small top app bar. [insets] defaults to none because most screens already pad
 * the status bar on their own column; pass `TopAppBarDefaults.windowInsets` when they do not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FariinTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    insets: WindowInsets = WindowInsets(0, 0, 0, 0),
    /** The bar's fill; Settings pages pass their grey page colour. */
    container: androidx.compose.ui.graphics.Color = Fariin.colors.bg,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Fariin.colors
    // The title centred, 17 semibold. Back is the plain Android arrow on a solid bar; its circle only
    // over glass (owner 2026-11-11: "remove the liquid glass" on the backs, replacing 2026-11-10's always-circle).
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(title, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) FloatingBack(onBack, Modifier.padding(start = 8.dp, end = 4.dp))   // its circle only over glass (owner 2026-11-11)
        },
        // Glass circles at the end keep the back circle's distance from the edge (the last one was cut).
        actions = { actions(); if (glassBar()) androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) },
        windowInsets = insets,

        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = container,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = c.label,
            navigationIconContentColor = c.label,
            actionIconContentColor = c.label,
        ),
    )
}

/** The app-wide snackbar (Android's answer to the iOS toast). Any screen or view model can call [show]. */
object Snack {
    data class Event(val message: String, val action: String? = null, val onAction: (() -> Unit)? = null, val long: Boolean = false,
                     /** Shown this long, then gone (the Undo of a leave lives 5 s, iOS LeaveGroupUndo). */
                     val durationMs: Long? = null,
                     /** The group's photo before the words (iOS leave bar), a squircle for a channel. */
                     val photo: String? = null, val squircle: Boolean = false, val showPhoto: Boolean = false)

    internal val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    /** A bar is on screen: floating buttons in its way (the chat list's New Chat) lift above it. */
    val showing = androidx.compose.runtime.mutableStateOf(false)

    fun show(message: String, action: String? = null, long: Boolean = false, durationMs: Long? = null, onAction: (() -> Unit)? = null) {
        events.tryEmit(Event(message, action, onAction, long, durationMs))
    }

    /** The leave / delete Undo (iOS leaveUndoBar): a ring counting the seconds down, then the photo. */
    fun undo(message: String, photo: String?, squircle: Boolean, durationMs: Long, onUndo: () -> Unit) {
        events.tryEmit(Event(message, "Undo", onUndo, false, durationMs, photo, squircle, showPhoto = true))
    }
}

/** Put once at the root, over everything: snackbars rise above the navigation bar and keyboard. */
@Composable
fun SnackHost(bottomOffset: androidx.compose.ui.unit.Dp = 80.dp) {
    val host = remember { SnackbarHostState() }
    val current = remember { androidx.compose.runtime.mutableStateOf<Snack.Event?>(null) }
    LaunchedEffect(Unit) {
        Snack.events.collect { e ->
            host.currentSnackbarData?.dismiss()
            current.value = e
            val ms = e.durationMs
            val r = if (ms != null) kotlinx.coroutines.withTimeoutOrNull(ms) {
                host.showSnackbar(e.message, e.action, withDismissAction = false, duration = SnackbarDuration.Indefinite)
            } else host.showSnackbar(e.message, e.action, withDismissAction = false,
                duration = if (e.action != null || e.long) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) e.onAction?.invoke()
        }
    }
    val active = host.currentSnackbarData != null
    androidx.compose.runtime.SideEffect { Snack.showing.value = active }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = bottomOffset), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host) { data -> SnackPill(data, current.value) }
    }
}

/**
 * The iOS small pop-up (owner 2026-11-12): a 40 dp capsule hugging its words, quiet grey (a medium
 * grey by day, a dark grey at night), white words, the action word in semibold at the end. A timed
 * Undo starts with a ring counting the seconds down, then the group's photo (iOS leaveUndoBar).
 */
@Composable
private fun SnackPill(data: androidx.compose.material3.SnackbarData, e: Snack.Event?) {
    val c = Fariin.colors
    val fill = if (c.dark) androidx.compose.ui.graphics.Color(0xFF2B2B2B) else androidx.compose.ui.graphics.Color(0xFF595959)
    val white = androidx.compose.ui.graphics.Color.White
    val action = data.visuals.actionLabel
    val timed = e != null && e.showPhoto && e.durationMs != null && e.message == data.visuals.message
    val shadowInk = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.15f)
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(8.dp, CircleShape, ambientColor = shadowInk, spotColor = shadowInk)
            .clip(CircleShape).background(fill)
            .then(if (action != null) Modifier.clickable { data.performAction() } else Modifier)
            .height(40.dp)
            .padding(start = if (timed) 8.dp else 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        if (timed && e != null) {
            CountdownRing(e.durationMs ?: 5_000L, white)
            Avatar(e.photo, 24.dp, squircle = e.squircle, kind = AvatarKind.of(true, e.squircle))
        }
        Text(data.visuals.message, fontSize = 15.sp, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (action != null) Text(action, fontSize = 15.sp, color = white,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A 24 dp ring that empties over [ms], the whole seconds left in its middle rolling down. */
@Composable
private fun CountdownRing(ms: Long, ink: androidx.compose.ui.graphics.Color) {
    val left = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        left.animateTo(0f, androidx.compose.animation.core.tween(ms.toInt(), easing = androidx.compose.animation.core.LinearEasing))
    }
    val secs = kotlin.math.ceil(left.value * ms / 1000f).toInt().coerceAtLeast(0)
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val w = 2.dp.toPx()
            val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
            val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            drawArc(ink.copy(alpha = 0.3f), 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(ink, -90f, 360f * left.value, false, tl, sz,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        androidx.compose.animation.AnimatedContent(secs, transitionSpec = {
            (androidx.compose.animation.slideInVertically { -it / 2 } + androidx.compose.animation.fadeIn()) togetherWith
                (androidx.compose.animation.slideOutVertically { it / 2 } + androidx.compose.animation.fadeOut())
        }, label = "undoSecs") { n ->
            Text("$n", fontSize = 11.sp, color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}
