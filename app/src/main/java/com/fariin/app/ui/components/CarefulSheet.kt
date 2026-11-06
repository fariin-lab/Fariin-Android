package com.fariin.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/*
 * Fariin's careful action sheet: the one place a step that is hard to take back is asked about
 * (delete account, leave, delete chat, block). A title, a short line, optional choices, then
 * stacked round buttons: softer ways out first, the action itself, Cancel last. Red only when the
 * action really destroys something. A flow may have several steps that slide inside one sheet.
 *
 * No glass in here: a sheet is its own window, so the page's blur cannot reach it (LocalGlassWindow).
 */

/** How a [CarefulButton] looks: a softer way out, the plain main action, or the destructive one. */
enum class CarefulKind { SOFT, MAIN, DESTRUCTIVE }

/**
 * One stacked button. [closes] = the sheet slides away first, then [onClick] runs (a step change
 * keeps it open). [busy] shows a spinner and ignores taps.
 */
data class CarefulButton(
    val label: String,
    val kind: CarefulKind = CarefulKind.MAIN,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    val closes: Boolean = true,
    val onClick: () -> Unit,
)

/** One page of the sheet. [options] are radio rows (key to label) with [selected] ticked. */
class CarefulStep(
    val title: String,
    val body: String? = null,
    val options: List<Pair<String, String>> = emptyList(),
    val selected: String? = null,
    val onSelect: (String) -> Unit = {},
    val error: String? = null,
    val extra: (@Composable () -> Unit)? = null,
    val buttons: List<CarefulButton>,
)

/**
 * The sheet. [step] picks which of [steps] shows; moving it forward slides the new step in from
 * the end, back slides from the start (no slide with Power Saving animations off).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarefulSheet(onDismiss: () -> Unit, steps: List<CarefulStep>, step: Int = 0, cancelLabel: String = "Cancel") {
    val c = Fariin.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val motion = animationsOn()
    fun close(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); then() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = c.bgSecondary,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.tertiary))
        },
    ) {
        AnimatedContent(
            targetState = step.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
            transitionSpec = {
                if (!motion) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                else {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(260)) { w -> if (forward) w else -w } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally(tween(260)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "careful-step",
        ) { i ->
            val s = steps.getOrNull(i) ?: return@AnimatedContent
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                if (!s.body.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.body, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (s.options.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)) {
                        s.options.forEachIndexed { n, (key, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { s.onSelect(key) }
                                    .heightIn(min = 50.dp).padding(start = 6.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = key == s.selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp),
                                    colors = RadioButtonDefaults.colors(selectedColor = c.blue, unselectedColor = c.secondary))
                                Text(label, fontSize = 16.sp, color = c.label)
                            }
                            if (n < s.options.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 50.dp))
                        }
                    }
                }
                s.extra?.let { Spacer(Modifier.height(16.dp)); it() }
                if (!s.error.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(s.error, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Two red choices (Block / Block and Report, Delete for Me / for Everyone): only the
                    // main one, the first, is solid; the others are tonal, red words on a light red.
                    val mainRed = s.buttons.indexOfFirst { it.kind == CarefulKind.DESTRUCTIVE }
                    s.buttons.forEachIndexed { n, b ->
                        val (fill, ink) = when (b.kind) {
                            CarefulKind.SOFT -> c.card to c.label
                            CarefulKind.MAIN -> c.blue to Color.White
                            CarefulKind.DESTRUCTIVE ->
                                if (n == mainRed) c.red to Color.White else c.red.copy(alpha = 0.12f) to c.red
                        }
                        CarefulPill(b.label, fill, ink, b.enabled && !b.busy, b.busy) {
                            if (b.closes) close(b.onClick) else b.onClick()
                        }
                    }
                    CarefulPill(cancelLabel, Color.Transparent, c.label, true, false) { close {} }
                }
            }
        }
    }
}

@Composable
private fun CarefulPill(label: String, fill: Color, ink: Color, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled || busy) Modifier else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) FariinSpinner(size = 22.dp, color = ink)
        else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}
