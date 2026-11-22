package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.StickerGifRules
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.delay

/**
 * A GIF with words (iOS GifCaptionScreen, owner 2026-11-21): "Add Caption" on a held GIF opens this.
 * The GIF playing in the middle on a dark screen, a round glass X, and the photo editor's caption bar
 * at the foot with the keyboard already up. Send hands back the trimmed caption ("" when none was
 * typed); the chat sends ONE GIF message whose `text` is the sealed caption (ThreadRepository.sendGif).
 * Plain words only, as on iOS (no bold or links), at most the photo caption's length.
 */
@Composable
internal fun GifCaptionScreen(gif: StickerGifRules.Gif, onClose: () -> Unit, onSend: (String) -> Unit) {
    com.fariin.app.ui.components.AppOrientation.KeepUpright()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EditorWindow()
        FariinTheme(dark = true) { GifCaptionBody(gif, onClose, onSend) }
    }
}

@Composable
private fun GifCaptionBody(gif: StickerGifRules.Gif, onClose: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    var caption by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up with the screen (iOS: 0.15 s after it shows).
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }

    fun send() {
        if (sent) return   // one send per screen, as the photo editor
        sent = true
        focusManager.clearFocus()
        onSend(caption.trim())
    }

    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))
        .clickable(interactionSource = noRipple, indication = null) { focusManager.clearFocus() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 12.dp, top = 6.dp)) {
                Box(Modifier.size(44.dp).floating().clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Color.White)
                }
            }
            // The GIF at its own shape, as large as fits, never wider than 420.
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val ratio = if (gif.width > 0 && gif.height > 0) (gif.width / gif.height).toFloat() else 1f
                Box(Modifier.widthIn(max = 420.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                    .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))
                    .clickable(interactionSource = noRipple, indication = null) { focusManager.clearFocus() }) {
                    // The first frame under it (kept in memory), the moving one over it once decoded.
                    AsyncImage(com.fariin.app.media.GifLoader.still(ctx, gif.url), contentDescription = null,
                        imageLoader = com.fariin.app.media.GifLoader.get(ctx), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                    AsyncImage(animatedRequest(remember(gif.url) { gridWebp(gif.url) }, true), contentDescription = "GIF",
                        imageLoader = com.fariin.app.media.GifLoader.get(ctx), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
            // The photo editor's caption bar: a glass field that grows to seven lines, the round send beside it.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Start) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).floating(RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (caption.isEmpty()) Text("Add a caption…", color = Color(0xFF8E8E93), fontSize = 17.sp)
                    BasicTextField(caption, { caption = it.take(com.fariin.app.media.MediaRules.CAPTION_CHARS) },
                        textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
                        maxLines = 7, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(EditorBlue).clickable { send() }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(R.drawable.ic_sf_arrow_up), contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
