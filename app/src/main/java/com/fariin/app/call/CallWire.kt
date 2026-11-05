package com.fariin.app.call

/**
 * The 1:1 call document, field for field as Kulan-iOS CallService.swift writes and reads it, so an
 * Android phone and an iPhone can call each other. Pure: no Firebase or WebRTC types, so all of it
 * is unit tested.
 *
 * calls/{id}:
 *   caller, callee, callerName, callerPhoto, type ("voice" | "video"), status ("ringing" |
 *   "active" | "ended"), cams {uid: Bool}, muted {uid: Bool}, hb {uid: seconds}, createdAt,
 *   offerEnc (enc1 sealed SDP, 1:1 chat crypto of convId(caller, callee)), sig: 2,
 *   answerEnc, ringingAt, acceptedAt, answeredDevice, endReason, cancelledAt, glare, refused,
 *   restartOffer/restartAnswer {enc, version}, restartRequest, moveTo ("adhoc_..."), caps,
 *   screen {uid: Bool}, screenMode {uid: "camera" | "track"}.
 * calls/{id}/callerCandidates/{auto} and calleeCandidates/{auto}: {enc} where enc seals the JSON
 *   {"candidate", "sdpMLineIndex", "sdpMid"}; old builds wrote those three keys in the clear.
 * The chat row: conversations/{cid}/messages/call_{id} {type "call", authorId, callerUid,
 *   callOutcome (ringing | ongoing | answered | missed), callVideo, text "", createdAt,
 *   callDuration (the caller only)} and the conversation's lastMessage marker.
 */
enum class EndReason(val wire: String) {
    NONE("none"), HANGUP("hangup"), DECLINED("declined"), MISSED("missed"), FAILED("failed"), BUSY("busy");

    companion object {
        /** iOS: `EndReason(rawValue:) ?? .hangup`. */
        fun from(s: String?): EndReason = entries.firstOrNull { it.wire == s && it != NONE } ?: HANGUP
    }
}

object CallWire {
    const val CALLS = "calls"
    const val CALLER_CANDIDATES = "callerCandidates"
    const val CALLEE_CANDIDATES = "calleeCandidates"
    const val SIG_SEALED = 2
    /** The caller gives up after this and the call is "No answer" (iOS startNoAnswerTimeout). */
    const val NO_ANSWER_MS = 45_000L
    /** A ringing doc older than this is never rung (iOS staleRingAge). */
    const val STALE_RING_MS = 120_000L
    /** The callee's own ring limit, a little past the caller's so the caller's end normally wins. */
    const val CALLEE_RING_MS = 60_000L
    /** Heartbeat while a call is live (iOS writes every 5 s, every 10 s when calm). */
    const val HEARTBEAT_MS = 10_000L
    /** The caller's data channel; the callee says "accepted" on it for a fast start (iOS). */
    const val ACCEPT_CHANNEL = "accept"
    const val ACCEPT_MESSAGE = "accepted"
    /** Stream ids iOS uses for its tracks. */
    const val STREAM_ID = "stream0"

    fun convId(a: String, b: String): String = listOf(a, b).sorted().joinToString("_")
    /** iOS CallService.earlyCap: this caller applies an answer sent during the ring without reading it as a pick-up. */
    const val EARLY_CAP = "early1"

    fun candidatesOf(isCaller: Boolean) = if (isCaller) CALLER_CANDIDATES else CALLEE_CANDIDATES
    fun remoteCandidatesOf(isCaller: Boolean) = if (isCaller) CALLEE_CANDIDATES else CALLER_CANDIDATES

    /**
     * The caller's create (iOS beginOutgoingMedia). `serverTime` is FieldValue.serverTimestamp()
     * in the app. `caps`: "early1" only (the callee may answer during the ring; iOS earlyCap).
     * No "screen2": the iPhone's second screen track is not offered by this build.
     */
    fun createFields(
        me: String, callee: String, myName: String?, myPhoto: String?, video: Boolean,
        offerEnc: String, muted: Boolean, serverTime: Any,
    ): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "caller" to me,
            "callee" to callee,
            "callerName" to (myName?.takeIf { it.isNotEmpty() } ?: "Caller"),
            "callerPhoto" to (myPhoto ?: ""),
            "type" to if (video) "video" else "voice",
            "status" to "ringing",
            "cams" to mapOf(me to video),
            "createdAt" to serverTime,
            "offerEnc" to offerEnc,
            "sig" to SIG_SEALED,
            "caps" to mapOf(me to listOf(EARLY_CAP)),
        )
        if (muted) m["muted"] = mapOf(me to true)
        return m
    }

    /** The callee's answer write (iOS buildAnswer), sent together with the accept. */
    /** The answer sent DURING THE RING (iOS publishEarlyAnswerIfAllowed): no status, never "active";
     *  the caller applies it without taking it for a pick-up. */
    fun earlyAnswerFields(me: String, answerEnc: String): Map<String, Any> = mapOf(
        "answerEnc" to answerEnc,
        "cams.$me" to false,
    )

    fun answerFields(me: String, answerEnc: String, cameraOn: Boolean): Map<String, Any> = mapOf(
        "answerEnc" to answerEnc,
        "cams.$me" to cameraOn,
        "status" to "active",
    )

    /** iOS acceptFields(): no VoIP token on Android, so no answeredTokenHash. */
    fun acceptFields(serverTime: Any): Map<String, Any> = mapOf("acceptedAt" to serverTime)

    fun endFields(reason: EndReason): Map<String, Any> = mapOf("status" to "ended", "endReason" to reason.wire)

    /** A ring the caller gave up on (no answer or cancelled): missed plus cancelledAt. */
    fun cancelFields(reason: EndReason, serverTime: Any): Map<String, Any> =
        endFields(reason) + ("cancelledAt" to serverTime)

    fun ringingFields(serverTime: Any): Map<String, Any> = mapOf("ringingAt" to serverTime)
    fun mutedField(me: String, muted: Boolean): Map<String, Any> = mapOf("muted.$me" to muted)
    fun camsField(me: String, on: Boolean): Map<String, Any> = mapOf("cams.$me" to on)
    /**
     * My screen share (iOS broadcastScreenState): `screen`, the `cams` that goes with it and, while
     * sharing, `screenMode` in ONE write, so the other phone never fits a face or crops a screen.
     * This build always shares on its camera's own track ("camera"): it never says `caps` screen2,
     * so an iPhone never sends it a second (screen) track either.
     */
    fun screenFields(me: String, sharing: Boolean, cams: Boolean): Map<String, Any> {
        val m = linkedMapOf<String, Any>("screen.$me" to sharing, "cams.$me" to cams)
        if (sharing) m["screenMode.$me"] = SCREEN_MODE_CAMERA
        return m
    }
    const val SCREEN_MODE_CAMERA = "camera"
    const val SCREEN_MODE_TRACK = "track"
    fun heartbeatField(me: String, nowMs: Long): Map<String, Any> = mapOf("hb.$me" to nowMs / 1000.0)
    fun claimField(device: String): Map<String, Any> = mapOf("answeredDevice" to device)
    /** An ICE-restart SDP (iOS writeSignalSdp). */
    fun restartField(field: String, enc: String, version: Int): Map<String, Any> =
        mapOf(field to mapOf("enc" to enc, "version" to version))

    // ------------------------------------------------------------------ candidates

    /** The plain inner object iOS seals (sealedCandidate): candidate, sdpMLineIndex, sdpMid. */
    fun candidateJson(c: Candidate): String = MiniJson.write(linkedMapOf<String, Any?>(
        "candidate" to c.sdp, "sdpMLineIndex" to c.mLineIndex,
    ).apply { if (c.mid != null) put("sdpMid", c.mid) })

    fun sealedCandidateDoc(enc: String): Map<String, Any> = mapOf("enc" to enc)

    /** A candidate document: sealed `enc` (opened by `open`) or the old plain keys. */
    fun parseCandidateDoc(d: Map<String, Any?>, open: (String) -> String?): CandidateRead {
        val enc = d["enc"] as? String
        val fields: Map<String, Any?> = if (enc != null) {
            val json = open(enc) ?: return CandidateRead.NotYet(enc)
            MiniJson.readFlat(json) ?: return CandidateRead.Bad
        } else d
        val sdp = fields["candidate"] as? String ?: return CandidateRead.Bad
        val idx = (fields["sdpMLineIndex"] as? Number)?.toInt() ?: 0
        return CandidateRead.Ok(Candidate(sdp, idx, fields["sdpMid"] as? String))
    }

    /** iOS matchesUfrag: a candidate for an older ICE generation is held, not added. */
    fun matchesUfrag(candidateSdp: String, remoteSdp: String): Boolean {
        val parts = candidateSdp.split(" ")
        val i = parts.indexOf("ufrag")
        if (i < 0 || i + 1 >= parts.size) return true
        val theirs = parts[i + 1]
        val line = remoteSdp.split("\n").firstOrNull { it.startsWith("a=ice-ufrag:") } ?: return true
        val current = line.removePrefix("a=ice-ufrag:").trim()
        return current.isEmpty() || current == theirs
    }

    // ------------------------------------------------------------------ the chat row

    fun ringRowId(callId: String) = "call_$callId"

    fun ringRowFields(callerUid: String, video: Boolean, serverTime: Any): Map<String, Any> = mapOf(
        "type" to "call", "authorId" to callerUid, "callerUid" to callerUid,
        "callOutcome" to "ringing", "callVideo" to video, "text" to "", "createdAt" to serverTime,
    )

    /** ChatService.recordCall: `isNew` = the row did not exist yet (then it gets a createdAt). */
    fun recordFields(
        me: String, callerUid: String, outcome: String, video: Boolean, durationSec: Int,
        isNew: Boolean, serverTime: Any,
    ): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "type" to "call", "authorId" to callerUid, "callerUid" to callerUid,
            "callOutcome" to outcome, "callVideo" to video, "text" to "",
        )
        if (me == callerUid) m["callDuration"] = durationSec
        if (isNew) m["createdAt"] = serverTime
        return m
    }

    /** A "missed" never overwrites an "answered" row (iOS recordCall). */
    fun recordSkipped(outcome: String, existingOutcome: String?) = outcome == "missed" && existingOutcome == "answered"

    fun marker(outcome: String, video: Boolean): String =
        if (outcome == "missed") (if (video) "📹 Missed video call" else "📞 Missed call")
        else (if (video) "📹 Video call" else "📞 Call")

    fun convMarkerFields(outcome: String, video: Boolean, callerUid: String, serverTime: Any): Map<String, Any> =
        mapOf("lastMessage" to marker(outcome, video), "lastSender" to callerUid, "updatedAt" to serverTime)

    // ------------------------------------------------------------------ ending

    /** iOS finishCall's reason when none was set yet. */
    fun finalReason(
        current: EndReason, isCaller: Boolean, callDocCreated: Boolean, connected: Boolean,
        accepted: Boolean, localUser: Boolean,
    ): EndReason {
        val neverPlaced = isCaller && !callDocCreated && !connected
        var r = current
        if (neverPlaced && !localUser && (r == EndReason.NONE || r == EndReason.MISSED)) r = EndReason.FAILED
        if (r == EndReason.NONE) {
            r = if (connected || accepted) EndReason.HANGUP
            else if (isCaller) EndReason.MISSED
            else if (localUser) EndReason.DECLINED
            else EndReason.FAILED
        }
        return r
    }

    /** The record outcome, or null when no record is written (a dial that never left the phone). */
    fun recordOutcome(reason: EndReason, isCaller: Boolean, callDocCreated: Boolean, connected: Boolean, accepted: Boolean): String? {
        val neverPlaced = isCaller && !callDocCreated && !connected
        if (neverPlaced && reason == EndReason.FAILED) return null
        return if (connected || accepted) "answered" else "missed"
    }

    /** What the caller's end transaction writes when the callee never accepted (iOS finishCall). */
    sealed class CallerEnd {
        data object AlreadyEnded : CallerEnd()
        /** They accepted in the meantime: a plain hang-up, and the row becomes "answered". */
        data object AcceptedMeanwhile : CallerEnd()
        data object Cancel : CallerEnd()
    }

    fun callerEnd(doc: Map<String, Any?>?): CallerEnd = when {
        doc?.get("status") == "ended" -> CallerEnd.AlreadyEnded
        doc?.get("acceptedAt") != null -> CallerEnd.AcceptedMeanwhile
        else -> CallerEnd.Cancel
    }

    /** The callee's ring end writes only when no device answered and it is still open. */
    fun calleeMayEndRing(doc: Map<String, Any?>?, myDevice: String): Boolean {
        if (doc == null) return false
        if (doc["status"] == "ended" || doc["acceptedAt"] != null) return false
        val owner = doc["answeredDevice"] as? String
        return owner.isNullOrEmpty() || owner == myDevice
    }

    /** iOS claimAnswer's read: null = mine to claim. */
    fun claimOutcome(doc: Map<String, Any?>?, myDevice: String): String? {
        if (doc?.get("status") == "ended") return if (cancelledBeforeAccept(doc)) "cancelled" else "ended"
        val owner = doc?.get("answeredDevice") as? String
        if (!owner.isNullOrEmpty() && owner != myDevice) return "taken"
        return null
    }

    fun cancelledBeforeAccept(d: Map<String, Any?>?): Boolean =
        d?.get("cancelledAt") != null || (d?.get("acceptedAt") == null && d?.get("endReason") == EndReason.MISSED.wire)

    // ------------------------------------------------------------------ ICE servers and relay

    val FALLBACK_STUN = listOf(IceServerSpec("stun:stun.l.google.com:19302"), IceServerSpec("stun:stun1.l.google.com:19302"))

    /** The `iceServers` callable's reply {iceServers: [{urls, username?, credential?}]}, one entry per URL. */
    fun parseIceServers(reply: Any?): List<IceServerSpec> {
        val arr = (reply as? Map<*, *>)?.get("iceServers") as? List<*> ?: return emptyList()
        return arr.flatMap { s ->
            val m = s as? Map<*, *> ?: return@flatMap emptyList()
            val urls = when (val u = m["urls"]) {
                is String -> listOf(u)
                is List<*> -> u.filterIsInstance<String>()
                else -> emptyList()
            }
            val user = m["username"] as? String
            val cred = m["credential"] as? String
            urls.map { url -> if (user != null && cred != null) IceServerSpec(url, user, cred) else IceServerSpec(url) }
        }
    }

    fun hasRelay(list: List<IceServerSpec>) = list.any { it.url.startsWith("turn:") || it.url.startsWith("turns:") }

    /** iOS config: direct routes only for an accepted chat and with "Always Relay Calls" off. */
    fun relayOnly(establishedContact: Boolean, alwaysRelay: Boolean) = !establishedContact || alwaysRelay

    /** A call that must be relayed with no relay in hand fails, never goes direct. */
    fun strangerWithoutRelay(establishedContact: Boolean, alwaysRelay: Boolean, list: List<IceServerSpec>) =
        relayOnly(establishedContact, alwaysRelay) && !hasRelay(list)

    // ------------------------------------------------------------------ reading the doc

    fun parse(id: String, d: Map<String, Any?>): CallDoc = CallDoc(
        id = id,
        caller = d["caller"] as? String ?: "",
        callee = d["callee"] as? String ?: "",
        callerName = d["callerName"] as? String,
        callerPhoto = (d["callerPhoto"] as? String)?.takeIf { it.isNotEmpty() },
        video = d["type"] == "video",
        status = d["status"] as? String ?: "",
        endReason = d["endReason"] as? String,
        offerEnc = d["offerEnc"] as? String,
        plainOffer = d["offer"] != null,
        answerEnc = d["answerEnc"] as? String,
        ringing = d["ringingAt"] != null,
        accepted = d["acceptedAt"] != null,
        answeredDevice = d["answeredDevice"] as? String,
        cancelled = d["cancelledAt"] != null,
        glare = d["glare"] == true,
        refused = d["refused"] == true,
        moveTo = d["moveTo"] as? String,
        cams = boolMap(d["cams"]),
        muted = boolMap(d["muted"]),
        screen = boolMap(d["screen"]),
        screenMode = (d["screenMode"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { kk -> (v as? String)?.let { kk to it } } }?.toMap() ?: emptyMap(),
        heartbeats = (d["hb"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { kk -> (v as? Number)?.let { kk to it.toDouble() } } }?.toMap() ?: emptyMap(),
        restartOffer = versioned(d["restartOffer"]),
        restartAnswer = versioned(d["restartAnswer"]),
        restartRequest = (d["restartRequest"] as? Number)?.toInt(),
        caps = capsOf(d["caps"]),
    )

    private fun capsOf(v: Any?): Map<String, List<String>> {
        val m = v as? Map<*, *> ?: return emptyMap()
        val out = HashMap<String, List<String>>()
        for ((k, x) in m) { val key = k as? String ?: continue; out[key] = (x as? List<*>)?.filterIsInstance<String>() ?: emptyList() }
        return out
    }

    private fun boolMap(v: Any?): Map<String, Boolean> =
        (v as? Map<*, *>)?.mapNotNull { (k, b) -> (k as? String)?.let { kk -> (b as? Boolean)?.let { kk to it } } }?.toMap() ?: emptyMap()

    private fun versioned(v: Any?): Versioned? {
        val m = v as? Map<*, *> ?: return null
        val ver = (m["version"] as? Number)?.toInt() ?: return null
        return Versioned(m["enc"] as? String, ver)
    }
}

data class Candidate(val sdp: String, val mLineIndex: Int, val mid: String?)

sealed class CandidateRead {
    data class Ok(val candidate: Candidate) : CandidateRead()
    /** Sealed but not openable yet (key not in memory): retried after a key warm. */
    data class NotYet(val enc: String) : CandidateRead()
    data object Bad : CandidateRead()
}

data class IceServerSpec(val url: String, val username: String? = null, val credential: String? = null)

data class Versioned(val enc: String?, val version: Int)

data class CallDoc(
    val id: String,
    val caller: String,
    val callee: String,
    val callerName: String?,
    val callerPhoto: String?,
    val video: Boolean,
    val status: String,
    val endReason: String?,
    val offerEnc: String?,
    val plainOffer: Boolean,
    val answerEnc: String?,
    val ringing: Boolean,
    val accepted: Boolean,
    val answeredDevice: String?,
    val cancelled: Boolean,
    val glare: Boolean,
    val refused: Boolean,
    val moveTo: String?,
    val cams: Map<String, Boolean>,
    val muted: Map<String, Boolean>,
    /** Who is sharing their screen (iOS `screen`); a missing key under the map means not sharing. */
    val screen: Map<String, Boolean> = emptyMap(),
    /** How each share is carried (iOS `screenMode`): "camera" (on the camera track) or "track". */
    val screenMode: Map<String, String> = emptyMap(),
    val heartbeats: Map<String, Double>,
    val restartOffer: Versioned?,
    val restartAnswer: Versioned?,
    val restartRequest: Int?,
    /** caps.<uid>: what each side's build can do ("early1", "screen2"). */
    val caps: Map<String, List<String>> = emptyMap(),
) {
    val ended get() = status == "ended"
    fun other(me: String) = if (caller == me) callee else caller
}

/**
 * Just enough JSON for the sealed candidate: a flat object of strings, numbers, booleans and null.
 * iOS JSONSerialization escapes "/" as "\/", which the reader takes.
 */
object MiniJson {
    fun write(m: Map<String, Any?>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean, is Int, is Long -> v.toString()
        is Number -> v.toString()
        else -> str(v.toString())
    }

    private fun str(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> b.append("\\\"")
            ch == '\\' -> b.append("\\\\")
            ch == '\n' -> b.append("\\n")
            ch == '\r' -> b.append("\\r")
            ch == '\t' -> b.append("\\t")
            ch < ' ' -> b.append(String.format("\\u%04x", ch.code))
            else -> b.append(ch)
        }
        return b.append('"').toString()
    }

    fun readFlat(s: String): Map<String, Any?>? = try { Reader(s).obj() } catch (_: Exception) { null }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun obj(): Map<String, Any?> {
            ws(); expect('{'); val out = LinkedHashMap<String, Any?>()
            ws(); if (peek() == '}') { i++; return out }
            while (true) {
                ws(); val k = string(); ws(); expect(':'); ws()
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> break; else -> error("bad object") }
            }
            ws(); require(i == s.length)
            return out
        }
        fun peek() = s[i]
        fun expect(c: Char) { require(s[i] == c); i++ }
        fun value(): Any? = when {
            peek() == '"' -> string()
            s.startsWith("null", i) -> { i += 4; null }
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            else -> {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                val t = s.substring(st, i)
                t.toLongOrNull() ?: t.toDouble()
            }
        }
        fun string(): String {
            expect('"'); val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                        'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape $e")
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
