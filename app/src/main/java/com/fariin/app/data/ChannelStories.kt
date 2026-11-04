package com.fariin.app.data

import com.fariin.app.channel.ChannelFollows
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Followed channels' stories (iOS StoriesService.refreshChannelStories). A follower is never in a
 * channel story's audience list, so the server names the live ids for every channel I follow
 * (`channelStoryIds`) and the rules let a follower read each. Asked at start, every five minutes,
 * and when the channels I follow change. Stories already read are kept, so only new ids cost a read.
 */
object ChannelStories {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories: StateFlow<List<Story>> = _stories
    private var loop: Job? = null
    private var run: Job? = null
    private var owner: String? = null

    fun start(me: String) {
        if (owner == me && loop != null) return
        stop()
        owner = me
        loop = scope.launch {
            launch { var last: Set<String>? = null
                ChannelFollows.entries.collect { e -> val k = e.keys; if (last != null && k != last) refresh(me); last = k } }
            while (true) { refresh(me); delay(5 * 60_000L) }
        }
    }

    fun stop() { loop?.cancel(); loop = null; run?.cancel(); owner = null; _stories.value = emptyList() }

    /** A channel story of a channel I follow and did not post myself (the counts footer, not a reply field). */
    fun isFollowerStory(s: Story, me: String) =
        s.authorUid != me && s.groupId != null && ChannelFollows.entries.value.containsKey(s.groupId)

    private fun refresh(me: String) {
        run?.cancel()
        val known = _stories.value.associateBy { it.id }
        run = scope.launch {
            val map = runCatching {
                (FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("channelStoryIds")
                    .call(emptyMap<String, Any>()).await().getData() as? Map<*, *>)?.get("channels") as? Map<*, *>
            }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            val found = mutableListOf<Story>()
            for ((cidAny, idsAny) in map) {
                val cid = cidAny as? String ?: continue
                val doc = ChannelFollows.docs.value[cid]
                for (id in (idsAny as? List<*>).orEmpty().filterIsInstance<String>().take(50)) {
                    if (id.contains('/')) continue
                    val s = known[id] ?: runCatching {
                        FirebaseFirestore.getInstance().collection("stories").document(id).get().await()
                    }.getOrNull()?.data?.let { StoryList.parse(id, it) } ?: continue
                    if (s.groupId != cid || s.expiresAtMs <= now) continue
                    // The ring's name and photo from the channel itself when the story did not carry them.
                    found += s.copy(groupName = s.groupName?.takeIf { it.isNotEmpty() } ?: doc?.get("title") as? String,
                        groupPhoto = s.groupPhoto?.takeIf { it.isNotEmpty() } ?: doc?.get("avatarUrl") as? String)
                }
            }
            if (owner == me) _stories.value = found
        }
    }
}
