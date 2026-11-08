package com.fariin.app.ui.thread

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CustomEmojiUpload
import com.fariin.app.data.UploadedEmoji
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/** True for the one account that may choose and add custom emoji (iOS CustomReaction.canChoose). */
fun canChooseCustom(): Boolean = CustomEmojiUpload.canUpload(FirebaseAuth.getInstance().currentUser?.uid)

/**
 * The emoji chooser's Custom tab (iOS EmojiMorePicker customGrid): every custom emoji, the shipped
 * ones then the uploaded ones, newest last. For the chooser account it starts with a "+" that opens
 * the file picker (any file, several at once: Android has no type for .tgs) and sends them to the
 * server; they show up here by themselves once the live list has them.
 */
@Composable
fun CustomEmojiGrid(onPick: (String) -> Unit, modifier: Modifier = Modifier,
                    picked: (String) -> Boolean = { false }, onNote: ((String) -> Unit)? = null) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val uploaded by UploadedEmoji.items.collectAsState()
    val hidden by UploadedEmoji.hidden.collectAsState()
    val keys = remember(uploaded, hidden) { CustomReaction.all() }
    val canAdd = remember { canChooseCustom() }
    val busy by CustomEmojiUpload.busy.collectAsState()
    // The upload runs outside this sheet, so closing it does not stop the files halfway.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Said inside the sheet when it is still open (a snackbar sits behind it), and as a snackbar for after.
        CustomEmojiUpload.start(ctx, uris) { line -> onNote?.invoke(line); Snack.show(line) }
    }
    LazyVerticalGrid(GridCells.Adaptive(52.dp), modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 12.dp)) {
        if (canAdd) item(key = "add") {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.fieldFill)
                    .clickable(enabled = !busy) { pick.launch(arrayOf("*/*")) }
                    .semantics { contentDescription = "Add custom emoji from files" },
                    contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.secondary, strokeWidth = 2.dp)
                    else Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), contentDescription = null, tint = c.secondary, modifier = Modifier.size(20.dp))
                }
            }
        }
        items(keys, key = { it }) { key ->
            Box(Modifier.size(52.dp).clip(CircleShape).clickable { onPick(key) }, contentAlignment = Alignment.Center) {
                ReactionGlyph(key, 30.sp, 40.dp)
                PickMark(picked(key))
            }
        }
    }
}

/** An emoji that is in the list while picking many at once (iOS pickMark): an accent ring and a small tick. */
@Composable
fun PickMark(on: Boolean) {
    if (!on) return
    val c = Fariin.colors
    Box(Modifier.size(48.dp)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(c.accent.copy(alpha = 0.18f)).border(2.dp, c.accent, CircleShape))
        Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = "Chosen", tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
}
