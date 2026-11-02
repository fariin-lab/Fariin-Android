package com.fariin.app.ui.stories

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.Conversation
import com.fariin.app.data.StoryAudienceChoice
import com.fariin.app.data.StoryMedia
import com.fariin.app.data.StoryRules
import com.fariin.app.data.StoryService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The post in flight (iOS postStoryBackground): the composer closes at once and the Stories row
 * says "Uploading…" until the story is up, or that it could not be posted.
 */
object StoryUploads {
    sealed interface State {
        data object Uploading : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(me: String, media: StoryMedia.Prepared, caption: String, choice: StoryAudienceChoice, picked: Set<String>,
              hours: Int, convs: List<Conversation>) {
        state.value = State.Uploading
        scope.launch {
            state.value = try {
                StoryService(me).post(media, caption, choice, picked, hours, convs)
                null
            } catch (e: StoryService.PostRefused) {
                State.Failed(e.message ?: "Couldn't post your story.")
            } catch (e: Exception) {
                android.util.Log.w("StoryPost", "post failed", e)
                State.Failed("Couldn't post your story. Check your connection and try again.")
            }
        }
    }
}

/**
 * The editor after picking a photo or video (iOS StoryEditorView, the parts Android has): the
 * picture, "Add a caption…", the lifetime menu (6, 12, 24, 48 hours) and Next, which opens
 * "Who can see your story" with Post Story.
 */
@Composable
fun StoryComposerDialog(
    me: String,
    media: StoryMedia.Prepared,
    conversations: List<Conversation>,
    onClose: () -> Unit,
    /** After Post Story; the camera closes with it (iOS closeAfterPost). Close alone goes back to the camera. */
    onPosted: () -> Unit = onClose,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        StoryComposer(me, media, conversations, onClose, onPosted)
    }
}

@Composable
private fun StoryComposer(me: String, media: StoryMedia.Prepared, conversations: List<Conversation>, onClose: () -> Unit, onPosted: () -> Unit) {
    var caption by remember { mutableStateOf("") }
    var hours by remember { mutableIntStateOf(StoryRules.DEFAULT_HOURS) }
    var hoursMenu by remember { mutableStateOf(false) }
    var audience by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val bmp = remember(media) { BitmapFactory.decodeByteArray(media.jpeg, 0, media.jpeg.size)?.asImageBitmap() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (media.video != null) {
            Text("Video · ${media.video.info.durationSec.toInt()} s", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(44.dp).clickable { hoursMenu = true }, contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(24.dp).border(1.3.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text("$hours", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                DropdownMenu(hoursMenu, onDismissRequest = { hoursMenu = false }) {
                    Text("Choose how long the story will be visible.", fontSize = 13.sp, color = Fariin.colors.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(220.dp))
                    StoryRules.HOURS.forEach { h ->
                        DropdownMenuItem(
                            text = { Text("$h Hours") },
                            trailingIcon = { if (h == hours) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { hours = h; hoursMenu = false },
                        )
                    }
                }
            }
        }
        // Gone while the audience page shows: a caption field left behind kept the keyboard up
        // over Post Story.
        if (!audience) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.4f))
                .navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (caption.isEmpty()) Text("Add a caption…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                BasicTextField(caption, { caption = it.take(1000) }, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White), maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Next", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).clickable { focus.clearFocus(); keyboard?.hide(); audience = true }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
        if (audience) {
            AudiencePage(me, conversations, onBack = { audience = false }) { choice, picked ->
                StoryUploads.start(me, media, caption, choice, picked, hours, conversations)
                onPosted()
            }
        }
    }
}

/** "Who can see your story": Everyone, My Chats, Custom and One-Time Story, then Post Story. */
@Composable
private fun AudiencePage(
    me: String,
    conversations: List<Conversation>,
    onBack: () -> Unit,
    onPost: (StoryAudienceChoice, Set<String>) -> Unit,
) {
    val c = Fariin.colors
    var choice by remember { mutableStateOf(StoryAudienceChoice.MY_CHATS) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf(false) }
    val contacts = remember(conversations) {
        val ids = StoryRules.contacts(me, conversations)
        conversations.filter { !it.isGroup && it.otherUid(me) in ids }
    }
    BackHandler { if (picking) picking = false else onBack() }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { if (picking) picking = false else onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label)
            }
            Text(if (picking) choice.title else "Share Story", color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
        if (!picking) {
            Text("Who can see your story", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            StoryAudienceChoice.entries.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        choice = a
                        if (a.needsPeople) picking = true
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = c.label, fontSize = 17.sp)
                        val sub = when {
                            a.needsPeople && choice == a && picked.isNotEmpty() -> if (picked.size == 1) "1 person" else "${picked.size} people"
                            a == StoryAudienceChoice.MY_CHATS -> if (contacts.size == 1) "1 person" else "${contacts.size} people"
                            else -> null
                        }
                        if (sub != null) Text(sub, color = c.secondary, fontSize = 14.sp)
                    }
                    Icon(if (choice == a) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                        tint = if (choice == a) c.accent else c.secondary)
                }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 20.dp))
            }
            Text(choice.footer, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(contacts.size, key = { contacts[it].id }) { i ->
                    val conv = contacts[i]
                    val uid = conv.otherUid(me)
                    val on = uid in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (on) picked - uid else picked + uid }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(conv.photoUrl(me), 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(conv.name(me), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                            tint = if (on) c.accent else c.secondary)
                    }
                }
            }
        }
        val ready = !choice.needsPeople || picked.isNotEmpty()
        Text(
            if (picking) "Done" else "Post Story", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (ready || picking) c.accent else c.accent.copy(alpha = 0.4f))
                .clickable(enabled = ready || picking) { if (picking) picking = false else onPost(choice, picked) }
                .padding(vertical = 14.dp),
        )
    }
}

/** Picks a photo or video, prepares it off the main thread, then the caller opens the composer. */
suspend fun prepareStoryMedia(context: android.content.Context, uri: Uri): Result<StoryMedia.Prepared> =
    kotlinx.coroutines.withContext(Dispatchers.Default) {
        try {
            StoryMedia.prepare(context, uri)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Couldn't open this file."))
        } catch (e: StoryMedia.TooBig) {
            Result.failure(e)
        }
    }
