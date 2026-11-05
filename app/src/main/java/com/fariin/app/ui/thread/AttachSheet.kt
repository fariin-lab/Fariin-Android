package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.fariin.app.ui.components.floating
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
 * Photo Picker (no photo or video permission, Play policy). The sheet opens first with its sources
 * (Gallery, Camera, File, Poll, Location, Contact) in view, one back closes it; the
 * chosen photos and videos fill the grid edge to edge with a numbered tick on each (the tick drops
 * one, a tap on a photo opens the editor, with two or more the pager editor), "Add photos or videos" opens the picker again, and File opens the document picker. Once
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
    /** A group where members may send neither photos nor videos: the grid is closed and says why. */
    mediaOff: String? = null,
    /** The Poll button (groups only, as iOS); the sheet goes down and the poll composer opens. */
    onPoll: (() -> Unit)? = null,
    /** The Location page and the contact picker (CardSheets); the sheet goes down before either opens. */
    onLocation: (() -> Unit)? = null,
    onContact: (() -> Unit)? = null,
) {
    val c = Fariin.colors
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picked = remember { mutableStateListOf<Uri>() }
    val videos = remember { mutableStateListOf<Uri>() }
    var caption by remember { mutableStateOf("") }
    var viewOnce by remember { mutableStateOf(false) }
    // A chosen photo open in the editor. One photo chosen: the whole editor page (iOS
    // ChatImageEditor: crop, pen, HD, its own caption and Send, which sends and closes the sheet).
    var drawOn by remember { mutableStateOf<Uri?>(null) }
    // Two or more chosen: a tap opens the pager editor on that item (iOS MediaApprovalView).
    var pagerAt by remember { mutableStateOf<Int?>(null) }
    // OUR OWN GRID OF THE PHONE'S PHOTOS, never the Google picker (owner 2026-11-04, showing the
    // reference app's sheet). Access is asked the first time the sheet opens; "some photos" works too.
    var access by remember { mutableStateOf(com.fariin.app.media.RecentMedia.access(context)) }
    var asked by remember { mutableStateOf(false) }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = com.fariin.app.media.RecentMedia.access(context)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (access == com.fariin.app.media.RecentMedia.Access.NONE && mediaOff == null && !asked) {
            asked = true
            askPhotos.launch(com.fariin.app.media.RecentMedia.permissions())
        }
    }
    val recents by produceState(emptyList<com.fariin.app.media.RecentMedia.Item>(), access) {
        value = if (access == com.fariin.app.media.RecentMedia.Access.NONE) emptyList()
            else withContext(Dispatchers.IO) { com.fariin.app.media.RecentMedia.load(context) }
    }
    val toggle = { it: com.fariin.app.media.RecentMedia.Item ->
        if (it.uri in picked) { picked.remove(it.uri); videos.remove(it.uri) }
        else if (picked.size < MediaRules.MEDIA_PER_PICK) { picked += it.uri; if (it.isVideo) videos += it.uri }
    }
    // Camera: the phone's own camera app (iOS UIImagePickerController .camera); the photo joins the
    // chosen ones, so caption, editor and Send work as for a picked photo.
    var shotUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri
        if (ok && u != null && picked.size < MediaRules.MEDIA_PER_PICK) picked += u
    }
    val launchCamera = {
        runCatching {
            val dir = java.io.File(context.cacheDir, "chat-camera").apply { mkdirs() }
            val f = java.io.File(dir, "shot-${System.currentTimeMillis()}.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
            shotUri = u
            camera.launch(u)
        }.onFailure { com.fariin.app.ui.components.Snack.show("Couldn't open the camera.") }
        Unit
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else com.fariin.app.ui.components.Snack.show("Allow the camera in Settings to take a photo.")
    }
    val openCamera = {
        if (mediaOff != null) com.fariin.app.ui.components.Snack.show(mediaOff)
        else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
        else askCamera.launch(android.Manifest.permission.CAMERA)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { onSendFile(uri); onDismiss() }
    }
    val onePhoto = picked.size == 1 && picked[0] !in videos
    if (!onePhoto || !allowViewOnce) viewOnce = false

    // The reference sheet: the phone's photos edge to edge in threes (camera first), a select circle
    // on each, the sources in one floating bar over the grid's bottom; once something is chosen
    // that bar becomes the caption and Send.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, dragHandle = null,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            val none = access == com.fariin.app.media.RecentMedia.Access.NONE
            if (mediaOff != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(mediaOff, fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(24.dp))
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(top = 10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "camera") { CameraTile(openCamera) }
                    if (none) item(key = "allow", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        AllowTile { askPhotos.launch(com.fariin.app.media.RecentMedia.permissions()) }
                    }
                    if (access == com.fariin.app.media.RecentMedia.Access.PARTIAL) item(key = "more") {
                        AllowTile(short = true) { askPhotos.launch(com.fariin.app.media.RecentMedia.permissions()) }
                    }
                    items(recents, key = { it.uri.toString() }) { r ->
                        val n = picked.indexOf(r.uri)
                        RecentTile(r, if (n >= 0) n + 1 else 0, onToggle = { toggle(r) }, onOpen = {
                            if (r.uri !in picked) toggle(r)
                            val at = picked.indexOf(r.uri)
                            if (at >= 0) { if (picked.size > 1) pagerAt = at else drawOn = r.uri }
                        })
                    }
                }
            }
            if (picked.isEmpty()) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                    .floating(RoundedCornerShape(32.dp)).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SheetButton("Gallery", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), selected = true) {}
                    SheetButton("File", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_file)) { files.launch(arrayOf("*/*")) }
                    if (onLocation != null) SheetButton("Location", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_location)) { onDismiss(); onLocation() }
                    if (onPoll != null) SheetButton("Poll", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_poll_tile)) { onDismiss(); onPoll() }
                }
            } else {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onePhoto && allowViewOnce) {
                        Box(
                            Modifier.size(44.dp).floating(fill = if (viewOnce) c.accent else null)
                                .clickable { viewOnce = !viewOnce },
                            contentAlignment = Alignment.Center,
                        ) { Text("1", color = if (viewOnce) c.onAccent else c.label, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).floating(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (caption.isEmpty()) Text(if (viewOnce) "View once" else "Add a caption", fontSize = 17.sp, color = c.secondary)
                        BasicTextField(caption, { caption = it }, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                            cursorBrush = SolidColor(c.accent), maxLines = 5, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.height(44.dp).floating(RoundedCornerShape(22.dp), fill = c.blue)
                            .clickable { onSendMedia(picked.toList(), caption, viewOnce); onDismiss() }
                            .padding(horizontal = 16.dp),
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
    drawOn?.let { u ->
        if (onePhoto) PhotoDrawEditor(u, editOnly = false, startDrawing = false, onClose = { drawOn = null },
            allowViewOnce = allowViewOnce,
            onSend = { photo, cap, once -> drawOn = null; onSendMedia(listOf(photo), cap, once); onDismiss() })
        // One video chosen: the video editor (iOS VideoApprovalView), the trimmer and Send.
        else if (picked.size == 1 && u in videos) VideoEditor(u, caption, onCaption = { caption = it }, onClose = { drawOn = null },
            onSend = { cap -> drawOn = null; onSendMedia(listOf(u), cap, false); onDismiss() })
    }
    pagerAt?.let { at ->
        MediaPagerEditor(picked, videos, at, caption, onCaption = { caption = it }, onClose = { pagerAt = null },
            onSend = { list, cap -> pagerAt = null; onSendMedia(list, cap, false); onDismiss() })
    }
}

/** First tile: the camera (reference app's first cell). */
@Composable
private fun CameraTile(onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).background(Color(0xFF2B2B2E)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), "Camera", tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

/** No access yet (or only some photos): one tap asks again. */
@Composable
private fun AllowTile(short: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.aspectRatio(if (short) 1f else 2f).background(c.card).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), null, tint = c.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(if (short) "Choose more photos" else "Allow access to your photos to send them here",
            fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SheetButton(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    // A tab of the floating bar; the open one (Gallery) sits on a soft accent pill, as the reference.
    Column(Modifier.clip(RoundedCornerShape(26.dp)).then(if (selected) Modifier.background(c.accent.copy(alpha = 0.12f)) else Modifier)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) c.accent else c.label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = if (selected) c.accent else c.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** One of the phone's photos or videos: square, a select circle top right (its number once chosen). */
@Composable
private fun RecentTile(item: com.fariin.app.media.RecentMedia.Item, number: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = Fariin.colors
    val context = LocalContext.current
    val uri = item.uri
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
                else if (item.isVideo) android.media.MediaMetadataRetriever().run {
                    try { setDataSource(context, uri); getScaledFrameAtTimeCompat(this) } finally { release() }
                }?.asImageBitmap()
                else com.fariin.app.media.BitmapDecode.fromUri(context, uri, 256)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val chosen = number > 0
    Box(Modifier.aspectRatio(1f).background(c.secondary.copy(alpha = 0.12f)).clickable(onClick = onOpen)) {
        bmp?.let {
            Image(it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (chosen) Modifier.padding(6.dp).clip(RoundedCornerShape(8.dp)) else Modifier))
        }
        if (item.isVideo) {
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(MediaRules.durationLabel(item.durationMs / 1000.0), color = Color.White, fontSize = 11.sp)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).size(44.dp).clickable(onClick = onToggle), contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier.padding(7.dp).size(24.dp).clip(CircleShape)
                    .background(if (chosen) c.blue else Color.Black.copy(alpha = 0.12f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (chosen) Text("$number", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Android 8 video tile: one frame near the start, shrunk to tile size (getScaledFrameAtTime is 27+). */
private fun getScaledFrameAtTimeCompat(r: android.media.MediaMetadataRetriever): android.graphics.Bitmap? {
    val f = r.getFrameAtTime(0) ?: return null
    val (w, h) = com.fariin.app.media.MediaRules.fit(f.width, f.height, 300)
    return android.graphics.Bitmap.createScaledBitmap(f, w, h, true).also { if (it !== f) f.recycle() }
}
