package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.LiveStories
import com.fariin.app.data.Message
import com.fariin.app.data.StoryGroup
import com.fariin.app.ui.theme.Fariin

/**
 * iOS story mention card (MessageRowModelBuilder.storyMentionRow): the story as the app's own story
 * card (100 wide, the Stories list's 66:94 shape), "NAME mentioned you in a story" or "You mentioned
 * NAME in a story", and View Story. Once the story is gone (deleted or expired) it is one centred
 * line saying so, broken after the name. 1:1 chats only.
 */
@Composable
internal fun StoryMentionCard(m: Message, sm: Message.StoryMentionRef, me: String, otherName: String,
                              fill: Color) {
    val c = Fariin.colors
    val mine = m.authorId == me
    val name = otherName.ifEmpty { if (mine) "them" else "Someone" }
    val feed by LiveStories.state.collectAsState()
    val live = LiveStories.find(feed, sm.id, m.authorId)
    var open by remember { mutableStateOf<StoryGroup?>(null) }
    open?.let { g -> com.fariin.app.ui.stories.StoryViewerDialog(me, listOf(g), 0, com.fariin.app.data.ChatsNow.list, onClose = { open = null }) }

    fun line(before: String, after: String) = buildAnnotatedString {
        append(before)
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
        append(after)
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        if (live == null) {
            val text = if (mine) line("The story where you mentioned ", "\nis no longer available")
            else line("The story where ", " mentioned you\nis no longer available")
            Text(text, fontSize = 12.sp, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(fill).padding(horizontal = 12.dp, vertical = 5.dp))
            return@Box
        }
        Column(Modifier.widthIn(max = 236.dp).clip(RoundedCornerShape(20.dp)).background(fill).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            coil3.compose.AsyncImage(sm.thumbUrl.ifEmpty { live.second.thumbUrl }, contentDescription = "Story",
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(100.dp).height(142.dp).clip(RoundedCornerShape(14.dp))
                    .background(c.secondary.copy(alpha = 0.15f)).clickable { open = live.first })
            Spacer(Modifier.height(12.dp))
            Text(if (mine) line("You mentioned ", " in a story") else line("", " mentioned you in a story"),
                fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(164.dp).height(38.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f))
                .clickable { open = live.first }, contentAlignment = Alignment.Center) {
                Text("View Story", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            }
        }
    }
}
