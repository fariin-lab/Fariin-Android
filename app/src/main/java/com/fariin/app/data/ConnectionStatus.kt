package com.fariin.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The chat list title while the app cannot reach the server, as iOS ConnectionStatus does it:
 * "Waiting for network" when the phone has no route at all (known at once), "Connecting" when it
 * has one but the server has not answered yet. Nothing is said on the first frame of a cold start
 * or for a short quiet spell: only after [SETTLE_MS] of answers from the phone's copy alone, and
 * then one server read decides (a quiet chat list is not a broken one).
 */
object ConnectionStatus {
    enum class State { ONLINE, WAITING_FOR_NETWORK, CONNECTING }

    /** What the header draws beside its spinner; null = draw the normal title. */
    fun label(s: State): String? = when (s) {
        State.ONLINE -> null
        State.WAITING_FOR_NETWORK -> "Waiting for network"
        State.CONNECTING -> "Connecting"
    }

    /**
     * Pure step, tested: the state after an event. [hasRoute] false always wins; a server answer
     * means online; a route coming back after none means connecting until the server answers.
     */
    fun next(current: State, hasRoute: Boolean, serverAnswered: Boolean?): State = when {
        !hasRoute -> State.WAITING_FOR_NETWORK
        serverAnswered == true -> State.ONLINE
        serverAnswered == false -> State.CONNECTING
        current == State.WAITING_FOR_NETWORK -> State.CONNECTING
        else -> current
    }

    const val SETTLE_MS = 2_500L
    /** A server read on EDGE can take a while; past this the header says "Connecting". */
    const val PROBE_TIMEOUT_MS = 12_000L

    private val _state = MutableStateFlow(State.ONLINE)
    val state: StateFlow<State> = _state

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    @Volatile private var hasRoute = true
    private var settle: Job? = null
    private var probe: ListenerRegistration? = null
    private var probeUid: String? = null
    private var started = false

    fun start(c: Context) {
        if (started) return
        started = true
        val cm = c.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        hasRoute = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        if (!hasRoute) _state.value = State.WAITING_FOR_NETWORK
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { route(true) } }
                override fun onLost(network: Network) {
                    // The default network moved or is gone; ask what is left.
                    scope.launch { route(cm.activeNetwork != null && cm.activeNetwork != network) }
                }
            })
        }
        FirebaseAuth.getInstance().addAuthStateListener { a -> scope.launch { watch(a.currentUser?.uid) } }
    }

    private fun route(up: Boolean) {
        hasRoute = up
        _state.value = next(_state.value, up, null)
        if (up) armSettle() else { settle?.cancel(); settle = null }
    }

    /** My own user document, with metadata changes: fromCache false = the server is talking. */
    private fun watch(uid: String?) {
        if (uid == probeUid) return
        probe?.remove(); probe = null; probeUid = uid
        if (uid == null) { _state.value = next(State.ONLINE, hasRoute, null); return }
        probe = FirebaseFirestore.getInstance().collection("users").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null) return@addSnapshotListener
                if (!snap.metadata.isFromCache) {
                    settle?.cancel(); settle = null
                    _state.value = next(_state.value, hasRoute, true)
                } else armSettle()
            }
    }

    private fun armSettle() {
        if (!hasRoute || settle?.isActive == true) return
        settle = scope.launch {
            delay(SETTLE_MS)
            val uid = probeUid ?: return@launch
            val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                runCatching {
                    FirebaseFirestore.getInstance().collection("users").document(uid).get(Source.SERVER).await()
                }.isSuccess
            } ?: false
            _state.value = next(_state.value, hasRoute, ok)
            // Still nothing: look again later (the listener also flips us back the moment it hears).
            if (!ok && hasRoute) { settle = null; delay(SETTLE_MS * 4); armSettle() }
        }
    }
}
