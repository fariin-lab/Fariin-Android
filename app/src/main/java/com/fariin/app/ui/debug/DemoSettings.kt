package com.fariin.app.ui.debug

import com.fariin.app.data.CallEntry
import com.fariin.app.data.MyProfile
import com.fariin.app.data.StoriesState
import com.fariin.app.data.Story
import com.fariin.app.data.StoryGroup

/**
 * Made-up data for the Settings, Calls and Stories tabs in debug demo mode
 * (`am start -n com.fariin.app/.MainActivity --ez demo true`). Nothing here touches Firebase.
 * Story pictures are "demo:" colours, drawn as a plain fill by the Stories row.
 */
object DemoSettings {
    private const val ME = DemoChats.ME

    val profile = MyProfile(uid = ME, name = "Abdi Kariim", handle = "abdi")

    fun calls(nowMs: Long = System.currentTimeMillis()): List<CallEntry> {
        val min = 60_000L
        val day = 24 * 60 * min
        fun call(id: String, who: String, name: String, mine: Boolean, outcome: String, video: Boolean, dur: Int, ago: Long) =
            CallEntry(
                id = id, cid = "c-$who", name = name, photoUrl = null, otherUid = who,
                callerUid = if (mine) ME else who, outcome = outcome, video = video,
                durationSec = dur, dateMs = nowMs - ago,
            )
        return listOf(
            call("k1", "amina", "Amina Yusuf", false, "missed", false, 0, 12 * min),
            call("k2", "amina", "Amina Yusuf", false, "missed", false, 0, 15 * min),
            call("k3", "omar", "Omar", true, "answered", true, 754, 2 * 60 * min),
            call("k4", "hodan", "Hodan", false, "answered", false, 42, 1 * day + 60 * min),
            call("k5", "sahra", "Sahra", true, "missed", false, 0, 3 * day),
            call("k6", "farah", "Farah", false, "answered", true, 3 * 3600 + 5 * 60, 5 * day),
            call("k7", "yasmin", "Yasmin", false, "missed", true, 0, 12 * day),
            call("k8", "omar", "Omar", true, "answered", false, 3600, 30 * day),
        )
    }

    fun stories(nowMs: Long = System.currentTimeMillis()): Pair<MyProfile, StoriesState> {
        val min = 60_000L
        fun s(id: String, who: String, ago: Long, color: String) =
            Story(id, who, nowMs - ago, nowMs - ago + 24 * 60 * min, "demo:$color", "demo:$color")
        val others = listOf(
            StoryGroup("amina", "Amina Yusuf", null, listOf(s("a1", "amina", 50 * min, "#3DA1FD"), s("a2", "amina", 5 * min, "#F2994A"))),
            StoryGroup("omar", "Omar", null, listOf(s("o1", "omar", 3 * 60 * min, "#34C76F"))),
            StoryGroup(
                "hodan", "Hodan", null, listOf(s("h1", "hodan", 8 * 60 * min, "#9B51E0")),
                lastViewedAtMs = nowMs,
            ),
            StoryGroup(
                "ali", "Ali", null, listOf(s("l1", "ali", 20 * 60 * min, "#EB5757")),
                lastViewedAtMs = nowMs,
            ),
        )
        return profile to StoriesState(loaded = true, mine = null, others = others)
    }
}
