package com.example.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.RecoveryKeyBundle
import com.example.crypto.VaultCryptoEngine
import com.example.crypto.VaultKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

data class ImportedFileOutcome(
    val entity: VaultFileEntity,
    val sourceUri: Uri,
    val originalDeletedDirectly: Boolean = false,
    val resolvedMediaUri: Uri? = null,
    val sourceRelativePath: String? = null,
    val sourceFilePath: String? = null
)

data class SourceDeleteAttempt(
    val deleted: Boolean,
    val mediaStoreUri: Uri? = null,
    val recoverableIntentSender: IntentSender? = null
)

data class ExternalVaultEntry(
    val backupFileName: String,
    val fileId: String,
    val displayName: String,
    val mimeType: String,
    val category: MediaCategory,
    val sizeBytes: Long,
    val albumId: String?,
    val albumName: String?,
    val createdAt: Long,
    val isFavorite: Boolean,
    val thumbFileName: String?,
    val hasManifestMetadata: Boolean,
    val alreadyInLocalVault: Boolean = false,
    val canDecrypt: Boolean = true
)

data class ExternalVaultInspectionResult(
    val folderDisplayName: String,
    val hasRecoveryKey: Boolean,
    val needsRecoveryPin: Boolean,
    val wrongRecoveryPin: Boolean = false,
    val unlockedWithRecoveryPin: Boolean = false,
    val unlockedKeysCount: Int = 0,
    val recoveredMasterKey: SecretKey? = null,
    val unlockedMasterKeys: List<SecretKey> = emptyList(),
    val manifestAlbums: List<VaultBackupAlbum> = emptyList(),
    val entries: List<ExternalVaultEntry> = emptyList()
)

object MediaUriResolver {
    fun isPhotoPickerUri(uri: Uri): Boolean {
        val path = uri.path.orEmpty()
        val authority = uri.authority.orEmpty()
        return path.startsWith("/picker") ||
            path.contains("/picker/") ||
            authority.contains("photopicker", ignoreCase = true)
    }

    fun buildMediaStoreUriForId(id: Long, mimeType: String?): Uri {
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            mime.startsWith("video/") ->
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            mime.startsWith("audio/") ->
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            mime.startsWith("image/") ->
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            else ->
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        }
    }

    fun parseMediaDocumentsId(documentId: String): Uri? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        return when (parts[0].lowercase()) {
            "image" -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            "audio" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            "document" -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            else -> null
        }
    }

    fun extractNumericPickerMediaId(uri: Uri): Long? {
        val segments = uri.pathSegments
        val mediaIdx = segments.indexOfLast { it == "media" }
        if (mediaIdx != -1 && mediaIdx + 1 < segments.size) {
            return segments[mediaIdx + 1].toLongOrNull()
        }
        return uri.lastPathSegment?.toLongOrNull()
    }
}

class VaultStorageManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val vaultKeyManager: VaultKeyManager
) {
    companion object {
        const val VAULT_FOLDER_NAME = "Locker"
        private const val LEGACY_VAULT_FOLDER_NAME = "BrankasKu"
        const val RECOVERY_KEY_FILE_NAME = "vault_recovery.key"
        const val RECOVERY_RING_FILE_NAME = "vault_recovery_ring.json"
        const val MANIFEST_FILE_NAME = "vault_manifest.enc"
        private val manifestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val vaultRootDir: File by lazy {
        File(context.filesDir, "secure_vault").apply {
            if (!exists()) mkdirs()
            val noMedia = File(this, ".nomedia")
            if (!noMedia.exists()) {
                runCatching { noMedia.createNewFile() }
            }
        }
    }

    private val blobsDir: File by lazy {
        File(vaultRootDir, "blobs").apply { if (!exists()) mkdirs() }
    }

    private val thumbsDir: File by lazy {
        File(vaultRootDir, "thumbs").apply { if (!exists()) mkdirs() }
    }

    // Decrypted thumbnail bitmaps kept strictly in RAM and cleared on lock
    private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getVaultInternalPathDisplay(): String = vaultRootDir.absolutePath

    private fun folderHasVaultPayload(dir: DocumentFile): Boolean {
        return dir.listFiles().any { doc ->
            val name = doc.name ?: return@any false
            name == RECOVERY_KEY_FILE_NAME ||
                name == RECOVERY_RING_FILE_NAME ||
                name == MANIFEST_FILE_NAME ||
                name.endsWith(".vault", ignoreCase = true)
        }
    }

    private fun resolveOrCreateVaultSubfolder(folderUri: Uri, createIfMissing: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (root.name.equals(VAULT_FOLDER_NAME, ignoreCase = true) ||
            root.name.equals(LEGACY_VAULT_FOLDER_NAME, ignoreCase = true)
        ) {
            if (createIfMissing && root.findFile(".nomedia") == null) {
                runCatching { root.createFile("text/plain", ".nomedia") }
            }
            return root
        }
        val lockerDir = root.findFile(VAULT_FOLDER_NAME)?.takeIf { it.isDirectory }
        val legacyDir = root.findFile(LEGACY_VAULT_FOLDER_NAME)?.takeIf { it.isDirectory }
        val existing = when {
            lockerDir != null && legacyDir != null -> {
                if (folderHasVaultPayload(lockerDir)) lockerDir
                else if (folderHasVaultPayload(legacyDir)) legacyDir
                else lockerDir
            }
            else -> lockerDir ?: legacyDir
        }
        if (existing != null) {
            if (createIfMissing && existing.findFile(".nomedia") == null) {
                runCatching { existing.createFile("text/plain", ".nomedia") }
            }
            return existing
        }
        if (!createIfMissing) return root
        val created = root.createDirectory(VAULT_FOLDER_NAME) ?: return null
        runCatching { created.createFile("text/plain", ".nomedia") }
        return created
    }

    fun getFolderDisplayName(folderUri: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, folderUri)
        val name = doc?.name?.takeIf { it.isNotBlank() } ?: "Folder Pilihan"
        return if (name.equals(VAULT_FOLDER_NAME, ignoreCase = true) ||
            name.equals(LEGACY_VAULT_FOLDER_NAME, ignoreCase = true)
        ) {
            name
        } else {
            val resolved = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            val subName = resolved?.name?.takeIf {
                it.equals(VAULT_FOLDER_NAME, ignoreCase = true) ||
                    it.equals(LEGACY_VAULT_FOLDER_NAME, ignoreCase = true)
            } ?: VAULT_FOLDER_NAME
            "$name/$subName"
        }
    }

    fun getSourceFolderDisplayName(folderUri: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, folderUri)
        val rawName = doc?.name?.takeIf { it.isNotBlank() }
        if (rawName != null) return rawName
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(folderUri) }.getOrNull()
        return treeId?.substringAfter(':', treeId)?.ifBlank { "Folder Sumber" } ?: "Folder Sumber"
    }

    /** Lists encrypted backup files in the user-selected external folder. */
    fun listExternalEncryptedFiles(folderUri: Uri): List<DocumentFile> {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false) ?: return emptyList()
        return vaultFolder.listFiles().filter {
            it.isFile &&
                it.name?.endsWith(".vault", ignoreCase = true) == true &&
                it.name?.endsWith(".thumb.vault", ignoreCase = true) != true
        }
    }

    private fun readBundlesFromDoc(doc: DocumentFile?): List<RecoveryKeyBundle> {
        if (doc == null || !doc.isFile) return emptyList()
        return readBundlesFromUri(doc.uri)
    }

    fun readBundlesFromUri(uri: Uri): List<RecoveryKeyBundle> {
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        return vaultKeyManager.parseRecoveryBundles(raw)
    }

    private fun collectRecoveryBundlesForFolder(folderUri: Uri, primarySubfolder: DocumentFile): List<RecoveryKeyBundle> {
        val folders = buildList {
            add(primarySubfolder)
            val root = DocumentFile.fromTreeUri(context, folderUri)
            if (root != null) {
                if (root.uri != primarySubfolder.uri) add(root)
                root.findFile(VAULT_FOLDER_NAME)?.takeIf { it.isDirectory && it.uri != primarySubfolder.uri }?.let(::add)
                root.findFile(LEGACY_VAULT_FOLDER_NAME)?.takeIf { it.isDirectory && it.uri != primarySubfolder.uri }?.let(::add)
            }
        }
        return folders.flatMap { dir ->
            readBundlesFromDoc(dir.findFile(RECOVERY_KEY_FILE_NAME)) +
                readBundlesFromDoc(dir.findFile(RECOVERY_RING_FILE_NAME))
        }.distinctBy { Triple(it.saltB64, it.ivB64, it.wrappedKeyB64) }
    }

    fun writeRecoveryKeyToFolder(
        folderUri: Uri,
        recoveryKeyBundleJson: String,
        overwritePrimary: Boolean = false
    ) {
        runCatching {
            val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = true) ?: return
            val existingPrimary = vaultFolder.findFile(RECOVERY_KEY_FILE_NAME)
            val existingBundles = readBundlesFromDoc(existingPrimary) +
                readBundlesFromDoc(vaultFolder.findFile(RECOVERY_RING_FILE_NAME))
            val incomingBundles = vaultKeyManager.parseRecoveryBundles(recoveryKeyBundleJson)
            val merged = (existingBundles + incomingBundles).distinctBy {
                Triple(it.saltB64, it.ivB64, it.wrappedKeyB64)
            }
            if (merged.isNotEmpty()) {
                val ringJson = vaultKeyManager.encodeRecoveryRing(merged)
                vaultFolder.findFile(RECOVERY_RING_FILE_NAME)?.delete()
                vaultFolder.createFile("application/json", RECOVERY_RING_FILE_NAME)?.let { target ->
                    context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                        out.write(ringJson.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            if (overwritePrimary || existingPrimary == null) {
                existingPrimary?.delete()
                val target = vaultFolder.createFile("application/octet-stream", RECOVERY_KEY_FILE_NAME) ?: return
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(recoveryKeyBundleJson.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    fun writeEncryptedManifestToFolder(
        folderUri: Uri,
        albums: List<AlbumEntity>,
        files: List<VaultFileEntity>,
        allowOverwriteLockedForeignManifest: Boolean = false
    ) {
        runCatching {
            val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = true) ?: return
            val existingDocs = vaultFolder.listFiles().filter { it.isFile && it.name != null }
            val existingManifestDoc = existingDocs.firstOrNull { it.name == MANIFEST_FILE_NAME }
            val existingVaultIds = existingDocs.mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                if (name.endsWith(".vault", ignoreCase = true) && !name.endsWith(".thumb.vault", ignoreCase = true)) {
                    name.removeSuffix(".vault")
                } else null
            }.toSet()

            var existingManifest: VaultBackupManifest? = null
            if (existingManifestDoc != null) {
                val existingBytes = context.contentResolver.openInputStream(existingManifestDoc.uri)?.use {
                    it.readBytes()
                }
                if (existingBytes != null && existingBytes.isNotEmpty()) {
                    val decryptedExisting = runCatching { cryptoEngine.decryptBytes(existingBytes) }.getOrNull()
                    if (decryptedExisting == null) {
                        if (!allowOverwriteLockedForeignManifest) {
                            // Never destroy an un-unlocked manifest from a previous install
                            return
                        }
                    } else {
                        existingManifest = runCatching {
                            manifestJson.decodeFromString<VaultBackupManifest>(
                                decryptedExisting.toString(Charsets.UTF_8)
                            )
                        }.getOrNull()
                        decryptedExisting.fill(0)
                    }
                }
            } else if (files.isEmpty() && existingVaultIds.isNotEmpty()) {
                // Folder already has encrypted .vault files (possibly from another key) and no local files to write
                val hasLockedFiles = existingDocs.any { doc ->
                    val name = doc.name ?: return@any false
                    name.endsWith(".vault", ignoreCase = true) &&
                        !name.endsWith(".thumb.vault", ignoreCase = true) &&
                        !canDecryptDocumentHeader(doc.uri)
                }
                if (hasLockedFiles) return
            }

            val localAlbums = albums.map {
                VaultBackupAlbum(
                    id = it.id,
                    name = it.name,
                    iconKey = it.iconKey,
                    colorHex = it.colorHex,
                    isPinned = it.isPinned,
                    createdAt = it.createdAt,
                    sortOrder = it.sortOrder
                )
            }
            val localAlbumIds = localAlbums.map { it.id }.toSet()
            val mergedAlbums = localAlbums + existingManifest?.albums.orEmpty().filter { it.id !in localAlbumIds }

            val localFiles = files.map {
                VaultBackupFile(
                    id = it.id,
                    albumId = it.albumId,
                    originalName = it.originalName,
                    mimeType = it.mimeType,
                    categoryName = it.category.name,
                    originalSizeBytes = it.originalSizeBytes,
                    hasThumbnail = it.encryptedThumbName != null,
                    createdAt = it.createdAt,
                    isFavorite = it.isFavorite
                )
            }
            val localFileIds = localFiles.map { it.id }.toSet()
            val mergedFiles = localFiles + existingManifest?.files.orEmpty().filter {
                it.id !in localFileIds && it.id in existingVaultIds
            }

            val manifest = VaultBackupManifest(
                version = 1,
                updatedAt = System.currentTimeMillis(),
                albums = mergedAlbums,
                files = mergedFiles
            )
            val jsonBytes = manifestJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val encryptedBytes = cryptoEngine.encryptBytes(jsonBytes)
            jsonBytes.fill(0)

            existingManifestDoc?.delete()
            val target = vaultFolder.createFile("application/octet-stream", MANIFEST_FILE_NAME) ?: return
            context.contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                out.write(encryptedBytes)
            }
        }
    }

    data class ExternalMirrorSession(
        val folderUri: Uri,
        val vaultFolder: DocumentFile,
        val existingNames: MutableSet<String>
    )

    fun prepareExternalMirrorSession(
        folderUri: Uri?,
        recoveryKeyBundleJson: String?
    ): ExternalMirrorSession? {
        if (folderUri == null) return null
        return runCatching {
            val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = true) ?: return null
            val existingNames = vaultFolder.listFiles().mapNotNull { it.name }.toMutableSet()
            if (recoveryKeyBundleJson != null &&
                (RECOVERY_KEY_FILE_NAME !in existingNames || RECOVERY_RING_FILE_NAME !in existingNames)
            ) {
                writeRecoveryKeyToFolder(folderUri, recoveryKeyBundleJson, overwritePrimary = false)
                existingNames.add(RECOVERY_KEY_FILE_NAME)
                existingNames.add(RECOVERY_RING_FILE_NAME)
            }
            ExternalMirrorSession(folderUri, vaultFolder, existingNames)
        }.getOrNull()
    }

    fun mirrorEncryptedFileToSession(session: ExternalMirrorSession, entity: VaultFileEntity) {
        runCatching {
            val blobName = "${entity.id}.vault"
            val sourceBlob = File(blobsDir, entity.encryptedFileName)
            if (sourceBlob.exists()) {
                copyCipherFile(
                    source = sourceBlob,
                    folder = session.vaultFolder,
                    targetName = blobName,
                    deleteIfPresent = blobName in session.existingNames
                )
                session.existingNames.add(blobName)
            }
            entity.encryptedThumbName?.let { name ->
                val thumbTargetName = "${entity.id}.thumb.vault"
                val sourceThumb = File(thumbsDir, name)
                if (sourceThumb.exists()) {
                    copyCipherFile(
                        source = sourceThumb,
                        folder = session.vaultFolder,
                        targetName = thumbTargetName,
                        deleteIfPresent = thumbTargetName in session.existingNames
                    )
                    session.existingNames.add(thumbTargetName)
                }
            }
        }
    }

    fun mirrorEncryptedFileToFolder(folderUri: Uri?, entity: VaultFileEntity) {
        if (folderUri == null) return
        runCatching {
            val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = true) ?: return
            val sourceBlob = File(blobsDir, entity.encryptedFileName)
            if (sourceBlob.exists()) copyCipherFile(sourceBlob, vaultFolder, "${entity.id}.vault")
            entity.encryptedThumbName?.let { name ->
                val sourceThumb = File(thumbsDir, name)
                if (sourceThumb.exists()) copyCipherFile(sourceThumb, vaultFolder, "${entity.id}.thumb.vault")
            }
        }
    }

    fun syncAllToDestinationFolder(
        folderUri: Uri,
        albums: List<AlbumEntity>,
        files: List<VaultFileEntity>,
        recoveryKeyJson: String?
    ) {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = true)
            ?: throw IllegalStateException("Tidak dapat membuat folder Locker di lokasi tujuan")
        recoveryKeyJson?.let { writeRecoveryKeyToFolder(folderUri, it, overwritePrimary = false) }
        val existingNames = vaultFolder.listFiles().mapNotNull { it.name }.toSet()
        files.forEach { entity ->
            val blobName = "${entity.id}.vault"
            if (blobName !in existingNames) {
                val sourceBlob = File(blobsDir, entity.encryptedFileName)
                if (sourceBlob.exists()) copyCipherFile(sourceBlob, vaultFolder, blobName)
            }
            entity.encryptedThumbName?.let { thumbName ->
                val targetThumbName = "${entity.id}.thumb.vault"
                if (targetThumbName !in existingNames) {
                    val sourceThumb = File(thumbsDir, thumbName)
                    if (sourceThumb.exists()) copyCipherFile(sourceThumb, vaultFolder, targetThumbName)
                }
            }
        }
        writeEncryptedManifestToFolder(
            folderUri = folderUri,
            albums = albums,
            files = files,
            allowOverwriteLockedForeignManifest = false
        )
    }

    fun removeMirroredFilesFromFolder(folderUri: Uri?, entities: List<VaultFileEntity>) {
        if (folderUri == null || entities.isEmpty()) return
        runCatching {
            val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false) ?: return
            entities.forEach { entity ->
                vaultFolder.findFile("${entity.id}.vault")?.delete()
                vaultFolder.findFile("${entity.id}.thumb.vault")?.delete()
            }
        }
    }

    private fun readHeaderPrefixBytes(uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(VaultCryptoEngine.FIXED_HEADER_SIZE)
                var total = 0
                while (total < buf.size) {
                    val n = stream.read(buf, total, buf.size - total)
                    if (n == -1) break
                    total += n
                }
                if (total == VaultCryptoEngine.FIXED_HEADER_SIZE) buf else null
            }
        }.getOrNull()
    }

    private fun canDecryptDocumentHeader(uri: Uri, overrideKey: SecretKey? = null): Boolean {
        val prefix = readHeaderPrefixBytes(uri) ?: return false
        return cryptoEngine.canDecryptPrefix(prefix, overrideKey)
    }

    suspend fun inspectExternalVaultFolder(
        folderUri: Uri,
        existingLocalIds: Set<String>,
        recoveryPin: String? = null,
        extraBundles: List<RecoveryKeyBundle> = emptyList()
    ): ExternalVaultInspectionResult = withContext(Dispatchers.IO) {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            ?: return@withContext ExternalVaultInspectionResult(
                folderDisplayName = getFolderDisplayName(folderUri),
                hasRecoveryKey = false,
                needsRecoveryPin = false
            )

        val folderDisplayName = getFolderDisplayName(folderUri)
        val allDocs = vaultFolder.listFiles().filter { it.isFile && it.name != null }
        val docsByName = allDocs.associateBy { it.name!! }
        val manifestDoc = docsByName[MANIFEST_FILE_NAME]
        val vaultDocs = allDocs.filter {
            val name = it.name!!
            name.endsWith(".vault", ignoreCase = true) && !name.endsWith(".thumb.vault", ignoreCase = true)
        }.sortedBy { it.name }

        val folderBundles = collectRecoveryBundlesForFolder(folderUri, vaultFolder)
        val allBundles = (folderBundles + extraBundles).distinctBy {
            Triple(it.saltB64, it.ivB64, it.wrappedKeyB64)
        }

        val unlockedKeys = mutableListOf<SecretKey>()
        var wrongPin = false
        var unlockedWithPin = false

        if (!recoveryPin.isNullOrBlank() && allBundles.isNotEmpty()) {
            val matches = vaultKeyManager.tryUnwrapAllWithPin(allBundles, recoveryPin)
            if (matches.isNotEmpty()) {
                vaultKeyManager.addLegacyVaultKeys(matches)
                unlockedKeys.addAll(matches)
                unlockedWithPin = true
            } else {
                wrongPin = true
            }
        }

        val primaryOverrideKey = unlockedKeys.firstOrNull()
        var decodedManifest: VaultBackupManifest? = null

        if (manifestDoc != null) {
            val encryptedManifest = context.contentResolver.openInputStream(manifestDoc.uri)?.use {
                it.readBytes()
            }
            if (encryptedManifest != null && encryptedManifest.isNotEmpty()) {
                val decryptedBytes = runCatching {
                    cryptoEngine.decryptBytes(encryptedManifest, primaryOverrideKey)
                }.getOrNull()
                if (decryptedBytes != null) {
                    decodedManifest = runCatching {
                        manifestJson.decodeFromString<VaultBackupManifest>(
                            decryptedBytes.toString(Charsets.UTF_8)
                        )
                    }.getOrNull()
                    decryptedBytes.fill(0)
                }
            }
        }

        val manifestAlbums = decodedManifest?.albums.orEmpty()
        val albumNamesById = manifestAlbums.associate { it.id to it.name }
        val manifestFilesById = decodedManifest?.files?.associateBy { it.id }.orEmpty()

        val entries = coroutineScope {
            val scanSemaphore = Semaphore(4)
            vaultDocs.map { doc ->
                async(Dispatchers.IO) {
                    scanSemaphore.withPermit {
                    val fileName = doc.name!!
                    val idCandidate = fileName.removeSuffix(".vault")
                    val thumbName = "$idCandidate.thumb.vault".takeIf { docsByName.containsKey(it) }
                    val meta = manifestFilesById[idCandidate]
                    val canOpen = canDecryptDocumentHeader(doc.uri, primaryOverrideKey)
                    if (meta != null) {
                        val category = runCatching { MediaCategory.valueOf(meta.categoryName) }
                            .getOrDefault(MediaCategory.fromMimeType(meta.mimeType, meta.originalName))
                        ExternalVaultEntry(
                            backupFileName = fileName,
                            fileId = meta.id,
                            displayName = meta.originalName,
                            mimeType = meta.mimeType,
                            category = category,
                            sizeBytes = meta.originalSizeBytes,
                            albumId = meta.albumId,
                            albumName = albumNamesById[meta.albumId],
                            createdAt = meta.createdAt,
                            isFavorite = meta.isFavorite,
                            thumbFileName = thumbName,
                            hasManifestMetadata = true,
                            alreadyInLocalVault = meta.id in existingLocalIds,
                            canDecrypt = canOpen
                        )
                    } else {
                        ExternalVaultEntry(
                            backupFileName = fileName,
                            fileId = idCandidate,
                            displayName = fileName,
                            mimeType = "application/octet-stream",
                            category = MediaCategory.OTHER,
                            sizeBytes = doc.length(),
                            albumId = null,
                            albumName = null,
                            createdAt = doc.lastModified(),
                            isFavorite = false,
                            thumbFileName = thumbName,
                            hasManifestMetadata = false,
                            alreadyInLocalVault = idCandidate in existingLocalIds,
                            canDecrypt = canOpen
                        )
                    }
                    }
                }
            }.awaitAll()
        }

        val manifestLocked = manifestDoc != null && decodedManifest == null
        val anyLockedFiles = entries.any { !it.canDecrypt }
        val needsPin = anyLockedFiles || manifestLocked

        ExternalVaultInspectionResult(
            folderDisplayName = folderDisplayName,
            hasRecoveryKey = allBundles.isNotEmpty(),
            needsRecoveryPin = needsPin,
            wrongRecoveryPin = wrongPin,
            unlockedWithRecoveryPin = unlockedWithPin,
            unlockedKeysCount = unlockedKeys.size,
            recoveredMasterKey = unlockedKeys.firstOrNull(),
            unlockedMasterKeys = unlockedKeys.toList(),
            manifestAlbums = manifestAlbums,
            entries = entries
        )
    }

    suspend fun loadExternalThumbnail(
        folderUri: Uri,
        thumbFileName: String,
        overrideKey: SecretKey? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false) ?: return@withContext null
        val doc = vaultFolder.findFile(thumbFileName)?.takeIf { it.isFile } ?: return@withContext null
        runCatching {
            val encrypted = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                ?: return@withContext null
            val plain = cryptoEngine.decryptBytes(encrypted, overrideKey)
            val bmp = BitmapFactory.decodeByteArray(plain, 0, plain.size)
            plain.fill(0)
            bmp
        }.getOrNull()
    }

    suspend fun decryptExternalFileToBytes(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            ?: throw IllegalStateException("Folder tujuan tidak dapat dibuka")
        val doc = vaultFolder.findFile(backupFileName)?.takeIf { it.isFile }
            ?: throw IllegalStateException("File $backupFileName tidak ditemukan")
        val baos = ByteArrayOutputStream()
        context.contentResolver.openInputStream(doc.uri)?.buffered()?.use { input ->
            cryptoEngine.decryptStream(input, baos, overrideKey)
        } ?: throw IllegalStateException("File terenkripsi tidak dapat dibaca")
        baos.toByteArray()
    }

    fun openExternalEncryptedMediaDataSource(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): MediaDataSource {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            ?: throw IllegalStateException("Folder tujuan tidak dapat dibuka")
        val doc = vaultFolder.findFile(backupFileName)?.takeIf { it.isFile }
            ?: throw IllegalStateException("File $backupFileName tidak ditemukan")
        val pfd = context.contentResolver.openFileDescriptor(doc.uri, "r")
            ?: throw IllegalStateException("Tidak dapat membuka stream media terenkripsi")
        return cryptoEngine.openEncryptedMediaDataSource(pfd, overrideKey)
    }

    suspend fun decryptExternalFolderFileToEphemeralCache(
        folderUri: Uri,
        backupFileName: String,
        overrideKey: SecretKey? = null
    ): File = withContext(Dispatchers.IO) {
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            ?: throw IllegalStateException("Folder tujuan tidak dapat dibuka")
        val doc = vaultFolder.findFile(backupFileName)?.takeIf { it.isFile }
            ?: throw IllegalStateException("File $backupFileName tidak ditemukan")
        decryptExternalEncryptedFileToEphemeralCache(doc.uri, backupFileName, overrideKey)
    }

    /**
     * Restores an encrypted file from the external destination folder into the internal vault.
     * When [reencryptForCurrentVault] is true and the file was encrypted with any key other than the
     * active vault key (e.g., pre-branding key, legacy Keystore key, or another recovery key),
     * it is cleanly re-wrapped under the active vault key so it works natively offline after recovery.
     */
    suspend fun recoverExternalEncryptedFile(
        folderUri: Uri,
        backupName: String,
        albumId: String,
        manifestEntry: ExternalVaultEntry? = null,
        overrideKey: SecretKey? = null,
        reencryptForCurrentVault: Boolean = true
    ): VaultFileEntity = withContext(Dispatchers.IO) {
        require(backupName.endsWith(".vault", ignoreCase = true) && !backupName.endsWith(".thumb.vault", ignoreCase = true)) {
            "File bukan backup utama .vault"
        }
        require(backupName == backupName.substringAfterLast('/')) { "Nama file backup tidak valid" }
        val vaultFolder = resolveOrCreateVaultSubfolder(folderUri, createIfMissing = false)
            ?: throw IllegalStateException("Folder Locker tidak dapat dibuka")
        val source = vaultFolder.findFile(backupName)?.takeIf { it.isFile }
            ?: throw IllegalStateException("Backup $backupName tidak ditemukan")
        val tempDir = File(context.cacheDir, "vault_recovery").apply { if (!exists()) mkdirs() }
        val plaintextTemp = File(tempDir, "recover_${UUID.randomUUID()}.tmp")
        var internalBlob: File? = null
        var internalThumb: File? = null
        try {
            val headerPrefix = readHeaderPrefixBytes(source.uri)
            val decodedHeader = headerPrefix?.let {
                runCatching { cryptoEngine.readHeaderFromPrefixBytes(it, overrideKey) }.getOrNull()
            }

            // Decrypt to validate integrity & key, and inspect magic bytes if manifest metadata is absent
            context.contentResolver.openInputStream(source.uri)?.buffered()?.use { input ->
                plaintextTemp.outputStream().buffered().use { output ->
                    cryptoEngine.decryptStream(input, output, overrideKey)
                }
            } ?: throw IllegalStateException("Backup eksternal tidak dapat dibaca")

            if (plaintextTemp.length() <= 0L) throw IllegalStateException("Backup terdekripsi kosong")

            val info = if (manifestEntry != null && manifestEntry.hasManifestMetadata) {
                RecoveredFileInfo(
                    mimeType = manifestEntry.mimeType,
                    displayName = manifestEntry.displayName,
                    category = manifestEntry.category
                )
            } else {
                detectRecoveredFileInfo(plaintextTemp, backupName)
            }

            val id = manifestEntry?.fileId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val encryptedName = "$id.vlt"
            internalBlob = File(blobsDir, encryptedName)

            val activeKey = vaultKeyManager.getActiveVaultKey()
            val isAlreadyEncryptedWithActiveKey = decodedHeader?.unwrappedWithActivePortableKey == true
            val shouldRewrap = reencryptForCurrentVault && activeKey != null && !isAlreadyEncryptedWithActiveKey

            if (shouldRewrap) {
                plaintextTemp.inputStream().buffered().use { plainIn ->
                    cryptoEngine.encryptToFile(plainIn, internalBlob!!, activeKey)
                }
            } else {
                context.contentResolver.openInputStream(source.uri)?.buffered()?.use { input ->
                    internalBlob!!.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Gagal menyalin ciphertext backup")
            }

            var encryptedThumbName: String? = null
            if (info.category == MediaCategory.IMAGE || info.category == MediaCategory.VIDEO) {
                val existingThumbDoc = manifestEntry?.thumbFileName?.let { vaultFolder.findFile(it) }
                val thumbName = "$id.thm"
                internalThumb = File(thumbsDir, thumbName)
                if (existingThumbDoc != null && !shouldRewrap) {
                    context.contentResolver.openInputStream(existingThumbDoc.uri)?.buffered()?.use { tIn ->
                        internalThumb!!.outputStream().buffered().use { tOut -> tIn.copyTo(tOut) }
                    }
                    encryptedThumbName = thumbName
                } else {
                    val rewrappedThumb = existingThumbDoc?.let { thumbDoc ->
                        runCatching {
                            val encryptedThumbBytes = context.contentResolver.openInputStream(thumbDoc.uri)?.use { it.readBytes() }
                            if (encryptedThumbBytes != null && encryptedThumbBytes.isNotEmpty()) {
                                val plainThumb = cryptoEngine.decryptBytes(encryptedThumbBytes, overrideKey)
                                try {
                                    cryptoEngine.encryptBytes(plainThumb, activeKey)
                                } finally {
                                    plainThumb.fill(0)
                                }
                            } else null
                        }.getOrNull()
                    }
                    if (rewrappedThumb != null && rewrappedThumb.isNotEmpty()) {
                        internalThumb!!.writeBytes(rewrappedThumb)
                        encryptedThumbName = thumbName
                    } else {
                        val thumbBytes = createThumbnailJpegFromFile(plaintextTemp, info.category)
                        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                            internalThumb!!.writeBytes(cryptoEngine.encryptBytes(thumbBytes, activeKey))
                            encryptedThumbName = thumbName
                            thumbBytes.fill(0)
                        }
                    }
                }
            }

            val contentSha256 = runCatching { computeFileSha256(plaintextTemp) }.getOrNull()

            VaultFileEntity(
                id = id,
                albumId = albumId,
                originalName = info.displayName,
                mimeType = info.mimeType,
                category = info.category,
                originalSizeBytes = plaintextTemp.length(),
                encryptedFileName = encryptedName,
                encryptedThumbName = encryptedThumbName,
                encryptionAlgorithm = "AES-256-GCM",
                createdAt = manifestEntry?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                isFavorite = manifestEntry?.isFavorite ?: false,
                contentHash = contentSha256
            )
        } catch (error: Throwable) {
            internalBlob?.let(::secureDeleteFile)
            internalThumb?.let(::secureDeleteFile)
            if (error is AEADBadTagException) {
                throw SecurityException("PIN pemulihan tidak cocok atau kunci enkripsi berbeda", error)
            }
            throw error
        } finally {
            secureDeleteFile(plaintextTemp)
        }
    }

    private data class RecoveredFileInfo(val mimeType: String, val displayName: String, val category: MediaCategory)

    private fun detectRecoveredFileInfo(file: File, backupName: String): RecoveredFileInfo {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }.coerceAtLeast(0)
        val baseName = backupName.removeSuffix(".vault")
        val baseExt = baseName.substringAfterLast('.', "").lowercase()
        val baseMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(baseExt)
        val mime = when {
            read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
            read >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            read >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8") -> "image/gif"
            read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            read >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> "video/mp4"
            read >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "video/webm"
            read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "%PDF" -> "application/pdf"
            read >= 3 && String(header, 0, 3, Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
            read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE" -> "audio/wav"
            read >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "OggS" -> "audio/ogg"
            read >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            baseMime != null -> baseMime
            else -> "application/octet-stream"
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: baseExt.takeIf { it.isNotBlank() && !it.matches(Regex("[0-9a-fA-F-]{20,}")) }
        val looksUuid = baseName.matches(Regex("[0-9a-fA-F-]{20,}"))
        val stableStem = if (looksUuid || baseName.isBlank()) baseName else baseName.substringBeforeLast('.', baseName)
        val preservedExt = ext ?: baseExt.takeIf { it.isNotBlank() && !looksUuid }
        val displayName = "Recovered_" + stableStem + (preservedExt?.let { ".$it" } ?: "")
        return RecoveredFileInfo(mime, displayName, MediaCategory.fromMimeType(mime, displayName))
    }

    private fun createThumbnailJpegFromFile(file: File, category: MediaCategory): ByteArray? = runCatching {
        val bitmap = when (category) {
            MediaCategory.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })?.let { scaleBitmapDown(it, 360) }
                }
            }
            MediaCategory.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L)
                    frame?.let { scaleBitmapDown(it, 360) }
                } finally { retriever.release() }
            }
            else -> null
        }
        bitmap?.let {
            val output = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 82, output)
            it.recycle()
            output.toByteArray()
        }
    }.getOrNull()

    /** Decrypts external ciphertext into app-private temporary storage for secure viewing only. */
    suspend fun decryptExternalEncryptedFileToEphemeralCache(
        sourceUri: Uri,
        displayName: String? = null,
        overrideKey: SecretKey? = null
    ): File = withContext(Dispatchers.IO) {
        val safeName = (displayName ?: "encrypted_preview").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val previewDir = File(context.cacheDir, "vault_ephemeral").apply { if (!exists()) mkdirs() }
        val tempFile = File(previewDir, "external_" + UUID.randomUUID() + "_" + safeName + ".tmp")
        try {
            context.contentResolver.openInputStream(sourceUri)?.let { rawIn ->
                java.io.BufferedInputStream(rawIn, VaultCryptoEngine.IO_BUFFER_SIZE)
            }?.use { input ->
                java.io.BufferedOutputStream(tempFile.outputStream(), VaultCryptoEngine.IO_BUFFER_SIZE).use { output ->
                    cryptoEngine.decryptStream(input, output, overrideKey)
                }
            } ?: throw IllegalStateException("File terenkripsi eksternal tidak dapat dibaca")
            tempFile
        } catch (error: Throwable) {
            secureDeleteFile(tempFile)
            throw error
        }
    }

    private fun copyCipherFile(
        source: File,
        folder: DocumentFile,
        targetName: String,
        deleteIfPresent: Boolean = true
    ) {
        if (deleteIfPresent) {
            folder.findFile(targetName)?.delete()
        }
        val target = folder.createFile("application/octet-stream", targetName) ?: return
        context.contentResolver.openOutputStream(target.uri, "w")?.let { rawOut ->
            java.io.BufferedOutputStream(rawOut, VaultCryptoEngine.IO_BUFFER_SIZE)
        }?.use { output ->
            java.io.BufferedInputStream(source.inputStream(), VaultCryptoEngine.IO_BUFFER_SIZE).use { input ->
                input.copyTo(output, bufferSize = VaultCryptoEngine.IO_BUFFER_SIZE)
            }
        }
    }

    suspend fun importUri(
        uri: Uri,
        albumId: String,
        deleteOriginal: Boolean,
        overrideKey: SecretKey? = null
    ): ImportedFileOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, queriedSize) = runCatching { queryDisplayNameAndSize(uri) }
            .getOrElse { "file_${System.currentTimeMillis()}" to 0L }
        val mimeType = runCatching { resolver.getType(uri) }
            .getOrNull() ?: "application/octet-stream"
        val category = MediaCategory.fromMimeType(mimeType, displayName)

        val fileId = UUID.randomUUID().toString()
        val encryptedBlobName = "$fileId.vlt"
        val destBlobFile = File(blobsDir, encryptedBlobName)

        var encryptedThumbName: String? = null
        try {
            val encryptResult = resolver.openInputStream(uri)?.buffered()?.use { input ->
                cryptoEngine.encryptToFileWithHash(input, destBlobFile, overrideKey)
            } ?: throw IllegalStateException("Tidak dapat membaca file sumber")

            val finalSize = if (encryptResult.totalPlaintextSize > 0) {
                encryptResult.totalPlaintextSize
            } else {
                queriedSize.coerceAtLeast(0L)
            }

            if (category == MediaCategory.IMAGE || category == MediaCategory.VIDEO) {
                val thumbBytes = createThumbnailJpeg(uri, category)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val thumbName = "$fileId.thm"
                    val thumbFile = File(thumbsDir, thumbName)
                    thumbFile.writeBytes(cryptoEngine.encryptBytes(thumbBytes, overrideKey))
                    encryptedThumbName = thumbName
                    thumbBytes.fill(0)
                }
            }

            val entity = VaultFileEntity(
                id = fileId,
                albumId = albumId,
                originalName = displayName,
                mimeType = mimeType,
                category = category,
                originalSizeBytes = finalSize,
                encryptedFileName = encryptedBlobName,
                encryptedThumbName = encryptedThumbName,
                encryptionAlgorithm = "AES-256-GCM",
                createdAt = System.currentTimeMillis(),
                contentHash = encryptResult.sha256Hex
            )

            val resolvedMediaUri = if (deleteOriginal) {
                resolveMediaStoreUri(uri, mimeType)
            } else null

            val (relPath, filePath) = if (deleteOriginal) {
                querySourcePathHints(uri, resolvedMediaUri)
            } else null to null

            ImportedFileOutcome(
                entity = entity,
                sourceUri = uri,
                originalDeletedDirectly = false,
                resolvedMediaUri = resolvedMediaUri,
                sourceRelativePath = relPath,
                sourceFilePath = filePath
            )
        } catch (error: Throwable) {
            secureDeleteFile(destBlobFile)
            encryptedThumbName?.let { secureDeleteFile(File(thumbsDir, it)) }
            throw error
        }
    }

    fun deleteOriginalAfterImport(
        uri: Uri,
        resolvedMediaUri: Uri?,
        sourceFolderUris: Set<String> = emptySet(),
        displayName: String? = null,
        expectedSizeBytes: Long = -1L,
        sourceRelativePath: String? = null,
        sourceFilePath: String? = null
    ): Boolean = deleteOriginalAfterImportDetailed(
        uri = uri,
        resolvedMediaUri = resolvedMediaUri,
        sourceFolderUris = sourceFolderUris,
        displayName = displayName,
        expectedSizeBytes = expectedSizeBytes,
        sourceRelativePath = sourceRelativePath,
        sourceFilePath = sourceFilePath
    ).deleted

    /**
     * Attempts automatic deletion of the source file after encryption & metadata commit.
     * If Android Scoped Storage protects the file from silent deletion, returns
     * [SourceDeleteAttempt] with `deleted = false` and the resolved MediaStore URI /
     * RecoverableSecurityException IntentSender so the UI can show a confirmation dialog.
     */
    fun deleteOriginalAfterImportDetailed(
        uri: Uri,
        resolvedMediaUri: Uri?,
        sourceFolderUris: Set<String> = emptySet(),
        displayName: String? = null,
        expectedSizeBytes: Long = -1L,
        sourceRelativePath: String? = null,
        sourceFilePath: String? = null
    ): SourceDeleteAttempt {
        val resolver = context.contentResolver

        // 1. Delete via DocumentsContract if it's a non-media SAF document URI (e.g. Pengelola File)
        if (DocumentsContract.isDocumentUri(context, uri) &&
            uri.authority != "com.android.providers.media.documents"
        ) {
            val deletedDoc = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            if (deletedDoc) {
                sourceFilePath?.let { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
                return SourceDeleteAttempt(deleted = true)
            }
        }

        // 2. Try DocumentFile deletion if applicable
        if (!MediaUriResolver.isPhotoPickerUri(uri) && uri.authority != MediaStore.AUTHORITY) {
            val docDeleted = runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() == true }.getOrDefault(false)
            if (docDeleted) {
                sourceFilePath?.let { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
                return SourceDeleteAttempt(deleted = true)
            }
        }

        // 3. Direct MediaStore deletion without confirmation dialog (captures RecoverableSecurityException if thrown)
        var capturedRecoverableSender: IntentSender? = null
        val targetMediaUri = resolvedMediaUri ?: uri
        if (targetMediaUri.authority == MediaStore.AUTHORITY && !MediaUriResolver.isPhotoPickerUri(targetMediaUri)) {
            val directDeleted = try {
                resolver.delete(targetMediaUri, null, null) > 0
            } catch (se: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se is android.app.RecoverableSecurityException) {
                    capturedRecoverableSender = se.userAction.actionIntent.intentSender
                }
                false
            } catch (_: Throwable) {
                false
            }
            if (directDeleted) return SourceDeleteAttempt(deleted = true)

            // Fallback: try direct filesystem delete if path is writable and re-scan MediaStore
            val filePath = sourceFilePath ?: queryDataPath(targetMediaUri)
            if (!filePath.isNullOrBlank()) {
                val targetFile = File(filePath)
                if (targetFile.exists() && runCatching { targetFile.delete() }.getOrDefault(false)) {
                    MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                    runCatching { resolver.delete(targetMediaUri, null, null) }
                    return SourceDeleteAttempt(deleted = true)
                }
            }
        }

        // 4. Last direct URI attempt before SAF source-tree lookup
        if (uri != targetMediaUri && !MediaUriResolver.isPhotoPickerUri(uri)) {
            val fallbackDeleted = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            if (fallbackDeleted) return SourceDeleteAttempt(deleted = true)
        }

        // 5. Zero-permission Play-compliant SAF Source Folder deletion (OpenDocumentTree grant)
        if (sourceFolderUris.isNotEmpty() && !displayName.isNullOrBlank()) {
            val deletedViaSafTree = deleteFromGrantedSourceTrees(
                sourceFolderUris = sourceFolderUris,
                displayName = displayName,
                expectedSizeBytes = expectedSizeBytes,
                relativePath = sourceRelativePath,
                knownFilePath = sourceFilePath,
                targetMediaUri = targetMediaUri
            )
            if (deletedViaSafTree) return SourceDeleteAttempt(deleted = true)
        }

        val confirmableMediaUri = targetMediaUri.takeIf {
            it.authority == MediaStore.AUTHORITY && !MediaUriResolver.isPhotoPickerUri(it)
        }
        return SourceDeleteAttempt(
            deleted = false,
            mediaStoreUri = confirmableMediaUri,
            recoverableIntentSender = capturedRecoverableSender
        )
    }

    private fun deleteFromGrantedSourceTrees(
        sourceFolderUris: Set<String>,
        displayName: String,
        expectedSizeBytes: Long,
        relativePath: String?,
        knownFilePath: String?,
        targetMediaUri: Uri
    ): Boolean {
        val resolver = context.contentResolver
        for (treeStr in sourceFolderUris) {
            val treeUri = runCatching { Uri.parse(treeStr) }.getOrNull() ?: continue
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            if (!rootDoc.exists() || !rootDoc.canWrite()) continue

            val match = findMatchingFileInTree(
                root = rootDoc,
                displayName = displayName,
                expectedSizeBytes = expectedSizeBytes,
                relativePath = relativePath
            ) ?: continue

            val deleted = runCatching {
                DocumentsContract.deleteDocument(resolver, match.uri)
            }.getOrDefault(false) || runCatching { match.delete() }.getOrDefault(false)

            if (deleted) {
                val scanPath = knownFilePath ?: reconstructScanPath(match.uri, relativePath, displayName)
                if (!scanPath.isNullOrBlank()) {
                    MediaScannerConnection.scanFile(context, arrayOf(scanPath), null, null)
                }
                if (targetMediaUri.authority == MediaStore.AUTHORITY && !MediaUriResolver.isPhotoPickerUri(targetMediaUri)) {
                    runCatching { resolver.delete(targetMediaUri, null, null) }
                }
                return true
            }
        }
        return false
    }

    private fun findMatchingFileInTree(
        root: DocumentFile,
        displayName: String,
        expectedSizeBytes: Long,
        relativePath: String?
    ): DocumentFile? {
        fun isMatch(doc: DocumentFile): Boolean {
            if (!doc.isFile || doc.name != displayName) return false
            if (expectedSizeBytes <= 0L) return true
            val len = doc.length()
            return len <= 0L || len == expectedSizeBytes
        }

        // Fast path: check directly in root
        root.findFile(displayName)?.takeIf(::isMatch)?.let { return it }

        // Fast path using relativePath segments (e.g., "DCIM/Camera/")
        if (!relativePath.isNullOrBlank()) {
            val segments = relativePath.trim('/').split('/').filter { it.isNotBlank() }
            for (startIdx in segments.indices) {
                var current: DocumentFile? = root
                for (i in startIdx until segments.size) {
                    val seg = segments[i]
                    if (current?.name.equals(seg, ignoreCase = true) == true && i == startIdx) {
                        continue
                    }
                    current = current?.findFile(seg)?.takeIf { it.isDirectory }
                    if (current == null) break
                }
                current?.findFile(displayName)?.takeIf(::isMatch)?.let { return it }
            }
        }

        // Bounded shallow BFS (depth <= 3, max 250 inspected entries) so UI never hangs
        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 250) {
            val (dir, depth) = queue.removeFirst()
            if (depth >= 3) continue
            for (child in dir.listFiles()) {
                visited++
                if (isMatch(child)) return child
                if (child.isDirectory && depth + 1 < 3) {
                    queue.add(child to depth + 1)
                }
                if (visited >= 250) break
            }
        }
        return null
    }

    private fun reconstructScanPath(docUri: Uri, relativePath: String?, displayName: String): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull()
        if (!docId.isNullOrBlank() && docId.startsWith("primary:", ignoreCase = true)) {
            val sub = docId.substringAfter(':').trimStart('/')
            return "/storage/emulated/0/$sub"
        }
        if (!relativePath.isNullOrBlank()) {
            return "/storage/emulated/0/${relativePath.trim('/')}/$displayName"
        }
        return null
    }

    private fun querySourcePathHints(originalUri: Uri, resolvedMediaUri: Uri?): Pair<String?, String?> {
        val candidate = resolvedMediaUri ?: originalUri
        if (candidate.authority != MediaStore.AUTHORITY || MediaUriResolver.isPhotoPickerUri(candidate)) {
            return null to null
        }
        return runCatching {
            val projection = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                }
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }.toTypedArray()
            var rel: String? = null
            var data: String? = null
            context.contentResolver.query(candidate, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        if (relIdx >= 0) rel = cursor.getString(relIdx)
                    }
                    @Suppress("DEPRECATION")
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0) data = cursor.getString(dataIdx)
                }
            }
            rel to data
        }.getOrElse { null to null }
    }

    private fun queryDataPath(mediaUri: Uri): String? {
        return runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    @Suppress("DEPRECATION")
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    private fun resolveMediaStoreUri(uri: Uri, mimeType: String?): Uri? {
        return try {
            // Case 1: SAF Media Documents URI (e.g. content://com.android.providers.media.documents/document/image%3A123)
            if (DocumentsContract.isDocumentUri(context, uri) &&
                uri.authority == "com.android.providers.media.documents"
            ) {
                val docId = DocumentsContract.getDocumentId(uri)
                return MediaUriResolver.parseMediaDocumentsId(docId)
            }

            // Case 2: Standard non-picker MediaStore URI
            if (uri.authority == MediaStore.AUTHORITY && !MediaUriResolver.isPhotoPickerUri(uri)) {
                return uri
            }

            // Case 3: Photo Picker or provider URI -> try MediaStore.getMediaUri first (if not returning a picker URI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val viaSystem = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
                if (viaSystem != null &&
                    viaSystem.authority == MediaStore.AUTHORITY &&
                    !MediaUriResolver.isPhotoPickerUri(viaSystem)
                ) {
                    return viaSystem
                }
            }

            // Case 4: Extract numeric MediaStore ID from Photo Picker URI (/picker/.../media/<id>)
            if (MediaUriResolver.isPhotoPickerUri(uri)) {
                val mediaId = MediaUriResolver.extractNumericPickerMediaId(uri)
                if (mediaId != null) {
                    return MediaUriResolver.buildMediaStoreUriForId(mediaId, mimeType)
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadDecryptedThumbnail(fileEntity: VaultFileEntity): Bitmap? = withContext(Dispatchers.IO) {
        val thumbName = fileEntity.encryptedThumbName ?: return@withContext null
        thumbnailCache.get(fileEntity.id)?.let { return@withContext it }

        val thumbFile = File(thumbsDir, thumbName)
        if (!thumbFile.exists()) return@withContext null

        return@withContext runCatching {
            val plainBytes = cryptoEngine.decryptBytes(thumbFile)
            val bitmap = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.size)
            plainBytes.fill(0)
            if (bitmap != null) {
                thumbnailCache.put(fileEntity.id, bitmap)
            }
            bitmap
        }.getOrNull()
    }

    /** Returns false when a database entry exists but its encrypted payload is missing/empty. */
    fun hasLocalEncryptedBlob(fileEntity: VaultFileEntity): Boolean {
        val blob = File(blobsDir, fileEntity.encryptedFileName)
        return blob.isFile && blob.length() > VaultCryptoEngine.FIXED_HEADER_SIZE
    }

    suspend fun decryptToBytes(fileEntity: VaultFileEntity): ByteArray = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        if (!blobFile.exists()) {
            throw IllegalStateException("File terenkripsi tidak ditemukan di brankas")
        }
        cryptoEngine.decryptBytes(blobFile)
    }

    suspend fun decryptToUri(fileEntity: VaultFileEntity, destinationUri: Uri) = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        if (!blobFile.exists()) {
            throw IllegalStateException("File terenkripsi tidak ditemukan")
        }
        context.contentResolver.openOutputStream(destinationUri)?.let { rawOut ->
            java.io.BufferedOutputStream(rawOut, VaultCryptoEngine.IO_BUFFER_SIZE)
        }?.use { out ->
            java.io.BufferedInputStream(blobFile.inputStream(), VaultCryptoEngine.IO_BUFFER_SIZE).use { input ->
                cryptoEngine.decryptStream(input, out)
            }
        } ?: throw IllegalStateException("Gagal membuka tujuan ekspor")
    }

    suspend fun exportToFolderUri(
        fileEntity: VaultFileEntity,
        destinationTreeUri: Uri
    ): Uri = withContext(Dispatchers.IO) {
        val destFolder = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: throw IllegalStateException("Folder tujuan tidak dapat dibuka")
        if (!destFolder.exists() || !destFolder.canWrite()) {
            throw IllegalStateException("Folder tujuan tidak memiliki izin tulis")
        }
        val targetName = findUniqueDocumentName(destFolder, fileEntity.originalName)
        val mime = fileEntity.mimeType.ifBlank { "application/octet-stream" }
        val targetDoc = destFolder.createFile(mime, targetName)
            ?: destFolder.createFile("application/octet-stream", targetName)
            ?: throw IllegalStateException("Gagal membuat file \"$targetName\" di folder tujuan")
        try {
            decryptToUri(fileEntity, targetDoc.uri)
            reconstructScanPath(targetDoc.uri, null, targetName)?.let { scanPath ->
                MediaScannerConnection.scanFile(context, arrayOf(scanPath), arrayOf(mime), null)
            }
            targetDoc.uri
        } catch (t: Throwable) {
            runCatching { targetDoc.delete() }
            throw t
        }
    }

    suspend fun exportToDeviceMediaOrDownloads(fileEntity: VaultFileEntity): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Ekspor langsung ke Galeri memerlukan Android 10+. Gunakan Pilih Folder Tujuan.")
        }
        val resolver = context.contentResolver
        val mime = fileEntity.mimeType.ifBlank { "application/octet-stream" }
        val (collectionUri, relativeDir) = when (fileEntity.category) {
            MediaCategory.IMAGE -> {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_PICTURES}/$VAULT_FOLDER_NAME"
            }
            MediaCategory.VIDEO -> {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MOVIES}/$VAULT_FOLDER_NAME"
            }
            MediaCategory.AUDIO -> {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MUSIC}/$VAULT_FOLDER_NAME"
            }
            MediaCategory.DOCUMENT, MediaCategory.OTHER -> {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_DOWNLOADS}/$VAULT_FOLDER_NAME"
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileEntity.originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collectionUri, values)
            ?: throw IllegalStateException("Gagal membuat entri MediaStore untuk ${fileEntity.originalName}")
        try {
            decryptToUri(fileEntity, itemUri)
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)
            itemUri
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }
    }

    private fun findUniqueDocumentName(folder: DocumentFile, desiredName: String): String {
        val cleanName = desiredName.ifBlank { "file_${System.currentTimeMillis()}" }
        if (folder.findFile(cleanName) == null) return cleanName
        val dotIdx = cleanName.lastIndexOf('.')
        val stem = if (dotIdx > 0) cleanName.substring(0, dotIdx) else cleanName
        val ext = if (dotIdx > 0) cleanName.substring(dotIdx) else ""
        for (counter in 1..999) {
            val candidate = "$stem ($counter)$ext"
            if (folder.findFile(candidate) == null) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    fun openEncryptedMediaDataSource(fileEntity: VaultFileEntity): MediaDataSource {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        return cryptoEngine.openEncryptedMediaDataSource(blobFile)
    }

    /**
     * Decrypts a document (e.g. PDF) into an app-private ephemeral cache file for PdfRenderer.
     * Caller MUST invoke [secureDeleteFile] when exiting the viewer.
     */
    suspend fun decryptToEphemeralCacheFile(fileEntity: VaultFileEntity): File = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        val previewDir = File(context.cacheDir, "vault_ephemeral").apply { if (!exists()) mkdirs() }
        val tempFile = File(previewDir, "${UUID.randomUUID()}.tmp")
        try {
            java.io.BufferedOutputStream(tempFile.outputStream(), VaultCryptoEngine.IO_BUFFER_SIZE).use { out ->
                java.io.BufferedInputStream(blobFile.inputStream(), VaultCryptoEngine.IO_BUFFER_SIZE).use { input ->
                    cryptoEngine.decryptStream(input, out)
                }
            }
            tempFile
        } catch (t: Throwable) {
            secureDeleteFile(tempFile)
            throw t
        }
    }

    fun deleteEncryptedAssets(fileEntity: VaultFileEntity) {
        val blob = File(blobsDir, fileEntity.encryptedFileName)
        val thumb = fileEntity.encryptedThumbName?.let { File(thumbsDir, it) }
        val blobDeleted = deleteCiphertextFile(blob)
        val thumbDeleted = thumb?.let(::deleteCiphertextFile) ?: true
        if (!blobDeleted || !thumbDeleted) {
            throw IllegalStateException("File terenkripsi gagal dihapus. Ciphertext dipertahankan agar tetap dapat dibaca.")
        }
        thumbnailCache.remove(fileEntity.id)
    }

    private fun deleteCiphertextFile(file: File): Boolean {
        if (!file.exists()) return true
        runCatching { file.delete() }
        return !file.exists()
    }

    fun secureDeleteFile(file: File): Boolean {
        if (!file.exists()) return true
        runCatching {
            val len = file.length()
            if (len > 0L) {
                RandomAccessFile(file, "rw").use { raf ->
                    val wipeLen = minOf(len, 65536L).toInt()
                    raf.seek(0L)
                    raf.write(ByteArray(wipeLen))
                }
            }
        }
        runCatching { file.delete() }
        return !file.exists()
    }

    fun clearSensitiveCaches() {
        thumbnailCache.evictAll()
        val previewDir = File(context.cacheDir, "vault_ephemeral")
        previewDir.listFiles()?.forEach { secureDeleteFile(it) }
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                if (sizeIdx != -1) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun createThumbnailJpeg(uri: Uri, category: MediaCategory): ByteArray? {
        return runCatching {
            val bitmap: Bitmap? = when (category) {
                MediaCategory.IMAGE -> decodeSampledBitmapFromUri(uri, 360, 360)
                MediaCategory.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(0L)
                        frame?.let { scaleBitmapDown(it, 360) }
                    } finally {
                        retriever.release()
                    }
                }
                else -> null
            }
            bitmap?.let { bmp ->
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                bmp.recycle()
                baos.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val height = options.outHeight
        val width = options.outWidth
        if (height <= 0 || width <= 0) return null

        var inSampleSize = 1
        while (width / inSampleSize > reqWidth * 2 || height / inSampleSize > reqHeight * 2) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }?.let { scaleBitmapDown(it, 360) }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap
        val ratio = width.toFloat() / height.toFloat()
        val newW: Int
        val newH: Int
        if (ratio > 1f) {
            newW = maxDimension
            newH = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            newH = maxDimension
            newW = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    suspend fun saveAdjustedVaultImage(
        sourceFile: VaultFileEntity,
        newPlainBytes: ByteArray,
        targetMimeType: String,
        overwriteOriginal: Boolean
    ): VaultFileEntity = withContext(Dispatchers.IO) {
        if (newPlainBytes.isEmpty()) {
            throw IllegalArgumentException("Hasil edit gambar kosong")
        }
        val activeKey = vaultKeyManager.getActiveVaultKey()
        val contentSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(newPlainBytes)
            .joinToString("") { "%02x".format(it) }

        if (overwriteOriginal) {
            val targetBlob = File(blobsDir, sourceFile.encryptedFileName)
            val tempBlob = File(blobsDir, "${sourceFile.encryptedFileName}.${UUID.randomUUID()}.tmp")
            val thumbName = sourceFile.encryptedThumbName ?: "${sourceFile.id}.thm"
            val targetThumb = File(thumbsDir, thumbName)
            val tempThumb = File(thumbsDir, "$thumbName.${UUID.randomUUID()}.tmp")
            var hasNewThumb = false
            try {
                java.io.ByteArrayInputStream(newPlainBytes).use { input ->
                    cryptoEngine.encryptToFile(input, tempBlob, activeKey)
                }
                val thumbBytes = createThumbnailJpegFromImageBytes(newPlainBytes)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    try {
                        tempThumb.writeBytes(cryptoEngine.encryptBytes(thumbBytes, activeKey))
                        hasNewThumb = true
                    } finally {
                        thumbBytes.fill(0)
                    }
                }
                replaceFileAtomically(tempBlob, targetBlob)
                if (hasNewThumb) {
                    replaceFileAtomically(tempThumb, targetThumb)
                } else {
                    secureDeleteFile(tempThumb)
                }
                thumbnailCache.remove(sourceFile.id)
                sourceFile.copy(
                    mimeType = targetMimeType,
                    originalSizeBytes = newPlainBytes.size.toLong(),
                    encryptedThumbName = if (hasNewThumb) thumbName else sourceFile.encryptedThumbName,
                    contentHash = contentSha256
                )
            } catch (t: Throwable) {
                secureDeleteFile(tempBlob)
                secureDeleteFile(tempThumb)
                throw t
            }
        } else {
            val newId = UUID.randomUUID().toString()
            val newBlobName = "$newId.vlt"
            val newBlobFile = File(blobsDir, newBlobName)
            var newThumbName: String? = null
            var newThumbFile: File? = null
            try {
                java.io.ByteArrayInputStream(newPlainBytes).use { input ->
                    cryptoEngine.encryptToFile(input, newBlobFile, activeKey)
                }
                val thumbBytes = createThumbnailJpegFromImageBytes(newPlainBytes)
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    try {
                        val tName = "$newId.thm"
                        val tFile = File(thumbsDir, tName)
                        tFile.writeBytes(cryptoEngine.encryptBytes(thumbBytes, activeKey))
                        newThumbName = tName
                        newThumbFile = tFile
                    } finally {
                        thumbBytes.fill(0)
                    }
                }
                val copyName = com.example.ui.components.ImageAdjustmentProcessor.buildEditedCopyFileName(
                    originalName = sourceFile.originalName,
                    targetMimeType = targetMimeType
                )
                VaultFileEntity(
                    id = newId,
                    albumId = sourceFile.albumId,
                    originalName = copyName,
                    mimeType = targetMimeType,
                    category = MediaCategory.IMAGE,
                    originalSizeBytes = newPlainBytes.size.toLong(),
                    encryptedFileName = newBlobName,
                    encryptedThumbName = newThumbName,
                    encryptionAlgorithm = "AES-256-GCM",
                    createdAt = System.currentTimeMillis(),
                    isFavorite = false,
                    contentHash = contentSha256
                )
            } catch (t: Throwable) {
                secureDeleteFile(newBlobFile)
                newThumbFile?.let(::secureDeleteFile)
                throw t
            }
        }
    }

    suspend fun computePlaintextSha256(fileEntity: VaultFileEntity): String = withContext(Dispatchers.IO) {
        val blobFile = File(blobsDir, fileEntity.encryptedFileName)
        cryptoEngine.computePlaintextSha256(blobFile)
    }

    private fun computeFileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun replaceFileAtomically(sourceTemp: File, destination: File) {
        if (!sourceTemp.exists()) {
            throw IllegalStateException("File sementara hasil enkripsi tidak ditemukan")
        }
        val backup = File(destination.parentFile, "${destination.name}.bak_${UUID.randomUUID()}")
        val hadExisting = destination.exists()
        if (hadExisting && !destination.renameTo(backup)) {
            if (!destination.delete()) {
                throw IllegalStateException("Gagal mengganti file terenkripsi lama")
            }
        }
        if (!sourceTemp.renameTo(destination)) {
            if (hadExisting && backup.exists()) {
                backup.renameTo(destination)
            }
            throw IllegalStateException("Gagal menyimpan file terenkripsi baru")
        }
        if (backup.exists()) {
            deleteCiphertextFile(backup)
        }
    }

    private fun createThumbnailJpegFromImageBytes(imageBytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return@runCatching null
        val scaled = scaleBitmapDown(decoded, 360)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        scaled.recycle()
        out.toByteArray()
    }.getOrNull()
}

