package com.fariin.app.ui.stories

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.TextStoryRules

/** The four faces (iOS TextStoryStyles.uiFont): bold, black, serif semibold, monospaced semibold. */
internal fun textStoryTypeface(fontIndex: Int): Typeface {
    val (family, weight) = when (TextStoryRules.face(fontIndex)) {
        1 -> Typeface.SANS_SERIF to 900
        2 -> Typeface.SERIF to 600
        3 -> Typeface.MONOSPACE to 600
        else -> Typeface.DEFAULT to 700
    }
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(family, weight, false) else Typeface.create(family, Typeface.BOLD)
}

private fun layoutOf(text: String, widthPx: Int, tf: Typeface, sizePx: Float, color: Int = 0): StaticLayout {
    val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizePx; this.color = color }
    return StaticLayout.Builder.obtain(text, 0, text.length, p, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
}

/** iOS fittedSize, measured with the same typeface the words are drawn in. */
internal fun fittedTextStorySize(text: String, boxWidthPx: Float, fontIndex: Int): Float {
    val tf = textStoryTypeface(fontIndex)
    return TextStoryRules.fittedSize(text, boxWidthPx) { s -> layoutOf(text, boxWidthPx.toInt(), tf, s).height.toFloat() }
}

/**
 * iOS renderTextStory: the words on their colour, 1080 x 2700 drawn at 1.5x, URLs shortened, the
 * same fit as the card at the picture's own width. Posted as a plain photo story. Off the main thread.
 */
internal fun renderTextStory(text: String, styleIndex: Int, fontIndex: Int): StoryMedia.Prepared? {
    val t = TextStoryRules.shortenedUrls(text).trim()
    if (t.isEmpty()) return null
    val k = TextStoryRules.RENDER_SCALE
    val w = (TextStoryRules.RENDER_W * k).toInt()
    val h = (TextStoryRules.RENDER_H * k).toInt()
    val pad = TextStoryRules.RENDER_PAD * k
    val box = w - pad * 2
    val style = TextStoryRules.style(styleIndex)
    val size = fittedTextStorySize(t, box, fontIndex)
    val ink = if (style.inkIsWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val layout = layoutOf(t, box.toInt(), textStoryTypeface(fontIndex), size, ink)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(style.bg.toInt())
    c.translate(pad, (h - layout.height) / 2f)
    layout.draw(c)
    return StoryMedia.fromRendered(bmp, TextStoryRules.RENDER_QUALITY)
}

/**
 * The TEXT half of the story camera (iOS StoryTextCard): a coloured card, the X top-left, the words
 * in the middle, "Aa" and the colour ring bottom-left, and a tick bottom-right while the keyboard is
 * up. A tap on the card raises or drops the keyboard; arriving never raises it.
 *
 * [bottomReserve]: what the page's bottom bar covers while the keyboard is down.
 */
@Composable
internal fun StoryTextCard(
    text: String,
    onText: (String) -> Unit,
    styleIndex: Int,
    onNextStyle: () -> Unit,
    fontIndex: Int,
    onNextFont: () -> Unit,
    onTyping: (Boolean) -> Unit,
    bottomReserve: Dp,
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var cardWidth by remember { mutableIntStateOf(0) }
    val style = TextStoryRules.style(styleIndex)
    val ink = if (style.inkIsWhite) Color.White else Color.Black
    val bg by animateColorAsState(Color(style.bg), tween(300, easing = FastOutSlowInEasing), label = "textStoryBg")

    val imePx = WindowInsets.ime.getBottom(density)
    val ime = with(density) { imePx.toDp() }
    val keyboardUp = imePx > 0

    // Fitted against a box that never changes (the card less 28 dp a side), so opening the
    // keyboard cannot resize the words under his hand.
    val sidePx = with(density) { TextStoryRules.CARD_SIDE_DP.dp.toPx() }
    val boxPx = (cardWidth - sidePx * 2).coerceAtLeast(1f)
    val face = remember(fontIndex) { FontFamily(textStoryTypeface(fontIndex)) }
    val sizePx = remember(text, boxPx, fontIndex) { fittedTextStorySize(text, boxPx, fontIndex) }
    val fontSize = with(density) { sizePx.toSp() }
    val textStyle = TextStyle(color = ink, fontSize = fontSize, fontFamily = face, textAlign = TextAlign.Center)

    fun setFocus(on: Boolean) {
        if (on) { requester.requestFocus(); keyboard?.show() } else { focus.clearFocus(force = true); keyboard?.hide() }
    }
    fun close() { if (text.isBlank()) onClose() else { setFocus(false); askDiscard = true } }
    LaunchedEffect(active) { if (!active && focused) setFocus(false) }

    BackHandler(enabled = active) { close() }

    Box(modifier.fillMaxSize().background(bg).onSizeChanged { cardWidth = it.width }) {
        // The whole card is the one gesture: tap to type, tap again to stop.
        // A real tap only: a flick between CAMERA and TEXT travels across the card and must not
        // raise the keyboard on the way.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val slop = viewConfiguration.touchSlop
                var moved = false
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > slop) moved = true
                    if (!c.pressed) break
                }
                if (!moved) setFocus(!focused)
            }
        })

        // The words' box is what the keyboard leaves (plus 68 dp for the button row above it).
        Box(
            Modifier.fillMaxSize().statusBarsPadding()
                .padding(top = 6.dp, bottom = if (keyboardUp) ime + 68.dp else bottomReserve),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isEmpty()) {
                Text("Type something…", style = textStyle.copy(color = ink.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TextStoryRules.CARD_SIDE_DP.dp))
            }
            BasicTextField(
                text, { onText(it.take(TextStoryRules.CHAR_LIMIT)) },
                textStyle = textStyle,
                cursorBrush = SolidColor(ink),
                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = TextStoryRules.CARD_SIDE_DP.dp)
                    .focusRequester(requester)
                    .onFocusChanged { focused = it.isFocused; onTyping(it.isFocused) },
            )
        }

        GlassCircle(ink, "Close", Modifier.statusBarsPadding().padding(14.dp), onClick = { close() }) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = ink)
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp)
                .padding(bottom = 14.dp + if (keyboardUp) ime else bottomReserve),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCircle(ink, "Change font", onClick = onNextFont) {
                    Text("Aa", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                GlassCircle(ink, "Change colour", onClick = onNextStyle) {
                    Box(Modifier.size(21.dp).border(2.dp, ink, CircleShape))
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(focused, enter = fadeIn(), exit = fadeOut()) {
                GlassCircle(ink, "Done", onClick = { setFocus(false) }) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = ink)
                }
            }
        }
    }

    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false; setFocus(true) },
        title = { Text("Discard this status?") },
        confirmButton = { TextButton({ askDiscard = false; onClose() }) { Text("Discard", color = Color(0xFFE5484D)) } },
        dismissButton = { TextButton({ askDiscard = false; setFocus(true) }) { Text("Cancel") } },
    )
}

/** The card's controls: one 46 dp round shape, a light glass over the colour. */
@Composable
private fun GlassCircle(ink: Color, label: String, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.size(46.dp).clip(CircleShape)
            .background(if (ink == Color.White) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.3f))
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
