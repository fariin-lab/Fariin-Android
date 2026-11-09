package com.fariin.app.ui.people

import com.fariin.app.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/**
 * iOS Edit Photo (owner 2026-11-08): a sheet over Edit Profile with X and a check mark, the photo big
 * in a circle with a minus to remove it, and a tap on the circle for Camera or Photo Library. Nothing
 * here touches the server: the check hands the choice back ([onDone]) and Edit Profile's Save sends it.
 */
@Composable
internal fun EditPhotoSheet(
    current: String?, held: ByteArray?, removed: Boolean,
    onCamera: () -> Unit, onLibrary: () -> Unit, onRemove: () -> Unit,
    onDone: () -> Unit, onCancel: () -> Unit,
) {
    val c = Fariin.colors
    val motion = com.fariin.app.ui.components.rememberSheetMotion(onCancel)
    val windowH = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    // Rests about halfway up, as the iOS sheet in the owner's screenshot.
    motion.mid = windowH * 0.42f
    var menu by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    val heldBmp = remember(held) { held?.let { j -> runCatching { android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size)?.asImageBitmap() }.getOrNull() } }
    val hasPhoto = heldBmp != null || (!removed && !current.isNullOrEmpty())
    com.fariin.app.ui.components.FariinSheetDialog(motion, fill = c.card) { _ ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().sheetDrag(motion).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable { motion.close() },
                    contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Cancel", tint = c.label) }
                Text("Edit Photo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd).size(44.dp).floating(CircleShape).clickable { onDone(); motion.close() },
                    contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Done", tint = c.label) }
            }
            Spacer(Modifier.height(20.dp))
            Box {
                Box(Modifier.size(220.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    if (heldBmp != null) Image(heldBmp, "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Avatar(if (removed) null else current, 220.dp)
                }
                // The minus at the circle's top right takes the photo off (asked first).
                if (hasPhoto) Box(Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 8.dp).size(44.dp).floating(CircleShape)
                    .clickable { askRemove = true }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_minus), "Remove photo", tint = c.label)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                    border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card,
                    offset = androidx.compose.ui.unit.DpOffset(40.dp, (-60).dp)) {
                    DropdownMenuItem(text = { Text("Camera", fontSize = 17.sp) },
                        trailingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), null, tint = c.label) },
                        onClick = { menu = false; onCamera() })
                    DropdownMenuItem(text = { Text("Photo Library", fontSize = 17.sp) },
                        trailingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), null, tint = c.label) },
                        onClick = { menu = false; onLibrary() })
                }
            }
        }
    }
    // iOS alert: a rounded card with two pills, over the sheet.
    if (askRemove) Dialog(onDismissRequest = { askRemove = false }) {
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(32.dp)).background(c.card).padding(24.dp)) {
            Text("Remove profile photo?", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Spacer(Modifier.height(8.dp))
            Text("Your initials will show instead. Nothing changes until you press Save.", fontSize = 16.sp, color = c.secondary)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(50.dp).clip(CircleShape).background(c.fieldFill).clickable { askRemove = false },
                    contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 17.sp, color = c.label) }
                Box(Modifier.weight(1f).height(50.dp).clip(CircleShape).background(c.fieldFill).clickable { askRemove = false; onRemove() },
                    contentAlignment = Alignment.Center) { Text("Remove", fontSize = 17.sp, color = c.red) }
            }
        }
    }
}
