package com.example.data

import android.content.IntentSender
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.net.Uri
import com.example.crypto.MasterKeyProvider
import com.example.crypto.PinCrypto
import com.example.crypto.VaultKeyManager
import com.example.ui.components.ImageAdjustmentParams
import com.example.ui.components.ImageAdjustmentProcessor
import com.example.ui.theme.AlbumAccentPalette
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

data class FullRecoveryOutcome(
    val restoredCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val lockedSkippedCount: Int = 0
)

data class PendingSourceDeleteItem(
    val fileName: String,
    val mediaStoreUri: Uri?,
    val recoverableIntentSender: IntentSender?
)

data class BatchImportResult(
    val importedCount: Int,
    val deletedCount: Int,
    val sourceDeleteFailedCount: Int,
    val failedCount: Int,
    val firstFailureReason: String?,
    val pendingSourceDeletes: List<PendingSourceDeleteItem> = emptyList()
)

data class BatchExportResult(
    val exportedCount: Int,
    val removedFromVaultCount: Int,
    val failedCount: Int,
    val firstFailureReason: String? = null
)

class VaultRepository(
    private val dao: VaultDao,
    private val storageManager: VaultStorageManager,
    private val securityPrefs: SecurityPreferencesDataStore,
    private val masterKeyProvider: MasterKeyProvider,
    private val vaultKeyManager: VaultKeyManager
) {
    val securityPreferencesFlow: Flow<SecurityPreferencesState> = securityPrefs.preferencesFlow
    val albumsWithStatsFlow: Flow<List<AlbumWithStats>> = dao.observeAlbumsWithStats()
    val allFilesFlow: Flow<List<VaultFileEntity>> = dao.observeAllFiles()

    fun observeAlbum(albumId: String): Flow<AlbumEntity?> = dao.observeAlbum(albumId)

    suspend fun getRecoveryTargetAlbumId(): String {
        ensureDefaultAlbums()
        return dao.getRecoveryTargetAlbum()?.id
            ?: dao.getAllAlbumsSync().firstOrNull()?.id
            ?: throw IllegalStateException("Album brankas tidak tersedia")
    }

    suspend fun getVaultOriginalNames(): List<String> =
        dao.getAllFilesForMirror().map { it.originalName }


    fun observeFilesInAlbum(albumId: String): Flow<List<VaultFileEntity>> =
        dao.observeFilesInAlbum(albumId)

    fun observeFileById(fileId: String): Flow<VaultFileEntity?> =
        dao.observeFileById(fileId)

    fun isHardwareKeystoreBacked(): Boolean = masterKeyProvider.isHardwareBacked()

    fun getVaultDirectoryDisplay(): String = storageManager.getVaultInternalPathDisplay()

    fun getSourceFolderDisplayName(folderUri: Uri): String = storageManager.getSourceFolderDisplayName(folderUri)

    fun listExternalEncryptedFiles(folderUri: Uri) = storageManager.listExternalEncryptedFiles(folderUri)

    suspend fun decryptExternalEncryptedFileToEphemeralCache(
        sourceUri: Uri,
        displayName: String? = null,
        overrideKey: SecretKey? = null
    ): File = storageManager.decryptExternalEncryptedFileToEphemeralCache(sourceUri, displayName, overrideKey)

    suspend fun decryptExternalFolderFileToEphemeralCache(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): File = storageManager.decryptExternalFolderFileToEphemeralCache(folderUri, backupFileName, overrideKey)

    suspend fun decryptExternalFileToBytes(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): ByteArray = storageManager.decryptExternalFileToBytes(folderUri, backupFileName, overrideKey)

    fun openExternalEncryptedMediaDataSource(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): MediaDataSource = storageManager.openExternalEncryptedMediaDataSource(folderUri, backupFileName, overrideKey)

    suspend fun loadExternalThumbnail(
        folderUri: Uri,
        thumbFileName: String,
        overrideKey: SecretKey? = null
    ): Bitmap? = storageManager.loadExternalThumbnail(folderUri, thumbFileName, overrideKey)

    suspend fun setupInitialPin(pin: String, enableBiometric: Boolean) {
        val salt = PinCrypto.generateSalt()
        val hash = PinCrypto.hashPin(pin, salt)
        val vaultKey = vaultKeyManager.generatePortableMasterKey()
        val (iv, sealed) = vaultKeyManager.sealWithKeystore(vaultKey)
        val recoveryBundle = vaultKeyManager.wrapWithPin(vaultKey, pin)
        vaultKeyManager.setActiveVaultKey(vaultKey)
        securityPrefs.savePinAndSetup(hash, salt, sealed, iv, recoveryBundle, enableBiometric)
        ensureDefaultAlbums()
    }

    private fun loadExtraSealedKeysFromState(state: SecurityPreferencesState) {
        for (record in state.extraSealedKeys) {
            val loaded = runCatching {
                val iv = android.util.Base64.decode(record.ivB64, android.util.Base64.NO_WRAP)
                val sealed = android.util.Base64.decode(record.sealedB64, android.util.Base64.NO_WRAP)
                vaultKeyManager.unsealWithKeystore(iv, sealed)
            }.getOrNull()
            if (loaded != null) {
                vaultKeyManager.addLegacyVaultKey(loaded)
            }
        }
    }

    private suspend fun persistExtraRecoveredKeys(keys: List<SecretKey>) {
        if (keys.isEmpty()) return
        vaultKeyManager.addLegacyVaultKeys(keys)
        val state = securityPrefs.preferencesFlow.first()
        val knownRaw = mutableListOf<ByteArray>()
        vaultKeyManager.getActiveVaultKey()?.encoded?.let { knownRaw.add(it) }
        for (record in state.extraSealedKeys) {
            runCatching {
                val iv = android.util.Base64.decode(record.ivB64, android.util.Base64.NO_WRAP)
                val sealed = android.util.Base64.decode(record.sealedB64, android.util.Base64.NO_WRAP)
                vaultKeyManager.unsealWithKeystore(iv, sealed)
            }.getOrNull()?.encoded?.let { knownRaw.add(it) }
        }
        try {
            for (key in keys) {
                val raw = key.encoded ?: continue
                val alreadyPersisted = knownRaw.any { it.contentEquals(raw) }
                if (!alreadyPersisted) {
                    val (iv, sealed) = vaultKeyManager.sealWithKeystore(key)
                    securityPrefs.addExtraSealedKey(iv, sealed)
                    knownRaw.add(raw)
                } else {
                    raw.fill(0)
                }
            }
        } finally {
            knownRaw.forEach { it.fill(0) }
        }
    }

    fun getActiveVaultKey(): SecretKey? = vaultKeyManager.getActiveVaultKey()

    suspend fun ensureActiveVaultKeyLoaded(pinIfAvailable: String? = null) {
        val state = securityPrefs.preferencesFlow.first()
        if (vaultKeyManager.getActiveVaultKey() != null) {
            loadExtraSealedKeysFromState(state)
            return
        }
        if (state.sealedVaultKey != null && state.sealedVaultKeyIv != null) {
            val loaded = runCatching {
                vaultKeyManager.unsealWithKeystore(state.sealedVaultKeyIv, state.sealedVaultKey)
            }.getOrNull()
            if (loaded != null) {
                vaultKeyManager.setActiveVaultKey(loaded)
                loadExtraSealedKeysFromState(state)
                if (state.recoveryKeyBundleJson == null && pinIfAvailable != null) {
                    val bundle = vaultKeyManager.wrapWithPin(loaded, pinIfAvailable)
                    securityPrefs.saveSealedVaultKey(state.sealedVaultKey, state.sealedVaultKeyIv, bundle)
                }
                return
            }
        }
        // Upgrade path for legacy v1 installs without a sealed portable vault key yet
        val freshKey = vaultKeyManager.generatePortableMasterKey()
        val (iv, sealed) = vaultKeyManager.sealWithKeystore(freshKey)
        val bundle = pinIfAvailable?.let { vaultKeyManager.wrapWithPin(freshKey, it) }
        vaultKeyManager.setActiveVaultKey(freshKey)
        loadExtraSealedKeysFromState(state)
        securityPrefs.saveSealedVaultKey(sealed, iv, bundle)
    }

    suspend fun verifyPin(pin: String): Boolean {
        val state = securityPrefs.preferencesFlow.first()
        val expectedHash = state.pinHash ?: return false
        val salt = state.pinSalt ?: return false
        val matches = PinCrypto.verifyPin(pin, salt, expectedHash)
        if (matches) {
            securityPrefs.resetFailedAttempts()
            ensureActiveVaultKeyLoaded(pinIfAvailable = pin)
        } else {
            val newAttempts = state.failedAttempts + 1
            val lockoutUntil = if (newAttempts >= 5) {
                System.currentTimeMillis() + 30_000L
            } else {
                0L
            }
            securityPrefs.recordFailedAttempt(newAttempts, lockoutUntil)
        }
        return matches
    }

    suspend fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        ensureActiveVaultKeyLoaded(pinIfAvailable = currentPin)
        val activeKey = vaultKeyManager.getActiveVaultKey()
            ?: vaultKeyManager.generatePortableMasterKey().also {
                val (iv, sealed) = vaultKeyManager.sealWithKeystore(it)
                securityPrefs.saveSealedVaultKey(sealed, iv)
                vaultKeyManager.setActiveVaultKey(it)
            }
        val newSalt = PinCrypto.generateSalt()
        val newHash = PinCrypto.hashPin(newPin, newSalt)
        val newBundle = vaultKeyManager.wrapWithPin(activeKey, newPin)
        securityPrefs.updatePin(newHash, newSalt, newBundle)
        val state = securityPrefs.preferencesFlow.first()
        state.externalVaultFolderUri?.let { uriStr ->
            withContext(Dispatchers.IO) {
                storageManager.writeRecoveryKeyToFolder(Uri.parse(uriStr), newBundle, overwritePrimary = true)
            }
        }
        return true
    }

    suspend fun ensureDefaultAlbums() {
        if (dao.getAlbumCount() == 0) {
            val now = System.currentTimeMillis()
            val defaults = listOf(
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Foto & Video Pribadi",
                    iconKey = "photo",
                    colorHex = AlbumAccentPalette[0],
                    isPinned = true,
                    createdAt = now,
                    sortOrder = 0
                ),
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Dokumen Penting",
                    iconKey = "document",
                    colorHex = AlbumAccentPalette[1],
                    isPinned = false,
                    createdAt = now + 1,
                    sortOrder = 1
                ),
                AlbumEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Identitas & Keuangan",
                    iconKey = "badge",
                    colorHex = AlbumAccentPalette[2],
                    isPinned = false,
                    createdAt = now + 2,
                    sortOrder = 2
                )
            )
            dao.insertAlbums(defaults)
        }
    }

    private suspend fun syncManifestToExternalFolderIfConnected() {
        val state = securityPrefs.preferencesFlow.first()
        val folderStr = state.externalVaultFolderUri ?: return
        val folderUri = Uri.parse(folderStr)
        withContext(Dispatchers.IO) {
            val albums = dao.getAllAlbumsSync()
            val files = dao.getAllFilesForMirror()
            state.recoveryKeyBundleJson?.let {
                storageManager.writeRecoveryKeyToFolder(folderUri, it, overwritePrimary = false)
            }
            storageManager.writeEncryptedManifestToFolder(
                folderUri = folderUri,
                albums = albums,
                files = files,
                allowOverwriteLockedForeignManifest = false
            )
        }
    }

    suspend fun createAlbum(
        name: String,
        iconKey: String,
        colorHex: Long,
        isPinned: Boolean
    ): AlbumEntity {
        val album = AlbumEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            iconKey = iconKey,
            colorHex = colorHex,
            isPinned = isPinned,
            createdAt = System.currentTimeMillis(),
            sortOrder = dao.getAlbumCount()
        )
        dao.insertAlbum(album)
        syncManifestToExternalFolderIfConnected()
        return album
    }

    suspend fun updateAlbum(album: AlbumEntity) {
        dao.updateAlbum(album)
        syncManifestToExternalFolderIfConnected()
    }

    suspend fun deleteAlbumAndFiles(albumId: String) {
        val files = dao.getFilesInAlbumSync(albumId)
        files.forEach { storageManager.deleteEncryptedAssets(it) }
        dao.deleteAlbumById(albumId)
        val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let(Uri::parse)
        withContext(Dispatchers.IO) {
            storageManager.removeMirroredFilesFromFolder(folderUri, files)
        }
        syncManifestToExternalFolderIfConnected()
    }

    suspend fun deleteAlbumMovingFilesTo(albumId: String, targetAlbumId: String) {
        val files = dao.getFilesInAlbumSync(albumId)
        if (files.isNotEmpty()) {
            dao.moveFilesToAlbum(files.map { it.id }, targetAlbumId)
        }
        dao.deleteAlbumById(albumId)
        syncManifestToExternalFolderIfConnected()
    }

    suspend fun inspectExternalVaultFolder(
        folderUri: Uri,
        recoveryPin: String? = null
    ): ExternalVaultInspectionResult {
        val existingIds = dao.getAllFilesForMirror().filter { storageManager.hasLocalEncryptedBlob(it) }.map { it.id }.toSet()
        val result = storageManager.inspectExternalVaultFolder(folderUri, existingIds, recoveryPin)
        if (result.unlockedMasterKeys.isNotEmpty() && vaultKeyManager.getActiveVaultKey() != null) {
            persistExtraRecoveredKeys(result.unlockedMasterKeys)
        }
        return result
    }

    suspend fun importRecoveryKeyFileAndInspect(
        folderUri: Uri,
        recoveryFileUri: Uri,
        pin: String
    ): ExternalVaultInspectionResult {
        val bundles = withContext(Dispatchers.IO) {
            storageManager.readBundlesFromUri(recoveryFileUri)
        }
        if (bundles.isEmpty()) {
            throw IllegalArgumentException("File kunci pemulihan tidak valid atau kosong")
        }
        val existingIds = dao.getAllFilesForMirror().map { it.id }.toSet()
        val result = storageManager.inspectExternalVaultFolder(
            folderUri = folderUri,
            existingLocalIds = existingIds,
            recoveryPin = pin,
            extraBundles = bundles
        )
        if (result.unlockedMasterKeys.isNotEmpty()) {
            persistExtraRecoveredKeys(result.unlockedMasterKeys)
            val ringJson = vaultKeyManager.encodeRecoveryRing(bundles)
            withContext(Dispatchers.IO) {
                storageManager.writeRecoveryKeyToFolder(folderUri, ringJson, overwritePrimary = false)
            }
        }
        return result
    }

    /**
     * Restores the entire app state (Vault Master Key, PIN, Albums, and Encrypted Files)
     * from a previously encrypted destination folder on a fresh install.
     */
    suspend fun restoreAppFromExternalFolder(
        folderUri: Uri,
        pin: String,
        enableBiometric: Boolean = false
    ): FullRecoveryOutcome {
        val inspection = inspectExternalVaultFolder(folderUri, recoveryPin = pin)
        if (inspection.wrongRecoveryPin || (inspection.hasRecoveryKey && inspection.recoveredMasterKey == null)) {
            throw SecurityException("PIN pemulihan tidak cocok untuk folder terenkripsi ini")
        }
        val masterKey = inspection.recoveredMasterKey
            ?: throw IllegalStateException("Kunci pemulihan (vault_recovery.key) tidak ditemukan di folder ini")

        val salt = PinCrypto.generateSalt()
        val hash = PinCrypto.hashPin(pin, salt)
        val (iv, sealed) = vaultKeyManager.sealWithKeystore(masterKey)
        val recoveryBundle = vaultKeyManager.wrapWithPin(masterKey, pin)
        vaultKeyManager.setActiveVaultKey(masterKey)
        securityPrefs.savePinAndSetup(hash, salt, sealed, iv, recoveryBundle, enableBiometric)
        persistExtraRecoveredKeys(inspection.unlockedMasterKeys)

        val folderName = storageManager.getFolderDisplayName(folderUri)
        securityPrefs.setExternalVaultFolder(folderUri.toString(), folderName)

        withContext(Dispatchers.IO) {
            storageManager.writeRecoveryKeyToFolder(folderUri, recoveryBundle, overwritePrimary = false)
        }

        return restoreAllFromInspection(folderUri, inspection, overrideKey = masterKey)
    }

    suspend fun restoreAllFromInspection(
        folderUri: Uri,
        inspection: ExternalVaultInspectionResult,
        overrideKey: SecretKey? = null
    ): FullRecoveryOutcome {
        ensureDefaultAlbums()
        if (inspection.manifestAlbums.isNotEmpty()) {
            val existingAlbumIds = dao.getAllAlbumsSync().map { it.id }.toSet()
            val newAlbums = inspection.manifestAlbums.filter { it.id !in existingAlbumIds }.map {
                AlbumEntity(
                    id = it.id,
                    name = it.name,
                    iconKey = it.iconKey,
                    colorHex = it.colorHex,
                    isPinned = it.isPinned,
                    createdAt = it.createdAt,
                    sortOrder = it.sortOrder
                )
            }
            if (newAlbums.isNotEmpty()) dao.insertAlbums(newAlbums)
        }
        val fallbackAlbum = dao.getRecoveryTargetAlbum()
            ?: throw IllegalStateException("Album tujuan pemulihan tidak tersedia")

        val restored = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val lockedSkipped = AtomicInteger(0)
        val semaphore = Semaphore(3)
        val dbMutex = Mutex()

        coroutineScope {
            inspection.entries.map { entry ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val local = dao.getFileById(entry.fileId)
                        if (local != null && storageManager.hasLocalEncryptedBlob(local)) {
                            skipped.incrementAndGet()
                            return@withPermit
                        }
                        if (!entry.canDecrypt) {
                            lockedSkipped.incrementAndGet()
                            return@withPermit
                        }
                        val targetAlbumId = local?.albumId?.takeIf { dao.getAlbumById(it) != null }
                            ?: entry.albumId?.takeIf { dao.getAlbumById(it) != null }
                            ?: fallbackAlbum.id
                        val metadataForRepair = local?.let { buildRecoveryEntryFromLocal(it, entry.backupFileName) }
                            ?: entry
                        val outcome = runCatching {
                            val recovered = storageManager.recoverExternalEncryptedFile(
                                folderUri = folderUri,
                                backupName = entry.backupFileName,
                                albumId = targetAlbumId,
                                manifestEntry = metadataForRepair,
                                overrideKey = overrideKey,
                                reencryptForCurrentVault = true
                            )
                            dbMutex.withLock {
                                if (local != null) {
                                    dao.updateFile(recovered)
                                    restored.incrementAndGet()
                                } else {
                                    val existingByName = if (!entry.hasManifestMetadata) dao.findFileByOriginalName(recovered.originalName) else null
                                    if (existingByName != null) {
                                        storageManager.deleteEncryptedAssets(recovered)
                                        skipped.incrementAndGet()
                                    } else {
                                        dao.insertFile(recovered)
                                        restored.incrementAndGet()
                                    }
                                }
                            }
                        }
                        if (outcome.isFailure) failed.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        if (restored.get() > 0) syncManifestToExternalFolderIfConnected()
        return FullRecoveryOutcome(
            restoredCount = restored.get(),
            skippedExistingCount = skipped.get(),
            failedCount = failed.get(),
            lockedSkippedCount = lockedSkipped.get()
        )
    }

    private suspend fun existingLocalForRepair(
        folderUri: Uri,
        entry: ExternalVaultEntry?,
        backupName: String
    ): VaultFileEntity? {
        val id = entry?.fileId ?: backupName.removeSuffix(".vault")
        val existing = dao.getFileById(id) ?: return null
        if (storageManager.hasLocalEncryptedBlob(existing)) return null
        return existing
    }

    private fun buildRecoveryEntryFromLocal(
        local: VaultFileEntity,
        backupName: String
    ): ExternalVaultEntry = ExternalVaultEntry(
        backupFileName = backupName,
        fileId = local.id,
        displayName = local.originalName,
        mimeType = local.mimeType,
        category = local.category,
        sizeBytes = local.originalSizeBytes,
        albumId = local.albumId,
        albumName = null,
        createdAt = local.createdAt,
        isFavorite = local.isFavorite,
        thumbFileName = null,
        hasManifestMetadata = true,
        alreadyInLocalVault = true,
        canDecrypt = true
    )

    suspend fun restoreExternalEncryptedFile(
        folderUri: Uri,
        backupName: String,
        entry: ExternalVaultEntry? = null,
        overrideKey: SecretKey? = null
    ): VaultFileEntity {
        ensureDefaultAlbums()
        if (entry != null) {
            val existingLocal = dao.getFileById(entry.fileId)
            if (existingLocal != null && storageManager.hasLocalEncryptedBlob(existingLocal)) return existingLocal
            entry.albumId?.let { albumId ->
                if (dao.getAlbumById(albumId) == null) {
                    // Try restoring album metadata from inspection if needed
                    val inspected = inspectExternalVaultFolder(folderUri)
                    inspected.manifestAlbums.firstOrNull { it.id == albumId }?.let { albumMeta ->
                        dao.insertAlbum(
                            AlbumEntity(
                                id = albumMeta.id,
                                name = albumMeta.name,
                                iconKey = albumMeta.iconKey,
                                colorHex = albumMeta.colorHex,
                                isPinned = albumMeta.isPinned,
                                createdAt = albumMeta.createdAt,
                                sortOrder = albumMeta.sortOrder
                            )
                        )
                    }
                }
            }
        }
        val targetAlbumId = entry?.albumId?.takeIf { dao.getAlbumById(it) != null }
            ?: dao.getRecoveryTargetAlbum()?.id
            ?: throw IllegalStateException("Album tujuan pemulihan tidak tersedia")

        val existingLocalForRepair = existingLocalForRepair(folderUri, entry, backupName)
        val localEntryForRepair = existingLocalForRepair?.let { buildRecoveryEntryFromLocal(it, backupName) }
        val recovered = storageManager.recoverExternalEncryptedFile(
            folderUri = folderUri,
            backupName = backupName,
            albumId = existingLocalForRepair?.albumId?.takeIf { dao.getAlbumById(it) != null } ?: targetAlbumId,
            manifestEntry = localEntryForRepair ?: entry,
            overrideKey = overrideKey,
            reencryptForCurrentVault = true
        )
        if (existingLocalForRepair != null) {
            dao.updateFile(recovered)
            syncManifestToExternalFolderIfConnected()
            return recovered
        }
        val existing = dao.getFileById(recovered.id)
            ?: if (entry == null || !entry.hasManifestMetadata) dao.findFileByOriginalName(recovered.originalName) else null
        if (existing != null) {
            storageManager.deleteEncryptedAssets(recovered)
            return existing
        }
        try {
            dao.insertFile(recovered)
        } catch (error: Throwable) {
            storageManager.deleteEncryptedAssets(recovered)
            throw error
        }
        syncManifestToExternalFolderIfConnected()
        return recovered
    }

    suspend fun importFilesBatch(
        uris: List<Uri>,
        albumId: String,
        deleteOriginal: Boolean,
        overrideKey: SecretKey? = null,
        onProgress: (processed: Int, total: Int) -> Unit
    ): BatchImportResult {
        if (uris.isEmpty()) {
            return BatchImportResult(0, 0, 0, 0, null)
        }
        val state = securityPrefs.preferencesFlow.first()
        val folderUri = state.externalVaultFolderUri?.let { Uri.parse(it) }
        val sourceFolderUris = state.sourceFolderUris
        val mirrorSession = withContext(Dispatchers.IO) {
            storageManager.prepareExternalMirrorSession(folderUri, state.recoveryKeyBundleJson)
        }

        val semaphore = Semaphore(2)
        val dbAndSessionMutex = Mutex()
        val processedCounter = AtomicInteger(0)

        val outcomes = coroutineScope {
            uris.mapIndexed { index, uri ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val result = runCatching {
                            val outcome = storageManager.importUri(uri, albumId, deleteOriginal, overrideKey)
                            dbAndSessionMutex.withLock {
                                try {
                                    dao.insertFile(outcome.entity)
                                } catch (error: Throwable) {
                                    storageManager.deleteEncryptedAssets(outcome.entity)
                                    throw error
                                }
                                if (mirrorSession != null) {
                                    storageManager.mirrorEncryptedFileToSession(mirrorSession, outcome.entity)
                                }
                            }
                            if (deleteOriginal) {
                                val attempt = storageManager.deleteOriginalAfterImportDetailed(
                                    uri = uri,
                                    resolvedMediaUri = outcome.resolvedMediaUri,
                                    sourceFolderUris = sourceFolderUris,
                                    displayName = outcome.entity.originalName,
                                    expectedSizeBytes = outcome.entity.originalSizeBytes,
                                    sourceRelativePath = outcome.sourceRelativePath,
                                    sourceFilePath = outcome.sourceFilePath
                                )
                                outcome.copy(originalDeletedDirectly = attempt.deleted) to attempt
                            } else {
                                outcome to SourceDeleteAttempt(deleted = false)
                            }
                        }
                        val done = processedCounter.incrementAndGet()
                        onProgress(done, uris.size)
                        index to result
                    }
                }
            }.awaitAll()
        }

        var importedCount = 0
        var deletedCount = 0
        var sourceDeleteFailedCount = 0
        var failedCount = 0
        var firstFailure: String? = null
        val pendingDeletes = mutableListOf<PendingSourceDeleteItem>()

        outcomes.sortedBy { it.first }.forEach { (idx, res) ->
            res.onSuccess { (outcome, deleteAttempt) ->
                importedCount++
                if (outcome.originalDeletedDirectly) {
                    deletedCount++
                } else if (deleteOriginal) {
                    sourceDeleteFailedCount++
                    pendingDeletes.add(
                        PendingSourceDeleteItem(
                            fileName = outcome.entity.originalName,
                            mediaStoreUri = deleteAttempt.mediaStoreUri,
                            recoverableIntentSender = deleteAttempt.recoverableIntentSender
                        )
                    )
                }
            }.onFailure { err ->
                failedCount++
                if (firstFailure == null) {
                    firstFailure = "File ${idx + 1}: ${err.javaClass.simpleName}"
                }
            }
        }

        if (importedCount > 0 && folderUri != null) {
            withContext(Dispatchers.IO) {
                storageManager.writeEncryptedManifestToFolder(
                    folderUri,
                    dao.getAllAlbumsSync(),
                    dao.getAllFilesForMirror()
                )
            }
        }

        return BatchImportResult(
            importedCount = importedCount,
            deletedCount = deletedCount,
            sourceDeleteFailedCount = sourceDeleteFailedCount,
            failedCount = failedCount,
            firstFailureReason = firstFailure,
            pendingSourceDeletes = pendingDeletes
        )
    }

    suspend fun importFile(
        uri: Uri,
        albumId: String,
        deleteOriginal: Boolean
    ): ImportedFileOutcome {
        val outcome = storageManager.importUri(uri, albumId, deleteOriginal)
        try {
            dao.insertFile(outcome.entity)
        } catch (error: Throwable) {
            storageManager.deleteEncryptedAssets(outcome.entity)
            throw error
        }
        val state = securityPrefs.preferencesFlow.first()
        val folderUri = state.externalVaultFolderUri?.let { Uri.parse(it) }
        if (folderUri != null) {
            withContext(Dispatchers.IO) {
                storageManager.mirrorEncryptedFileToFolder(folderUri, outcome.entity)
                state.recoveryKeyBundleJson?.let {
                    storageManager.writeRecoveryKeyToFolder(folderUri, it)
                }
                storageManager.writeEncryptedManifestToFolder(
                    folderUri,
                    dao.getAllAlbumsSync(),
                    dao.getAllFilesForMirror()
                )
            }
        }
        if (!deleteOriginal) return outcome

        val deletedDirectly = storageManager.deleteOriginalAfterImport(
            uri = uri,
            resolvedMediaUri = outcome.resolvedMediaUri,
            sourceFolderUris = state.sourceFolderUris,
            displayName = outcome.entity.originalName,
            expectedSizeBytes = outcome.entity.originalSizeBytes,
            sourceRelativePath = outcome.sourceRelativePath,
            sourceFilePath = outcome.sourceFilePath
        )
        return outcome.copy(originalDeletedDirectly = deletedDirectly)
    }

    suspend fun moveFilesToAlbum(fileIds: List<String>, targetAlbumId: String): String {
        val targetAlbum = dao.getAlbumById(targetAlbumId)
            ?: throw IllegalArgumentException("Album tujuan tidak ditemukan")
        if (fileIds.isNotEmpty()) {
            dao.moveFilesToAlbum(fileIds, targetAlbumId)
            syncManifestToExternalFolderIfConnected()
        }
        return targetAlbum.name
    }

    suspend fun createAlbumAndMoveFiles(
        fileIds: List<String>,
        name: String,
        iconKey: String,
        colorHex: Long,
        isPinned: Boolean
    ): AlbumEntity {
        val album = createAlbum(name, iconKey, colorHex, isPinned)
        if (fileIds.isNotEmpty()) {
            dao.moveFilesToAlbum(fileIds, album.id)
            syncManifestToExternalFolderIfConnected()
        }
        return album
    }

    suspend fun toggleFavorite(fileId: String, currentFavorite: Boolean) {
        dao.setFavorite(fileId, !currentFavorite)
        syncManifestToExternalFolderIfConnected()
    }

    suspend fun saveEditedImage(
        sourceFile: VaultFileEntity,
        params: ImageAdjustmentParams,
        overwriteOriginal: Boolean
    ): VaultFileEntity {
        val plainBytes = storageManager.decryptToBytes(sourceFile)
        val (editedBytes, targetMime) = try {
            withContext(Dispatchers.Default) {
                ImageAdjustmentProcessor.applyAdjustmentsToImageBytes(
                    imageBytes = plainBytes,
                    mimeType = sourceFile.mimeType,
                    originalName = sourceFile.originalName,
                    params = params
                )
            }
        } finally {
            plainBytes.fill(0)
        }
        val savedEntity = try {
            storageManager.saveAdjustedVaultImage(
                sourceFile = sourceFile,
                newPlainBytes = editedBytes,
                targetMimeType = targetMime,
                overwriteOriginal = overwriteOriginal
            )
        } finally {
            editedBytes.fill(0)
        }
        dao.insertFile(savedEntity)
        val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let(Uri::parse)
        if (folderUri != null) {
            withContext(Dispatchers.IO) {
                storageManager.mirrorEncryptedFileToFolder(folderUri, savedEntity)
            }
            syncManifestToExternalFolderIfConnected()
        }
        return savedEntity
    }

    suspend fun scanForDuplicates(
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allFiles = dao.getAllFilesForMirror()
        if (allFiles.size < 2) {
            onProgress(allFiles.size, allFiles.size)
            return@withContext emptyList()
        }

        val countBySize = allFiles.groupingBy { it.originalSizeBytes }.eachCount()
        val needsHash = allFiles.filter { file ->
            file.contentHash.isNullOrBlank() &&
                ((countBySize[file.originalSizeBytes] ?: 0) > 1 || file.originalSizeBytes <= 0L)
        }

        val totalToScan = needsHash.size
        val computedHashes = mutableMapOf<String, String>()
        needsHash.forEachIndexed { idx, file ->
            runCatching {
                storageManager.computePlaintextSha256(file)
            }.onSuccess { hash ->
                computedHashes[file.id] = hash
                dao.updateContentHash(file.id, hash)
            }
            onProgress(idx + 1, totalToScan)
        }

        val resolved = allFiles.map { file ->
            val newHash = computedHashes[file.id]
            if (newHash != null) file.copy(contentHash = newHash) else file
        }
        DuplicateDetector.groupExactDuplicates(resolved)
    }

    suspend fun deleteFiles(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        val entities = dao.getFilesByIds(fileIds)
        entities.forEach { storageManager.deleteEncryptedAssets(it) }
        dao.deleteFilesByIds(fileIds)
        val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let(Uri::parse)
        withContext(Dispatchers.IO) {
            storageManager.removeMirroredFilesFromFolder(folderUri, entities)
        }
        syncManifestToExternalFolderIfConnected()
    }

    suspend fun exportFile(
        fileEntity: VaultFileEntity,
        destinationUri: Uri,
        removeFromVault: Boolean
    ) {
        storageManager.decryptToUri(fileEntity, destinationUri)
        if (removeFromVault) {
            storageManager.deleteEncryptedAssets(fileEntity)
            dao.deleteFilesByIds(listOf(fileEntity.id))
            val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let(Uri::parse)
            withContext(Dispatchers.IO) {
                storageManager.removeMirroredFilesFromFolder(folderUri, listOf(fileEntity))
            }
            syncManifestToExternalFolderIfConnected()
        }
    }

    suspend fun exportFilesToMediaStore(
        fileIds: List<String>,
        removeFromVault: Boolean,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchExportResult = exportFilesInternal(
        fileIds = fileIds,
        removeFromVault = removeFromVault,
        onProgress = onProgress
    ) { entity ->
        storageManager.exportToDeviceMediaOrDownloads(entity)
    }

    suspend fun exportFilesToFolder(
        fileIds: List<String>,
        destinationTreeUri: Uri,
        removeFromVault: Boolean,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchExportResult = exportFilesInternal(
        fileIds = fileIds,
        removeFromVault = removeFromVault,
        onProgress = onProgress
    ) { entity ->
        storageManager.exportToFolderUri(entity, destinationTreeUri)
    }

    private suspend fun exportFilesInternal(
        fileIds: List<String>,
        removeFromVault: Boolean,
        onProgress: (processed: Int, total: Int) -> Unit,
        exportSingle: suspend (VaultFileEntity) -> Unit
    ): BatchExportResult {
        if (fileIds.isEmpty()) return BatchExportResult(0, 0, 0, null)
        val entities = dao.getFilesByIds(fileIds)
        if (entities.isEmpty()) return BatchExportResult(0, 0, 0, null)

        val exportedEntities = mutableListOf<VaultFileEntity>()
        var failedCount = 0
        var firstFailure: String? = null

        entities.forEachIndexed { index, entity ->
            runCatching {
                exportSingle(entity)
            }.onSuccess {
                exportedEntities.add(entity)
            }.onFailure { err ->
                failedCount++
                if (firstFailure == null) {
                    firstFailure = err.localizedMessage ?: err.javaClass.simpleName
                }
            }
            onProgress(index + 1, entities.size)
        }

        var removedCount = 0
        if (removeFromVault && exportedEntities.isNotEmpty()) {
            val successfullyRemoved = mutableListOf<VaultFileEntity>()
            exportedEntities.forEach { entity ->
                runCatching { storageManager.deleteEncryptedAssets(entity) }
                    .onSuccess { successfullyRemoved.add(entity) }
                    .onFailure { err ->
                        if (firstFailure == null) {
                            firstFailure = err.localizedMessage ?: "Sebagian ciphertext gagal dihapus"
                        }
                    }
            }
            if (successfullyRemoved.isNotEmpty()) {
                dao.deleteFilesByIds(successfullyRemoved.map { it.id })
                removedCount = successfullyRemoved.size
                val folderUri = securityPrefs.preferencesFlow.first().externalVaultFolderUri?.let(Uri::parse)
                withContext(Dispatchers.IO) {
                    storageManager.removeMirroredFilesFromFolder(folderUri, successfullyRemoved)
                }
                syncManifestToExternalFolderIfConnected()
            }
        }

        return BatchExportResult(
            exportedCount = exportedEntities.size,
            removedFromVaultCount = removedCount,
            failedCount = failedCount,
            firstFailureReason = firstFailure
        )
    }

    suspend fun loadThumbnail(fileEntity: VaultFileEntity): Bitmap? =
        storageManager.loadDecryptedThumbnail(fileEntity)

    suspend fun decryptBytes(fileEntity: VaultFileEntity): ByteArray {
        // Self-heal orphaned Room records when the internal ciphertext was lost but an
        // external mirrored .vault backup still exists. Never delete or overwrite the backup.
        val normalResult = runCatching { storageManager.decryptToBytes(fileEntity) }
        if (normalResult.isSuccess) return normalResult.getOrThrow()

        val prefs = securityPrefs.preferencesFlow.first()
        val externalUri = prefs.externalVaultFolderUri?.let(Uri::parse)
            ?: throw normalResult.exceptionOrNull()!!
        val backupName = "${fileEntity.id}.vault"
        val backupExists = withContext(Dispatchers.IO) {
            storageManager.listExternalEncryptedFiles(externalUri)
                .any { it.name.equals(backupName, ignoreCase = true) }
        }
        if (!backupExists) throw normalResult.exceptionOrNull()!!

        val recoveryEntry = ExternalVaultEntry(
            backupFileName = backupName,
            fileId = fileEntity.id,
            displayName = fileEntity.originalName,
            mimeType = fileEntity.mimeType,
            category = fileEntity.category,
            sizeBytes = fileEntity.originalSizeBytes,
            albumId = fileEntity.albumId,
            albumName = null,
            createdAt = fileEntity.createdAt,
            isFavorite = fileEntity.isFavorite,
            thumbFileName = fileEntity.encryptedThumbName?.let { "${fileEntity.id}.thumb.vault" },
            hasManifestMetadata = true,
            alreadyInLocalVault = true,
            canDecrypt = true
        )
        val repaired = storageManager.recoverExternalEncryptedFile(
            folderUri = externalUri,
            backupName = backupName,
            albumId = fileEntity.albumId,
            manifestEntry = recoveryEntry,
            overrideKey = null,
            reencryptForCurrentVault = true
        )
        dao.insertFile(repaired)
        return storageManager.decryptToBytes(repaired)
    }

    fun openEncryptedMediaDataSource(fileEntity: VaultFileEntity): MediaDataSource =
        storageManager.openEncryptedMediaDataSource(fileEntity)

    suspend fun decryptToEphemeralCacheFile(fileEntity: VaultFileEntity): File =
        storageManager.decryptToEphemeralCacheFile(fileEntity)

    fun secureDeleteEphemeralFile(file: File) {
        storageManager.secureDeleteFile(file)
    }

    fun clearSensitiveCaches() {
        storageManager.clearSensitiveCaches()
        vaultKeyManager.clearActiveVaultKey()
    }

    suspend fun setBiometricEnabled(enabled: Boolean) = securityPrefs.setBiometricEnabled(enabled)
    suspend fun setAutoLockOnBackground(enabled: Boolean) = securityPrefs.setAutoLockOnBackground(enabled)
    suspend fun setBlockScreenshots(enabled: Boolean) = securityPrefs.setBlockScreenshots(enabled)
    suspend fun setDeleteOriginalOnImport(enabled: Boolean) = securityPrefs.setDeleteOriginalOnImport(enabled)

    suspend fun setExternalVaultFolder(uri: String?) {
        if (uri == null) {
            securityPrefs.setExternalVaultFolder(null, null)
            return
        }
        val parsed = Uri.parse(uri)
        val displayName = withContext(Dispatchers.IO) { storageManager.getFolderDisplayName(parsed) }
        securityPrefs.setExternalVaultFolder(uri, displayName)
        val state = securityPrefs.preferencesFlow.first()
        withContext(Dispatchers.IO) {
            storageManager.syncAllToDestinationFolder(
                folderUri = parsed,
                albums = dao.getAllAlbumsSync(),
                files = dao.getAllFilesForMirror(),
                recoveryKeyJson = state.recoveryKeyBundleJson
            )
        }
    }

    suspend fun setThemePreference(theme: VaultThemePreference) = securityPrefs.setThemePreference(theme)

    suspend fun setGallerySortOrder(order: GallerySortOrder) = securityPrefs.setGallerySortOrder(order)

    suspend fun addSourceFolderUri(uri: String) = securityPrefs.addSourceFolderUri(uri)

    suspend fun removeSourceFolderUri(uri: String) = securityPrefs.removeSourceFolderUri(uri)
}
