package com.fariin.app.ui.people

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fariin.app.crypto.Wire
import com.fariin.app.data.ChatExport
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.HiddenMessages
import com.fariin.app.data.Message
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

/**
 * Export Chat (iOS DataExport): reads one chat's messages, opens them the way the chat does, writes
 * the text (ChatExport) to the app's cache and shares it as a FILE, read-only, like Your Account Data.
 * Never as message text: picking a chat in the sheet would send the whole history as one message.
 */
object ChatExportRun {
    /** Hidden on both pages, as iOS: the owner took Export Chat off the profile (2026-09-30) and the group menu. */
    const val SHOWN = false
    private const val UNREADABLE = "Could not load everything. Check your connection and try again."

    /** What to export: a 1:1 with [otherUid] named [title], or a group named [title]. */
    data class Job(val cid: String, val title: String, val otherUid: String? = null)

    /** Reads, opens and writes on a background thread; throws a sentence to show. */
    suspend fun file(context: Context, job: Job): File = withContext(Dispatchers.IO) {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: throw IllegalStateException("You are logged out.")
        val group = Wire.isGroupCid(job.cid)
        val crypto = CryptoHub.get(context)
        try { crypto.ensureReady() } catch (_: Exception) {}
        val conv = CryptoHub.listedConversation(job.cid)
        val clearedMs = conv?.clearedAt?.get(me) ?: 0.0
        val col = FirebaseFirestore.getInstance().collection("conversations").document(job.cid).collection("messages")
        // A failed read is an error, never a half-empty file that looks whole (iOS).
        val docs = try {
            if (group) col.orderBy("createdAt", Query.Direction.DESCENDING).limit(ChatExport.GROUP_CAP.toLong()).get().await().documents.reversed()
            else col.orderBy("createdAt").get().await().documents
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            throw IllegalStateException(UNREADABLE)
        }
        // Every author's key before any line is opened.
        val authors = if (group) docs.mapNotNull { it.getString("authorId") }.filter { it.isNotEmpty() && it != me }.toSet()
            else setOfNotNull(job.otherUid)
        try { crypto.preloadKeys(authors) } catch (_: Exception) {}
        val now = System.currentTimeMillis().toDouble()
        val messages = ArrayList<Message>(docs.size)
        for (d in docs) {
            coroutineContext.ensureActive()
            val m = Message.fromFirestore(d.id, d.data ?: emptyMap(), job.cid, now) { raw, author -> crypto.decrypt(raw, job.cid, author) }
            if (ChatExport.keeps(m, clearedMs, HiddenMessages::isHidden, now)) messages.add(m)
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val fmt: (Double) -> String = { df.format(Date(it.toLong())) }
        val stamp = df.format(Date())
        val text = if (group) ChatExport.groupText(job.title, me, conv?.names ?: emptyMap(), messages,
                cut = docs.size >= ChatExport.GROUP_CAP, now = stamp, fmt = fmt)
            else ChatExport.chatText(job.title, me, messages, stamp, fmt)
        val name = if (group) ChatExport.groupFileName(job.title) else ChatExport.chatFileName(job.title)
        coroutineContext.ensureActive()
        val dir = File(context.cacheDir, "open").also { it.mkdirs() }
        cleanup(dir)
        File(dir, name).also { it.writeText(text) }
    }

    /** An earlier export's decrypted text does not stay behind: only the newest file is kept. */
    private fun cleanup(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith("Fariin-Chat-") || it.name.startsWith("Fariin-Group-") }?.forEach { it.delete() }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export Chat")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** The spinner while [job] is read and written; Cancel (or leaving the page) stops it. */
@Composable
fun ChatExportDialog(job: ChatExportRun.Job, onDone: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    LaunchedEffect(job) {
        try {
            val f = ChatExportRun.file(ctx, job)
            ChatExportRun.share(ctx, f)
        } catch (e: IllegalStateException) {
            Snack.show(e.message ?: "Could not export the chat.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            Snack.show("Could not export the chat.")
        }
        onDone()
    }
    AlertDialog(onDismissRequest = onDone,
        title = { Text("Export Chat") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = c.accent)
                Spacer(Modifier.width(14.dp))
                Text("Preparing the file...", color = c.label)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}
