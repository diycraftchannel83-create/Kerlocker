package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BrankasKuApp
import com.example.biometric.BiometricAuthHelper
import com.example.biometric.BiometricAvailability
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.BatchExportResult
import com.example.data.BatchImportResult
import com.example.data.ExternalVaultEntry
import com.example.data.GallerySortOrder
import com.example.data.MediaCategory
import com.example.data.PendingSourceDeleteItem
import com.example.data.SecurityPreferencesState
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.data.VaultRepository
import com.example.ui.components.ImageAdjustmentParams
import com.example.ui.theme.VaultThemePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.SecretKey

enum class SetupStep {
    CREATE_PIN,
    CONFIRM_PIN,
    BIOMETRIC_OPT_IN
}

data class ImportProgressState(
    val processed: Int,
    val total: Int,
    val currentFileName: String
)

data class ExternalVaultUiState(
    val isScanning: Boolean = false,
    val isRestoring: Boolean = false,
    val folderDisplayName: String = "",
    val hasRecoveryKey: Boolean = false,
    val needsRecoveryPin: Boolean = false,
    val wrongRecoveryPin: Boolean = false,
    val unlockedWithRecoveryPin: Boolean = false,
    val entries: List<ExternalVaultEntry> = emptyList(),
    val activeOverrideKey: SecretKey? = null,
    val errorMessage: String? = null
)

data class PendingSourceDeletePrompt(
    val importedCount: Int,
    val autoDeletedCount: Int,
    val items: List<PendingSourceDeleteItem>
)

data class VaultUiState(
    val isPrefsLoaded: Boolean = false,
    val isLocked: Boolean = true,
    val isSetupComplete: Boolean = false,
    val setupStep: SetupStep = SetupStep.CREATE_PIN,
    val pinInput: String = "",
    val firstPinCandidate: String = "",
    val pinError: String? = null,
    val lockoutSecondsRemaining: Long = 0L,
    val biometricAvailability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    val securityPrefs: SecurityPreferencesState = SecurityPreferencesState(),
    val isHardwareBackedKeystore: Boolean = false,
    val vaultInternalPath: String = "",
    val albums: List<AlbumWithStats> = emptyList(),
    val allFiles: List<VaultFileEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: VaultFileFilter = VaultFileFilter.OVERVIEW,
    val importProgress: ImportProgressState? = null,
    val externalVaultState: ExternalVaultUiState = ExternalVaultUiState(),
    val isRecoveringApp: Boolean = false,
    val lockoutTick: Long = 0L,
    val pendingSourceDeletePrompt: PendingSourceDeletePrompt? = null
)

sealed interface VaultEvent {
    data class ShowMessage(val message: String) : VaultEvent
    data class ShowSourceAccessSuggestion(val message: String) : VaultEvent
}

class BrankasViewModel(
    application: Application,
    private val repository: VaultRepository
) : AndroidViewModel(application) {

    private val _localState = MutableStateFlow(
        VaultUiState(
            biometricAvailability = BiometricAuthHelper.checkAvailability(application),
            isHardwareBackedKeystore = repository.isHardwareKeystoreBacked(),
            vaultInternalPath = repository.getVaultDirectoryDisplay()
        )
    )

    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    @Volatile
    private var suppressNextAutoLock = false

    val uiState: StateFlow<VaultUiState> = combine(
        _localState,
        repository.securityPreferencesFlow,
        repository.albumsWithStatsFlow,
        repository.allFilesFlow
    ) { local, prefs, albums, files ->
        val now = System.currentTimeMillis()
        val lockoutRemaining = if (prefs.lockoutUntilMillis > now) {
            ((prefs.lockoutUntilMillis - now) + 999L) / 1000L
        } else {
            0L
        }
        local.copy(
            isPrefsLoaded = true,
            isSetupComplete = prefs.isSetupComplete,
            securityPrefs = prefs,
            albums = albums,
            allFiles = files,
            lockoutSecondsRemaining = lockoutRemaining
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _localState.value
    )

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val prefs = uiState.value.securityPrefs
                if (prefs.lockoutUntilMillis > System.currentTimeMillis()) {
                    _localState.update { it.copy(lockoutTick = System.currentTimeMillis()) }
                }
            }
        }
    }

    fun observeAlbum(albumId: String): Flow<AlbumEntity?> = repository.observeAlbum(albumId)

    fun observeFilesInAlbum(albumId: String): Flow<List<VaultFileEntity>> =
        repository.observeFilesInAlbum(albumId)

    fun observeFileById(fileId: String): Flow<VaultFileEntity?> =
        repository.observeFileById(fileId)

    fun markLaunchingExternalPicker() {
        suppressNextAutoLock = true
    }

    fun clearExternalPickerFlag() {
        suppressNextAutoLock = false
    }

    fun refreshBiometricAvailability() {
        val availability = BiometricAuthHelper.checkAvailability(getApplication())
        _localState.update { it.copy(biometricAvailability = availability) }
    }

    fun onAppBackgrounded() {
        if (suppressNextAutoLock) return
        val state = uiState.value
        if (state.importProgress != null) return
        if (state.isSetupComplete && state.securityPrefs.autoLockOnBackground && !state.isLocked) {
            lockVault()
        }
    }

    fun lockVault() {
        repository.clearSensitiveCaches()
        _localState.update {
            it.copy(
                isLocked = true,
                pinInput = "",
                pinError = null,
                externalVaultState = it.externalVaultState.copy(
                    activeOverrideKey = null,
                    unlockedWithRecoveryPin = false
                )
            )
        }
    }

    fun onBiometricUnlockSuccess() {
        viewModelScope.launch {
            repository.ensureActiveVaultKeyLoaded()
            repository.ensureDefaultAlbums()
            _localState.update {
                it.copy(
                    isLocked = false,
                    pinInput = "",
                    pinError = null
                )
            }
        }
    }

    fun onPinDigitEntered(digit: Char) {
        val current = uiState.value
        if (current.lockoutSecondsRemaining > 0L) return
        if (current.pinInput.length >= 6) return

        val updated = current.pinInput + digit
        _localState.update { it.copy(pinInput = updated, pinError = null) }

        if (updated.length == 6) {
            if (!current.isSetupComplete) {
                handleSetupPinComplete(updated)
            } else {
                verifyUnlockPin(updated)
            }
        }
    }

    fun onPinBackspace() {
        _localState.update { state ->
            if (state.pinInput.isNotEmpty()) {
                state.copy(pinInput = state.pinInput.dropLast(1), pinError = null)
            } else {
                state
            }
        }
    }

    fun onPinClear() {
        _localState.update { it.copy(pinInput = "", pinError = null) }
    }

    private fun handleSetupPinComplete(pin: String) {
        val step = _localState.value.setupStep
        if (step == SetupStep.CREATE_PIN) {
            _localState.update {
                it.copy(
                    firstPinCandidate = pin,
                    pinInput = "",
                    setupStep = SetupStep.CONFIRM_PIN,
                    pinError = null
                )
            }
        } else if (step == SetupStep.CONFIRM_PIN) {
            if (pin == _localState.value.firstPinCandidate) {
                val bioAvailable = _localState.value.biometricAvailability == BiometricAvailability.AVAILABLE
                if (bioAvailable) {
                    _localState.update {
                        it.copy(
                            setupStep = SetupStep.BIOMETRIC_OPT_IN,
                            pinInput = "",
                            pinError = null
                        )
                    }
                } else {
                    completeInitialSetup(enableBiometric = false)
                }
            } else {
                _localState.update {
                    it.copy(
                        pinInput = "",
                        firstPinCandidate = "",
                        setupStep = SetupStep.CREATE_PIN,
                        pinError = "PIN tidak cocok. Silakan buat ulang PIN 6 digit."
                    )
                }
            }
        }
    }

    fun completeInitialSetup(enableBiometric: Boolean) {
        val pin = _localState.value.firstPinCandidate
        if (pin.length != 6) return
        viewModelScope.launch {
            repository.setupInitialPin(pin, enableBiometric)
            _localState.update {
                it.copy(
                    isLocked = false,
                    pinInput = "",
                    firstPinCandidate = "",
                    pinError = null
                )
            }
            _events.tryEmit(VaultEvent.ShowMessage("Locker siap. Enkripsi AES-256-GCM aktif."))
        }
    }

    fun restoreAppFromExternalFolder(
        folderUri: String,
        pin: String,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _localState.update { it.copy(isRecoveringApp = true) }
            runCatching {
                repository.restoreAppFromExternalFolder(Uri.parse(folderUri), pin, enableBiometric = false)
            }.onSuccess { outcome ->
                _localState.update {
                    it.copy(
                        isRecoveringApp = false,
                        isLocked = false,
                        pinInput = "",
                        firstPinCandidate = "",
                        pinError = null
                    )
                }
                _events.tryEmit(
                    VaultEvent.ShowMessage(
                        "Aplikasi & ${outcome.restoredCount} file terenkripsi berhasil dipulihkan ke Locker."
                    )
                )
            }.onFailure { err ->
                _localState.update { it.copy(isRecoveringApp = false) }
                onError(err.message ?: "Gagal memulihkan brankas. Periksa folder dan PIN Anda.")
            }
        }
    }

    private fun verifyUnlockPin(pin: String) {
        viewModelScope.launch {
            val ok = repository.verifyPin(pin)
            if (ok) {
                repository.ensureDefaultAlbums()
                _localState.update {
                    it.copy(
                        isLocked = false,
                        pinInput = "",
                        pinError = null
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        pinInput = "",
                        pinError = "PIN salah. Coba lagi."
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _localState.update { it.copy(searchQuery = query) }
    }

    fun selectCategoryFilter(filter: VaultFileFilter) {
        _localState.update { it.copy(selectedFilter = filter) }
    }

    fun createAlbum(name: String, iconKey: String, colorHex: Long, isPinned: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createAlbum(name, iconKey, colorHex, isPinned)
            _events.tryEmit(VaultEvent.ShowMessage("Album \"$name\" berhasil dibuat"))
        }
    }

    fun updateAlbum(album: AlbumEntity) {
        if (album.name.isBlank()) return
        viewModelScope.launch {
            repository.updateAlbum(album.copy(name = album.name.trim()))
            _events.tryEmit(VaultEvent.ShowMessage("Album diperbarui"))
        }
    }

    fun deleteAlbum(albumId: String, moveToAlbumId: String?) {
        viewModelScope.launch {
            if (moveToAlbumId != null) {
                repository.deleteAlbumMovingFilesTo(albumId, moveToAlbumId)
                _events.tryEmit(VaultEvent.ShowMessage("Album dihapus & file dipindahkan"))
            } else {
                repository.deleteAlbumAndFiles(albumId)
                _events.tryEmit(VaultEvent.ShowMessage("Album dan file di dalamnya dihapus permanen"))
            }
        }
    }

    fun importUris(uris: List<Uri>, albumId: String) {
        clearExternalPickerFlag()
        if (uris.isEmpty()) return
        val deleteOriginal = uiState.value.securityPrefs.deleteOriginalOnImport

        viewModelScope.launch {
            _localState.update {
                it.copy(
                    importProgress = ImportProgressState(
                        processed = 0,
                        total = uris.size,
                        currentFileName = "Mengenkripsi ${uris.size} file dengan AES-256..."
                    )
                )
            }
            val result = runCatching {
                repository.ensureActiveVaultKeyLoaded()
                repository.importFilesBatch(
                    uris = uris,
                    albumId = albumId,
                    deleteOriginal = deleteOriginal
                ) { processed, total ->
                    _localState.update {
                        it.copy(
                            importProgress = ImportProgressState(
                                processed = processed,
                                total = total,
                                currentFileName = "Mengenkripsi $processed dari $total file..."
                            )
                        )
                    }
                }
            }.getOrElse { error ->
                BatchImportResult(
                    importedCount = 0,
                    deletedCount = 0,
                    sourceDeleteFailedCount = 0,
                    failedCount = uris.size,
                    firstFailureReason = error.localizedMessage ?: error.javaClass.simpleName
                )
            }

            _localState.update { it.copy(importProgress = null) }

            val hasDestFolder = uiState.value.securityPrefs.externalVaultFolderUri != null
            val destNote = if (hasDestFolder) " & folder tujuan" else ""
            val msg = when {
                result.importedCount == 0 && result.failedCount > 0 ->
                    "Import gagal untuk ${result.failedCount} file. Penyebab: ${result.firstFailureReason ?: "gagal membaca file"}"
                result.importedCount == 0 -> "Tidak ada file yang berhasil diimpor"
                result.failedCount > 0 ->
                    "${result.importedCount} file berhasil diamankan$destNote; ${result.failedCount} file gagal."
                deleteOriginal && result.deletedCount == result.importedCount ->
                    "${result.importedCount} file terenkripsi ke brankas$destNote & langsung dihapus dari Galeri"
                deleteOriginal && result.deletedCount > 0 && result.sourceDeleteFailedCount > 0 ->
                    "${result.importedCount} file masuk ke brankas$destNote (${result.deletedCount} terhapus, ${result.sourceDeleteFailedCount} dilindungi Android)"
                deleteOriginal && result.sourceDeleteFailedCount > 0 ->
                    "${result.importedCount} file masuk ke brankas$destNote (sumber dilindungi sistem. Hubungkan folder sumber atau pilih via Pengelola File untuk hapus otomatis)"
                else ->
                    "${result.importedCount} file berhasil dienkripsi (AES-256) ke brankas$destNote"
            }

            if (deleteOriginal && result.sourceDeleteFailedCount > 0 && result.importedCount > 0) {
                _localState.update {
                    it.copy(
                        pendingSourceDeletePrompt = PendingSourceDeletePrompt(
                            importedCount = result.importedCount,
                            autoDeletedCount = result.deletedCount,
                            items = result.pendingSourceDeletes
                        )
                    )
                }
            } else {
                _events.tryEmit(VaultEvent.ShowMessage(msg))
            }
        }
    }

    fun dismissPendingSourceDeletePrompt() {
        _localState.update { it.copy(pendingSourceDeletePrompt = null) }
    }

    fun onSystemSourceDeleteCompleted(deletedCount: Int) {
        _localState.update { it.copy(pendingSourceDeletePrompt = null) }
        if (deletedCount > 0) {
            _events.tryEmit(VaultEvent.ShowMessage("$deletedCount file asli berhasil dihapus dari Galeri"))
        } else {
            _events.tryEmit(VaultEvent.ShowMessage("File tetap terenkripsi di Locker; file sumber tidak dihapus"))
        }
    }

    fun onSystemSourceDeleteUnavailable() {
        _localState.update { it.copy(pendingSourceDeletePrompt = null) }
        _events.tryEmit(
            VaultEvent.ShowSourceAccessSuggestion(
                "File sudah terenkripsi di Locker. Sumber tidak mendukung konfirmasi hapus langsung — hubungkan folder sumber untuk hapus otomatis."
            )
        )
    }

    fun moveFilesToAlbum(fileIds: List<String>, targetAlbumId: String) {
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.moveFilesToAlbum(fileIds, targetAlbumId)
            }.onSuccess { albumName ->
                val countLabel = if (fileIds.size == 1) "1 file" else "${fileIds.size} file"
                _events.tryEmit(VaultEvent.ShowMessage("$countLabel dipindahkan ke album \"$albumName\""))
            }.onFailure { err ->
                _events.tryEmit(VaultEvent.ShowMessage("Gagal memindahkan file: ${err.localizedMessage ?: "album tidak ditemukan"}"))
            }
        }
    }

    fun createAlbumAndMoveFiles(
        fileIds: List<String>,
        name: String,
        iconKey: String,
        colorHex: Long,
        isPinned: Boolean
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.createAlbumAndMoveFiles(fileIds, name, iconKey, colorHex, isPinned)
            }.onSuccess { album ->
                val countLabel = if (fileIds.size == 1) "1 file" else "${fileIds.size} file"
                _events.tryEmit(VaultEvent.ShowMessage("Album \"${album.name}\" dibuat & $countLabel dipindahkan"))
            }.onFailure { err ->
                _events.tryEmit(VaultEvent.ShowMessage("Gagal membuat album: ${err.localizedMessage ?: "kesalahan"}"))
            }
        }
    }

    fun toggleFavorite(file: VaultFileEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(file.id, file.isFavorite)
        }
    }

    fun saveEditedImage(
        file: VaultFileEntity,
        params: ImageAdjustmentParams,
        overwriteOriginal: Boolean,
        onComplete: (VaultFileEntity?) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repository.ensureActiveVaultKeyLoaded()
                repository.saveEditedImage(file, params, overwriteOriginal)
            }.onSuccess { saved ->
                val msg = if (overwriteOriginal) {
                    "Perubahan gambar disimpan & dienkripsi ulang"
                } else {
                    "Salinan terenkripsi \"${saved.originalName}\" berhasil disimpan"
                }
                _events.tryEmit(VaultEvent.ShowMessage(msg))
                onComplete(saved)
            }.onFailure { err ->
                _events.tryEmit(
                    VaultEvent.ShowMessage("Gagal menyimpan edit gambar: ${err.localizedMessage ?: "kesalahan"}")
                )
                onComplete(null)
            }
        }
    }

    fun deleteFiles(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.deleteFiles(fileIds) }
                .onSuccess { _events.tryEmit(VaultEvent.ShowMessage("${fileIds.size} file berhasil dihapus permanen")) }
                .onFailure { error ->
                    _events.tryEmit(VaultEvent.ShowMessage("Penghapusan gagal: ${error.localizedMessage ?: "file masih tersimpan"}"))
                }
        }
    }

    fun exportFileToUri(file: VaultFileEntity, destinationUri: Uri, removeFromVault: Boolean) {
        clearExternalPickerFlag()
        viewModelScope.launch {
            runCatching {
                repository.exportFile(file, destinationUri, removeFromVault)
            }.onSuccess {
                val msg = if (removeFromVault) {
                    "\"${file.originalName}\" didekripsi & dikeluarkan dari brankas"
                } else {
                    "Salinan dekripsi \"${file.originalName}\" berhasil disimpan"
                }
                _events.tryEmit(VaultEvent.ShowMessage(msg))
            }.onFailure { err ->
                _events.tryEmit(VaultEvent.ShowMessage("Gagal mengekspor: ${err.localizedMessage}"))
            }
        }
    }

    fun exportFilesToDeviceGallery(fileIds: List<String>, removeFromVault: Boolean) {
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            _localState.update {
                it.copy(
                    importProgress = ImportProgressState(
                        processed = 0,
                        total = fileIds.size,
                        currentFileName = "Mengeluarkan 0/${fileIds.size} file ke Galeri/Unduhan..."
                    )
                )
            }
            try {
                val outcome = repository.exportFilesToMediaStore(fileIds, removeFromVault) { done, total ->
                    _localState.update {
                        it.copy(
                            importProgress = ImportProgressState(
                                processed = done,
                                total = total,
                                currentFileName = "Mengeluarkan $done/$total file ke Galeri/Unduhan..."
                            )
                        )
                    }
                }
                emitBatchExportSummary(outcome, destinationLabel = "Galeri/Unduhan", removeFromVault = removeFromVault)
            } catch (err: Throwable) {
                _events.tryEmit(
                    VaultEvent.ShowMessage("Gagal mengeluarkan file: ${err.localizedMessage ?: "kesalahan"}")
                )
            } finally {
                _localState.update { it.copy(importProgress = null) }
            }
        }
    }

    fun exportFilesToFolder(fileIds: List<String>, folderUri: Uri, removeFromVault: Boolean) {
        clearExternalPickerFlag()
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            _localState.update {
                it.copy(
                    importProgress = ImportProgressState(
                        processed = 0,
                        total = fileIds.size,
                        currentFileName = "Mengeluarkan 0/${fileIds.size} file ke folder..."
                    )
                )
            }
            try {
                val outcome = repository.exportFilesToFolder(fileIds, folderUri, removeFromVault) { done, total ->
                    _localState.update {
                        it.copy(
                            importProgress = ImportProgressState(
                                processed = done,
                                total = total,
                                currentFileName = "Mengeluarkan $done/$total file ke folder..."
                            )
                        )
                    }
                }
                val folderName = runCatching { repository.getSourceFolderDisplayName(folderUri) }
                    .getOrDefault("folder tujuan")
                emitBatchExportSummary(outcome, destinationLabel = folderName, removeFromVault = removeFromVault)
            } catch (err: Throwable) {
                _events.tryEmit(
                    VaultEvent.ShowMessage("Gagal mengeluarkan file: ${err.localizedMessage ?: "kesalahan"}")
                )
            } finally {
                _localState.update { it.copy(importProgress = null) }
            }
        }
    }

    private fun emitBatchExportSummary(
        outcome: BatchExportResult,
        destinationLabel: String,
        removeFromVault: Boolean
    ) {
        val msg = when {
            outcome.exportedCount == 0 && outcome.failedCount > 0 ->
                "Gagal mengeluarkan file: ${outcome.firstFailureReason ?: "tidak dapat menulis ke tujuan"}"
            outcome.failedCount > 0 ->
                "${outcome.exportedCount} file dikeluarkan ke $destinationLabel, ${outcome.failedCount} gagal"
            removeFromVault ->
                "${outcome.exportedCount} file didekripsi ke $destinationLabel & dikeluarkan dari brankas"
            else ->
                "${outcome.exportedCount} salinan dekripsi disimpan ke $destinationLabel"
        }
        _events.tryEmit(VaultEvent.ShowMessage(msg))
    }

    suspend fun loadThumbnail(file: VaultFileEntity): Bitmap? = repository.loadThumbnail(file)

    suspend fun decryptBytes(file: VaultFileEntity): ByteArray = repository.decryptBytes(file)

    fun openEncryptedMediaDataSource(file: VaultFileEntity): MediaDataSource =
        repository.openEncryptedMediaDataSource(file)

    suspend fun decryptToEphemeralCacheFile(file: VaultFileEntity): File =
        repository.decryptToEphemeralCacheFile(file)

    suspend fun loadExternalThumbnail(folderUri: String, thumbFileName: String): Bitmap? =
        repository.loadExternalThumbnail(
            Uri.parse(folderUri),
            thumbFileName,
            _localState.value.externalVaultState.activeOverrideKey
        )

    suspend fun decryptExternalFileToBytes(folderUri: String, backupFileName: String): ByteArray =
        repository.decryptExternalFileToBytes(
            Uri.parse(folderUri),
            backupFileName,
            _localState.value.externalVaultState.activeOverrideKey
        )

    fun openExternalEncryptedMediaDataSource(folderUri: String, backupFileName: String): MediaDataSource =
        repository.openExternalEncryptedMediaDataSource(
            Uri.parse(folderUri),
            backupFileName,
            _localState.value.externalVaultState.activeOverrideKey
        )

    suspend fun decryptExternalFileToEphemeralCache(folderUri: String, backupFileName: String): File =
        repository.decryptExternalFolderFileToEphemeralCache(
            Uri.parse(folderUri),
            backupFileName,
            _localState.value.externalVaultState.activeOverrideKey
        )

    fun inspectExternalVaultFolder(folderUri: String, recoveryPin: String? = null) {
        viewModelScope.launch {
            _localState.update {
                it.copy(
                    externalVaultState = it.externalVaultState.copy(
                        isScanning = true,
                        errorMessage = null,
                        wrongRecoveryPin = false
                    )
                )
            }
            runCatching {
                repository.ensureActiveVaultKeyLoaded()
                repository.inspectExternalVaultFolder(Uri.parse(folderUri), recoveryPin)
            }.onSuccess { result ->
                _localState.update {
                    it.copy(
                        externalVaultState = ExternalVaultUiState(
                            isScanning = false,
                            folderDisplayName = result.folderDisplayName,
                            hasRecoveryKey = result.hasRecoveryKey,
                            needsRecoveryPin = result.needsRecoveryPin,
                            wrongRecoveryPin = result.wrongRecoveryPin,
                            unlockedWithRecoveryPin = result.unlockedWithRecoveryPin,
                            entries = result.entries,
                            activeOverrideKey = result.recoveredMasterKey ?: it.externalVaultState.activeOverrideKey
                        )
                    )
                }
                val decryptableCount = result.entries.count { it.canDecrypt }
                val lockedCount = result.entries.count { !it.canDecrypt }
                if (result.wrongRecoveryPin) {
                    _events.tryEmit(VaultEvent.ShowMessage("PIN pemulihan tidak cocok dengan kunci yang tersimpan di folder ini"))
                } else if (result.unlockedWithRecoveryPin) {
                    val detail = if (lockedCount > 0) {
                        "$decryptableCount file siap dipulihkan, $lockedCount file masih terkunci kunci lain."
                    } else {
                        "Semua $decryptableCount file terenkripsi siap dibaca & dipulihkan."
                    }
                    _events.tryEmit(VaultEvent.ShowMessage("Kunci pemulihan cocok! $detail"))
                }
            }.onFailure { err ->
                _localState.update {
                    it.copy(
                        externalVaultState = it.externalVaultState.copy(
                            isScanning = false,
                            errorMessage = err.localizedMessage ?: "Gagal memindai folder"
                        )
                    )
                }
            }
        }
    }

    fun importRecoveryKeyFile(folderUri: String, keyFileUri: String, pin: String) {
        clearExternalPickerFlag()
        viewModelScope.launch {
            _localState.update {
                it.copy(
                    externalVaultState = it.externalVaultState.copy(
                        isScanning = true,
                        errorMessage = null,
                        wrongRecoveryPin = false
                    )
                )
            }
            runCatching {
                repository.ensureActiveVaultKeyLoaded()
                repository.importRecoveryKeyFileAndInspect(
                    folderUri = Uri.parse(folderUri),
                    recoveryFileUri = Uri.parse(keyFileUri),
                    pin = pin
                )
            }.onSuccess { result ->
                _localState.update {
                    it.copy(
                        externalVaultState = ExternalVaultUiState(
                            isScanning = false,
                            folderDisplayName = result.folderDisplayName,
                            hasRecoveryKey = result.hasRecoveryKey,
                            needsRecoveryPin = result.needsRecoveryPin,
                            wrongRecoveryPin = result.wrongRecoveryPin,
                            unlockedWithRecoveryPin = result.unlockedWithRecoveryPin,
                            entries = result.entries,
                            activeOverrideKey = result.recoveredMasterKey ?: it.externalVaultState.activeOverrideKey
                        )
                    )
                }
                if (result.wrongRecoveryPin || !result.unlockedWithRecoveryPin) {
                    _events.tryEmit(VaultEvent.ShowMessage("PIN tidak cocok dengan file kunci pemulihan tersebut"))
                } else {
                    val decryptableCount = result.entries.count { it.canDecrypt }
                    _events.tryEmit(
                        VaultEvent.ShowMessage("File kunci diterima! $decryptableCount file kini dapat didekripsi & dipulihkan.")
                    )
                }
            }.onFailure { err ->
                _localState.update {
                    it.copy(
                        externalVaultState = it.externalVaultState.copy(
                            isScanning = false,
                            errorMessage = err.localizedMessage ?: "Gagal membaca file kunci"
                        )
                    )
                }
                _events.tryEmit(VaultEvent.ShowMessage(err.localizedMessage ?: "Gagal membaca file kunci pemulihan"))
            }
        }
    }

    fun restoreExternalEntry(
        folderUri: String,
        entry: ExternalVaultEntry,
        onRestored: ((VaultFileEntity?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _localState.update {
                it.copy(externalVaultState = it.externalVaultState.copy(isRestoring = true))
            }
            val overrideKey = _localState.value.externalVaultState.activeOverrideKey
            val result = runCatching {
                repository.ensureActiveVaultKeyLoaded()
                repository.restoreExternalEncryptedFile(
                    folderUri = Uri.parse(folderUri),
                    backupName = entry.backupFileName,
                    entry = entry,
                    overrideKey = overrideKey
                )
            }
            result.onSuccess { entity ->
                _events.tryEmit(VaultEvent.ShowMessage("Berhasil dipulihkan ke Brankas: ${entity.originalName}"))
                inspectExternalVaultFolder(folderUri)
            }.onFailure { error ->
                _events.tryEmit(VaultEvent.ShowMessage("Pemulihan gagal: ${error.message ?: "PIN atau kunci enkripsi berbeda"}"))
            }
            _localState.update {
                it.copy(externalVaultState = it.externalVaultState.copy(isRestoring = false))
            }
            onRestored?.invoke(result.getOrNull())
        }
    }

    fun restoreAllFromExternalFolder(folderUri: String) {
        viewModelScope.launch {
            _localState.update {
                it.copy(externalVaultState = it.externalVaultState.copy(isRestoring = true))
            }
            val overrideKey = _localState.value.externalVaultState.activeOverrideKey
            runCatching {
                repository.ensureActiveVaultKeyLoaded()
                val inspection = repository.inspectExternalVaultFolder(Uri.parse(folderUri))
                repository.restoreAllFromInspection(Uri.parse(folderUri), inspection, overrideKey)
            }.onSuccess { outcome ->
                val summary = buildString {
                    append("Pemulihan selesai: ${outcome.restoredCount} file dipulihkan")
                    if (outcome.skippedExistingCount > 0) append(", ${outcome.skippedExistingCount} sudah ada")
                    if (outcome.lockedSkippedCount > 0) append(", ${outcome.lockedSkippedCount} butuh PIN kunci lama")
                    if (outcome.failedCount > 0) append(", ${outcome.failedCount} gagal")
                }
                _events.tryEmit(VaultEvent.ShowMessage(summary))
                inspectExternalVaultFolder(folderUri)
            }.onFailure { err ->
                _events.tryEmit(VaultEvent.ShowMessage("Gagal memulihkan folder: ${err.localizedMessage}"))
            }
            _localState.update {
                it.copy(externalVaultState = it.externalVaultState.copy(isRestoring = false))
            }
        }
    }

    fun scanExternalVaultFolder(folderUri: String, onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            val names = runCatching {
                repository.listExternalEncryptedFiles(Uri.parse(folderUri))
                    .mapNotNull { it.name }
                    .sorted()
            }.getOrElse { emptyList() }
            onResult(names)
            _events.tryEmit(VaultEvent.ShowMessage("Ditemukan ${names.size} file terenkripsi .vault"))
        }
    }

    fun restoreExternalVaultFile(
        folderUri: String,
        backupName: String,
        onResult: (VaultFileEntity?) -> Unit
    ) {
        viewModelScope.launch {
            val overrideKey = _localState.value.externalVaultState.activeOverrideKey
            val result = runCatching {
                repository.restoreExternalEncryptedFile(
                    folderUri = Uri.parse(folderUri),
                    backupName = backupName,
                    overrideKey = overrideKey
                )
            }
            result.onSuccess { entity ->
                _events.tryEmit(VaultEvent.ShowMessage("File dipulihkan: ${entity.originalName}"))
            }.onFailure { error ->
                _events.tryEmit(VaultEvent.ShowMessage("Pemulihan gagal: ${error.message ?: "backup tidak kompatibel"}"))
            }
            onResult(result.getOrNull())
        }
    }

    fun secureDeleteEphemeralFile(file: File) = repository.secureDeleteEphemeralFile(file)

    fun changePin(currentPin: String, newPin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repository.changePin(currentPin, newPin)
            if (ok) {
                _events.tryEmit(VaultEvent.ShowMessage("PIN Brankas & kunci pemulihan folder diperbarui"))
            }
            onResult(ok)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBiometricEnabled(enabled) }
    }

    fun setAutoLockOnBackground(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoLockOnBackground(enabled) }
    }

    fun setBlockScreenshots(enabled: Boolean) {
        viewModelScope.launch { repository.setBlockScreenshots(enabled) }
    }

    fun setExternalVaultFolder(uri: String?) {
        viewModelScope.launch {
            runCatching { repository.setExternalVaultFolder(uri) }
                .onSuccess {
                    if (uri != null) {
                        _events.tryEmit(VaultEvent.ShowMessage("Folder tujuan Locker terhubung & file terenkripsi disinkronkan."))
                        inspectExternalVaultFolder(uri)
                    } else {
                        _localState.update { it.copy(externalVaultState = ExternalVaultUiState()) }
                        _events.tryEmit(VaultEvent.ShowMessage("Folder tujuan eksternal diputuskan."))
                    }
                }
                .onFailure { _events.tryEmit(VaultEvent.ShowMessage("Gagal menyiapkan folder tujuan: ${it.localizedMessage}")) }
        }
    }

    fun setDeleteOriginalOnImport(enabled: Boolean) {
        viewModelScope.launch { repository.setDeleteOriginalOnImport(enabled) }
    }

    fun setThemePreference(theme: VaultThemePreference) {
        viewModelScope.launch { repository.setThemePreference(theme) }
    }

    fun setGallerySortOrder(order: GallerySortOrder) {
        viewModelScope.launch { repository.setGallerySortOrder(order) }
    }

    fun addSourceFolderUri(uri: String) {
        viewModelScope.launch {
            runCatching { repository.addSourceFolderUri(uri) }
                .onSuccess {
                    val label = repository.getSourceFolderDisplayName(Uri.parse(uri))
                    _events.tryEmit(VaultEvent.ShowMessage("Folder sumber \"$label\" terhubung untuk penghapusan otomatis"))
                }
        }
    }

    fun removeSourceFolderUri(uri: String) {
        viewModelScope.launch {
            repository.removeSourceFolderUri(uri)
            _events.tryEmit(VaultEvent.ShowMessage("Akses folder sumber dilepas"))
        }
    }

    fun getSourceFolderDisplayName(uriStr: String): String {
        return runCatching { repository.getSourceFolderDisplayName(Uri.parse(uriStr)) }
            .getOrDefault("Folder Sumber")
    }

    fun emitMessage(msg: String) {
        _events.tryEmit(VaultEvent.ShowMessage(msg))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ): T {
                val app = checkNotNull(
                    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ) as BrankasKuApp
                return BrankasViewModel(app, app.vaultRepository) as T
            }
        }
    }
}
