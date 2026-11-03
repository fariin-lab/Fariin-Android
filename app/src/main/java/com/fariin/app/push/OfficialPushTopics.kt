package com.fariin.app.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The Fariin chat's pushes come by topic (functions-announcements topicsFor), as iOS
 * OfficialPushTopics: security topics always (a mute never silences them), the announcement topics
 * only while unmuted, each for everyone and for this phone's country.
 */
object OfficialPushTopics {
    private const val PREFS = "fariin_official"
    /** What Firebase was last told, so a country change can leave the old country's topics. */
    private const val JOINED = "official.push.topics"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun country(): String? =
        java.util.Locale.getDefault().country.uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }

    /** The topics this phone should be in right now. */
    fun wanted(muted: Boolean, signedIn: Boolean, pushOn: Boolean, country: String?): Set<String> {
        if (!signedIn || !pushOn) return emptySet()
        val t = mutableSetOf("sec_all")
        if (country != null) t += "sec_c_$country"
        if (muted) return t
        t += "ann_all"
        if (country != null) t += "ann_c_$country"
        return t
    }

    /** Join what is missing, leave what is no longer wanted. Cheap to call often: only the difference goes. */
    @Synchronized
    fun sync(c: Context, muted: Boolean, signedIn: Boolean = true) {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val joined = prefs.getStringSet(JOINED, emptySet()).orEmpty()
        val target = wanted(muted, signedIn, Push.pushOn(c), country())
        if (joined == target) return
        // Recorded first, taken back on a failure, so a failed subscribe is asked again next time
        // instead of being remembered as joined (iOS: a phone quietly losing security alerts).
        prefs.edit().putStringSet(JOINED, target).apply()
        scope.launch {
            val fm = FirebaseMessaging.getInstance()
            try {
                for (t in target - joined) fm.subscribeToTopic(t).await()
                for (t in joined - target) fm.unsubscribeFromTopic(t).await()
            } catch (_: Exception) {
                prefs.edit().putStringSet(JOINED, joined).apply()
            }
        }
    }
}
