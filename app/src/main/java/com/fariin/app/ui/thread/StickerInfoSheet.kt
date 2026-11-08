package com.fariin.app.ui.thread

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.StickerCatalog
import com.fariin.app.data.StickerFavorites
import com.fariin.app.data.StickerRef
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import com.fariin.app.R

/** Sends a sticker into the open chat: set by the thread while it is on screen (the pack grid uses it). */
object StickerSendHook { var send: ((StickerRef) -> Unit)? = null }

/**
 * A sticker tapped in a chat (iOS StickerInfoSheet): the sticker at 200, "<pack> · Fariin", Add to or
 * Remove from Favorites, and for a built-in one View sticker pack (its grid; a tap sends). One someone
 * made is saved from its sealed picture in the message (iOS StickerSend.toggleFavorite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerInfoSheet(m: Message, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val packId = m.stickerPack ?: return
    val s = StickerRef(packId, m.stickerId ?: return, m.stickerEmoji ?: "", m.stickerAnimated)
    val mine = packId == StickerFavorites.MINE
    val favs by StickerFavorites.list.collectAsState()
    val favorite = favs.any { it.key == s.key }
    val pack = remember(packId) { StickerCatalog.packs(ctx).firstOrNull { it.id == packId } }
    var showPack by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    // The sealed picture of one someone made, opened as the bubble opens it.
    val file = if (mine) rememberGatedFile(m.localPath ?: m.imageUrl, m.enc, null).file else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetFill, shape = SheetShape, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                Box(Modifier.padding(start = 12.dp).size(44.dp).align(Alignment.CenterStart).clip(CircleShape)
                    .background(c.label.copy(alpha = 0.08f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = c.label, modifier = Modifier.size(20.dp))
                }
            }
            if (!showPack) {
                StickerArt(packId, s.stickerId, m.localPath ?: m.imageUrl, m.enc, Modifier.size(200.dp))
                Spacer(Modifier.height(10.dp))
                Text("${pack?.title ?: "Stickers"} · Fariin", fontSize = 15.sp, color = c.secondary)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(c.card)) {
                    Row(rowMod(enabled = !working && (!mine || favorite || file != null)) {
                        working = true
                        scope.launch {
                            if (mine && !favorite) file?.readBytes()?.let { runCatching { StickerFavorites.addMine(it, s.emoji, keepingId = s.stickerId) } }
                            else runCatching { StickerFavorites.toggle(s) }
                            working = false
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_star_outline), null, tint = c.label, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(if (favorite) "Remove from Favorites" else "Add to Favorites", fontSize = 17.sp, color = c.label)
                    }
                    if (pack != null) Row(rowMod(true) { showPack = true }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_square_grid), null, tint = c.label, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("View sticker pack", fontSize = 17.sp, color = c.label)
                    }
                }
            } else if (pack != null) {
                Text(pack.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pack.stickers, key = { it.key }) { p ->
                        Box(Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).clickable { StickerSendHook.send?.invoke(p); onDismiss() },
                            contentAlignment = Alignment.Center) {
                            StickerArt(p.packId, p.stickerId, null, null, Modifier.size(72.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun rowMod(enabled: Boolean, onClick: () -> Unit) =
    Modifier.fillMaxWidth().height(54.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp)
