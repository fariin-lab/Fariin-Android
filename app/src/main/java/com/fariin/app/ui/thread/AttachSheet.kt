package com.fariin.app.ui.thread

import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.media.MediaRules
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The attach sheet, after iOS AttachSheetController + AttachRecentsStrip, built on the Android
 * Photo Picker (no photo or video permission, Play policy): the picker opens with the sheet, the
 * chosen photos and videos fill the grid edge to edge with a numbered tick on each (tap to drop
 * one), "Add photos or videos" opens the picker again, and File opens the document picker. Once
 * something is chosen the bar is a caption field, a "View once" switch (one photo only, as iOS)
 * and Send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    allowViewOnce: Boolean,
    onDismiss: () -> Unit,
    onSendMedia: (uris: List<Uri>, caption: String, viewOnce: Boolean) -> Unit,
    onSendFile: (Uri) -> Unit,
) {
    val c = Fariin.colors
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picked = remember { mutableStateListOf<Uri>() }
    val videos = remember { mutableStateListOf<Uri>() }
    var caption by remember { mutableStateOf("") }
    var viewOnce by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaRules.MEDIA_PER_PICK),
    ) { uris ->
        for (u in uris) if (u !in picked && picked.size < MediaRules.MEDIA_PER_PICK) {
            picked += u
            if ((context.contentResolver.getType(u) ?: "").startsWith("video/")) videos += u
        }
    }
    val openGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    // The library opens with the sheet (iOS shows recents at once).
    LaunchedEffect(Unit) { openGallery() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { onSendFile(uri); onDismiss() }
    }
    val onePhoto = picked.size == 1 && picked[0] !in videos
    if (!onePhoto || !allowViewOnce) viewOnce = false

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                if (picked.isEmpty()) {
                    AddTile(Modifier.fillMaxSize(), openGallery)
                } else {
                    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(picked.toList(), key = { it.toString() }) { u ->
                            PickedTile(u, u in videos, picked.indexOf(u) + 1) { picked.remove(u); videos.remove(u) }
                        }
                        if (picked.size < MediaRules.MEDIA_PER_PICK) item { AddTile(Modifier.aspectRatio(1f), openGallery) }
                    }
                }
            }
            if (picked.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SheetButton("Gallery", Icons.Filled.PhotoLibrary, openGallery)
                    SheetButton("File", Icons.AutoMirrored.Filled.InsertDriveFile) { files.launch(arrayOf("*/*")) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onePhoto && allowViewOnce) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (viewOnce) c.blue else c.secondary.copy(alpha = 0.15f))
                                .clickable { viewOnce = !viewOnce },
                            contentAlignment = Alignment.Center,
                        ) { Text("1", color = if (viewOnce) Color.White else c.label, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                            .border(0.5.dp, c.separator, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (caption.isEmpty()) Text(if (viewOnce) "View once" else "Add a caption", fontSize = 17.sp, color = c.secondary)
                        BasicTextField(caption, { caption = it }, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                            cursorBrush = SolidColor(c.blue), maxLines = 5, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.blue)
                            .clickable { onSendMedia(picked.toList(), caption, viewOnce); onDismiss() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                            if (picked.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                Text("${picked.size}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTile(modifier: Modifier, onAdd: () -> Unit) {
    val c = Fariin.colors
    Column(modifier.background(c.secondary.copy(alpha = 0.08f)).clickable(onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.PhotoSizeSelectActual, null, tint = c.blue, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(6.dp))
        Text("Add photos or videos", fontSize = 14.sp, color = c.blue, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun SheetButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = c.label)
    }
}

@Composable
private fun PickedTile(uri: Uri, isVideo: Boolean, number: Int, onRemove: () -> Unit) {
    val c = Fariin.colors
    val context = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(300, 300), null).asImageBitmap()
                else if (isVideo) android.media.MediaMetadataRetriever().run {
                    try { setDataSource(context, uri); getScaledFrameAtTimeCompat(this) } finally { release() }
                }?.asImageBitmap()
                else com.fariin.app.media.BitmapDecode.fromUri(context, uri, 300)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.aspectRatio(1f).background(c.secondary.copy(alpha = 0.15f)).clickable(onClick = onRemove)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (isVideo) {
            Text("Video", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape).background(c.blue)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$number", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Android 8 video tile: one frame near the start, shrunk to tile size (getScaledFrameAtTime is 27+). */
private fun getScaledFrameAtTimeCompat(r: android.media.MediaMetadataRetriever): android.graphics.Bitmap? {
    val f = r.getFrameAtTime(0) ?: return null
    val (w, h) = com.fariin.app.media.MediaRules.fit(f.width, f.height, 300)
    return android.graphics.Bitmap.createScaledBitmap(f, w, h, true).also { if (it !== f) f.recycle() }
}
