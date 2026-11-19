package com.fariin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.PlusBubble
import com.fariin.app.data.PlusBubbles
import com.fariin.app.data.PlusStore
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.components.GlassAlert
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal const val PAGE_PLUS_BUBBLE = "look:plusbubble"

/** Settings > Appearance > Plus Bubble Style (iOS PlusBubbleStylePage): a preview and the four looks. */
@Composable
internal fun PlusBubbleStylePage(pop: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(PlusStore.visible) { if (!PlusStore.visible) pop() }
    var myName by remember { mutableStateOf("") }
    // My stored style, read once (the chat's listener keeps it fresh while a chat is open).
    LaunchedEffect(Unit) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return@LaunchedEffect
        val d = try { com.fariin.app.Fb.db.collection("users").document(me).get().await().data } catch (_: Exception) { null } ?: return@LaunchedEffect
        myName = (d["name"] as? String).orEmpty()
        PlusBubbles.record(PlusBubble.parse(d["plusBubble"]))
    }
    val current = PlusBubbles.drawn(PlusBubbles.mine)?.id
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plusNote by remember { mutableStateOf(false) }
    val on = PlusStore.state("bubbleStyles") == PlusStore.State.ON
    fun pick(id: String?) {
        if (busy || id == current) return
        if (!on) { plusNote = true; return }
        busy = true; error = null
        scope.launch {
            try {
                PlusBubbles.choose(id)
                // A Plus style replaces the free all-chats style.
                if (id != null) { com.fariin.app.data.BubbleStyles.init(ctx); com.fariin.app.data.BubbleStyles.save(null, com.fariin.app.data.BubbleStyles.decorations()) }
            } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    ScrollPage("Plus Bubble Style", pop) {
        // Preview: their bubble plain, mine in the picked look.
        val look = PlusBubbles.look(current)
        SettingsSection {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(c.card.copy(alpha = 1f)).border(0.5.dp, c.separator, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)) { Text("Nice colours", fontSize = 16.sp, color = c.label) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (look != null) gradient(look) else Brush.linearGradient(listOf(c.accent, c.accent)))
                        .padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text("${myName.ifEmpty { "Your" }} style", fontSize = 16.sp, color = if (look != null) Color(look.text) else Color.White)
                    }
                }
            }
        }
        error?.let { Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp)) }
        val tiles = listOf<Pair<String?, String>>(null to PlusBubbles.CLASSIC) + PlusBubbles.all.map { it.id to it.name }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (id, name) ->
                        val l = PlusBubbles.look(id)
                        val sel = id == current
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.card)
                            .border(if (sel) 2.dp else 0.dp, if (sel) PlusBlue else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { pick(id) }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp))
                                .background(if (l != null) gradient(l) else Brush.linearGradient(listOf(c.accent, c.accent))))
                            Text(name, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = c.label,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
    if (plusNote) GlassAlert(onDismissRequest = { plusNote = false },
        title = { Text("Fariin Plus") },
        text = { Text("These bubble styles come with Fariin Plus.") },
        confirmButton = { TextButton({ plusNote = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
}

private fun gradient(l: PlusBubbles.Look) = Brush.linearGradient(listOf(Color(l.from), Color(l.to)), start = Offset.Zero, end = Offset.Infinite)
