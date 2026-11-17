package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.fariin.app.data.Message

/**
 * The first chat opened after launch cost about half as much again as the next ones on a cheap
 * phone (2026-11-16, 132-186 ms against 66-143 ms for its opening frame): the page's code, its text
 * and its drawing were all met for the first time in the tap's frame. So, once the list has sat
 * still for a moment, a made-up chat page (no real person, nothing sent or read from the server)
 * is built once off screen for a few frames and dropped. A chat opened before that skips it.
 */
object ThreadWarm {
    /** A real chat has opened, or the warm-up ran: nothing more to do this run of the app. */
    @Volatile var done = false
    /** When a finger last touched the app (MainActivity), so the warm-up's heavy frame never lands in a scroll or fling. */
    @Volatile var lastTouchMs = 0L
}

@Composable
fun ThreadWarmUp(me: String) {
    if (ThreadWarm.done || me.isEmpty()) return
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(WAIT_MS)
        // Never in a page change: the warm-up's frames must not land in a slide.
        // Nor under a finger or a fling still settling: only after the screen has been left alone a moment.
        while (com.fariin.app.ui.components.PageMotion.moving ||
            android.os.SystemClock.uptimeMillis() - ThreadWarm.lastTouchMs < QUIET_MS) kotlinx.coroutines.delay(200)
        if (ThreadWarm.done) return@LaunchedEffect
        on = true
        // Built, measured and drawn (a few frames for the list's rows and the deferred parts), then gone.
        repeat(FRAMES) { androidx.compose.runtime.withFrameNanos { } }
        ThreadWarm.done = true
        on = false
    }
    if (!on) return
    val ui = remember(me) { warmUi(me) }
    // Its own back dispatcher: the page's back handlers never reach the app's.
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val back = remember { object : androidx.activity.OnBackPressedDispatcherOwner {
        override val onBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher()
        override val lifecycle = life
    } }
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }.graphicsLayer {
        // Off to the right of the screen: drawn into its layer, never seen.
        translationX = size.width * 2f
    }) {
        CompositionLocalProvider(androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides back) {
            ThreadScreen(ui, onBack = {}, onSend = {})
        }
    }
}

/** A 1:1 with nobody ("warm-up" is no account): a few lines each way, as a chat opens. */
private fun warmUi(me: String): ThreadUi {
    val now = System.currentTimeMillis().toDouble()
    val peer = "warm-up"
    val words = listOf("Hi", "How are you doing today?", "Good, thanks. Coming over later?",
        "Yes, around six if that works for you", "See you then", "Ok 👍")
    val msgs = words.mapIndexed { i, w ->
        Message(id = "warm-$i", authorId = if (i % 2 == 0) peer else me, text = w,
            createdAtMillis = now - (words.size - i) * 60_000.0)
    }
    return ThreadUi(title = " ", me = me, messages = msgs, cid = "${me}_$peer", canLoadOlder = false)
}

private const val WAIT_MS = 2_500L
private const val FRAMES = 4
private const val QUIET_MS = 1_500L
