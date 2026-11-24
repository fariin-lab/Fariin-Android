package com.fariin.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex

/**
 * Pages that swap inside one screen (steps, sub-pages) with the app's page motion, never a cut:
 * a deeper page slides in from the right over the old one, which drifts left; going back slides
 * the top page off to the right. [depth] says how deep a state is (0 = the first page).
 */
@Composable
fun <T> SlidePages(
    target: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val to = depth(targetState)
            val spec = if (to >= depth(initialState)) Motion.pageIn() togetherWith Motion.pageUnder()
                else Motion.pageBackUnder() togetherWith Motion.pageOut()
            spec.apply { targetContentZIndex = to.toFloat() }
        },
        label = "slidePages",
    ) { content(it) }
}

/** A full page opened on top of a screen (was `if (open) Page()`): slides in from the right, off on close. */
@Composable
fun SlideOver(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize().zIndex(10f),
        enter = Motion.pageIn(),
        exit = Motion.pageOut(),
    ) { content() }
}
