package com.fariin.app.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sealing for what this phone keeps on disk about chats: drafts, the newest messages per chat, the
 * chat list, the send queue. iOS keeps drafts sealed with a key in the Keychain (this device only,
 * readable after the first unlock) and the caches under file protection. Here ONE Android Keystore
 * AES-256-GCM key seals all of them: it never leaves the phone and cannot be restored elsewhere, so
 * a copied file is useless. Losing the key (a wipe, a new phone) only means the files are dropped
 * and filled again from the server.
 *
 * Format: 12-byte IV, then the GCM ciphertext with its tag.
 */
object AtRest {
    private const val ALIAS = "fariin_local_at_rest_v1"
    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                g.generateKey()
            }
            cached = k
            return k
        }
    }

    fun seal(plain: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.iv + c.doFinal(plain)
    } catch (_: Exception) { null }

    fun open(sealed: ByteArray): ByteArray? = try {
        if (sealed.size < 13) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            c.doFinal(sealed, 12, sealed.size - 12)
        }
    } catch (_: Exception) { null }

    /** Sign-out: the next account starts with a fresh key, so nothing sealed before can be read. */
    fun forgetKey() {
        synchronized(this) {
            cached = null
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        }
    }
}
