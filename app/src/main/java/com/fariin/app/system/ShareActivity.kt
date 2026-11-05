package com.fariin.app.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import com.fariin.app.MainActivity
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.ThreadRepository
import com.fariin.app.local.LocalCache
import com.fariin.app.media.ChatMedia
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.photo.FariinPhotoFetcher
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import com.fariin.app.ui.thread.ForwardSheet
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fariin in the Android share sheet: text, links, photos, videos and files from any app.
 *  - From the app row: the chat picker (the forward sheet: search, up to five chats, Send).
 *  - From a chat in the Direct Share row (a [ChatShortcuts] shortcut): that chat straight away,
 *    with what is shared shown ready to send, a caption for photos, and Send.
 * The shared items are copied into the app's cache first (another app's permission to read
 * them ends with this screen, the upload does not), then sent through the chat's normal paths:
 * text through the send queue, photos and videos as the attach sheet sends them, files as
 * documents. Then the chat opens so the upload can be watched.
 */
class ShareActivity : ComponentActivity() {

    private data class Shared(val text: String?, val media: List<Uri>, val files: List<Uri>) {
        val empty get() = text == null && media.isEmpty() && files.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) {
            Toast.makeText(this, "Log in to Fariin first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val target = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        val shared = mutableStateOf<Shared?>(null)
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { read(intent) }
            if (s.empty) { Toast.makeText(this@ShareActivity, "Nothing to share", Toast.LENGTH_SHORT).show(); finish() }
            else shared.value = s
        }
        // The chat list for the picker: what the app already holds, or the phone's saved copy.
        lifecycleScope.launch(Dispatchers.IO) {
            if (CryptoHub.listedAll().isEmpty()) {
                val saved = LocalCache.loadChats(me).map { (id, d) -> Conversation.fromFirestore(id, d) }
                if (saved.isNotEmpty()) CryptoHub.noteListed(saved)
            }
        }
        setContent {
            setSingletonImageLoaderFactory { ctx -> ImageLoader.Builder(ctx).components { add(FariinPhotoFetcher.Factory()) }.build() }
            FariinTheme {
                val s = shared.value ?: return@FariinTheme
                var chosen by remember { mutableStateOf(target?.let { listOf(it) }) }
                val pick = chosen
                if (pick == null) {
                    ForwardSheet(me, title = "Send to", onDismiss = { if (chosen == null) finish() }, onSend = { chosen = it })
                } else {
                    ConfirmSheet(me, pick, s, onCancel = { finish() }, onSend = { caption -> send(me, pick, s, caption) })
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfirmSheet(me: String, cids: List<String>, s: Shared, onCancel: () -> Unit, onSend: (String) -> Unit) {
        val c = Fariin.colors
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var caption by remember { mutableStateOf(if (s.media.isNotEmpty()) s.text.orEmpty() else "") }
        val convs = remember(cids) { cids.map { id -> CryptoHub.listedConversation(id) } }
        val names = convs.mapIndexed { i, conv -> conv?.displayName(me) ?: ChatShortcuts.titleOf(this, cids[i]) ?: "Chat" }
        ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet, containerColor = c.bg) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(convs.firstOrNull()?.displayPhoto(me), 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Send to " + names.joinToString(", "), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2)
                        Text(ShareIntake.summary(s.media.size, s.files.size, s.text != null), fontSize = 14.sp, color = c.secondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (s.media.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(s.media) { u ->
                            AsyncImage(u, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(c.secondary.copy(alpha = 0.15f)))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                s.files.forEach { u ->
                    Text(u.lastPathSegment ?: "File", fontSize = 15.sp, color = c.label, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.secondary.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp))
                    Spacer(Modifier.height(6.dp))
                }
                if (s.media.isNotEmpty()) {
                    OutlinedTextField(caption, { caption = it }, placeholder = { Text("Add a caption") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4)
                } else if (s.text != null) {
                    Text(s.text, fontSize = 16.sp, color = c.label, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.secondary.copy(alpha = 0.12f))
                            .padding(12.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(c.blue).clickable { onSend(caption) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("Send", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    /** Sends to each chosen chat through its normal paths, then opens the chat (one) or the list. */
    private fun send(me: String, cids: List<String>, s: Shared, caption: String) {
        val app = applicationContext
        val crypto = CryptoHub.get(app)
        for (cid in cids) {
            val repo = ThreadRepository.openOrQuiet(cid, me, crypto, sends, ChatMedia(app, cid, crypto))
            if (s.media.isNotEmpty()) repo.sendPick(app, s.media, caption, viewOnce = false)
            else s.text?.let { repo.sendText(it) }
            // With media the words went as the caption; with files they go as their own message.
            if (s.media.isNotEmpty() && s.files.isNotEmpty() && caption.isBlank()) s.text?.let { repo.sendText(it) }
            for (f in s.files) repo.sendDocument(app, f) { why -> sends.launch(Dispatchers.Main) { Toast.makeText(app, why, Toast.LENGTH_LONG).show() } }
        }
        startActivity(if (cids.size == 1) ChatShortcuts.openIntent(this, cids[0])
            else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** The intent's text and streams, each stream copied into cache/shared-in under our provider. */
    private fun read(i: Intent): Shared {
        val text = ShareIntake.textOf(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        val streams: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.take(MAX_ITEMS)
        val media = ArrayList<Uri>(); val files = ArrayList<Uri>()
        for (u in streams) {
            val type = contentResolver.getType(u) ?: i.type
            val copy = copyIn(this, u, type) ?: continue
            if (ShareIntake.kindOf(type) == ShareIntake.Kind.MEDIA) media += copy else files += copy
        }
        // A shared text file with no other text is still a file, not the words in it.
        return Shared(text, media, files)
    }

    companion object {
        private const val MAX_ITEMS = 32
        private const val MAX_BYTES = 200L * 1024 * 1024
        /** Sends outlive this screen (it closes as soon as Send is tapped). */
        private val sends = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The stream's bytes in cache/shared-in/<id>/<name>, served by our FileProvider so
         *  the send path reads the type from the extension. */
        fun copyIn(c: Context, u: Uri, type: String?): Uri? = try {
            // Only another app's content may come in. A file:// path or our own provider's address
            // would be opened with Fariin's own rights, so a hostile app could pass one of our
            // private files (keys, the chat cache) under a harmless name and have it sent.
            require(ShareIntake.isForeignContent(u.scheme, u.authority, c.packageName)) { "not another app's content" }
            val name = c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
            val ext = ShareIntake.extensionFor(type, name)
            val base = (name?.substringBeforeLast('.') ?: "shared").filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
                .take(60).ifBlank { "shared" }
            val root = File(c.cacheDir, "shared-in")
            // Copies from earlier shares (sent by now) go after a day.
            root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.deleteRecursively() }
            // One folder per item, so the file keeps its own name (a document is sent under it).
            val dir = File(root, UUID.randomUUID().toString().take(12)).also { it.mkdirs() }
            val out = File(dir, "$base.$ext")
            var total = 0L
            c.contentResolver.openInputStream(u)?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n; if (total > MAX_BYTES) error("too large")
                        o.write(buf, 0, n)
                    }
                }
            } ?: error("not readable")
            FileProvider.getUriForFile(c, "${c.packageName}.files", out)
        } catch (_: Exception) { null }
    }
}
