package com.osint.unified.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The passphrase the case database is encrypted with.
 *
 * Thirty-two random bytes rendered as hexadecimal, so the passphrase is
 * sixty-four printable characters. That matters: the same secret has to be
 * handed to Room as bytes and written into an ATTACH statement as text
 * during the one-off migration of an existing database, and raw bytes are
 * interpreted differently in those two places. Hex avoids the question.
 *
 * It is generated once, and never stored in the clear:
 * they are sealed with an AES key that lives in the device's hardware
 * keystore and cannot be exported from it, and the sealed form sits in
 * ordinary preferences. Copying the app's data directory off the device
 * therefore yields an encrypted database and a sealed key that only that
 * device can open.
 *
 * Deliberately not tied to the screen lock. A key that needs
 * authentication cannot be used by the background watch check, and a
 * feature that silently stops working when the phone is in a pocket is
 * worse than one that does not claim more than it does. The optional app
 * lock is a separate thing and sits in front of the UI.
 */
object DatabaseKey {

    private const val PREFS = "db-key"
    private const val SEALED = "sealed"
    private const val ALIAS = "osint-unified-db"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /**
     * The passphrase as sixty-four hexadecimal characters, created on
     * first use.
     *
     * Returns null when the keystore refuses to play — an emulator image
     * without one, a device whose keystore has been reset by a lock-screen
     * change. The caller then runs unencrypted rather than refusing to
     * start, because an investigation tool that will not open is worse
     * than one whose database is readable by a forensic image of a device
     * the owner is holding.
     */
    fun passphrase(context: Context): ByteArray? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(SEALED, null)
        if (stored != null) return@runCatching unseal(stored)

        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val fresh = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        // A key that was not written down is not a key.
        //
        // commit() returns false when the preferences file cannot be
        // written — a full disk, a broken profile — and that result
        // was discarded. The consequences were not recoverable: the
        // migration copies every case into a database sealed with
        // this key, renames the plaintext file aside, opens the
        // encrypted one successfully, and then deletes the plaintext
        // backup. On the next launch there is no stored key, a brand
        // new one is generated, the encrypted file will not open with
        // it, there is no backup left, and the app tells the owner
        // the keystore lost their key. Every case, gone, from one
        // ignored boolean.
        //
        // Returning null here means the app runs unencrypted this
        // session — the same path as a device with no usable keystore
        // — and the plaintext database it keeps using is the one
        // thing that still has the data in it.
        if (!prefs.edit().putString(SEALED, seal(fresh)).commit()) {
            return@runCatching null
        }
        fresh
    }.getOrNull()

    /** True once a passphrase exists, so the UI can say whether the
     *  database on this device is actually encrypted. */
    fun exists(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(SEALED)

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun seal(secret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.doFinal(secret)
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    private fun unseal(stored: String): ByteArray {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES)
            )
        }
        return cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES)
    }
}
