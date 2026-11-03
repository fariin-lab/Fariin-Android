package com.fariin.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            // Back in the chat screen's floating circle with the iOS chevron, on every page.
            if (onBack != null) FloatingBack(onBack, Modifier.padding(start = 8.dp, end = 4.dp))
        },
        actions = actions,
        windowInsets = insets,

        colors = TopAppBarDefaults.topAppBarColors(
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
                     val durationMs: Long? = null)

    internal val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    fun show(message: String, action: String? = null, long: Boolean = false, durationMs: Long? = null, onAction: (() -> Unit)? = null) {
        events.tryEmit(Event(message, action, onAction, long, durationMs))
    }
}

/** Put once at the root, over everything: snackbars rise above the navigation bar and keyboard. */
@Composable
fun SnackHost(bottomOffset: androidx.compose.ui.unit.Dp = 80.dp) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        Snack.events.collect { e ->
            host.currentSnackbarData?.dismiss()
            val ms = e.durationMs
            val r = if (ms != null) kotlinx.coroutines.withTimeoutOrNull(ms) {
                host.showSnackbar(e.message, e.action, withDismissAction = false, duration = SnackbarDuration.Indefinite)
            } else host.showSnackbar(e.message, e.action, withDismissAction = false,
                duration = if (e.action != null || e.long) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) e.onAction?.invoke()
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = bottomOffset), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host)
    }
}
