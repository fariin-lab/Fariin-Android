package com.fariin.app.crypto

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The account's end-to-end encryption, the Android twin of `Crypto` in Kulan/Services/Crypto.swift.
 * Same public operations, same formats (see [Wire] and WIRE_FORMAT.md), same rules:
 *  - never returns plaintext from a seal; a missing recipient key throws [MissingRecipientKeyException]
 *  - "could not read the key store" is never "no key" (no new keypair on a read failure)
 *  - a private key that did not reach storage is never published
 *  - a message that will not open with someone's current key is tried with their earlier keys
 *
 * Firebase is reached only through [KeyDirectory], so everything here runs in JVM unit tests.
 */
class FariinCrypto(
    private val nacl: NaCl,
    private val secrets: SecretStore,
    val peers: PeerKeys,
    private val directory: KeyDirectory,
    private val currentUid: () -> String?,
    /** ChatService.groupIsPlain: true for a group whose doc says `e2ee == false`. */
    private val groupIsPlain: suspend (cid: String) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** A peer's key differs from the one seen before (iOS posts `.peerKeyChanged`). */
    var onPeerKeyChanged: (uid: String) -> Unit = {},
    /** When this phone's key store was made (ms), for a keypair older than the keys-since stamp. */
    private val keyStoreMadeAt: () -> Long? = { null },
    /** The automatic backup (Android: Block Store; iOS: iCloud Keychain): read before a new keypair
     *  is ever made, written once the keypair is known, forgotten with the account. */
    private val backupLoad: suspend (uid: String) -> KeyBlockStore.Saved? = { null },
    private val backupSave: suspend (uid: String, sk: ByteArray, pk: ByteArray, made: Long) -> Unit = { _, _, _, _ -> },
    private val backupForget: suspend (uid: String) -> Unit = {},
) {
    val wire = Wire(nacl)

    companion object {
        private const val LEGACY_SK = "fariin_secret_key_v1"
        private const val LEGACY_PK = "fariin_public_key_v1"
        fun skName(uid: String) = "fariin_secret_key_v1.$uid"
        fun pkName(uid: String) = "fariin_public_key_v1.$uid"
        /** When this phone's keypair was made, ms as a string (iOS Keychain, same name). */
        fun sinceName(uid: String) = "fariin_keys_since_v1.$uid"

        /** The app's instance wiring: Keystore-wrapped private key, prefs for public keys. */
        fun android(
            context: Context, directory: KeyDirectory, currentUid: () -> String?,
            groupIsPlain: suspend (String) -> Boolean = { false },
        ) = FariinCrypto(
            NaCl.android().also { SafetyKeyLog.init(context) }, KeystoreSecretStore(context),
            PeerKeys(PrefsKeyValueStore(context.getSharedPreferences("fariin_crypto_public", Context.MODE_PRIVATE))),
            directory, currentUid, groupIsPlain,
            keyStoreMadeAt = { KeystoreSecretStore.Made.at() },
            backupLoad = { uid -> KeyBlockStore.load(context.applicationContext, uid) },
            backupSave = { uid, sk, pk, made -> KeyBlockStore.save(context.applicationContext, uid, sk, pk, made) },
            backupForget = { uid -> KeyBlockStore.delete(context.applicationContext, uid) },
        )
    }

    // ------------------------------------------------------------------ identity

    @Volatile private var mySecretKey: ByteArray? = null
    @Volatile private var myPublicKey: ByteArray? = null
    private val pubCache = ConcurrentHashMap<String, ByteArray>()
    private val readyMutex = Mutex()
    @Volatile private var didWarm = false

    val isReady: Boolean get() = mySecretKey != null
    val myPublicKeyB64: String? get() = myPublicKey?.let { B64.encode(it) }

    /** Load (or make, store and publish) this account's keypair. Idempotent and single-flight,
     *  so two first-launch callers can never make two keypairs. */
    suspend fun ensureReady() {
        if (mySecretKey != null) { backUpOnce(); return }
        readyMutex.withLock {
            if (mySecretKey != null) return@withLock
            // No key on this phone (a reinstall, a new phone): the backup's key first, never a new
            // one while an old one can be had (owner 2026-11-11, his key lost on the Pixel).
            val uid = currentUid()
            val restored = if (uid != null && secrets.lookup(skName(uid)) is SecretStore.Lookup.NotFound &&
                secrets.lookup(LEGACY_SK) is SecretStore.Lookup.NotFound)
                runCatching { kotlinx.coroutines.withTimeoutOrNull(4_000) { backupLoad(uid) } }.getOrNull() else null
            initKeys(restored)
        }
        backUpOnce()
    }

    @Volatile private var backedUp = false

    /** Once a run: the keypair in the automatic backup (a write of the same bytes is harmless). */
    private fun backUpOnce() {
        if (backedUp) return
        val uid = currentUid() ?: return
        val sk = mySecretKey ?: return
        val pk = myPublicKey ?: return
        backedUp = true
        scope.launch { runCatching { backupSave(uid, sk, pk, keysSince() ?: 0L) }.onFailure { backedUp = false } }
    }

    private fun initKeys(restored: KeyBlockStore.Saved? = null) {
        val uid = currentUid() ?: throw CryptoException("ensureReady() called before sign-in")
        for (k in listOf(skName(uid), pkName(uid), LEGACY_SK, LEGACY_PK)) {
            if (secrets.lookup(k) is SecretStore.Lookup.Unreadable) throw CryptoException("key store not readable right now")
        }
        val sk: ByteArray
        val pk: ByteArray
        val stored = loadPair(skName(uid), pkName(uid))
        val legacy = if (stored == null) loadPair(LEGACY_SK, LEGACY_PK) else null
        when {
            stored != null -> { sk = stored.first; pk = stored.second }
            legacy != null -> {
                // One-time claim of a device-wide key into this account's slot (inert on iOS too).
                sk = legacy.first; pk = legacy.second
                secrets.set(skName(uid), B64.encode(sk)); secrets.set(pkName(uid), B64.encode(pk))
                secrets.delete(LEGACY_SK); secrets.delete(LEGACY_PK)
            }
            restored != null && restored.sk.size == NaCl.SECRET_KEY_BYTES &&
                nacl.boxPublicKey(restored.sk).contentEquals(restored.pk) &&
                secrets.set(skName(uid), B64.encode(restored.sk)) && secrets.set(pkName(uid), B64.encode(restored.pk)) -> {
                // The same key as before the reinstall: every older message opens again.
                sk = restored.sk; pk = restored.pk
                if (restored.made > 0) { secrets.set(sinceName(uid), restored.made.toString()); keysSinceCache = uid to restored.made }
                else { secrets.set(sinceName(uid), "0"); keysSinceCache = uid to 0L }
            }
            else -> {
                val kp = nacl.boxKeyPair()
                sk = kp.secretKey; pk = kp.publicKey
                // A brand-new keypair: everything sealed before now was for another key.
                secrets.set(sinceName(uid), now().toString())
                keysSinceCache = uid to now()
                val okSk = secrets.set(skName(uid), B64.encode(sk))
                val okPk = secrets.set(pkName(uid), B64.encode(pk))
                if (!okSk || !okPk) {
                    secrets.delete(skName(uid)); secrets.delete(pkName(uid))
                    throw CryptoException("identity key could not be stored")
                }
            }
        }
        myPublicKey = pk; mySecretKey = sk; pubCache[uid] = pk
        val b64 = B64.encode(pk)
        scope.launch { directory.publishIfChanged(uid, b64) }
    }

    private fun loadPair(skKey: String, pkKey: String): Pair<ByteArray, ByteArray>? {
        val s = (secrets.lookup(skKey) as? SecretStore.Lookup.Found)?.value ?: return null
        val p = (secrets.lookup(pkKey) as? SecretStore.Lookup.Found)?.value ?: return null
        val sk = B64.decode(s) ?: return null
        val pk = B64.decode(p) ?: return null
        return if (sk.size == NaCl.SECRET_KEY_BYTES) sk to pk else null
    }

    /** Re-publish my key (self-healing; call on every launch once the profile exists). */
    suspend fun publishPublicKey() {
        try { ensureReady() } catch (_: Exception) { return }
        val uid = currentUid() ?: return
        directory.publishIfChanged(uid, myPublicKeyB64 ?: return)
    }

    /** Sign-out: forget in memory; the stored key is KEPT (keys are per uid). */
    fun wipeIdentity() {
        mySecretKey = null; myPublicKey = null; keysSinceCache = null; backedUp = false
        pubCache.clear(); didWarm = false; opened.clear()
    }

    /** This account's keypair and when it was made, for the PIN backup ([KeyBackup]). */
    suspend fun identityForBackup(): Triple<ByteArray, ByteArray, Long>? {
        runCatching { ensureReady() }
        val sk = mySecretKey ?: return null
        val pk = myPublicKey ?: return null
        return Triple(sk.copyOf(), pk.copyOf(), keysSince() ?: 0L)
    }

    /** The argon2id step of the PIN backup (the same library as everything else here). */
    fun pinHash(outLen: Int, pin: ByteArray, salt: ByteArray, ops: Long, mem: Long): ByteArray? = nacl.pwhash(outLen, pin, salt, ops, mem)
    fun randomBytes(n: Int): ByteArray = nacl.randomBytes(n)
    fun sealWith(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray>? {
        val n = nacl.randomBytes(NaCl.NONCE_BYTES)
        return nacl.secretBoxEasy(plain, n, key)?.let { n to it }
    }
    fun openWith(key: ByteArray, nonce: ByteArray, box: ByteArray): ByteArray? = nacl.secretBoxOpenEasy(box, nonce, key)

    /**
     * The PIN backup's key takes the place of the one this phone made (a reinstall, a new phone):
     * stored, dated from when it was first made, and published again, so every older message opens.
     * False when the pair does not belong together; nothing changes then.
     */
    fun adoptIdentity(sk: ByteArray, pk: ByteArray, made: Long): Boolean {
        val uid = currentUid() ?: return false
        if (sk.size != NaCl.SECRET_KEY_BYTES || !nacl.boxPublicKey(sk).contentEquals(pk)) return false
        if (!secrets.set(skName(uid), B64.encode(sk)) || !secrets.set(pkName(uid), B64.encode(pk))) return false
        val since = if (made > 0) made else 0L
        secrets.set(sinceName(uid), since.toString())
        synchronized(this) {
            mySecretKey = sk; myPublicKey = pk; pubCache[uid] = pk
            keysSinceCache = uid to since
            opened.clear(); fileOpenSeen.clear()
        }
        backedUp = false
        scope.launch { directory.publishIfChanged(uid, B64.encode(pk)) }
        backUpOnce()
        return true
    }

    /** Account deletion: the stored key goes too. */
    fun destroyIdentity(uid: String) {
        listOf(skName(uid), pkName(uid), sinceName(uid), LEGACY_SK, LEGACY_PK).forEach { secrets.delete(it) }
        scope.launch { runCatching { backupForget(uid) } }   // a deleted account leaves no key behind
        wipeIdentity()
    }

    /** Off the main thread at app start: the sodium library, my stored keypair and the cached peer
     *  keys, so the chat list's first rows do not pay for them while the first frame is built. */
    fun prewarm() {
        runCatching { nacl.randomBytes(1) }
        warmIfNeeded()
        runCatching { keysSince() }
    }

    /** Synchronous warm start on the first decrypt: stored keypair + every cached peer key. */
    private fun warmIfNeeded() {
        if (didWarm) return
        val uid = currentUid() ?: return
        synchronized(this) {
            if (didWarm) return
            didWarm = true
            for ((u, k) in peers.allCurrent()) pubCache.putIfAbsent(u, k)
            if (mySecretKey == null) {
                (loadPair(skName(uid), pkName(uid)) ?: loadPair(LEGACY_SK, LEGACY_PK))?.let { (sk, pk) ->
                    myPublicKey = pk; mySecretKey = sk; pubCache[uid] = pk
                }
            }
        }
    }

    // ------------------------------------------------------------------ peer keys

    private val keyFetches = ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val lastSendKeyCheck = ConcurrentHashMap<String, Long>()
    private val lastOldOpenRecheck = ConcurrentHashMap<String, Long>()
    private val keyRefreshInFlight = ConcurrentHashMap.newKeySet<String>()
    private val refreshedItems = ConcurrentHashMap.newKeySet<String>()
    private val noKeySeen = ConcurrentHashMap<String, Long>()

    fun hasCachedKey(uid: String) = uid.isNotEmpty() && pubCache.containsKey(uid)

    /** Every fetched key enters here (Crypto.swift:645). */
    private fun adoptKey(uid: String, key: ByteArray): ByteArray {
        pubCache[uid] = key
        peers.adopt(uid, key)
        // Asked of the append-only record, not of the cache: the cache forgets a key on purpose
        // after a failed open, so it would call a swapped key a first sighting.
        if (SafetyKeyLog.note(uid, key)) onPeerKeyChanged(uid)
        return key
    }

    private suspend fun fetchKeyResult(uid: String, serverOnly: Boolean = false): KeyDirectory.Fetch {
        val r = directory.fetch(uid, serverOnly)
        if (r is KeyDirectory.Fetch.Key) {
            // Oldest first, as stored: each key goes to the front, so the newest end up first and
            // the ring of 8 keeps the newest earlier keys (reversed, it kept the 8 oldest).
            peers.remember(uid, r.history)
            adoptKey(uid, B64.decode(r.publicKeyB64)!!)
        }
        return r
    }

    /** Cached key, or one shared fetch per uid, 10 s at most. */
    suspend fun preloadKey(uid: String): ByteArray? {
        if (uid.isEmpty()) return null
        pubCache[uid]?.let { return it }
        val d = keyFetches.computeIfAbsent(uid) {
            scope.async { (fetchKeyResult(uid) as? KeyDirectory.Fetch.Key)?.let { pubCache[uid] } }
        }
        val r = withTimeoutOrNull(10_000) { d.await() }
        keyFetches.remove(uid, d)
        return r
    }

    suspend fun publicKey(uid: String): ByteArray? = preloadKey(uid)

    /** Up to 8 reads in flight. */
    suspend fun preloadKeys(uids: Collection<String>): Map<String, ByteArray> = coroutineScope {
        val gate = Semaphore(8)
        uids.filter { it.isNotEmpty() }.toSet()
            .map { u -> async { gate.withPermit { u to preloadKey(u) } } }
            .mapNotNull { val (u, k) = it.await(); k?.let { u to it } }.toMap()
    }

    /** At most every 5 min per person, ask the server first (1.5 s cap) before sealing to them. */
    private suspend fun keyForSending(uid: String): ByteArray? {
        val cached = preloadKey(uid)
        val t = now()
        val last = lastSendKeyCheck[uid]
        if (last != null && t - last < 300_000) return cached
        lastSendKeyCheck[uid] = t
        val fresh = withTimeoutOrNull(1_500) { fetchKeyResult(uid, serverOnly = true) }
        return (fresh as? KeyDirectory.Fetch.Key)?.let { pubCache[uid] } ?: cached
    }

    sealed class FreshKey {
        class Key(val key: ByteArray) : FreshKey()
        data object NoKey : FreshKey()
        data object Unreachable : FreshKey()
    }

    /** Server-only read for the safety-number page. */
    suspend fun fetchFreshKey(uid: String): FreshKey {
        if (uid.isEmpty()) return FreshKey.NoKey
        return when (val r = directory.fetch(uid, serverOnly = true)) {
            is KeyDirectory.Fetch.Key -> FreshKey.Key(adoptKey(uid, B64.decode(r.publicKeyB64)!!))
            KeyDirectory.Fetch.NoKey -> FreshKey.NoKey
            KeyDirectory.Fetch.Failed -> FreshKey.Unreachable
        }
    }

    /** A decrypt failed: refetch once per (uid, item) per session; keep the old key on errors. */
    fun refreshKeyAfterFailure(uid: String, item: String? = null) {
        if (uid.isEmpty()) return
        if (item != null) {
            if (refreshedItems.size > 5000) refreshedItems.clear()
            if (!refreshedItems.add("$uid|$item")) return
        }
        if (!keyRefreshInFlight.add(uid)) return
        scope.launch {
            try {
                if (fetchKeyResult(uid, serverOnly = true) == KeyDirectory.Fetch.NoKey) {
                    pubCache.remove(uid); peers.forget(uid)
                }
            } finally { keyRefreshInFlight.remove(uid) }
        }
    }

    private fun recheckKeyAfterOldOpen(uid: String) {
        val t = now()
        val last = lastOldOpenRecheck[uid]
        if (last != null && t - last < 300_000) return
        lastOldOpenRecheck[uid] = t
        refreshKeyAfterFailure(uid)
    }

    /** Group seal keys: a failed read throws (the send queues) instead of dropping the member. */
    private suspend fun groupKeys(members: Collection<String>): Map<String, ByteArray> {
        val keys = preloadKeys(members).toMutableMap()
        val t = now()
        for (uid in members.toSet()) {
            if (uid.isEmpty() || keys.containsKey(uid)) continue
            // A member with no key costs one extra read a minute, not one per message.
            if (noKeySeen[uid]?.let { t - it < 60_000 } == true) continue
            when (withTimeoutOrNull(10_000) { fetchKeyResult(uid) } ?: KeyDirectory.Fetch.Failed) {
                is KeyDirectory.Fetch.Key -> pubCache[uid]?.let { keys[uid] = it }
                KeyDirectory.Fetch.NoKey -> noKeySeen[uid] = now()
                KeyDirectory.Fetch.Failed -> throw GroupKeyReadException()
            }
        }
        return keys
    }

    private fun me() = currentUid() ?: ""
    private fun otherUid(cid: String) = Wire.otherUid(cid, me())

    /** Sender keys to try for [uid]: in-memory current first, then the ring. Notes an old-key open. */
    private fun candidates(uid: String, current: ByteArray) = peers.candidates(uid, current)

    private fun <T> noteOpen(uid: String, r: Pair<T, Int>?): T? {
        r ?: return null
        if (r.second > 0) recheckKeyAfterOldOpen(uid)
        return r.first
    }

    // ------------------------------------------------------------------ 1:1 text

    /** "enc1:<nonce>:<box>". Never plaintext. */
    suspend fun encryptForConversation(cid: String, text: String): String {
        ensureReady()
        val sk = mySecretKey ?: throw CryptoException("keys not ready")
        val other = otherUid(cid)
        val pub = keyForSending(other) ?: throw MissingRecipientKeyException(other)
        return wire.sealText(text, pub, sk)
    }

    /** Same seal from keys already in memory or on disk; null = not ready (call signalling). */
    fun encryptForConversationIfCached(cid: String, text: String): String? {
        warmIfNeeded()
        val sk = mySecretKey ?: return null
        val pub = pubCache[otherUid(cid)] ?: return null
        return try { wire.sealText(text, pub, sk) } catch (_: CryptoException) { null }
    }

    suspend fun recipientReady(cid: String): Boolean = try {
        ensureReady(); preloadKey(otherUid(cid)) != null
    } catch (_: Exception) { false }

    /** Synchronous decrypt, the iOS sentinels included: not "enc1:" -> as is; "enc:" -> [old
     *  message]; keys missing -> "…"; will not open -> the lock. */
    fun decrypt(raw: String, cid: String): String {
        if (raw.startsWith(Wire.LEGACY)) return Wire.OLD_MESSAGE
        if (!raw.startsWith(Wire.ENC1)) return raw
        warmIfNeeded()
        val sk = mySecretKey ?: return Wire.NOT_READY
        val other = otherUid(cid)
        val cur = pubCache[other] ?: return Wire.NOT_READY
        val parsed = wire.parseEnc1(raw) ?: return Wire.LOCKED
        noteOpen(other, wire.openText(raw, candidates(other, cur), sk))?.let { return it }
        refreshKeyAfterFailure(other, parsed.nonceB64)
        return Wire.LOCKED
    }

    /** Live call signal: an open with an earlier key makes that key current at once. */
    fun decryptLive(raw: String, cid: String): String {
        if (!raw.startsWith(Wire.ENC1)) return decrypt(raw, cid)
        warmIfNeeded()
        val uid = otherUid(cid)
        val sk = mySecretKey ?: return decrypt(raw, cid)
        val cur = pubCache[uid] ?: return decrypt(raw, cid)
        val e = wire.parseEnc1(raw) ?: return decrypt(raw, cid)
        if (nacl.boxOpenEasy(e.box, e.nonce, cur, sk) != null) return decrypt(raw, cid)
        for (old in peers.earlier(uid)) {
            if (old.contentEquals(cur)) continue
            val o = nacl.boxOpenEasy(e.box, e.nonce, old, sk) ?: continue
            val text = Utf8.decodeStrict(o) ?: continue
            adoptKey(uid, old)
            return text
        }
        return decrypt(raw, cid)
    }

    // ------------------------------------------------------------------ group text

    suspend fun encryptForGroup(text: String, members: Collection<String>): String {
        ensureReady()
        val sk = mySecretKey ?: throw CryptoException("keys not ready")
        return wire.sealGroupText(text, me(), members, groupKeys(members), sk)
    }

    fun groupEnvelopeSkipsMe(raw: String, cid: String): Boolean {
        val me = currentUid() ?: return false
        val env = wire.parseEncg1(raw) ?: return false
        if (env.w.containsKey(me)) return false
        if (Wire.isGroupCid(cid) && releasedKey(env.n) != null) return false
        return true
    }

    fun decryptGroup(raw: String, authorId: String, cid: String = ""): String {
        if (!raw.startsWith(Wire.ENCG1)) return raw
        warmIfNeeded()
        val env = wire.parseEncg1(raw) ?: return Wire.LOCKED
        val released: (String) -> ByteArray? = { n -> if (Wire.isGroupCid(cid)) releasedKey(n) else null }
        fun openReleased(): String? = released(env.n)?.let { k ->
            nacl.secretBoxOpenEasy(env.cipher!!, env.dataNonce!!, k)?.let { Utf8.decodeStrict(it) }
        }
        val sk = mySecretKey
        val me = currentUid()
        if (sk == null || me == null) return openReleased() ?: Wire.NOT_READY
        val authorPub = pubCache[authorId] ?: return openReleased() ?: Wire.NOT_READY
        if (!env.w.containsKey(me)) return openReleased() ?: Wire.LOCKED
        return noteOpen(authorId, wire.openGroupText(raw, me, candidates(authorId, authorPub), sk, released))
            ?: Wire.LOCKED
    }

    /** Routing decrypt: group envelopes need the author; 1:1 uses the cid pair. */
    fun decrypt(raw: String, cid: String, authorId: String): String {
        if (!raw.startsWith(Wire.ENCG1) && !raw.startsWith(Wire.ENC1)) return decrypt(raw, cid)
        // Battery/lag audit 2026-11-05: every change in an open chat re-read all its messages and
        // opened each box again on the main thread. A sealed text never changes (an edit is a new
        // one), so a message that opened is remembered. Only real opens: "not ready" and the lock
        // can change once keys arrive, so they are never kept.
        val key = openedKey(raw, cid, authorId)
        opened[key]?.let { return it }
        val out = if (raw.startsWith(Wire.ENCG1)) decryptGroup(raw, authorId, cid) else decrypt(raw, cid)
        if (out != Wire.NOT_READY && out != Wire.LOCKED && out != Wire.OLD_MESSAGE) opened[key] = out
        return out
    }

    /** [decrypt] with no box opened: the remembered open, or null when it would need one. Plain
     *  and old lines need no box, so they answer as [decrypt] does. */
    fun cachedDecrypt(raw: String, cid: String, authorId: String): String? {
        if (!raw.startsWith(Wire.ENCG1) && !raw.startsWith(Wire.ENC1)) return decrypt(raw, cid)
        return opened[openedKey(raw, cid, authorId)]
    }

    /**
     * A short key for a sealed text: its length and last 96 characters (the sealed body and its tag,
     * random per message) instead of the whole text. A group message carries a key wrap per member,
     * ~17 KB at 100 members; keyed whole, 1500 entries could hold 25 MB (review 2026-11-06).
     */
    private fun openedKey(raw: String, cid: String, authorId: String) = "$cid|$authorId|${raw.length}|${raw.takeLast(96)}"

    private val opened: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1500
        })

    /** A group that switched to plain (`e2ee == false`) sends text as is. Never for 1:1. */
    suspend fun sealForGroup(text: String, members: Collection<String>, cid: String): String =
        if (groupIsPlain(cid)) text else encryptForGroup(text, members)

    // ------------------------------------------------------------------ files

    suspend fun encryptBytes(cid: String, data: ByteArray): Pair<ByteArray, EncMeta> {
        ensureReady()
        val sk = mySecretKey ?: throw CryptoException("keys not ready")
        val other = otherUid(cid)
        val pub = keyForSending(other) ?: throw MissingRecipientKeyException(other)
        return wire.sealFile(data, pub, sk)
    }

    suspend fun encryptBytesForGroup(data: ByteArray, members: Collection<String>): Pair<ByteArray, EncMeta> {
        ensureReady()
        val sk = mySecretKey ?: throw CryptoException("keys not ready")
        val me = currentUid() ?: throw CryptoException("keys not ready")
        return wire.sealGroupFile(data, me, members, groupKeys(members), sk)
    }

    suspend fun sealBytesForGroup(data: ByteArray, members: Collection<String>, cid: String): Pair<ByteArray, EncMeta> =
        if (groupIsPlain(cid)) data to EncMeta.PLAIN else encryptBytesForGroup(data, members)

    suspend fun decryptBytes(cid: String, cipher: ByteArray, meta: EncMeta): ByteArray? {
        // A plain file is the file, in groups only; a "plain" 1:1 file is refused.
        if (meta.isPlain) return if (Wire.isGroupCid(cid)) cipher else null
        val released: (String) -> ByteArray? = { n -> if (Wire.isGroupCid(cid)) releasedKey(n) else null }
        val wraps = meta.w
        val author = meta.a
        if (wraps != null && author != null) {
            try { ensureReady(); preloadKey(author) } catch (_: Exception) {}
            val sk = mySecretKey; val me = currentUid(); val cur = pubCache[author]
            if (sk == null || me == null || cur == null || !wraps.containsKey(me)) {
                return released(meta.n)?.let { k -> B64.decode(meta.n)?.let { nacl.secretBoxOpenEasy(cipher, it, k) } }
            }
            return wire.openGroupFile(cipher, meta, me, candidates(author, cur), sk, released)
        }
        try { ensureReady(); preloadKey(otherUid(cid)) } catch (_: Exception) {}
        val sk = mySecretKey ?: return null
        if (meta.v != 1) return null
        val other = otherUid(cid)
        val cur = pubCache[other] ?: return null
        val r = wire.openFile(cipher, meta, candidates(other, cur), sk)
        if (r == null) {
            refreshKeyAfterFailure(other, meta.kn)
            return null
        }
        return noteOpen(other, r)
    }

    @Volatile private var keysSinceCache: Pair<String, Long?>? = null

    /**
     * When this phone's keypair was made, in ms (iOS keysSince, same rule): the stamp written when a
     * keypair is made; for an older keypair, once, the date this phone's key store was made (a
     * reinstall makes a new one; never later than the keypair, so it can only hide less). Null =
     * unknown, and then nothing is hidden.
     */
    fun keysSince(): Long? {
        val uid = currentUid() ?: return null
        keysSinceCache?.takeIf { it.first == uid }?.let { return it.second }
        warmIfNeeded()
        val stored = when (val l = secrets.lookup(sinceName(uid))) {
            is SecretStore.Lookup.Found -> l.value.toLongOrNull()
            is SecretStore.Lookup.Unreadable -> return null   // ask again later
            else -> null
        }
        val since = stored ?: if (mySecretKey != null) keyStoreMadeAt()?.also { secrets.set(sinceName(uid), it.toString()) } else null
        if (since != null || mySecretKey != null) keysSinceCache = uid to since
        return since
    }

    /** Opens checked before, keyed by the wrap's nonce and the sender key it was tried against. */
    private val fileOpenSeen: MutableMap<String, Boolean> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 3000
        })

    /**
     * A sealed file this phone can never open: its key was sealed to a key this phone no longer has
     * (a reinstall, a new phone). The chat leaves it out, as the big messengers do after a reinstall,
     * instead of an endless grey box. False whenever the keys are not in yet, so a slow start never
     * hides anything; a group file not sealed for me at all stays as before.
     */
    fun fileWillNotOpen(cid: String, meta: EncMeta?): Boolean {
        if (meta == null || meta.isPlain || meta.v != 1) return false
        warmIfNeeded()
        val sk = mySecretKey ?: return false
        val wraps = meta.w
        val author = meta.a
        if (wraps != null && author != null) {
            if (releasedKey(meta.n) != null) return false
            val cur = pubCache[author] ?: return false
            val mine = wraps[currentUid() ?: return false] ?: return false
            val seenKey = "g|$mine|${cur.contentHashCode()}"
            fileOpenSeen[seenKey]?.let { return !it }
            val opens = wire.openWrap(mine, candidates(author, cur), sk) != null
            fileOpenSeen[seenKey] = opens
            return !opens
        }
        val other = otherUid(cid)
        val cur = pubCache[other] ?: return false
        val seenKey = "d|${meta.kn}|${cur.contentHashCode()}"
        fileOpenSeen[seenKey]?.let { return !it }
        val wrapped = B64.decode(meta.k) ?: return false
        val kn = B64.decode(meta.kn) ?: return false
        val opens = wire.openBoxAny(wrapped, kn, candidates(other, cur), sk) != null
        fileOpenSeen[seenKey] = opens
        return !opens
    }

    // ------------------------------------------------------------------ released group keys

    private val released = ConcurrentHashMap<String, ByteArray>()   // b64(nonce) -> key

    /** Takes a message's (or conversation's) `rk` map; returns the newly learned nonces. Groups only. */
    fun noteReleased(cid: String, rk: Map<*, *>?): Set<String> {
        if (!Wire.isGroupCid(cid) || rk.isNullOrEmpty()) return emptySet()
        val fresh = HashSet<String>()
        for ((n, v) in rk) {
            val nonce = (n as? String)?.let { B64.decodeLoose(it) } ?: continue
            val key = (v as? String)?.let { B64.decodeLoose(it) } ?: continue
            if (nonce.size != 24 || key.size != 32) continue
            val id = B64.encode(nonce)
            if (released.containsKey(id)) continue
            if (released.size > 20_000) released.clear()
            released[id] = key
            fresh.add(id)
        }
        return fresh
    }

    fun releasedKey(nonceB64: String): ByteArray? =
        B64.decodeLoose(nonceB64)?.let { released[B64.encode(it)] }

    /** For the group key release: my own wrap's key of a sealed text, checked against the text. */
    fun ownGroupTextKey(raw: String, authorId: String): Pair<String, String>? {
        val sk = mySecretKey ?: return null
        val me = currentUid() ?: return null
        val cur = pubCache[authorId] ?: return null
        val env = wire.parseEncg1(raw) ?: return null
        val (key, idx) = wire.openWrap(env.w[me] ?: return null, candidates(authorId, cur), sk) ?: return null
        if (idx > 0) recheckKeyAfterOldOpen(authorId)
        nacl.secretBoxOpenEasy(env.cipher!!, env.dataNonce!!, key) ?: return null
        return env.n to B64.encode(key)
    }


    fun ownGroupFileKey(meta: EncMeta): Pair<String, String>? {
        if (meta.isPlain || meta.n.isEmpty()) return null
        val wraps = meta.w ?: return null
        val author = meta.a ?: return null
        val sk = mySecretKey ?: return null
        val me = currentUid() ?: return null
        val cur = pubCache[author] ?: return null
        val (key, _) = wire.openWrap(wraps[me] ?: return null, candidates(author, cur), sk) ?: return null
        return if (key.size == 32) meta.n to B64.encode(key) else null
    }
}
