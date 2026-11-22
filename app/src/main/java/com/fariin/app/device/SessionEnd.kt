package com.fariin.app.device

import kotlinx.coroutines.tasks.await

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
        val uid = com.fariin.app.Fb.auth.currentUser?.uid
        DeviceRegistry.stopWatching()
        // iOS endCallsBeforeSignOut (audit M-015): a live call or a ring ends first, as my own end,
        // while the rules still let this account write it; otherwise the other side keeps a call
        // nobody is in. Capped like every step.
        // iOS SessionWipe: "offline" goes out while the account can still write it. The server
        // steps do not depend on each other: they run side by side, so offline the wait is one
        // cap, not four in a row.
        val deleting = forDeletion.also { forDeletion = false }
        var tokenOff = false
        kotlinx.coroutines.coroutineScope {
            launch { step(uid, "calls") { endCalls() } }
            launch { step(uid, "presence") { com.fariin.app.data.Presence.set(online = false, context = c) } }
            launch { step(uid, "device record") { DeviceRegistry.removeThisDevice() } }
            launch { step(uid, "push token") { tokenOff = Push.unregister(c) } }
        }
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
            runCatching { com.fariin.app.Fb.db.waitForPendingWrites().await() }
        }
    }

    private suspend fun step(uid: String?, name: String, work: suspend () -> Unit) {
        if (uid == null || com.fariin.app.Fb.auth.currentUser?.uid != uid) {
            android.util.Log.w(BootWatch.TAG, "log out: $name skipped, the session changed"); return
        }
        if (BootWatch.capped(BootWatch.SIGN_OUT_STEP_MS) { work(); true } == null)
            android.util.Log.w(BootWatch.TAG, "log out: $name gave no answer in ${BootWatch.SIGN_OUT_STEP_MS} ms, moving on")
    }

    /** Auth signs out only the account the teardown was for, never one signed in since. */
    private fun endAuth(uid: String?): Boolean {
        val now = com.fariin.app.Fb.auth.currentUser?.uid
        if (now == null || now == uid) { com.fariin.app.Fb.auth.signOut(); return true }
        android.util.Log.w(BootWatch.TAG, "log out: another account is signed in now, left as it is")
        return false
    }

    /** Texts still in this account's send queue (they are wiped with the account on log out). */
    fun unsentCount(): Int {
        val uid = com.fariin.app.Fb.auth.currentUser?.uid ?: return 0
        return runCatching { com.fariin.app.local.SendQueue.all(uid).count { !it.refused } }.getOrDefault(0)
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
        if (uid == null || com.fariin.app.Fb.auth.currentUser?.uid != uid) {
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
        com.fariin.app.Fb.auth.signOut()
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
        for (s in wipeSteps) s.run(c, keepFor)
    }

    /** One named store [wipe] clears: [run] gets the phone and the account kept for a return (or null). */
    internal class WipeStep(val name: String, val run: (Context, String?) -> Unit)

    /**
     * EVERY PER-ACCOUNT STORE ON THIS PHONE GOES HERE. A new store that holds anything of the signed-in
     * account (messages, names, lists, choices, files) gets its own step, and its name goes into
     * SessionEndWipeTest too, or the next account on this phone can see it. Order is the run order.
     * The server steps (calls, presence, device record, push token) run before Auth in [signOutSteps].
     */
    internal val wipeSteps: List<WipeStep> = listOf(
        WipeStep("activity pin") { c, _ -> com.fariin.app.data.ActivityFeed.clearPin(c) },
        WipeStep("notification store") { c, _ -> NotifStore.clearAccount(c) },
        // Posted notifications, the app badge and the chat shortcuts (NotificationPoster.clearAll).
        WipeStep("notifications, badge, shortcuts") { c, _ -> NotificationPoster.clearAll(c) },
        WipeStep("widget") { c, _ -> com.fariin.app.system.FariinWidget.clear(c) },
        WipeStep("last seen lines") { c, _ -> com.fariin.app.data.LastSeenLine.clear(c) },
        WipeStep("photo outbox") { c, _ -> com.fariin.app.local.PhotoOutbox.wipe(c) },
        // The origin index names files of this account's chats (MediaOrigin): it goes with them.
        WipeStep("media origin") { _, _ -> com.fariin.app.media.MediaOrigin.removeAll() },
        WipeStep("profile photo index") { _, _ -> com.fariin.app.data.ProfilePhotoIndex.clear() },
        WipeStep("played voice") { c, _ -> com.fariin.app.data.PlayedVoice.clear(c) },   // which voice notes were heard belongs to the account (iOS)
        WipeStep("push account") { c, _ -> Push.clearAccount(c) },
        // The token outlives the account, so the Fariin chat's topics are left by hand.
        WipeStep("official push topics") { c, _ -> com.fariin.app.push.OfficialPushTopics.sync(c, muted = false, signedIn = false) },
        // The phone's copies of this account's chats: opened messages, the list, drafts, unsent
        // texts, opened media and faces. None of it survives into the next account (iOS SessionWipe).
        WipeStep("local cache") { c, keepFor ->
            val prefs = c.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE)
            if (keepFor.isNullOrEmpty()) { prefs.edit().remove(KEPT_KEY).apply(); com.fariin.app.local.LocalCache.wipe() }
            else {
                prefs.edit().putString(KEPT_KEY, keepFor).apply()   // per account on disk, so nobody else reads it
                // iOS SessionWipe keeps only the chat list; the opened messages (decrypted text) always go.
                com.fariin.app.local.LocalCache.wipeThreads()
            }
        },
        // Each chat's newest stored line, in memory by chat id: never the next account's preview.
        WipeStep("message store summaries") { _, _ -> com.fariin.app.local.LocalDb.forgetSummaries() },
        WipeStep("hidden last line") { c, _ -> com.fariin.app.data.HiddenLast.clear(c) },   // the list line kept after a delete for me (opened text)
        WipeStep("drafts") { _, _ -> com.fariin.app.local.Drafts.clear() },
        WipeStep("voice drafts") { c, _ -> com.fariin.app.media.VoiceDrafts.wipe(c) },   // recorded voice notes not sent yet (iOS wipeAllDrafts)
        WipeStep("send queue") { _, _ -> com.fariin.app.local.SendQueue.wipe() },
        WipeStep("delivery cursors") { _, _ -> com.fariin.app.local.Cursors.reset() },   // delivery marks sent, by chat id: the next account writes its own
        WipeStep("safety key log") { _, _ -> com.fariin.app.crypto.SafetyKeyLog.wipe() },   // the next account starts its own key record (iOS)
        WipeStep("group page previews") { _, _ -> com.fariin.app.channel.ChannelService.wipePreviews() },   // kept public group pages hold my member/requested state (iOS)
        WipeStep("explore cache") { c, _ -> com.fariin.app.channel.ExploreCache.wipe(c) },   // Explore's kept lists are per account (iOS SessionWipe)
        WipeStep("call links") { _, _ -> com.fariin.app.call.CallLinks.reset() },
        WipeStep("sticker favorites") { c, _ -> com.fariin.app.data.StickerFavorites.wipe(c) },   // the account keeps its backup
        WipeStep("gate memory") { c, _ -> com.fariin.app.ui.auth.GateMemory.clear(c) },   // the next account waits for its checks
        WipeStep("live group calls") { _, _ -> com.fariin.app.ui.call.LiveGroupCalls.stopAll() },   // the live group-call watchers belong to the account
        WipeStep("story privacy") { _, _ -> com.fariin.app.data.StoryPrivacyStore.stop() },   // a return to the same account listens again
        WipeStep("passkeys") { _, _ -> com.fariin.app.auth.Passkeys.lastList = null },   // the next account never sees this one's passkeys
        WipeStep("chat media") { c, keepFor -> if (keepFor.isNullOrEmpty()) com.fariin.app.media.ChatMedia.wipe(c) },
        WipeStep("temp copies") { c, _ -> removeTempCopies(c) },
        WipeStep("image cache") { c, _ ->
            try { coil3.SingletonImageLoader.get(c).let { it.diskCache?.clear(); it.memoryCache?.clear() } } catch (_: Exception) {}
        },
        WipeStep("decoded pictures") { _, _ -> com.fariin.app.ui.thread.DecodedPictures.clear() },   // decoded chat photos held in memory
        WipeStep("chat search") { _, _ -> com.fariin.app.data.ChatSearch.seen.clear() },        // in-chat search corpora
        WipeStep("shared media") { _, _ -> com.fariin.app.data.SharedMediaSource.clear() },      // All Media's opened messages
        WipeStep("verified people") { c, _ -> com.fariin.app.data.VerifiedPeople.clear(c) },   // whose badges this account looked up (iOS VerificationIndex)
        WipeStep("contact names") { _, _ -> com.fariin.app.data.ContactNames.clear() },       // names I gave people belong to the account
        WipeStep("search recents") { c, _ -> com.fariin.app.data.SearchRecents.wipe(c) },      // what the account searched for
        WipeStep("send effects") { c, _ -> com.fariin.app.data.SendEffects.wipe(c) },        // which send effects already played here
        WipeStep("avatar videos") { c, _ -> com.fariin.app.data.AvatarVideoIndex.clear(c) },  // who has a moving profile picture, and the clips
        WipeStep("shared wallpapers") { c, _ -> com.fariin.app.data.SharedWallpapers.wipe(c) },   // 1:1 shared wallpapers and this phone's opt-outs
        WipeStep("chat folders") { c, _ -> com.fariin.app.data.ChatFoldersStore.clear(c) },   // every account's chat folders kept on this phone
        WipeStep("plus billing") { _, _ -> com.fariin.app.data.PlusBilling.clear() },          // loaded plans and this run's purchase check
        WipeStep("official channel") { c, _ -> com.fariin.app.data.OfficialChannel.wipe(c) },    // the official row kept for launch (personal notices too)
        WipeStep("official stories") { c, _ -> com.fariin.app.data.OfficialStories.reset(c) },
        WipeStep("channel stories") { _, _ -> com.fariin.app.data.ChannelStories.stop() },
        WipeStep("thread seed") { _, _ -> com.fariin.app.data.ThreadSeed.clear() },
        WipeStep("clear cuts") { _, _ -> com.fariin.app.data.ClearCuts.reset() },   // this account's Clear Messages cuts, by chat id
        WipeStep("former groups") { _, _ -> com.fariin.app.data.FormerGroups.clear() },   // groups this account was removed from, by chat id
        WipeStep("quote store") { _, _ -> com.fariin.app.data.QuoteStore.reset() },   // the quote cards this account was shown
        WipeStep("chats now") { _, _ ->   // the live list restricted-chat checks read
            com.fariin.app.data.ChatsNow.list = emptyList(); com.fariin.app.data.ChatsNow.all = emptyList()
        },
        WipeStep("bubble styles") { c, _ -> com.fariin.app.data.BubbleStyles.wipeChats(c) },   // per-chat styles and my all-chats pick (iOS)
        WipeStep("story limits") { _, _ -> com.fariin.app.data.StoryLimits.reset() },   // the daily story count and badge are per account
        WipeStep("chat key lockout") { _, _ -> com.fariin.app.data.ChatKey.lockedUntilMs = 0L },   // a chat-key lockout is this account's
        WipeStep("voice player") { _, _ -> com.fariin.app.media.VoicePlayer.stop() },
        WipeStep("live location") { c, _ -> com.fariin.app.location.LiveShareService.stopAll(c) },   // no live location outlives the account on this phone
        WipeStep("story outbox") { c, _ -> java.io.File(c.filesDir, "story-outbox").deleteRecursively() },  // unfinished story posts are this account's
        WipeStep("profile photo history") { c, _ -> com.fariin.app.data.ProfilePhotoHistory.wipe(c) },  // my past profile photos
        WipeStep("glow cache") { c, _ -> com.fariin.app.data.Glow.Cache.clear(c) },       // the Glowing grid's last answer, every account's
        WipeStep("chat looks") { c, _ -> com.fariin.app.data.ChatLookStore.wipe(c) },      // wallpapers, chat colours, wallpaper photos
        // Per-account choices iOS SessionWipe.wipeAccountPrefs clears (2026-11-10 parity): whose
        // stories I hid, the new-chat timer default, the missed-calls mark, Save to Photos.
        WipeStep("story mutes") { c, _ -> com.fariin.app.data.StoryMutes.wipe(c) },
        WipeStep("disappearing default") { _, _ -> com.fariin.app.data.Disappearing.defaultSeconds = 0 },
        WipeStep("calls seen") { c, _ -> com.fariin.app.data.CallsSeen.wipe(c) },
        WipeStep("auto save media") { c, _ -> com.fariin.app.media.AutoSaveMedia.wipe(c) },
        // The kept chat list is sealed with this key: it stays while there is a kept list, or the
        // list could never be read again. A different account's sign-in wipes the list (claimKept).
        WipeStep("at rest key") { _, keepFor -> if (keepFor.isNullOrEmpty()) com.fariin.app.local.AtRest.forgetKey() },
    )

    /**
     * Plain copies made to open, share, edit or export (iOS SessionWipe.removeTempCopies): an opened
     * file or an exported chat is decrypted, so it goes on every log out, kept media or not.
     */
    private fun removeTempCopies(c: Context) {
        val dirs = listOf("open", "shared-in", "edited", "chat-camera", "story-camera", "story-repost")
        val prefixes = listOf("story-save-", "repost-", "story-edit-", "story-part-")
        runCatching {
            for (d in dirs) java.io.File(c.cacheDir, d).deleteRecursively()
            c.cacheDir.listFiles()?.forEach { f -> if (prefixes.any { f.name.startsWith(it) }) f.deleteRecursively() }
        }
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
