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
