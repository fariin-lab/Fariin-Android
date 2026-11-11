package com.fariin.app.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay

/**
 * Hold a profile's name or @username to copy it (iOS ui-batch-1111): a glass "Copy" pill shows
 * 6 dp above the text; a tap copies [copyText] with a light tap of haptics, the pill says "Copied"
 * and fades. Untouched, it goes by itself after 3 s.
 */
@Composable
fun CopyOnHold(copyText: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val shown = remember { MutableTransitionState(false) }
    var copied by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(0) }
    Box(modifier.pointerInput(copyText) {
        detectTapGestures(onLongPress = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            copied = false; shown.targetState = true; opened++
        })
    }) {
        content()
        if (shown.currentState || shown.targetState) {
            LaunchedEffect(opened, copied) {
                delay(if (copied) 900 else 3_000)
                shown.targetState = false
            }
            val gap = with(LocalDensity.current) { 2.dp.roundToPx() }   // + the pill's 4 dp shadow room = 6 dp
            Popup(popupPositionProvider = remember(gap) { AboveCentre(gap) }, onDismissRequest = { shown.targetState = false }) {
                AnimatedVisibility(shown, enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f),
                    exit = fadeOut(tween(220))) {
                    val c = Fariin.colors
                    val shape = RoundedCornerShape(50)
                    Box(Modifier.padding(4.dp).shadow(10.dp, shape, clip = false)
                        .background(if (c.dark) c.card else Color.White, shape)
                        .border(if (c.dark) 0.5.dp else 1.dp, if (c.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.9f), shape)
                        .clickable(remember { MutableInteractionSource() }, null, enabled = !copied) {
                            clipboard.setText(AnnotatedString(copyText))
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            copied = true
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(if (copied) "Copied" else "Copy", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label)
                    }
                }
            }
        }
    }
}

/** The pill centred over its text, [gap] above it (below when there is no room above). */
private class AboveCentre(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + gap)
    }
}
