package com.fariin.app.ui.thread

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import org.json.JSONArray
import org.json.JSONObject
import com.fariin.app.R

/**
 * Files sent from this phone, newest first, so one can go again in one tap (iOS RecentFiles).
 * Per account. The row keeps the picker's lasting read grant, not a copy; a file since deleted
 * from the phone drops out of the list.
 */
object RecentFiles {
    data class Entry(val uri: String, val name: String, val size: Long, val at: Long) {
        /** A photo or video from the phone's gallery (drawn with its own picture). */
        val isMedia: Boolean get() = uri.startsWith("content://media/")
    }

    /** Name and size of a phone file, for the Recent list. */
    fun describe(c: Context, uri: Uri, at: Long): Entry? = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null else Entry(uri.toString(), cur.getString(0) ?: "File", if (cur.isNull(1)) 0L else cur.getLong(1), at)
        }
    }.getOrNull()

    private const val MAX = 60
    private fun key(): String = "recentFiles." + (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "none")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("fariin_recent_files", Context.MODE_PRIVATE)

    fun list(c: Context): List<Entry> = runCatching {
        val a = JSONArray(prefs(c).getString(key(), "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getString("u"), it.getString("n"), it.optLong("s"), it.optLong("t")) } }
    }.getOrDefault(emptyList())

    /** Keeps the grant and puts [uri] at the top (the same file moves up instead of doubling). */
    fun record(c: Context, uri: Uri) {
        runCatching { c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = uri.lastPathSegment ?: "File"
        var size = 0L
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        }
        val kept = listOf(Entry(uri.toString(), name, size, System.currentTimeMillis())) + list(c).filter { it.uri != uri.toString() }
        val a = JSONArray()
        kept.take(MAX).forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("s", it.size).put("t", it.at)) }
        prefs(c).edit().putString(key(), a.toString()).apply()
    }

    /** Still on the phone and still readable. */
    fun reachable(c: Context, e: Entry): Boolean = runCatching {
        c.contentResolver.openAssetFileDescriptor(Uri.parse(e.uri), "r")?.use { true } ?: false
    }.getOrDefault(false)
}

private enum class FileSort { DATE, NAME }

/** How many photos one Send from Add files > Photos may carry; each is its own file message (iOS PhotoFileExport.maxPicks). */
internal const val PHOTO_FILES_MAX = 20

/**
 * The attach sheet's Files page (iOS AttachFilesPage, plus the search and sort the owner asked for
 * on 2026-11-04 from the reference app): back, title, search, sort; the Upload card opens the
 * phone's file browser; Choose from gallery goes back to the photos; Recent sends a file again.
 */
@Composable
internal fun AttachFilesPage(onBack: () -> Unit, onBrowse: () -> Unit, onGallery: () -> Unit, onAudio: () -> Unit, onSend: (Uri) -> Unit,
                             /** Photos ticked in the app's own library picker, in tap order, to go out as files. */
                             onSendPhotos: (List<Uri>) -> Unit = { it.forEach(onSend) }) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    // PHOTOS AS FILES (iOS f46637b3, owner 2026-11-08): the story's own library picker (never the
    // system one), photos only, several ticked, "Send N". The hand-off waits for that sheet to be gone.
    var choosingPhotos by remember { mutableStateOf(false) }
    var pickedPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    if (choosingPhotos) {
        val multi = remember {
            com.fariin.app.ui.stories.StoryLibraryMulti(emptyList(), 0, { _, _ -> },
                onSendFiles = { pickedPhotos = it }, maxPicks = PHOTO_FILES_MAX)
        }
        com.fariin.app.ui.stories.StoryLibrarySheet(busy = false, onPick = {}, onClose = {
            choosingPhotos = false
            val picked = pickedPhotos
            pickedPhotos = emptyList()
            if (picked.isNotEmpty()) onSendPhotos(picked)
        }, multi = multi)
    }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FileSort.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    // Recent = the phone's own newest photos and videos (shown at once, as the reference app's Files
    // page; owner 2026-11-04: "upload is another step") plus files sent from here before.
    val all by androidx.compose.runtime.produceState(emptyList<RecentFiles.Entry>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sent = RecentFiles.list(ctx).filter { RecentFiles.reachable(ctx, it) }
            val phone = if (com.fariin.app.media.RecentMedia.access(ctx) == com.fariin.app.media.RecentMedia.Access.NONE) emptyList()
                else com.fariin.app.media.RecentMedia.load(ctx, limit = 80).mapNotNull { RecentFiles.describe(ctx, it.uri, it.dateAdded * 1000) }
            (sent + phone).distinctBy { it.uri }
        }
    }
    val shown = remember(all, query, sort) {
        all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .let { l -> if (sort == FileSort.NAME) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.at } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_left), "Back", tint = c.label)
            }
            if (searching) {
                com.fariin.app.ui.components.FloatingSearchField(query, { query = it }, "Search files", Modifier.weight(1f).padding(end = 8.dp))
            } else {
                Text("Files", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { searching = true }) { Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), "Search", tint = c.label) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_sort), "Sort", tint = c.label) }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = c.card) {
                        listOf(FileSort.DATE to "Sort by date", FileSort.NAME to "Sort by name").forEach { (s, label) ->
                            DropdownMenuItem(text = { Text(label, color = c.label) }, onClick = { sort = s; sortMenu = false },
                                trailingIcon = { if (sort == s) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.accent) })
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (!searching) {
                item {
                    // Three ways in, as one rounded card (owner 2026-11-04: rounded and polished, as iOS).
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).background(c.card)) {
                        SourceRow(ImageVector.vectorResource(R.drawable.ic_sf_photo_on_rectangle), "Photos", "Original quality, sent as files") { choosingPhotos = true }
                        SourceRow(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_file), "Browse documents", "PDFs, documents and other files", onBrowse)
                        SourceRow(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), "Choose from gallery", "Photos or videos in full quality", onGallery)
                        SourceRow(ImageVector.vectorResource(R.drawable.ic_sf_headphones), "Browse audio", "Music and other sound files", onAudio)
                    }
                }
                item { Text("Recent", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp)) }
            }
            if (shown.isEmpty()) item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "Your recent files will appear here." else "No files found", fontSize = 15.sp, color = c.secondary)
                }
            }
            // The Recent rows read as one rounded card: the first and last rows carry the corners.
            items(shown.size, key = { shown[it].uri }) { i ->
                val f = shown[i]
                val top = if (i == 0) 22.dp else 0.dp
                val bottom = if (i == shown.lastIndex) 22.dp else 0.dp
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.card)
                    .clickable { onSend(Uri.parse(f.uri)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val ext = f.name.substringAfterLast('.', "").uppercase().take(4)
                    val pic by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, f.uri) {
                        value = if (!f.isMedia) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            attachThumb(ctx, Uri.parse(f.uri), f.name.endsWith(".mp4", true), 120)
                        }
                    }
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.blue), contentAlignment = Alignment.Center) {
                        val p0 = pic
                        if (p0 != null) androidx.compose.foundation.Image(p0, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(ext.ifEmpty { "FILE" }, color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(android.text.format.Formatter.formatShortFileSize(ctx, f.size) + " • " +
                            android.text.format.DateUtils.getRelativeTimeSpanString(f.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
                            fontSize = 14.sp, color = c.secondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** One way in on the Files page: icon tile, title, one line under it. */
@Composable
private fun SourceRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.fieldFill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.label, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 17.sp, color = c.label)
            Text(sub, fontSize = 14.sp, color = c.secondary)
        }
    }
}
