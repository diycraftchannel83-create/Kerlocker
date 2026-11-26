package com.example.crypto

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class RecoveryKeyBundle(
    val version: Int = 1,
    val saltB64: String,
    val ivB64: String,
    val wrappedKeyB64: String,
    val iterations: Int = 100_000
)

@Serializable
data class RecoveryKeyRing(
    val version: Int = 1,
    val bundles: List<RecoveryKeyBundle> = emptyList()
)

/**
 * Manages the portable 256-bit Vault Master Key and any unlocked legacy keys.
 * - Locally, it is sealed using the hardware-backed Android Keystore key and stored in DataStore.
 * - In the external destination folder (vault_recovery.key & vault_recovery_ring.json),
 *   keys are wrapped with PBKDF2-HMAC-SHA256 + AES-256-GCM from the user's 6-digit PIN,
 *   allowing full recovery across reinstalls, rebranding, or mixed key histories.
 */
class VaultKeyManager(
    private val keystoreMasterKeyProvider: MasterKeyProvider
) {
    companion object {
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_BYTES = 32
        private val RECOVERY_AAD = "BRANKAS_RECOVERY_V1".toByteArray(Charsets.UTF_8)
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val secureRandom = SecureRandom()

    @Volatile
    private var activeVaultKey: SecretKey? = null

    private val legacyVaultKeys = CopyOnWriteArrayList<SecretKey>()

    fun setActiveVaultKey(key: SecretKey?) {
        activeVaultKey = key
    }

    fun getActiveVaultKey(): SecretKey? = activeVaultKey

    fun addLegacyVaultKey(key: SecretKey) {
        val candidateRaw = key.encoded ?: return
        synchronized(legacyVaultKeys) {
            val activeRaw = activeVaultKey?.encoded
            if (activeRaw != null && activeRaw.contentEquals(candidateRaw)) {
                return
            }
            val exists = legacyVaultKeys.any { existing ->
                existing.encoded?.contentEquals(candidateRaw) == true
            }
            if (!exists) {
                legacyVaultKeys.add(SecretKeySpec(candidateRaw, AES_ALGORITHM))
            }
        }
        candidateRaw.fill(0)
    }

    fun addLegacyVaultKeys(keys: List<SecretKey>) {
        keys.forEach(::addLegacyVaultKey)
    }

    fun getLegacyVaultKeys(): List<SecretKey> = legacyVaultKeys.toList()

    fun getAllPortableKeys(): List<SecretKey> = buildList {
        activeVaultKey?.let(::add)
        addAll(legacyVaultKeys)
    }

    fun clearActiveVaultKey() {
        activeVaultKey = null
        legacyVaultKeys.clear()
    }

    fun generatePortableMasterKey(): SecretKey {
        val raw = ByteArray(KEY_BYTES)
        secureRandom.nextBytes(raw)
        return try {
            SecretKeySpec(raw, AES_ALGORITHM)
        } finally {
            raw.fill(0)
        }
    }

    fun sealWithKeystore(vaultKey: SecretKey): Pair<ByteArray, ByteArray> {
        val keystoreKey = keystoreMasterKeyProvider.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
        val raw = vaultKey.encoded
        return try {
            val encrypted = cipher.doFinal(raw)
            cipher.iv to encrypted
        } finally {
            raw.fill(0)
        }
    }

    fun unsealWithKeystore(iv: ByteArray, sealedKey: ByteArray): SecretKey {
        val keystoreKey = keystoreMasterKeyProvider.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val raw = cipher.doFinal(sealedKey)
        return try {
            SecretKeySpec(raw, AES_ALGORITHM)
        } finally {
            raw.fill(0)
        }
    }

    fun wrapWithPin(vaultKey: SecretKey, pin: String): String {
        val salt = PinCrypto.generateSalt()
        val kekBytes = PinCrypto.hashPin(pin, salt)
        val kek = SecretKeySpec(kekBytes, AES_ALGORITHM)
        val rawVaultKey = vaultKey.encoded
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, kek)
            cipher.updateAAD(RECOVERY_AAD)
            val wrapped = cipher.doFinal(rawVaultKey)
            val bundle = RecoveryKeyBundle(
                version = 1,
                saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
                ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                wrappedKeyB64 = Base64.encodeToString(wrapped, Base64.NO_WRAP)
            )
            json.encodeToString(bundle)
        } finally {
            kekBytes.fill(0)
            rawVaultKey.fill(0)
        }
    }

    fun unwrapWithPin(bundleJson: String, pin: String): SecretKey {
        val bundle = json.decodeFromString<RecoveryKeyBundle>(bundleJson)
        return unwrapBundleWithPin(bundle, pin)
    }

    fun unwrapBundleWithPin(bundle: RecoveryKeyBundle, pin: String): SecretKey {
        val salt = Base64.decode(bundle.saltB64, Base64.NO_WRAP)
        val iv = Base64.decode(bundle.ivB64, Base64.NO_WRAP)
        val wrapped = Base64.decode(bundle.wrappedKeyB64, Base64.NO_WRAP)
        val kekBytes = PinCrypto.hashPin(pin, salt)
        val kek = SecretKeySpec(kekBytes, AES_ALGORITHM)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(RECOVERY_AAD)
            val rawVaultKey = cipher.doFinal(wrapped)
            try {
                SecretKeySpec(rawVaultKey, AES_ALGORITHM)
            } finally {
                rawVaultKey.fill(0)
            }
        } finally {
            kekBytes.fill(0)
        }
    }

    fun parseRecoveryBundles(rawJson: String): List<RecoveryKeyBundle> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<RecoveryKeyRing>(trimmed).bundles.takeIf { it.isNotEmpty() }
        }.getOrNull()
            ?: runCatching {
                json.decodeFromString<List<RecoveryKeyBundle>>(trimmed)
            }.getOrNull()
            ?: runCatching {
                listOf(json.decodeFromString<RecoveryKeyBundle>(trimmed))
            }.getOrNull()
            ?: emptyList()
        return parsed.distinctBy { Triple(it.saltB64, it.ivB64, it.wrappedKeyB64) }
    }

    fun encodeRecoveryRing(bundles: List<RecoveryKeyBundle>): String {
        val unique = bundles.distinctBy { Triple(it.saltB64, it.ivB64, it.wrappedKeyB64) }
        return json.encodeToString(RecoveryKeyRing(version = 1, bundles = unique))
    }

    fun tryUnwrapAllWithPin(bundles: List<RecoveryKeyBundle>, pin: String): List<SecretKey> {
        val distinct = bundles.distinctBy { Triple(it.saltB64, it.ivB64, it.wrappedKeyB64) }
        val result = mutableListOf<SecretKey>()
        for (bundle in distinct) {
            val unlocked = runCatching { unwrapBundleWithPin(bundle, pin) }.getOrNull() ?: continue
            val raw = unlocked.encoded ?: continue
            if (result.none { it.encoded?.contentEquals(raw) == true }) {
                result.add(unlocked)
            }
            raw.fill(0)
        }
        return result
    }
}
