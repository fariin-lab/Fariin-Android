package com.fariin.app.ui.auth

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.HandleRules
import com.fariin.app.data.HandleState
import com.fariin.app.data.PeopleApi
import com.fariin.app.data.Usernames
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Kulan-iOS Views/RootView.swift OnboardingView, "Create your profile": an optional photo, the
 * name, the username (checked as typed: a quick read of `usernames/{h}`, then the checkUsername
 * callable; taken names get up to three free ideas; a name fills in a free username by itself
 * until the person types their own), and Continue, which claims the username on the server
 * (claimUsername, one transaction), saves the name, publishes the key and uploads the photo.
 */
@Composable
fun OnboardingPage(
    api: PeopleApi,
    currentHandle: String,
    prefillName: String?,
    onSaved: suspend () -> Unit,
    demoName: String? = null,
    demoHandle: String? = null,
    demoState: HandleState? = null,
    demoIdeas: List<String> = emptyList(),
) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val demo = demoState != null
    var name by remember { mutableStateOf(demoName ?: prefillName ?: "") }
    var handle by remember { mutableStateOf(demoHandle ?: currentHandle) }
    var state by remember { mutableStateOf(demoState ?: HandleRules.localState(currentHandle)) }
    var ideas by remember { mutableStateOf(demoIdeas) }
    var edited by remember { mutableStateOf(false) }
    var autoHandle by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val photoBitmap = remember(photo) { photo?.let { com.fariin.app.media.BitmapDecode.fromBytes(it, 300)?.asImageBitmap() } }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var ideaJob by remember { mutableStateOf<Job?>(null) }
    // Picker, then the circle cropper (iOS onboarding hands the pick to ProfilePhotoCropper); held until Continue.
    val cropPicker = com.fariin.app.ui.photo.rememberCirclePhotoPicker { photo = it }

    suspend fun quickLookup(h: String): HandleState? = try {
        val snap = FirebaseFirestore.getInstance().collection("usernames").document(h.lowercase()).get(Source.SERVER).await()
        val owner = snap.getString("uid")
        when { !snap.exists() -> HandleState.Free; owner == api.me -> null; else -> HandleState.Taken }
    } catch (_: Exception) { null }

    suspend fun freeOf(cands: List<String>, want: Int): List<String> {
        val out = ArrayList<String>()
        for (cand in cands.take(6)) {
            if (runCatching { api.checkUsername(cand).first }.getOrDefault(false) || quickLookup(cand) == HandleState.Free) out += cand
            if (out.size == want) break
        }
        return out
    }

    fun scheduleCheck() {
        checkJob?.cancel()
        val h = Usernames.sanitize(handle)
        state = HandleRules.localState(h)
        if (state != HandleState.Checking) return
        checkJob = scope.launch {
            delay(350)
            val quick = quickLookup(h)
            if (quick != null) { state = quick; if (quick == HandleState.Taken) return@launch }
            val answer = try { val (free, reason) = api.checkUsername(h); HandleRules.fromServer(free, reason) }
            catch (_: Exception) { if (quick != null) return@launch; HandleState.Unknown }
            if (Usernames.sanitize(handle) == h) state = answer
        }
    }

    fun schedulePrefill() {
        if (edited) return
        ideaJob?.cancel()
        val source = name
        ideaJob = scope.launch {
            delay(700)
            val pick = freeOf(HandleRules.ideas(source), 1).firstOrNull() ?: return@launch
            if (!edited && name == source) { autoHandle = pick; handle = pick; ideas = emptyList(); scheduleCheck() }
        }
    }

    LaunchedEffect(state) {
        if (demo || state != HandleState.Taken) return@LaunchedEffect
        val h = Usernames.sanitize(handle)
        val free = freeOf(HandleRules.ideas(h).filter { !it.equals(h, ignoreCase = true) }, 3)
        if (Usernames.sanitize(handle) == h && state == HandleState.Taken) ideas = free
    }
    LaunchedEffect(Unit) { if (!demo && handle.isNotEmpty()) scheduleCheck() }

    fun save() {
        val problem = HandleRules.saveProblem(name, handle, currentHandle)
        if (problem != null) { error = problem; return }
        saving = true; error = null
        scope.launch {
            try {
                val h = Usernames.sanitize(handle)
                if (h.isNotEmpty() && !h.equals(currentHandle, ignoreCase = false)) api.claimUsername(h)
                api.saveName(name.trim())
                photo?.let { try { api.uploadPhoto(it) } catch (_: Exception) {} }
                onSaved()
            } catch (e: Exception) {
                val msg = e.message ?: "Try again."
                error = if (msg.contains("username", ignoreCase = true)) msg else "Could not save: $msg"
            }
            saving = false
        }
    }

    if (cropPicker.pending != null) { cropPicker.Host("Avatar", "Shown in chats, stories and groups"); return }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.clickable { cropPicker.launch() }) {
            if (photoBitmap != null) Image(photoBitmap, "Change photo", contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(CircleShape))
            else InitialAvatar(name.ifEmpty { "?" }, 96)
            Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(30.dp).clip(CircleShape).background(c.bg).padding(2.5.dp)
                .clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(if (photo == null) Icons.Filled.CameraAlt else Icons.Filled.Edit, if (photo == null) "Add a photo" else "Change photo",
                    tint = c.onAccent, modifier = Modifier.size(14.dp))
            }
        }
        Text(if (photo == null) "Add a photo (optional)" else "Tap to change", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(top = 10.dp))
        Text("Create your profile", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        Text("Friends find you by your name and username. No phone number needed.", fontSize = 15.sp, color = c.secondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).padding(horizontal = 12.dp))
        Spacer(Modifier.height(26.dp))
        AuthField(name, { name = it; schedulePrefill() }, "Your name", Icons.Outlined.Person,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
        Spacer(Modifier.height(14.dp))
        AuthField(handle, { v ->
            val clean = Usernames.sanitize(v)
            handle = clean
            if (clean != autoHandle) edited = clean.isNotEmpty()
            ideas = emptyList()
            scheduleCheck()
        }, "username", Icons.Outlined.AlternateEmail, invalid = state == HandleState.Taken,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { if (HandleRules.canContinue(name, state, saving)) save() }),
            accessory = { HandleMark(state) })
        val noteColor = when (state) { HandleState.Taken -> c.red; HandleState.Free -> c.label; else -> c.secondary }
        Text(HandleRules.note(state, handle, ideas.isNotEmpty()), fontSize = 13.sp, color = noteColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp))
        if (state == HandleState.Taken && ideas.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(BorderStroke(1.dp, c.label.copy(alpha = 0.18f)), RoundedCornerShape(18.dp))) {
                ideas.forEachIndexed { i, idea ->
                    Row(Modifier.fillMaxWidth().height(50.dp).clickable { edited = true; handle = idea; ideas = emptyList(); scheduleCheck() }
                        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("@$idea", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.CheckCircle, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    }
                    if (i < ideas.size - 1) HorizontalDivider(Modifier.padding(start = 18.dp), color = c.separator)
                }
            }
        }
        error?.let { Spacer(Modifier.height(14.dp)); ErrorLine(it) }
        Spacer(Modifier.height(28.dp))
        ActionPill("Continue", enabled = HandleRules.canContinue(name, state, saving), busy = saving) { save() }
        Spacer(Modifier.height(14.dp))
        TermsLine("By tapping Continue you agree to Fariin's ", " and ",
            ". Fariin has zero tolerance for objectionable content or abusive behavior.", fontSize = 11)
    }
}

/** The mark at the end of the username box: dashed (bad shape), spinner, tick, cross, offline. */
@Composable
private fun HandleMark(state: HandleState) {
    val c = Fariin.colors
    when (state) {
        HandleState.Empty -> {}
        HandleState.Invalid -> Box(Modifier.size(17.dp).border(BorderStroke(1.5.dp, c.secondary.copy(alpha = 0.6f)), CircleShape))
        HandleState.Checking -> Busy(c.secondary, 17)
        HandleState.Free -> Box(Modifier.size(20.dp).clip(CircleShape).background(GreenOk), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Filled.Check, "Available", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(13.dp))
        }
        HandleState.Taken -> Box(Modifier.size(20.dp).clip(CircleShape).background(c.red), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Filled.Close, "Taken", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(13.dp))
        }
        HandleState.Unknown -> Icon(Icons.Outlined.WifiOff, "Couldn't check", tint = c.secondary, modifier = Modifier.size(16.dp))
    }
}
