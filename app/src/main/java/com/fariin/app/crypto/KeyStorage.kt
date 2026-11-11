package com.fariin.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A plain string store. Android: SharedPreferences; tests: a map. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String)
    /** Every entry whose key starts with [prefix], prefix stripped. */
    fun withPrefix(prefix: String): Map<String, String>
}

class MemoryKeyValueStore : KeyValueStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String): Boolean { m[key] = value; return true }
    override fun remove(key: String) { m.remove(key) }
    override fun withPrefix(prefix: String) =
        m.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).commit()
    override fun remove(key: String) { prefs.edit().remove(key).commit() }
    override fun withPrefix(prefix: String): Map<String, String> =
        prefs.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(prefix) to it } }.toMap()
}

/**
 * Where this account's PRIVATE key lives. Mirrors the iOS `Keychain` enum (Crypto.swift:1132),
 * including its three answers: found, not there, and "could not read right now". The last one
 * must never be treated as "not there": that path makes a new keypair, which is permanent.
 */
interface SecretStore {
    sealed class Lookup {
        class Found(val value: String) : Lookup()
        data object NotFound : Lookup()
        data object Unreadable : Lookup()
    }
    fun lookup(key: String): Lookup
    fun set(key: String, value: String): Boolean
    fun delete(key: String)
}

class MemorySecretStore : SecretStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
    var failReads = false     // tests: simulate a store that cannot be read
    var failWrites = false
    override fun lookup(key: String): SecretStore.Lookup = when {
        failReads -> SecretStore.Lookup.Unreadable
        else -> m[key]?.let { SecretStore.Lookup.Found(it) } ?: SecretStore.Lookup.NotFound
    }
    override fun set(key: String, value: String): Boolean { if (failWrites) return false; m[key] = value; return true }
    override fun delete(key: String) { m.remove(key) }
}

/**
 * The private key, encrypted with an AES-256-GCM key that lives in the Android Keystore and can
 * never be exported. The same intent as iOS `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
 * (Crypto.swift:1179): the blob in SharedPreferences is useless off this device, so a backup
 * restored elsewhere cannot carry the identity. A blob whose wrapping key is gone (restore onto
 * a new phone, app data cleared) counts as NOT FOUND and is dropped, as a missing Keychain item.
 *
 * Blob = base64(12-byte IV || GCM ciphertext+tag), stored under the same names iOS uses
 * ("fariin_secret_key_v1.<uid>").
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "fariin_crypto_identity"
        const val ALIAS = "fariin_identity_wrap_v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    object Made {
        /** When the wrapping key was made (ms): this install's key store, gone with an uninstall. */
        fun at(): Long? = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCreationDate(ALIAS)?.time
        }.getOrNull()
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-auth requirement: the app must open messages in the background (push),
                // as the iOS item is readable after first unlock.
                .build()
        )
        return gen.generateKey()
    }

    override fun lookup(key: String): SecretStore.Lookup {
        val blob = prefs.getString(key, null) ?: return SecretStore.Lookup.NotFound
        return try {
            val raw = B64.decode(blob) ?: return dropStale(key)
            val wk = existingKey() ?: return dropStale(key)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wk, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            SecretStore.Lookup.Found(String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            dropStale(key)             // wrapped by a Keystore key that no longer exists
        } catch (_: UnrecoverableKeyException) {
            dropStale(key)
        } catch (_: Exception) {
            SecretStore.Lookup.Unreadable   // keystore busy / not unlocked: NOT "not there"
        }
    }

    private fun dropStale(key: String): SecretStore.Lookup {
        prefs.edit().remove(key).commit()
        return SecretStore.Lookup.NotFound
    }

    override fun set(key: String, value: String): Boolean = try {
        val wk = existingKey() ?: createKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wk)
        val iv = c.iv
        check(iv.size == IV_BYTES)
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, B64.encode(iv + ct)).commit()
    } catch (_: Exception) { false }

    override fun delete(key: String) { prefs.edit().remove(key).commit() }
}

/**
 * Other people's PUBLIC keys: the current one per person, plus a ring of earlier keys, newest
 * first, 8 at most (Crypto.swift:89-118, 158-169). Not secret. A message sealed with someone's
 * old key still opens with the ring.
 */
class PeerKeys(private val store: KeyValueStore) {
    private companion object {
        const val PUB = "crypto.pubKeys.v1."
        const val RING = "crypto.pubRing.v1."
        const val RING_MAX = 8
    }

    fun currentB64(uid: String): String? = store.get(PUB + uid)
    fun current(uid: String): ByteArray? = currentB64(uid)?.let { B64.decode(it) }
    fun allCurrent(): Map<String, ByteArray> =
        store.withPrefix(PUB).mapNotNull { (u, b) -> B64.decode(b)?.let { u to it } }.toMap()

    fun ringB64(uid: String): List<String> =
        store.get(RING + uid)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    fun earlier(uid: String): List<ByteArray> = ringB64(uid).mapNotNull { B64.decode(it) }

    /** Current key first, then the ring, duplicates dropped. What every open tries. */
    fun candidates(uid: String, current: ByteArray? = current(uid)): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        current?.let { out.add(it) }
        for (k in earlier(uid)) if (out.none { it.contentEquals(k) }) out.add(k)
        return out
    }

    /** `rememberKeys`: each new key is inserted at the FRONT in the order given, then cut to 8.
     *  Give it oldest first (the server history's own order), so the 8 kept are the newest. */
    fun remember(uid: String, b64s: List<String>) {
        if (uid.isEmpty() || b64s.isEmpty()) return
        val before = ringB64(uid)
        val list = before.toMutableList()
        for (b in b64s) if (b.isNotEmpty() && b !in list) list.add(0, b)
        if (list == before) return
        store.put(RING + uid, list.take(RING_MAX).joinToString(","))
    }

    /** `persistPubKey`: a replaced key moves into the ring. Returns true when the key changed. */
    fun adopt(uid: String, key: ByteArray): Boolean {
        val b64 = B64.encode(key)
        val old = currentB64(uid)
        if (old == b64) return false
        remember(uid, listOfNotNull(old, b64))
        store.put(PUB + uid, b64)
        return true
    }

    fun forget(uid: String) = store.remove(PUB + uid)
}
