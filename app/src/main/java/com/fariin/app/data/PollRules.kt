package com.fariin.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Polls, read from Kulan-iOS: Models.swift PollCard / Message.poll / pollMarkerText (the
 * "fariin-poll:" marker, base64 JSON, sent sealed in `text` like any words), ChatService's clear
 * `pollMulti` / `pollEndsAt`, Polls.swift PollService (votes at messages/{mid}/votes/{uid} as
 * `{options: [index], at}`, Stop Poll = `pollClosed: true` by the creator, a channel's counts from
 * the server's `pollCounts` / `pollVoters`), PollComposerSheet, PollResultsSheet and
 * CardBubbleViews PollBubbleView (who sees bars, quiz marks, footer words). Pure, so it is tested.
 */
data class PollCard(
    val id: String,
    val question: String,
    val options: List<String>,
    val multiple: Boolean,
    /** Names stay off the results (display only: the votes are still readable by members). */
    val hideVoters: Boolean = false,
    /** Quiz: the right option(s); empty = an ordinary poll. */
    val correct: List<Int> = emptyList(),
    /** When voting ends (ms), null = never. */
    val endsAt: Double? = null,
    /** One picture per option or null (iOS poll-media-1114 `imgs`); empty = no pictures at all. */
    val images: List<PollImage?> = emptyList(),
) {
    val isQuiz: Boolean get() = correct.isNotEmpty()
    fun imageAt(i: Int): PollImage? = images.getOrNull(i)
}

/** An option's picture: where it is, and its key unless it sits in the clear (plain group/channel). */
data class PollImage(val url: String, val enc: com.fariin.app.crypto.EncMeta? = null)

object PollRules {
    const val MARKER = "fariin-poll:"
    /** PollComposerSheet.maxOptions; the rules take 1 to 10 options in a vote. */
    const val MAX_OPTIONS = 10
    /** PollService.anonPrefix: a channel poll's placeholder voters, never a real uid. */
    const val ANON_PREFIX = "~voter:"

    fun isPoll(text: String) = text.startsWith(MARKER)

    /** Message.poll: null for anything that is not a well-formed poll (at least two options). */
    fun parse(text: String): PollCard? {
        if (!text.startsWith(MARKER)) return null
        return try {
            val obj = JSONObject(String(Base64.getDecoder().decode(text.substring(MARKER.length).trim()), Charsets.UTF_8))
            val id = obj.opt("id") as? String ?: return null
            val q = obj.opt("q") as? String ?: return null
            val arr = obj.optJSONArray("opts") ?: return null
            val opts = (0 until arr.length()).map { arr.opt(it) as? String ?: return null }
            if (opts.size < 2) return null
            val quiz = obj.optJSONArray("quiz")
            val correct = if (quiz == null) emptyList() else (0 until quiz.length())
                .mapNotNull { (quiz.opt(it) as? Number)?.toInt() }.filter { it >= 0 && it < opts.size }
            val ends = (obj.opt("ends") as? Number)?.toDouble() ?: 0.0
            PollCard(id, q, opts, obj.optBoolean("multi", false), obj.optBoolean("anon", false),
                correct, if (ends > 0) ends else null, readImages(obj.optJSONArray("imgs"), opts.size))
        } catch (_: Exception) { null }
    }

    /** `imgs` is read only when it is a list exactly as long as `opts`; a null or url-less entry = no picture. */
    fun readImages(arr: JSONArray?, count: Int): List<PollImage?> {
        if (arr == null || arr.length() != count) return emptyList()
        val out = (0 until count).map { i ->
            val o = arr.optJSONObject(i) ?: return@map null
            val url = o.optString("url", "").takeIf { it.isNotEmpty() } ?: return@map null
            val enc = o.optJSONObject("enc")?.let { e ->
                val m = HashMap<String, Any?>()
                e.keys().forEach { k -> m[k] = if (k == "w") e.optJSONObject("w")?.let { w ->
                    w.keys().asSequence().associateWith { w.optString(it) } } else e.opt(k) }
                com.fariin.app.crypto.EncMeta.fromMap(m)
            }
            PollImage(url, enc)
        }
        return if (out.all { it == null }) emptyList() else out
    }

    /** Message.pollMarkerText: `anon`, `quiz`, `ends` and `imgs` only when set, as iOS writes them. */
    fun markerText(id: String, question: String, options: List<String>, multiple: Boolean,
                   hideVoters: Boolean = false, correct: List<Int> = emptyList(), endsAt: Double? = null,
                   images: List<PollImage?> = emptyList()): String {
        val obj = JSONObject()
        obj.put("id", id).put("q", question).put("opts", JSONArray(options)).put("multi", multiple)
        if (hideVoters) obj.put("anon", true)
        if (correct.isNotEmpty()) obj.put("quiz", JSONArray(correct))
        if (endsAt != null) obj.put("ends", endsAt.toLong())
        if (images.size == options.size && images.any { it != null }) obj.put("imgs", JSONArray().also { a ->
            images.forEach { im ->
                if (im == null) a.put(JSONObject.NULL) else a.put(JSONObject().put("url", im.url).also { o ->
                    im.enc?.takeIf { !it.isPlain }?.let { e -> o.put("enc", JSONObject(e.asMap())) }
                })
            }
        })
        return MARKER + Base64.getEncoder().encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
    }

    /** ChatService send: in the clear only whether it takes several answers, and its end time,
     *  so the rules can hold a vote to one option and refuse a late one. Empty for other text. */
    fun clearFields(text: String): Map<String, Any> {
        val p = parse(text) ?: return emptyMap()
        val out = hashMapOf<String, Any>("pollMulti" to p.multiple)
        p.endsAt?.let { out["pollEndsAt"] = it.toLong() }
        return out
    }

    /** A vote document (PollService.setVote); an empty choice deletes the doc instead. */
    fun voteDoc(options: List<Int>, at: Any): Map<String, Any> = mapOf("options" to options.sorted(), "at" to at)

    /** The `options` of a vote doc, whatever number type Firestore hands back. */
    fun readOptions(raw: Any?): List<Int> = (raw as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

    /** PollBubbleView.isOpen: until the creator stops it or its end time passes. */
    fun isOpen(p: PollCard, closed: Boolean, now: Double): Boolean {
        if (closed) return false
        return p.endsAt?.let { now < it } ?: true
    }

    /**
     * PollBubbleView.castVote: my next choice after tapping option [i], or null when the tap does
     * nothing (closed, or a quiz already answered: changing it after seeing right or wrong is
     * cheating). Single choice replaces, and tapping my own answer clears it; multiple toggles.
     */
    fun toggle(p: PollCard, mine: List<Int>, i: Int, open: Boolean): List<Int>? {
        if (!open || i !in p.options.indices) return null
        if (p.isQuiz && mine.isNotEmpty()) return null
        val s = mine.toMutableSet()
        if (p.multiple) { if (i in s) s.remove(i) else s.add(i) }
        else return if (mine == listOf(i)) emptyList() else listOf(i)
        return s.sorted()
    }

    /** How many voters chose option [i]. */
    fun count(votes: Map<String, List<Int>>, i: Int) = votes.values.count { i in it }

    /** The share of voters on option [i], 0...1 (a multiple poll can add up past 1). */
    fun fraction(votes: Map<String, List<Int>>, i: Int): Double =
        if (votes.isEmpty()) 0.0 else count(votes, i).toDouble() / votes.size

    fun percent(votes: Map<String, List<Int>>, i: Int): String = "${(fraction(votes, i) * 100).roundToInt()}%"

    /** Who chose option [i], uids only (the screen sorts by name). */
    fun voters(votes: Map<String, List<Int>>, i: Int): List<String> = votes.filter { i in it.value }.keys.sorted()

    /** Bars and percentages show once I have voted or the poll is over (rows are plain before). */
    fun showResults(mine: List<Int>, open: Boolean) = mine.isNotEmpty() || !open

    enum class QuizMark { NONE, RIGHT, WRONG }

    /** A quiz shows right and wrong once I have answered, or once the poll is over. */
    fun quizMark(p: PollCard, mine: List<Int>, open: Boolean, i: Int): QuizMark = when {
        !p.isQuiz || !showResults(mine, open) -> QuizMark.NONE
        i in p.correct -> QuizMark.RIGHT
        i in mine -> QuizMark.WRONG
        else -> QuizMark.NONE
    }

    fun voteCount(n: Int) = "$n vote${if (n == 1) "" else "s"}"

    /** The line under the question. */
    fun subtitle(p: PollCard) = (if (p.isQuiz) "Quiz · " else "") + (if (p.multiple) "Select one or more" else "Select one")

    /** The bubble footer, the door to the results page. */
    fun footer(voters: Int, open: Boolean): String = when {
        open -> if (voters > 0) "${voteCount(voters)} · View results" else "No votes yet"
        else -> if (voters > 0) "Poll ended · ${voteCount(voters)}" else "Poll ended"
    }

    /** PollResultsSheet.status ([endsIn] is the relative end time, e.g. "in 3 hours"). */
    fun resultsStatus(voters: Int, open: Boolean, endsIn: String?): String = when {
        !open -> "Poll ended · ${voteCount(voters)}"
        endsIn != null -> "${voteCount(voters)} · ends $endsIn"
        else -> voteCount(voters)
    }

    /** Stop Poll: the creator only, while it is open (the rules: the author, `pollClosed` once). */
    fun canStop(authorId: String, me: String, open: Boolean) = me.isNotEmpty() && authorId == me && open

    /** The chat list line (MainShell: the chart glyph and "Poll"). */
    const val PREVIEW = "📊 Poll"

    /** Message.safeText: reply quotes, notifications, copy. */
    fun safeText(p: PollCard) = "📊 ${p.question}"

    /**
     * A post's words for a quote card or the Quotes sheet (iOS poll-fast-1114): a poll reads
     * "Poll: <question>", any other `fariin-xxx:` marker reads as [kind], plain words stay as they are.
     * Never prints a raw marker.
     */
    fun cardWords(text: String, kind: String): String {
        val t = text.trim()
        if (!t.startsWith("fariin-")) return t
        parse(t)?.let { return "Poll: ${it.question}" }
        return kind
    }

    /** PollComposerSheet.EndChoice, in seconds (null = never). */
    enum class EndChoice(val label: String, val seconds: Long?) {
        NEVER("Never", null), HOUR("In 1 hour", 3600), DAY("In 1 day", 86_400), WEEK("In 1 week", 604_800)
    }

    /** PollComposerSheet.canSend: a question, two options with words, and a right answer on a quiz. */
    fun canSend(question: String, options: List<String>, quiz: Boolean, right: List<Boolean>): Boolean {
        val kept = options.indices.filter { options[it].isNotBlank() }
        return question.isNotBlank() && kept.size >= 2 && (!quiz || kept.any { right.getOrElse(it) { false } })
    }

    /** PollComposerSheet.send: the marker for what was typed (blank options dropped). */
    fun compose(id: String, question: String, options: List<String>, multiple: Boolean, hideVoters: Boolean,
                quiz: Boolean, right: List<Boolean>, ends: EndChoice, nowMs: Double,
                images: List<PollImage?> = emptyList()): String {
        // Order on send = order on screen: quiz marks and pictures go by the final, kept rows.
        val kept = options.indices.filter { options[it].isNotBlank() }
        return markerText(id, question.trim(), kept.map { options[it].trim() }, multiple && !quiz, hideVoters,
            if (quiz) kept.indices.filter { right.getOrElse(kept[it]) { false } } else emptyList(),
            ends.seconds?.let { nowMs + it * 1000.0 }, kept.map { images.getOrNull(it) })
    }

    /** Drag to reorder (iOS poll-media-1114): a filled row moves; the empty row at the end stays last. */
    fun <T> move(list: List<T>, from: Int, to: Int, lastIsEmpty: Boolean): List<T> {
        val limit = if (lastIsEmpty) list.size - 2 else list.size - 1
        if (from !in 0..limit) return list
        val out = list.toMutableList()
        out.add(to.coerceIn(0, limit), out.removeAt(from))
        return out
    }

    /** The composer's next option appears by itself: one empty row at the end while there is room,
     *  two empty rows at the end fold back to one, never below two rows. */
    fun tidyOptions(options: List<String>): List<String> {
        val out = options.toMutableList()
        if (out.isNotEmpty() && out.last().isNotEmpty() && out.size < MAX_OPTIONS) out.add("")
        while (out.size > 2 && out[out.size - 1].isEmpty() && out[out.size - 2].isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    /**
     * PollService.listenChannel: followers never read each other's votes, so a channel poll is
     * drawn from the server's `pollCounts` / `pollVoters` as placeholder voters, round robin, plus
     * my own vote under my uid. Mine comes off the server's numbers only once they include it
     * ([tallyAt] at or after [mineAt]).
     */
    fun channelVotes(counts: Map<Int, Int>, voters: Int, tallyAt: Double, mine: List<Int>, mineAt: Double,
                     me: String): Map<String, List<Int>> {
        val c = counts.toMutableMap()
        var v = voters
        if (mine.isNotEmpty() && mineAt > 0 && tallyAt >= mineAt) {
            for (i in mine) c[i] = maxOf(0, (c[i] ?: 0) - 1)
            v = maxOf(0, v - 1)
        }
        val out = linkedMapOf<String, MutableList<Int>>()
        val n = maxOf(v, c.values.maxOrNull() ?: 0)
        if (n > 0) {
            var slot = 0
            for (i in c.keys.sorted()) repeat(c[i] ?: 0) {
                out.getOrPut(ANON_PREFIX + (slot % n)) { mutableListOf() }.add(i)
                slot++
            }
        }
        val res: MutableMap<String, List<Int>> = out.mapValues { it.value.sorted() }.toMutableMap()
        if (mine.isNotEmpty() && me.isNotEmpty()) res[me] = mine
        return res
    }
}
