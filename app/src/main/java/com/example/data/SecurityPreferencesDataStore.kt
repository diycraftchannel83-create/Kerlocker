package com.example.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "brankasku_security_prefs")

@Serializable
data class SealedKeyRecord(
    val ivB64: String,
    val sealedB64: String
)

data class SecurityPreferencesState(
    val isSetupComplete: Boolean = false,
    val pinHash: ByteArray? = null,
    val pinSalt: ByteArray? = null,
    val sealedVaultKey: ByteArray? = null,
    val sealedVaultKeyIv: ByteArray? = null,
    val recoveryKeyBundleJson: String? = null,
    val extraSealedKeys: List<SealedKeyRecord> = emptyList(),
    val biometricEnabled: Boolean = false,
    val autoLockOnBackground: Boolean = true,
    val blockScreenshots: Boolean = true,
    val deleteOriginalOnImport: Boolean = true,
    val themePreference: VaultThemePreference = VaultThemePreference.DARK,
    val externalVaultFolderUri: String? = null,
    val externalVaultFolderName: String? = null,
    val sourceFolderUris: Set<String> = emptySet(),
    val gallerySortOrder: GallerySortOrder = GallerySortOrder.DATE_DESC,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

class SecurityPreferencesDataStore(private val context: Context) {

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private object Keys {
        val IS_SETUP_COMPLETE = booleanPreferencesKey("is_setup_complete")
        val PIN_HASH = stringPreferencesKey("pin_hash_b64")
        val PIN_SALT = stringPreferencesKey("pin_salt_b64")
        val SEALED_VAULT_KEY = stringPreferencesKey("sealed_vault_key_b64")
        val SEALED_VAULT_KEY_IV = stringPreferencesKey("sealed_vault_key_iv_b64")
        val RECOVERY_KEY_BUNDLE_JSON = stringPreferencesKey("recovery_key_bundle_json")
        val EXTRA_SEALED_KEYS = stringPreferencesKey("extra_sealed_keys_json")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val AUTO_LOCK_ON_BG = booleanPreferencesKey("auto_lock_on_bg")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val DELETE_ORIGINAL = booleanPreferencesKey("delete_original_on_import")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val EXTERNAL_VAULT_FOLDER = stringPreferencesKey("external_vault_folder_uri")
        val EXTERNAL_VAULT_FOLDER_NAME = stringPreferencesKey("external_vault_folder_name")
        val SOURCE_FOLDER_URIS = stringSetPreferencesKey("source_folder_uris")
        val GALLERY_SORT_ORDER = stringPreferencesKey("gallery_sort_order")
        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val LOCKOUT_UNTIL = longPreferencesKey("lockout_until_millis")
    }

    val preferencesFlow: Flow<SecurityPreferencesState> = context.securityDataStore.data.map { prefs ->
        val hashB64 = prefs[Keys.PIN_HASH]
        val saltB64 = prefs[Keys.PIN_SALT]
        val sealedKeyB64 = prefs[Keys.SEALED_VAULT_KEY]
        val sealedKeyIvB64 = prefs[Keys.SEALED_VAULT_KEY_IV]
        val themeStr = prefs[Keys.THEME_PREF] ?: VaultThemePreference.DARK.name
        val sortOrderStr = prefs[Keys.GALLERY_SORT_ORDER] ?: GallerySortOrder.DATE_DESC.name
        val extraKeys = prefs[Keys.EXTRA_SEALED_KEYS]?.let { raw ->
            runCatching { jsonCodec.decodeFromString<List<SealedKeyRecord>>(raw) }.getOrNull()
        }.orEmpty()
        SecurityPreferencesState(
            isSetupComplete = prefs[Keys.IS_SETUP_COMPLETE] ?: false,
            pinHash = hashB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            pinSalt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            sealedVaultKey = sealedKeyB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            sealedVaultKeyIv = sealedKeyIvB64?.let { Base64.decode(it, Base64.NO_WRAP) },
            recoveryKeyBundleJson = prefs[Keys.RECOVERY_KEY_BUNDLE_JSON],
            extraSealedKeys = extraKeys,
            biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: false,
            autoLockOnBackground = prefs[Keys.AUTO_LOCK_ON_BG] ?: true,
            blockScreenshots = prefs[Keys.BLOCK_SCREENSHOTS] ?: true,
            deleteOriginalOnImport = prefs[Keys.DELETE_ORIGINAL] ?: true,
            themePreference = runCatching { VaultThemePreference.valueOf(themeStr) }
                .getOrDefault(VaultThemePreference.DARK),
            externalVaultFolderUri = prefs[Keys.EXTERNAL_VAULT_FOLDER],
            externalVaultFolderName = prefs[Keys.EXTERNAL_VAULT_FOLDER_NAME],
            sourceFolderUris = prefs[Keys.SOURCE_FOLDER_URIS] ?: emptySet(),
            gallerySortOrder = runCatching { GallerySortOrder.valueOf(sortOrderStr) }
                .getOrDefault(GallerySortOrder.DATE_DESC),
            failedAttempts = prefs[Keys.FAILED_ATTEMPTS] ?: 0,
            lockoutUntilMillis = prefs[Keys.LOCKOUT_UNTIL] ?: 0L
        )
    }

    suspend fun savePinAndSetup(
        pinHash: ByteArray,
        pinSalt: ByteArray,
        sealedVaultKey: ByteArray,
        sealedVaultKeyIv: ByteArray,
        recoveryKeyBundleJson: String,
        biometricEnabled: Boolean
    ) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.PIN_HASH] = Base64.encodeToString(pinHash, Base64.NO_WRAP)
            prefs[Keys.PIN_SALT] = Base64.encodeToString(pinSalt, Base64.NO_WRAP)
            prefs[Keys.SEALED_VAULT_KEY] = Base64.encodeToString(sealedVaultKey, Base64.NO_WRAP)
            prefs[Keys.SEALED_VAULT_KEY_IV] = Base64.encodeToString(sealedVaultKeyIv, Base64.NO_WRAP)
            prefs[Keys.RECOVERY_KEY_BUNDLE_JSON] = recoveryKeyBundleJson
            prefs[Keys.BIOMETRIC_ENABLED] = biometricEnabled
            prefs[Keys.IS_SETUP_COMPLETE] = true
            prefs[Keys.FAILED_ATTEMPTS] = 0
            prefs[Keys.LOCKOUT_UNTIL] = 0L
        }
    }

    suspend fun saveSealedVaultKey(
        sealedVaultKey: ByteArray,
        sealedVaultKeyIv: ByteArray,
        recoveryKeyBundleJson: String? = null
    ) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.SEALED_VAULT_KEY] = Base64.encodeToString(sealedVaultKey, Base64.NO_WRAP)
            prefs[Keys.SEALED_VAULT_KEY_IV] = Base64.encodeToString(sealedVaultKeyIv, Base64.NO_WRAP)
            if (recoveryKeyBundleJson != null) {
                prefs[Keys.RECOVERY_KEY_BUNDLE_JSON] = recoveryKeyBundleJson
            }
        }
    }

    suspend fun addExtraSealedKey(iv: ByteArray, sealedKey: ByteArray) {
        val record = SealedKeyRecord(
            ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            sealedB64 = Base64.encodeToString(sealedKey, Base64.NO_WRAP)
        )
        context.securityDataStore.edit { prefs ->
            val current = prefs[Keys.EXTRA_SEALED_KEYS]?.let { raw ->
                runCatching { jsonCodec.decodeFromString<List<SealedKeyRecord>>(raw) }.getOrNull()
            }.orEmpty()
            if (current.none { it.ivB64 == record.ivB64 && it.sealedB64 == record.sealedB64 }) {
                prefs[Keys.EXTRA_SEALED_KEYS] = jsonCodec.encodeToString(current + record)
            }
        }
    }

    suspend fun updatePin(pinHash: ByteArray, pinSalt: ByteArray, recoveryKeyBundleJson: String? = null) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.PIN_HASH] = Base64.encodeToString(pinHash, Base64.NO_WRAP)
            prefs[Keys.PIN_SALT] = Base64.encodeToString(pinSalt, Base64.NO_WRAP)
            if (recoveryKeyBundleJson != null) {
                prefs[Keys.RECOVERY_KEY_BUNDLE_JSON] = recoveryKeyBundleJson
            }
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setAutoLockOnBackground(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.AUTO_LOCK_ON_BG] = enabled }
    }

    suspend fun setBlockScreenshots(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.BLOCK_SCREENSHOTS] = enabled }
    }

    suspend fun setDeleteOriginalOnImport(enabled: Boolean) {
        context.securityDataStore.edit { it[Keys.DELETE_ORIGINAL] = enabled }
    }

    suspend fun setExternalVaultFolder(uri: String?, displayName: String? = null) {
        context.securityDataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(Keys.EXTERNAL_VAULT_FOLDER)
                prefs.remove(Keys.EXTERNAL_VAULT_FOLDER_NAME)
            } else {
                prefs[Keys.EXTERNAL_VAULT_FOLDER] = uri
                if (!displayName.isNullOrBlank()) {
                    prefs[Keys.EXTERNAL_VAULT_FOLDER_NAME] = displayName
                } else {
                    prefs.remove(Keys.EXTERNAL_VAULT_FOLDER_NAME)
                }
            }
        }
    }

    suspend fun addSourceFolderUri(uri: String) {
        context.securityDataStore.edit { prefs ->
            val current = prefs[Keys.SOURCE_FOLDER_URIS] ?: emptySet()
            prefs[Keys.SOURCE_FOLDER_URIS] = current + uri
        }
    }

    suspend fun removeSourceFolderUri(uri: String) {
        context.securityDataStore.edit { prefs ->
            val current = prefs[Keys.SOURCE_FOLDER_URIS] ?: emptySet()
            prefs[Keys.SOURCE_FOLDER_URIS] = current - uri
        }
    }

    suspend fun setThemePreference(theme: VaultThemePreference) {
        context.securityDataStore.edit { it[Keys.THEME_PREF] = theme.name }
    }

    suspend fun setGallerySortOrder(order: GallerySortOrder) {
        context.securityDataStore.edit { it[Keys.GALLERY_SORT_ORDER] = order.name }
    }

    suspend fun recordFailedAttempt(attempts: Int, lockoutUntilMillis: Long) {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.FAILED_ATTEMPTS] = attempts
            prefs[Keys.LOCKOUT_UNTIL] = lockoutUntilMillis
        }
    }

    suspend fun resetFailedAttempts() {
        context.securityDataStore.edit { prefs ->
            prefs[Keys.FAILED_ATTEMPTS] = 0
            prefs[Keys.LOCKOUT_UNTIL] = 0L
        }
    }
}
