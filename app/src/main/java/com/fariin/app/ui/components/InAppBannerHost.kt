package com.fariin.app.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.push.InAppBanner
import com.fariin.app.push.InAppNotify
import com.fariin.app.push.PushRouter
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The in-app message banner (iOS InAppBannerCard): a message for another chat while Fariin is open.
 * Face, name, two lines of the message; a tap or a pull down opens the chat, a swipe up sends it
 * away, it leaves by itself after [InAppNotify.VISIBLE_MS]. A newer message replaces it. Mounted
 * once over the signed-in app; the In-App Vibrate buzz is done here too.
 */
@Composable
fun InAppBannerHost() {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        InAppNotify.buzz.collect { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }
    val banner by InAppNotify.banner.collectAsState()
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = banner,
            transitionSpec = {
                (slideInVertically(tween(300)) { -it * 2 } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically(tween(300)) { -it * 2 } + fadeOut(tween(250)))
            },
            label = "inAppBanner",
        ) { b -> if (b != null) Card(b) }
    }
}

@Composable
private fun Card(b: InAppBanner) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val px = LocalDensity.current.density
    val dragY = remember(b) { Animatable(0f) }
    val felt = remember(b) { booleanArrayOf(false) }
    fun open() { InAppNotify.dismiss(); PushRouter.pendingOpen.value = b.cid }
    // Down is rubber-banded and capped at 50, up is free (iOS rubberBanded, coefficient 0.55).
    fun banded(y: Float): Float = if (y <= 0f) y else (1f - 1f / (y * 0.55f / 50f + 1f)) * 50f
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .offset { IntOffset(0, (banded(dragY.value / px) * px).roundToInt()) }
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .background(Fariin.colors.card, RoundedCornerShape(24.dp))
            .fillMaxWidth()
            .height(64.dp)
            // Read out when it lands (a polite live region), as one item that opens the chat.
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                onClick(label = "Open chat") { open(); true }
            }
            .pointerInput(b) {
                detectVerticalDragGestures(
                    onDragStart = { InAppNotify.hold() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dragY.snapTo(dragY.value + amount) }
                        // The pull-down is felt at 24 points, just past the 20 that commits it.
                        if (!felt[0] && banded((dragY.value + amount) / px) > 24f) {
                            felt[0] = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    },
                    onDragEnd = {
                        felt[0] = false
                        val travel = dragY.value / px
                        when {
                            banded(travel) > 20f -> open()
                            travel < -5f -> InAppNotify.dismiss()
                            else -> { scope.launch { dragY.animateTo(0f, tween(300)) }; InAppNotify.release() }
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f, tween(300)) }; InAppNotify.release() },
                )
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) { open() }
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A channel's face is its rounded square, as on its row and its page (owner 2026-11-10).
        Avatar(b.photo, 40.dp, squircle = com.fariin.app.data.CryptoHub.listedConversation(b.cid)?.isChannel == true ||
            com.fariin.app.channel.ChannelFollows.docs.value[b.cid]?.get("channel") == true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(b.title, color = Fariin.colors.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(b.body, color = Fariin.colors.secondary, fontSize = 15.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
