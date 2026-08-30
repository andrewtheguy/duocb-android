package com.andrewtheguy.duocb

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the app keeps its three secrets (see [DuocbSecrets]). Values are small
 * strings keyed by name. Every method throws [SecretStoreException] on failure
 * — a dropped write here would silently lose the identity, so callers report it.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The Android counterpart of the iOS Keychain: values AES-GCM-encrypted under
 * a key that lives in the hardware-backed AndroidKeyStore (never exportable,
 * never leaves this device), stored in a private `SharedPreferences` file.
 * Writes are committed synchronously so a failure is observable. The Jetpack
 * `EncryptedSharedPreferences` did the same and is deprecated, so the few lines
 * it needs live here.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val parts = stored.split(':')
        if (parts.size != 2) throw SecretStoreException("stored secret \"$key\" is malformed")
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretStoreException("couldn't decrypt secret \"$key\": ${e.message}", e)
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        val encoded = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw SecretStoreException("couldn't encrypt secret \"$key\": ${e.message}", e)
        }
        if (!prefs.edit().putString(key, encoded).commit()) {
            throw SecretStoreException("couldn't write secret \"$key\"")
        }
    }

    @Synchronized
    override fun remove(key: String) {
        if (!prefs.edit().remove(key).commit()) {
            throw SecretStoreException("couldn't remove secret \"$key\"")
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "duocb-secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "duocb-secret-store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * The three things this app keeps in the secret store: the application private
 * key (`nsec`), this device's permanent name suffix, and its iroh transport
 * key. Everything else — the device name, this device's signed self-card, the
 * trusted peers' cards — lives in an ordinary JSON file ([ConfigStore]): cards
 * are public by design, so encryption would buy them nothing.
 *
 * An entry that cannot be read is reported through the return values below
 * rather than thrown, so a first launch and a broken store both land the user
 * on the setup flow instead of a crash; the reason is logged under `duocb`.
 */
class DuocbSecrets(private val store: SecretStore) {
    /** The application private key, or null before setup / when unreadable. */
    fun loadIdentity(): String? = read(IDENTITY)

    /**
     * Persist the key, replacing anything there. An empty value is treated as
     * a clear. Returns whether the value is now stored — setup refuses to
     * advance on false, so a secret never exists only in memory.
     */
    fun saveIdentity(nsec: String): Boolean {
        if (nsec.isEmpty()) {
            clearIdentity()
            return false
        }
        return write(IDENTITY, nsec)
    }

    fun clearIdentity() {
        runCatching { store.remove(IDENTITY) }
            .onFailure { Log.e(TAG, "cannot clear the identity key: ${it.message}") }
    }

    /**
     * The permanent 8-character suffix, minted and persisted on the first
     * call; null when it could not be stored. A suffix that only exists in
     * memory is worse than none — the next launch would mint another and this
     * device would rename itself behind the user's back — so the write must
     * succeed before the value counts.
     */
    fun loadOrCreateSuffix(): String? {
        read(SUFFIX)?.let { return it }
        val suffix = DuocbNative.generateSuffix()
        return if (write(SUFFIX, suffix)) suffix else null
    }

    /**
     * The iroh transport key every session in this process passes as
     * `iroh_secret`. The desktop mints it per launch because a config
     * directory can be copied between machines; an Android app's private
     * storage cannot (and `allowBackup` is off), so it is minted once and kept,
     * and the node id survives relaunches. If the store refuses the write, the
     * fresh key serves this process only.
     */
    fun loadOrCreateIrohSecret(): String {
        read(IROH_SECRET)?.let { return it }
        val secret = DuocbNative.generateIrohSecret()
        write(IROH_SECRET, secret)
        return secret
    }

    private fun read(key: String): String? = try {
        store.get(key)?.takeIf { it.isNotEmpty() }
    } catch (e: SecretStoreException) {
        Log.e(TAG, "cannot read $key: ${e.message}")
        null
    }

    private fun write(key: String, value: String): Boolean = try {
        store.put(key, value)
        true
    } catch (e: SecretStoreException) {
        Log.e(TAG, "cannot write $key: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "duocb"
        const val IDENTITY = "identity_secret"
        const val SUFFIX = "device_suffix"
        const val IROH_SECRET = "iroh_secret"
    }
}
