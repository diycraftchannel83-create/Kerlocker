package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.BatchImportResult
import com.example.data.VaultRepository
import com.example.ui.ImportProgressState
import com.example.ui.PendingSourceDeletePrompt
import com.example.ui.VaultEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

class BackgroundEncryptionCoordinator(
    private val appContext: Context,
    private val repository: VaultRepository
) {
    private data class ImportJob(
        val uris: List<Uri>,
        val albumId: String,
        val deleteOriginal: Boolean,
        val capturedKey: SecretKey?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobQueue = Channel<ImportJob>(Channel.UNLIMITED)

    private val _progressFlow = MutableStateFlow<ImportProgressState?>(null)
    val progressFlow: StateFlow<ImportProgressState?> = _progressFlow.asStateFlow()

    private val _pendingSourceDeleteFlow = MutableStateFlow<PendingSourceDeletePrompt?>(null)
    val pendingSourceDeleteFlow: StateFlow<PendingSourceDeletePrompt?> = _pendingSourceDeleteFlow.asStateFlow()

    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    private val totalQueuedFiles = AtomicInteger(0)
    private val totalProcessedFiles = AtomicInteger(0)

    val isBusy: Boolean
        get() = _progressFlow.value != null

    init {
        scope.launch {
            for (job in jobQueue) {
                processSingleJob(job)
                if (jobQueue.isEmpty) {
                    totalQueuedFiles.set(0)
                    totalProcessedFiles.set(0)
                    _progressFlow.value = null
                }
            }
        }
    }

    // Persistent scan index: results are kept app-private and reused for recovery.
    private val galleryScanPrefs by lazy {
        appContext.getSharedPreferences("gallery_scan_cache", Context.MODE_PRIVATE)
    }

    fun cachedGalleryScanCount(): Int = runCatching {
        org.json.JSONArray(galleryScanPrefs.getString("items", "[]")).length()
    }.getOrDefault(0)

    fun scanGalleryAndCache() {
        scope.launch {
            val imagesPermission = if (android.os.Build.VERSION.SDK_INT >= 33) appContext.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED else appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val videosPermission = if (android.os.Build.VERSION.SDK_INT >= 33) appContext.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED else appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!imagesPermission && !videosPermission) {
                _events.tryEmit(VaultEvent.ShowMessage("Izin foto/video diperlukan untuk scan galeri."))
                return@launch
            }
            runCatching {
                val cache = org.json.JSONArray()
                val resolver = appContext.contentResolver
                if (imagesPermission) resolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME, android.provider.MediaStore.Images.Media.SIZE, android.provider.MediaStore.Images.Media.DATE_ADDED), null, null, "date_added DESC")?.use { c ->
                    val id = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID); val name = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME); val size = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE); val date = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                    while (c.moveToNext()) { val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)); cache.put(org.json.JSONObject().put("uri", uri.toString()).put("name", c.getString(name) ?: "").put("size", c.getLong(size)).put("date", c.getLong(date)).put("type", "image")) }
                }
                if (videosPermission) resolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(android.provider.MediaStore.Video.Media._ID, android.provider.MediaStore.Video.Media.DISPLAY_NAME, android.provider.MediaStore.Video.Media.SIZE, android.provider.MediaStore.Video.Media.DATE_ADDED), null, null, "date_added DESC")?.use { c ->
                    val id = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID); val name = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME); val size = c.getColumnIndexOrThrow(android.provider.Media.Media.SIZE); val date = c.getColumnIndexOrThrow(android.provider.Media.Video.Media.DATE_ADDED)
                    while (c.moveToNext()) { val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(id)); cache.put(org.json.JSONObject().put("uri", uri.toString()).put("name", c.getString(name) ?: "").put("size", c.getLong(size)).put("date", c.getLong(date)).put("type", "video")) }
                }
                galleryScanPrefs.edit().putString("items", cache.toString()).putLong("scan_time", System.currentTimeMillis()).apply()
                _events.tryEmit(VaultEvent.ShowMessage("Scan selesai: " + cache.length() + " media tersimpan sebagai indeks recovery. Bisa dipakai ulang tanpa scan ulang."))
            }.onFailure { e -> _events.tryEmit(VaultEvent.ShowMessage("Scan galeri gagal: " + (e.localizedMessage ?: "akses galeri bermasalah"))) }
        }
    }

    fun reuseCachedGalleryScan() {
        scope.launch {
            runCatching {
                val cached = org.json.JSONArray(galleryScanPrefs.getString("items", "[]"))
                if (cached.length() == 0) { _events.tryEmit(VaultEvent.ShowMessage("Belum ada indeks scan tersimpan. Jalankan Scan Galeri dahulu.")); return@launch }
                repository.ensureDefaultAlbums()
                val targetAlbum = repository.getRecoveryTargetAlbumId()
                val existing = repository.getVaultOriginalNames().map { it.lowercase() }.toSet()
                val uris = mutableListOf<Uri>()
                for (i in 0 until cached.length()) { val item = cached.optJSONObject(i) ?: continue; val name = item.optString("name").lowercase(); val uri = item.optString("uri"); if (name.isNotBlank() && name !in existing && uri.isNotBlank()) uris += Uri.parse(uri) }
                if (uris.isEmpty()) _events.tryEmit(VaultEvent.ShowMessage("Indeks scan tersimpan: tidak ada media baru yang belum diamankan."))
                else { enqueueImport(uris, targetAlbum, deleteOriginal = false); _events.tryEmit(VaultEvent.ShowMessage("Recovery memakai indeks scan tersimpan: " + uris.size + " media antre tanpa scan ulang.")) }
            }.onFailure { e -> _events.tryEmit(VaultEvent.ShowMessage("Recovery indeks scan gagal: " + (e.localizedMessage ?: "error"))) }
        }
    }

    fun enqueueImport(uris: List<Uri>, albumId: String, deleteOriginal: Boolean) {
        if (uris.isEmpty()) return

        // Persist read access for SAF document URIs so background execution stays reliable
        uris.forEach { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        val newTotal = totalQueuedFiles.addAndGet(uris.size)
        val doneSoFar = totalProcessedFiles.get()
        _progressFlow.value = ImportProgressState(
            processed = doneSoFar,
            total = newTotal,
            currentFileName = "Mengenkripsi di background ($doneSoFar/$newTotal file)..."
        )

        val capturedKey = repository.getActiveVaultKey()
        jobQueue.trySend(
            ImportJob(
                uris = uris,
                albumId = albumId,
                deleteOriginal = deleteOriginal,
                capturedKey = capturedKey
            )
        )
        VaultEncryptionService.start(appContext)
    }

    private suspend fun processSingleJob(job: ImportJob) {
        val batchStartProcessed = totalProcessedFiles.get()
        val result = runCatching {
            if (job.capturedKey == null) {
                repository.ensureActiveVaultKeyLoaded()
            }
            val effectiveKey = job.capturedKey ?: repository.getActiveVaultKey()
            repository.importFilesBatch(
                uris = job.uris,
                albumId = job.albumId,
                deleteOriginal = job.deleteOriginal,
                overrideKey = effectiveKey
            ) { processedInBatch, _ ->
                val cumulativeDone = batchStartProcessed + processedInBatch
                totalProcessedFiles.set(cumulativeDone)
                val cumulativeTotal = totalQueuedFiles.get().coerceAtLeast(cumulativeDone)
                _progressFlow.value = ImportProgressState(
                    processed = cumulativeDone,
                    total = cumulativeTotal,
                    currentFileName = "Mengenkripsi di background ($cumulativeDone/$cumulativeTotal file)..."
                )
            }
        }.getOrElse { error ->
            totalProcessedFiles.set(batchStartProcessed + job.uris.size)
            BatchImportResult(
                importedCount = 0,
                deletedCount = 0,
                sourceDeleteFailedCount = 0,
                failedCount = job.uris.size,
                firstFailureReason = error.localizedMessage ?: error.javaClass.simpleName
            )
        }

        val hasDestFolder = runCatching {
            repository.securityPreferencesFlow.first().externalVaultFolderUri != null
        }.getOrDefault(false)
        val destNote = if (hasDestFolder) " & folder tujuan" else ""

        val msg = when {
            result.importedCount == 0 && result.failedCount > 0 ->
                "Import gagal untuk ${result.failedCount} file. Penyebab: ${result.firstFailureReason ?: "gagal membaca file"}"
            result.importedCount == 0 ->
                "Tidak ada file yang berhasil diimpor"
            result.failedCount > 0 ->
                "${result.importedCount} file berhasil diamankan$destNote; ${result.failedCount} file gagal."
            job.deleteOriginal && result.deletedCount == result.importedCount ->
                "${result.importedCount} file terenkripsi ke brankas$destNote & langsung dihapus dari Galeri"
            job.deleteOriginal && result.deletedCount > 0 && result.sourceDeleteFailedCount > 0 ->
                "${result.importedCount} file masuk ke brankas$destNote (${result.deletedCount} terhapus, ${result.sourceDeleteFailedCount} dilindungi Android)"
            job.deleteOriginal && result.sourceDeleteFailedCount > 0 ->
                "${result.importedCount} file masuk ke brankas$destNote (konfirmasi diperlukan untuk hapus file asli)"
            else ->
                "${result.importedCount} file berhasil dienkripsi (AES-256) ke brankas$destNote"
        }

        if (job.deleteOriginal && result.sourceDeleteFailedCount > 0 && result.importedCount > 0) {
            _pendingSourceDeleteFlow.update { existing ->
                if (existing == null) {
                    PendingSourceDeletePrompt(
                        importedCount = result.importedCount,
                        autoDeletedCount = result.deletedCount,
                        items = result.pendingSourceDeletes
                    )
                } else {
                    existing.copy(
                        importedCount = existing.importedCount + result.importedCount,
                        autoDeletedCount = existing.autoDeletedCount + result.deletedCount,
                        items = existing.items + result.pendingSourceDeletes
                    )
                }
            }
        } else {
            _events.tryEmit(VaultEvent.ShowMessage(msg))
        }
    }

    fun dismissPendingSourceDeletePrompt() {
        _pendingSourceDeleteFlow.value = null
    }

    fun onSystemSourceDeleteCompleted(deletedCount: Int) {
        _pendingSourceDeleteFlow.value = null
        if (deletedCount > 0) {
            _events.tryEmit(VaultEvent.ShowMessage("$deletedCount file asli berhasil dihapus permanen dari Galeri"))
        } else {
            _events.tryEmit(VaultEvent.ShowMessage("File tetap terenkripsi di Locker; file sumber tidak dihapus"))
        }
    }

    fun onSystemSourceDeleteUnavailable() {
        _pendingSourceDeleteFlow.value = null
        _events.tryEmit(
            VaultEvent.ShowSourceAccessSuggestion(
                "File sudah terenkripsi di Locker. Sumber tidak mendukung konfirmasi hapus langsung — hubungkan folder sumber untuk hapus otomatis."
            )
        )
    }
}
