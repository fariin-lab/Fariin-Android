package com.fariin.app.ui.settings

import android.content.Context
import android.content.Intent
import com.fariin.app.data.BlockList
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.MyProfile
import com.fariin.app.data.Presence
import com.fariin.app.device.DeviceRegistry
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/**
 * Your Account Data, after Kulan-iOS DataExport.accountReport: profile, sign-in methods, privacy,
 * devices, blocked accounts and the chat count. Never any message. Shared as plain text.
 */
object AccountReport {
    data class Device(val model: String, val os: String, val app: String, val lastSeenMs: Long, val thisDevice: Boolean)
    data class Blocked(val name: String, val handle: String)

    fun providerName(id: String) = when (id) {
        "password" -> "Email and password"; "apple.com" -> "Apple"; "google.com" -> "Google"; else -> id
    }

    fun blockedLine(b: Blocked) = when {
        b.name.isNotEmpty() && b.handle.isNotEmpty() -> "${b.name} (@${b.handle})"
        b.handle.isNotEmpty() -> "@${b.handle}"
        b.name.isNotEmpty() -> b.name
        else -> "An account you blocked"
    }

    /** Pure: the file's text. */
    fun build(
        now: Long, uid: String, name: String, handle: String, about: String, email: String?, joinedMs: Long?, hasPhoto: Boolean,
        providers: List<String>, privacy: Map<String, String>, devices: List<Device>, blocked: List<Blocked>, chats: Int,
        fmt: (Long) -> String,
    ): String = buildString {
        append("Fariin, account information\n")
        append("Created ${fmt(now)}\n\n")
        append("ACCOUNT\n")
        append("Name: $name\n")
        append("Username: @$handle\n")
        if (about.isNotEmpty()) append("Bio: $about\n")
        append("Account ID: $uid\n")
        if (!email.isNullOrEmpty()) append("Email: $email\n")
        if (joinedMs != null) append("Joined: ${fmt(joinedMs)}\n")
        append("Profile photo: ${if (hasPhoto) "Yes" else "No"}\n\n")
        append("SIGN-IN METHODS\n")
        if (providers.isEmpty()) append("  None\n")
        for (p in providers) append("  ${providerName(p)}\n")
        append("\nPRIVACY\n")
        if (privacy.isEmpty()) append("  Defaults\n")
        for ((k, v) in privacy.toSortedMap()) append("  $k: $v\n")
        append("\nDEVICES\n")
        if (devices.isEmpty()) append("  None\n")
        for (d in devices.sortedByDescending { it.lastSeenMs }) {
            append("  ${d.model}, ${d.os}, app ${d.app}, last active ${fmt(d.lastSeenMs)}")
            append(if (d.thisDevice) " (this phone)\n" else "\n")
        }
        append("\nBLOCKED ACCOUNTS\n")
        if (blocked.isEmpty()) append("  None\n")
        for (b in blocked) append("  ${blockedLine(b)}\n")
        append("\nACTIVITY\n")
        append("Chats: $chats\n\n")
        append("Messages are not in this file. One-to-one messages are end-to-end encrypted and only ")
        append("your phones can read them.\n")
    }

    /** True while an export is being read; a second tap meanwhile does nothing (one share sheet). */
    val exporting = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Reads what the file needs and opens the share sheet. Throws a sentence to show. */
    suspend fun share(context: Context) {
        if (!exporting.compareAndSet(false, true)) return
        try { shareOnce(context) } finally { exporting.value = false }
    }

    private suspend fun shareOnce(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("You are logged out.")
        val uid = user.uid
        val db = FirebaseFirestore.getInstance()
        val me = try { db.collection("users").document(uid).get().await().data ?: emptyMap() }
            catch (_: Exception) { throw IllegalStateException("Could not load everything. Check your connection and try again.") }
        val thisId = DeviceRegistry.thisDeviceId(context)
        val devices = try {
            db.collection("users").document(uid).collection("devices").get().await().documents.map { d ->
                Device(d.getString("model") ?: "", d.getString("os") ?: "", d.getString("appVersion") ?: "",
                    (d.get("lastSeenAt") as? Timestamp)?.toDate()?.time ?: 0L, d.id == thisId)
            }
        } catch (_: Exception) { throw IllegalStateException("Could not load everything. Check your connection and try again.") }
        val blocked = BlockList.snapshot.entries.keys.sorted().map { b ->
            val d = try { db.collection("users").document(b).get().await().data } catch (_: Exception) { null }
            Blocked(d?.get("name") as? String ?: "", d?.get("handle") as? String ?: "")
        }
        val profile = MyProfile.fromFirestore(uid, me)
        val joined = (me["joinedAt"] as? Timestamp)?.toDate()?.time ?: user.metadata?.creationTimestamp
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val text = build(
            System.currentTimeMillis(), uid, profile.name, profile.handle, profile.about, user.email, joined, profile.photoUrl != null,
            user.providerData.map { it.providerId }.filter { it != "firebase" }, Presence.privacyOf(me), devices, blocked,
            CryptoHub.listedAll().count { !it.isCleared(uid) }, fmt = { df.format(Date(it)) },
        )
        // A file, as iOS DataExport (Fariin-Account-Information.txt): shared as text, picking a chat in the
        // sheet sent the whole account summary as a message. In the app's cache, shared read-only.
        val dir = java.io.File(context.cacheDir, "open").also { it.mkdirs() }
        val file = java.io.File(dir, "Fariin-Account-Information.txt").also { it.writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Fariin-Account-Information.txt")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Your Account Data")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
