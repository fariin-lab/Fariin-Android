package com.fariin.app.device

import kotlinx.coroutines.tasks.await

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import com.fariin.app.ui.components.GlassAlert
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.fariin.app.push.NotificationPoster
import com.fariin.app.push.NotifStore
import com.fariin.app.push.Push
import com.google.firebase.auth.FirebaseAuth

/**
 * The two ways a session ends on this phone, in the iOS order (DeviceRegistry.removeThisDevice /
 * performRevokedSignOut, Push.unregister, then Auth, then the wipe). Everything that needs the
 * account's permission runs before Auth signs out.
 */
object SessionEnd {
    /** Log out tapped here: our own record goes, so it does not linger as a ghost in the list. */
    /** True while Log Out runs, so the app shows "Logging out…" instead of standing still. */
    val signingOut = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Set just before the Log Out that follows "schedule deletion" (iOS DeleteAccountView runs
     * SessionWipe with no kept account): nothing is kept on this phone for a return. The identity
     * key still stays, so a restore within the grace period reads old messages.
     */
    @Volatile var forDeletion = false

    suspend fun signOut(c: Context) {
        signingOut.value = true
        try { signOutSteps(c) } finally { signingOut.value = false }
    }

    private suspend fun signOutSteps(c: Context) {
        DeviceRegistry.init(c)
        // The account this Log Out is for, taken once: every step checks it is still the signed-in
        // one, and each server step has a cap (offline, a Firestore write never answers, and
        // "Logging out…" stood for good). A step past its cap is dropped; its write stays queued
        // under the old account and never runs as the next one.
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        DeviceRegistry.stopWatching()
        // iOS endCallsBeforeSignOut (audit M-015): a live call or a ring ends first, as my own end,
        // while the rules still let this account write it; otherwise the other side keeps a call
        // nobody is in. Capped like every step.
        step(uid, "calls") { endCalls() }
        // iOS SessionWipe: "offline" goes out while the account can still write it.
        step(uid, "presence") { com.fariin.app.data.Presence.set(online = false, context = c) }
        step(uid, "device record") { DeviceRegistry.removeThisDevice() }
        val deleting = forDeletion.also { forDeletion = false }
        var tokenOff = false
        step(uid, "push token") { tokenOff = Push.unregister(c) }
        if (endAuth(uid)) {
            // Offline, the token could not come off the account: the token itself is retired, so
            // the account's pushes stop reaching this phone; the next sign-in gets a new one.
            if (!tokenOff) Push.retireToken(c)
            wipe(c, keepFor = if (deleting) null else uid)
        }
    }

    private const val KEPT_PREFS = "fariin_session"
    private const val KEPT_KEY = "keptMediaFor"

    /** At every sign-in: kept data goes back to its own account; any other account wipes it first. */
    fun claimKept(c: Context, uid: String) {
        val prefs = c.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE)
        val kept = prefs.getString(KEPT_KEY, null) ?: return
        prefs.edit().remove(KEPT_KEY).apply()
        if (kept != uid) {
            com.fariin.app.media.ChatMedia.wipe(c)
            com.fariin.app.local.LocalCache.wipe()
        }
    }

    private suspend fun endCalls() = withContext(Dispatchers.Main) {
        val one = com.fariin.app.call.CallManager.ui.value.status.live
        val g = com.fariin.app.call.GroupCall.ui.value
        val inGroup = g.joined || g.connecting || g.waiting
        if (one) com.fariin.app.call.CallManager.hangUp()
        if (inGroup) com.fariin.app.call.GroupCall.leave()
        // A group ring still up for this account goes quietly (no decline), as iOS.
        com.fariin.app.call.GroupRing.stop()
        if (!one && !inGroup) return@withContext
        // The end and the leave are written by their own jobs: a moment for them to queue, then the
        // pending writes, about 3 s at most so a dead network cannot hold the sign-out.
        kotlinx.coroutines.delay(800)
        kotlinx.coroutines.withTimeoutOrNull(2200) {
            runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().waitForPendingWrites().await() }
        }
    }

    private suspend fun step(uid: String?, name: String, work: suspend () -> Unit) {
        if (uid == null || FirebaseAuth.getInstance().currentUser?.uid != uid) {
            android.util.Log.w(BootWatch.TAG, "log out: $name skipped, the session changed"); return
        }
        if (BootWatch.capped(BootWatch.SIGN_OUT_STEP_MS) { work(); true } == null)
            android.util.Log.w(BootWatch.TAG, "log out: $name gave no answer in ${BootWatch.SIGN_OUT_STEP_MS} ms, moving on")
    }

    /** Auth signs out only the account the teardown was for, never one signed in since. */
    private fun endAuth(uid: String?): Boolean {
        val now = FirebaseAuth.getInstance().currentUser?.uid
        if (now == null || now == uid) { FirebaseAuth.getInstance().signOut(); return true }
        android.util.Log.w(BootWatch.TAG, "log out: another account is signed in now, left as it is")
        return false
    }

    /** Texts still in this account's send queue (they are wiped with the account on log out). */
    fun unsentCount(): Int {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return 0
        return runCatching { com.fariin.app.local.SendQueue.all(uid).size }.getOrDefault(0)
    }

    /** How many texts a remote sign-out dropped, for its notice; 0 once the notice is read. */
    @Volatile var droppedByRevoke = 0

    /** Pure, tested: the line the Log Out dialog and the remote sign-out notice add, or null. */
    fun unsentLine(n: Int, afterRevoke: Boolean, drafts: Int = 0): String? = when {
        // Drafts are wiped at Log Out too, so they are named (iOS SessionWipe.unsentSummary).
        !afterRevoke && drafts > 0 -> {
            val parts = listOfNotNull(
                if (n > 0) (if (n == 1) "1 unsent message" else "$n unsent messages") else null,
                if (drafts == 1) "1 draft" else "$drafts drafts")
            val pron = if (n + drafts == 1) "it" else "they"
            "You have ${parts.joinToString(" and ")}. If you log out now, $pron will be lost." +
                (if (n > 0) " Wait until you are online so messages can be sent, or log out anyway." else "")
        }
        n <= 0 -> null
        afterRevoke -> if (n == 1) "1 message that was not sent yet could not be delivered."
            else "$n messages that were not sent yet could not be delivered."
        n == 1 -> "1 message is not sent yet. If you log out now, it will be lost. " +
            "Wait until you are online so it can be sent, or log out anyway."
        else -> "$n messages are not sent yet. If you log out now, they will be lost. " +
            "Wait until you are online so they can be sent, or log out anyway."
    }

    /** The server removed this phone (another phone signed in, or it was signed out from one).
     *  False (nothing done) when the removal was about an account that is no longer signed in. */
    suspend fun revokedSignOut(c: Context): Boolean {
        val uid = DeviceRegistry.revokedUid
        if (uid == null || FirebaseAuth.getInstance().currentUser?.uid != uid) {
            android.util.Log.w(BootWatch.TAG, "removed-device sign-out for an old session dropped")
            return false
        }
        // The server already ended this session, so nothing can wait: the count goes into the notice.
        droppedByRevoke = unsentCount()
        DeviceRegistry.forgetAfterRevoke()
        var tokenOff = false
        step(uid, "push token") { tokenOff = Push.unregister(c) }
        if (!endAuth(uid)) return false
        if (!tokenOff) Push.retireToken(c)
        wipe(c)
        return true
    }

    /** "Delete it now" finished on the server (iOS RestoreAccountView): nothing to remove first. */
    suspend fun deletedNow(c: Context) {
        DeviceRegistry.stopWatching()
        Push.clearAccount(c)
        FirebaseAuth.getInstance().signOut()
        wipe(c)
    }

    /**
     * A plain Log Out passes [keepFor] (the account leaving): its received photos, videos and voice
     * notes and its saved chats stay on this phone for when it signs back in (iOS
     * SessionWipe.claimKeptMedia, owner 2026-09-26: "never lose my messages"; the server deletes a
     * delivered 1:1 file once this phone has it, so these are the only copy). They are wiped the
     * moment a DIFFERENT account signs in ([claimKept]), and right away on deletion or revocation.
     */
    private fun wipe(c: Context, keepFor: String? = null) {
        com.fariin.app.data.ActivityFeed.clearPin(c)
        NotifStore.clearAccount(c)
        NotificationPoster.clearAll(c)
        com.fariin.app.system.FariinWidget.clear(c)
        Push.clearAccount(c)
        // The token outlives the account, so the Fariin chat's topics are left by hand.
        com.fariin.app.push.OfficialPushTopics.sync(c, muted = false, signedIn = false)
        // The phone's copies of this account's chats: opened messages, the list, drafts, unsent
        // texts, opened media and faces. None of it survives into the next account (iOS SessionWipe).
        val prefs = c.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE)
        if (keepFor.isNullOrEmpty()) { prefs.edit().remove(KEPT_KEY).apply(); com.fariin.app.local.LocalCache.wipe() }
        else {
            prefs.edit().putString(KEPT_KEY, keepFor).apply()   // per account on disk, so nobody else reads it
            // iOS SessionWipe keeps only the chat list; the opened messages (decrypted text) always go.
            com.fariin.app.local.LocalCache.wipeThreads()
        }
        com.fariin.app.local.Drafts.clear()
        com.fariin.app.local.SendQueue.wipe()
        com.fariin.app.crypto.SafetyKeyLog.wipe()   // the next account starts its own key record (iOS)
        com.fariin.app.channel.ExploreCache.wipe(c)   // Explore's kept lists are per account (iOS SessionWipe)
        com.fariin.app.call.CallLinks.reset()
        com.fariin.app.data.StickerFavorites.wipe(c)   // the account keeps its backup
        com.fariin.app.ui.auth.GateMemory.clear(c)   // the next account waits for its checks
        com.fariin.app.ui.call.LiveGroupCalls.stopAll()   // the live group-call watchers belong to the account
        com.fariin.app.data.StoryPrivacyStore.stop()   // a return to the same account listens again
        com.fariin.app.auth.Passkeys.lastList = null   // the next account never sees this one's passkeys
        if (keepFor.isNullOrEmpty()) com.fariin.app.media.ChatMedia.wipe(c)
        try { coil3.SingletonImageLoader.get(c).let { it.diskCache?.clear(); it.memoryCache?.clear() } } catch (_: Exception) {}
        com.fariin.app.ui.thread.DecodedPictures.clear()   // decoded chat photos held in memory
        com.fariin.app.data.SharedMediaSource.clear()      // All Media's opened messages
        com.fariin.app.data.ContactNames.clear()       // names I gave people belong to the account
        com.fariin.app.data.SearchRecents.wipe(c)      // what the account searched for
        com.fariin.app.data.OfficialStories.reset(c)
        com.fariin.app.data.ChannelStories.stop()
        com.fariin.app.data.ThreadSeed.clear()
        com.fariin.app.data.QuoteStore.reset()   // the quote cards this account was shown
        com.fariin.app.data.ChatsNow.list = emptyList(); com.fariin.app.data.ChatsNow.all = emptyList()   // the live list restricted-chat checks read
        com.fariin.app.data.BubbleStyles.wipeChats(c)   // per-chat styles name this account's chats
        com.fariin.app.data.StoryLimits.reset()   // the daily story count and badge are per account
        com.fariin.app.data.ChatKey.lockedUntilMs = 0L   // a chat-key lockout is this account's
        com.fariin.app.media.VoicePlayer.stop()
        java.io.File(c.filesDir, "story-outbox").deleteRecursively()  // unfinished story posts are this account's  // the next account must not inherit this one's feed
        com.fariin.app.data.ProfilePhotoHistory.wipe(c)  // my past profile photos
        com.fariin.app.data.Glow.Cache.clear(c)       // the Glowing grid's last answer, every account's
        com.fariin.app.data.ChatLookStore.wipe(c)      // wallpapers, chat colours, wallpaper photos
        // Per-account choices iOS SessionWipe.wipeAccountPrefs clears (2026-11-10 parity): whose
        // stories I hid, the new-chat timer default, the missed-calls mark, Save to Photos.
        com.fariin.app.data.StoryMutes.wipe(c)
        com.fariin.app.data.Disappearing.defaultSeconds = 0
        com.fariin.app.data.CallsSeen.wipe(c)
        com.fariin.app.media.AutoSaveMedia.wipe(c)
        // The kept chat list is sealed with this key: it stays while there is a kept list, or the
        // list could never be read again. A different account's sign-in wipes the list (claimKept).
        if (keepFor.isNullOrEmpty()) com.fariin.app.local.AtRest.forgetKey()
    }

}

/** The iOS alert after a remote sign-out, same wording, one OK. */
@Composable
fun SignedOutNotice(reason: DeviceRecord.Reason, onDismiss: () -> Unit) {
    val lost = SessionEnd.unsentLine(SessionEnd.droppedByRevoke, afterRevoke = true)
    val done = { SessionEnd.droppedByRevoke = 0; onDismiss() }
    GlassAlert(
        onDismissRequest = done,
        title = { Text(DeviceRecord.noticeTitle(reason)) },
        text = { Text(DeviceRecord.noticeMessage(reason) + (lost?.let { "\n\n$it" } ?: "")) },
        confirmButton = { TextButton(onClick = done) { Text("OK") } },
    )
}
