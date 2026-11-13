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

    val profile = MyProfile(uid = ME, name = "You", handle = "you", photoUrl = DemoChats.PHOTOS[ME])

    private fun face(uid: String) = DemoChats.PHOTOS[uid]

    fun calls(nowMs: Long = DemoChats.shotNow()): List<CallEntry> {
        val min = 60_000L
        val day = 24 * 60 * min
        fun call(id: String, who: String, mine: Boolean, outcome: String, video: Boolean, dur: Int, ago: Long) =
            CallEntry(id = id, cid = who, name = DemoChats.NAMES[who] ?: "", photoUrl = face(who), otherUid = who,
                callerUid = if (mine) ME else who, outcome = outcome, video = video, durationSec = dur, dateMs = nowMs - ago)
        return listOf(
            call("k1", "demo-hooyo", false, "answered", true, 1260, 25 * min),
            call("k2", "demo-ayaan", true, "answered", false, 754, 2 * 60 * min),
            call("k3", "demo-cabdi", false, "missed", false, 0, 5 * 60 * min),
            call("k4", "demo-linnea", true, "answered", true, 2100, 1 * day + 60 * min),
            call("k5", "demo-khadra", false, "answered", false, 42, 2 * day),
            call("k6", "demo-ilhan", true, "answered", true, 3 * 3600 + 5 * 60, 4 * day),
        )
    }

    /** The store's Stories picture (iOS demoStoryPeople): real pictures, the owner's own AI images. */
    fun stories(nowMs: Long = System.currentTimeMillis()): Pair<MyProfile, StoriesState> {
        val min = 60_000L
        fun s(id: String, who: String, ago: Long, pic: String) =
            Story(id, who, nowMs - ago, nowMs - ago + 24 * 60 * min, DemoChats.pic(pic), DemoChats.pic(pic))
        val mine = StoryGroup(ME, "My Story", face(ME), listOf(s("m1", ME, 90 * min, "demo-story-mine"), s("m2", ME, 40 * min, "demo-story-mine-2")))
        val others = listOf(
            StoryGroup("demo-hooyo", "Maya", face("demo-hooyo"), listOf(s("h1", "demo-hooyo", 20 * min, "demo-story-maya"))),
            StoryGroup("demo-khadra", "Sofia", face("demo-khadra"), listOf(s("k1", "demo-khadra", 50 * min, "demo-story-khadra"),
                s("k2", "demo-khadra", 35 * min, "demo-story-khadra-2"))),
            StoryGroup("demo-cabdi", "Daniel", face("demo-cabdi"), listOf(s("c1", "demo-cabdi", 2 * 60 * min, "demo-story-cabdi"),
                s("c2", "demo-cabdi", 90 * min, "demo-story-cabdi-2"))),
            StoryGroup("demo-linnea", "Linnea", face("demo-linnea"), listOf(s("l1", "demo-linnea", 3 * 60 * min, "demo-story-linnea"))),
            StoryGroup("demo-ayaan", "Ayaan Warsame", face("demo-ayaan"), listOf(s("a1", "demo-ayaan", 5 * 60 * min, "demo-story-ayaan")),
                lastViewedAtMs = nowMs),
            StoryGroup("demo-ilhan", "Leo", face("demo-ilhan"), listOf(s("i1", "demo-ilhan", 8 * 60 * min, "demo-story-ilhan")),
                lastViewedAtMs = nowMs),
        )
        return profile to StoriesState(loaded = true, mine = mine, others = others)
    }

    /** The store's Channels picture (iOS storeChannels): six made-up public channels, ours first. */
    val channels = listOf(
        com.fariin.app.channel.DiscoveredChannel("demo-news", "fariinnews", "Fariin News", DemoChats.pic("demo-official"),
            "Official news from Fariin. New features, tips and updates.", 128_400, verified = true, official = true),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-news", "worldnews", "World News", DemoChats.pic("demo-chan-news"),
            "The day's top stories, in two minutes.", 2_400_000, verified = true),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-football", "footballdaily", "Football Daily", DemoChats.pic("demo-chan-football"),
            "Scores, goals and transfer news, live.", 860_000),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-recipes", "easyrecipes", "Easy Recipes", DemoChats.pic("demo-chan-recipes"),
            "Quick home cooking for every day of the week.", 312_000),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-travel", "traveldiaries", "Travel Diaries", DemoChats.pic("demo-chan-travel"),
            "Beaches, cities and hidden places to visit.", 540_000),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-tech", "techtoday", "Tech Today", DemoChats.pic("demo-chan-tech"),
            "Phones, apps and gadgets, made simple.", 198_000),
        com.fariin.app.channel.DiscoveredChannel("demo-chan-music", "musicmix", "Music Mix", DemoChats.pic("demo-chan-music"),
            "New songs and live shows every week.", 1_100_000),
    )
}
