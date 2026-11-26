package com.example

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.ui.BrankasViewModel
import com.example.ui.VaultEvent
import com.example.ui.screens.AlbumDetailScreen
import com.example.ui.screens.ExternalVaultScreen
import com.example.ui.screens.SecureViewerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultHomeScreen
import com.example.ui.screens.VaultLockScreen
import com.example.ui.theme.BrankasKuTheme
import kotlinx.serialization.Serializable

@Serializable
object RouteHome

@Serializable
data class RouteAlbumDetail(val albumId: String)

@Serializable
data class RouteSecureViewer(
    val fileId: String,
    val albumId: String? = null,
    val filterName: String? = null,
    val autoStartSlideshow: Boolean = false
)

@Serializable
object RouteSettings

@Serializable
object RouteExternalVault

@Serializable
data class RouteExternalViewer(
    val folderUri: String,
    val backupFileName: String,
    val fileId: String,
    val displayName: String,
    val mimeType: String,
    val categoryName: String,
    val sizeBytes: Long,
    val createdAt: Long
)

class MainActivity : FragmentActivity() {

    private val viewModel: BrankasViewModel by viewModels { BrankasViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val lifecycleOwner = LocalLifecycleOwner.current

            // Enforce FLAG_SECURE when locked or when screenshot blocking is enabled
            val shouldBlockScreenshots = uiState.isLocked || uiState.securityPrefs.blockScreenshots
            LaunchedEffect(shouldBlockScreenshots) {
                if (shouldBlockScreenshots) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Auto-lock when moving to background (unless launching a system picker)
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                        Lifecycle.Event.ON_RESUME -> {
                            viewModel.clearExternalPickerFlag()
                            viewModel.refreshBiometricAvailability()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val sourceFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri ->
                viewModel.clearExternalPickerFlag()
                if (treeUri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    viewModel.addSourceFolderUri(treeUri.toString())
                }
            }

            var isBatchCreateDelete by remember { mutableStateOf(false) }
            var systemDeleteTargetCount by remember { mutableIntStateOf(0) }
            var systemDeletedSoFar by remember { mutableIntStateOf(0) }
            var pendingDeleteSenders by remember { mutableStateOf<List<IntentSender>>(emptyList()) }
            var awaitingDeleteResult by remember { mutableStateOf(false) }

            val systemDeleteLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                viewModel.clearExternalPickerFlag()
                if (isBatchCreateDelete) {
                    isBatchCreateDelete = false
                    awaitingDeleteResult = false
                    val deleted = if (result.resultCode == Activity.RESULT_OK) systemDeleteTargetCount else 0
                    systemDeleteTargetCount = 0
                    viewModel.onSystemSourceDeleteCompleted(deleted)
                } else {
                    if (result.resultCode == Activity.RESULT_OK) {
                        systemDeletedSoFar++
                    }
                    awaitingDeleteResult = false
                    if (pendingDeleteSenders.isEmpty()) {
                        val totalDeleted = systemDeletedSoFar
                        systemDeletedSoFar = 0
                        systemDeleteTargetCount = 0
                        viewModel.onSystemSourceDeleteCompleted(totalDeleted)
                    }
                }
            }

            LaunchedEffect(pendingDeleteSenders, awaitingDeleteResult) {
                if (!awaitingDeleteResult && pendingDeleteSenders.isNotEmpty()) {
                    val nextSender = pendingDeleteSenders.first()
                    pendingDeleteSenders = pendingDeleteSenders.drop(1)
                    awaitingDeleteResult = true
                    viewModel.markLaunchingExternalPicker()
                    systemDeleteLauncher.launch(IntentSenderRequest.Builder(nextSender).build())
                }
            }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is VaultEvent.ShowMessage -> {
                            snackbarHostState.showSnackbar(event.message)
                        }
                        is VaultEvent.ShowSourceAccessSuggestion -> {
                            val result = snackbarHostState.showSnackbar(
                                message = event.message,
                                actionLabel = "Izin Folder",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.markLaunchingExternalPicker()
                                sourceFolderLauncher.launch(null)
                            }
                        }
                    }
                }
            }

            BrankasKuTheme(themePreference = uiState.securityPrefs.themePreference) {
                Scaffold(
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { outerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(outerPadding)
                    ) {
                        Crossfade(
                            targetState = uiState.isLocked,
                            label = "vault_lock_transition"
                        ) { locked ->
                            if (locked) {
                                VaultLockScreen(
                                    uiState = uiState,
                                    onDigitEntered = viewModel::onPinDigitEntered,
                                    onBackspace = viewModel::onPinBackspace,
                                    onBiometricSuccess = viewModel::onBiometricUnlockSuccess,
                                    onCompleteInitialSetup = viewModel::completeInitialSetup,
                                    onRestoreFromExternalFolder = viewModel::restoreAppFromExternalFolder,
                                    onBiometricError = { msg -> viewModel.emitMessage(msg) }
                                )
                            } else {
                                VaultNavGraph(viewModel = viewModel)
                            }
                        }

                        val deletePrompt = uiState.pendingSourceDeletePrompt
                        if (!uiState.isLocked && deletePrompt != null) {
                            AlertDialog(
                                onDismissRequest = viewModel::dismissPendingSourceDeletePrompt,
                                title = { Text("Konfirmasi Hapus File Asli") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = buildString {
                                                append("${deletePrompt.importedCount} file berhasil dienkripsi ke Locker")
                                                if (deletePrompt.autoDeletedCount > 0) {
                                                    append(" (${deletePrompt.autoDeletedCount} sumber sudah dihapus otomatis)")
                                                }
                                                append(". Android melindungi ${deletePrompt.items.size} file sumber berikut dari penghapusan otomatis:")
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        deletePrompt.items.take(4).forEach { item ->
                                            Text(
                                                text = "• ${item.fileName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (deletePrompt.items.size > 4) {
                                            Text(
                                                text = "...dan ${deletePrompt.items.size - 4} file lainnya",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "Konfirmasi untuk menghapus file asli yang sudah terenkripsi dari Galeri?",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel.dismissPendingSourceDeletePrompt()
                                            val mediaUris = deletePrompt.items.mapNotNull { it.mediaStoreUri }.distinct()
                                            val batchSender = buildBatchDeleteSender(contentResolver, mediaUris)
                                            if (batchSender != null) {
                                                isBatchCreateDelete = true
                                                systemDeleteTargetCount = batchSender.second
                                                awaitingDeleteResult = true
                                                viewModel.markLaunchingExternalPicker()
                                                systemDeleteLauncher.launch(
                                                    IntentSenderRequest.Builder(batchSender.first).build()
                                                )
                                            } else {
                                                val senders = deletePrompt.items.mapNotNull { it.recoverableIntentSender }
                                                if (senders.isNotEmpty()) {
                                                    isBatchCreateDelete = false
                                                    systemDeletedSoFar = 0
                                                    systemDeleteTargetCount = senders.size
                                                    awaitingDeleteResult = false
                                                    pendingDeleteSenders = senders
                                                } else {
                                                    viewModel.onSystemSourceDeleteUnavailable()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("Hapus File Asli")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = viewModel::dismissPendingSourceDeletePrompt) {
                                        Text("Biarkan")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildBatchDeleteSender(
    resolver: ContentResolver,
    uris: List<Uri>
): Pair<IntentSender, Int>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
    runCatching {
        return MediaStore.createDeleteRequest(resolver, uris).intentSender to uris.size
    }
    val validUris = uris.filter { uri ->
        runCatching { MediaStore.createDeleteRequest(resolver, listOf(uri)) }.isSuccess
    }
    if (validUris.isEmpty()) return null
    return runCatching {
        MediaStore.createDeleteRequest(resolver, validUris).intentSender to validUris.size
    }.getOrNull()
}

@Composable
private fun VaultNavGraph(viewModel: BrankasViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = RouteHome
    ) {
        composable<RouteHome> {
            VaultHomeScreen(
                uiState = uiState,
                onCategorySelected = viewModel::selectCategoryFilter,
                onAlbumClick = { albumId ->
                    navController.navigate(RouteAlbumDetail(albumId))
                },
                onFileClick = { fileId, filter ->
                    val effectiveFilter = if (filter == VaultFileFilter.OVERVIEW) VaultFileFilter.ALL_FILES else filter
                    navController.navigate(
                        RouteSecureViewer(
                            fileId = fileId,
                            albumId = null,
                            filterName = effectiveFilter.name,
                            autoStartSlideshow = false
                        )
                    )
                },
                onStartSlideshow = { firstFileId, filter ->
                    val effectiveFilter = if (filter == VaultFileFilter.OVERVIEW) VaultFileFilter.ALL_FILES else filter
                    navController.navigate(
                        RouteSecureViewer(
                            fileId = firstFileId,
                            albumId = null,
                            filterName = effectiveFilter.name,
                            autoStartSlideshow = true
                        )
                    )
                },
                onCreateAlbum = viewModel::createAlbum,
                onUpdateAlbum = viewModel::updateAlbum,
                onDeleteAlbum = viewModel::deleteAlbum,
                onMoveFiles = viewModel::moveFilesToAlbum,
                onCreateAlbumAndMoveFiles = viewModel::createAlbumAndMoveFiles,
                onToggleFavorite = viewModel::toggleFavorite,
                onDeleteFiles = viewModel::deleteFiles,
                onExportFilesToDeviceGallery = viewModel::exportFilesToDeviceGallery,
                onExportFilesToFolder = viewModel::exportFilesToFolder,
                onImportUris = viewModel::importUris,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                onLockNow = viewModel::lockVault,
                onOpenSettings = { navController.navigate(RouteSettings) },
                onOpenExternalVault = { navController.navigate(RouteExternalVault) },
                onSortOrderChange = viewModel::setGallerySortOrder,
                loadThumbnail = viewModel::loadThumbnail
            )
        }

        composable<RouteAlbumDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteAlbumDetail>()
            val initialAlbum = remember(route.albumId) {
                uiState.albums.firstOrNull { it.album.id == route.albumId }?.album
            }
            val initialFiles = remember(route.albumId) {
                if (uiState.isPrefsLoaded) {
                    uiState.allFiles.filter { it.albumId == route.albumId }
                } else {
                    null
                }
            }
            val albumFlow = remember(route.albumId) { viewModel.observeAlbum(route.albumId) }
            val filesFlow = remember(route.albumId) { viewModel.observeFilesInAlbum(route.albumId) }
            val album by albumFlow.collectAsStateWithLifecycle(initialValue = initialAlbum)
            val files by filesFlow.collectAsStateWithLifecycle(initialValue = initialFiles)

            AlbumDetailScreen(
                albumId = route.albumId,
                album = album,
                files = files,
                allAlbums = uiState.albums,
                sortOrder = uiState.securityPrefs.gallerySortOrder,
                onSortOrderChange = viewModel::setGallerySortOrder,
                importProgress = uiState.importProgress,
                onBack = { navController.popBackStack() },
                onFileClick = { fileId, filter ->
                    navController.navigate(
                        RouteSecureViewer(
                            fileId = fileId,
                            albumId = route.albumId,
                            filterName = filter.name,
                            autoStartSlideshow = false
                        )
                    )
                },
                onStartSlideshow = { firstFileId, filter ->
                    navController.navigate(
                        RouteSecureViewer(
                            fileId = firstFileId,
                            albumId = route.albumId,
                            filterName = filter.name,
                            autoStartSlideshow = true
                        )
                    )
                },
                onImportUris = { uris -> viewModel.importUris(uris, route.albumId) },
                onUpdateAlbum = viewModel::updateAlbum,
                onMoveFiles = viewModel::moveFilesToAlbum,
                onCreateAlbumAndMoveFiles = viewModel::createAlbumAndMoveFiles,
                onToggleFavorite = viewModel::toggleFavorite,
                onDeleteFiles = viewModel::deleteFiles,
                onExportFile = viewModel::exportFileToUri,
                onExportFilesToDeviceGallery = viewModel::exportFilesToDeviceGallery,
                onExportFilesToFolder = viewModel::exportFilesToFolder,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                loadThumbnail = viewModel::loadThumbnail
            )
        }

        composable<RouteSecureViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteSecureViewer>()
            val file by viewModel.observeFileById(route.fileId).collectAsStateWithLifecycle(initialValue = null)
            val activeFilter = remember(route.filterName) {
                route.filterName?.let { name ->
                    runCatching { VaultFileFilter.valueOf(name) }.getOrDefault(VaultFileFilter.ALL_FILES)
                } ?: VaultFileFilter.ALL_FILES
            }
            val sortOrder = uiState.securityPrefs.gallerySortOrder
            val galleryFiles = remember(uiState.allFiles, route.albumId, activeFilter, sortOrder) {
                sortOrder.sortFiles(
                    uiState.allFiles.filter { item ->
                        (route.albumId == null || item.albumId == route.albumId) && activeFilter.matchesFile(item)
                    }
                )
            }

            SecureViewerScreen(
                file = file,
                galleryFiles = galleryFiles,
                autoStartSlideshow = route.autoStartSlideshow,
                allAlbums = uiState.albums,
                vaultInternalPath = uiState.vaultInternalPath,
                decryptBytes = viewModel::decryptBytes,
                openMediaDataSource = viewModel::openEncryptedMediaDataSource,
                decryptToEphemeralFile = viewModel::decryptToEphemeralCacheFile,
                secureDeleteEphemeralFile = viewModel::secureDeleteEphemeralFile,
                onBack = { navController.popBackStack() },
                onToggleFavorite = viewModel::toggleFavorite,
                onMoveFile = { fileId, targetAlbumId ->
                    viewModel.moveFilesToAlbum(listOf(fileId), targetAlbumId)
                },
                onCreateAlbumAndMoveFile = { fileId, name, iconKey, colorHex, isPinned ->
                    viewModel.createAlbumAndMoveFiles(listOf(fileId), name, iconKey, colorHex, isPinned)
                },
                onDeleteFile = { fileId ->
                    viewModel.deleteFiles(listOf(fileId))
                },
                onExportFile = viewModel::exportFileToUri,
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                onSaveEditedImage = viewModel::saveEditedImage
            )
        }

        composable<RouteExternalVault> {
            ExternalVaultScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onFolderSelected = viewModel::setExternalVaultFolder,
                onInspectFolder = viewModel::inspectExternalVaultFolder,
                onImportRecoveryKeyFile = viewModel::importRecoveryKeyFile,
                onRestoreEntry = { folderUri, entry ->
                    viewModel.restoreExternalEntry(folderUri, entry)
                },
                onRestoreAll = viewModel::restoreAllFromExternalFolder,
                onOpenExternalEntry = { folderUri, entry ->
                    navController.navigate(
                        RouteExternalViewer(
                            folderUri = folderUri,
                            backupFileName = entry.backupFileName,
                            fileId = entry.fileId,
                            displayName = entry.displayName,
                            mimeType = entry.mimeType,
                            categoryName = entry.category.name,
                            sizeBytes = entry.sizeBytes,
                            createdAt = entry.createdAt
                        )
                    )
                },
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                loadExternalThumbnail = viewModel::loadExternalThumbnail
            )
        }

        composable<RouteExternalViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<RouteExternalViewer>()
            val category = runCatching { MediaCategory.valueOf(route.categoryName) }
                .getOrDefault(MediaCategory.OTHER)
            val virtualFile = remember(route) {
                VaultFileEntity(
                    id = route.fileId,
                    albumId = "",
                    originalName = route.displayName,
                    mimeType = route.mimeType,
                    category = category,
                    originalSizeBytes = route.sizeBytes,
                    encryptedFileName = route.backupFileName,
                    encryptedThumbName = null,
                    createdAt = route.createdAt
                )
            }
            val externalGalleryFiles = remember(uiState.externalVaultState.entries, virtualFile) {
                val mapped = uiState.externalVaultState.entries.map { entry ->
                    VaultFileEntity(
                        id = entry.fileId,
                        albumId = "",
                        originalName = entry.displayName,
                        mimeType = entry.mimeType,
                        category = entry.category,
                        originalSizeBytes = entry.sizeBytes,
                        encryptedFileName = entry.backupFileName,
                        encryptedThumbName = null,
                        createdAt = entry.createdAt
                    )
                }
                if (mapped.any { it.encryptedFileName == route.backupFileName }) mapped else listOf(virtualFile)
            }

            SecureViewerScreen(
                file = virtualFile,
                galleryFiles = externalGalleryFiles,
                allAlbums = uiState.albums,
                vaultInternalPath = route.folderUri,
                decryptBytes = { target ->
                    viewModel.decryptExternalFileToBytes(route.folderUri, target.encryptedFileName)
                },
                openMediaDataSource = { target ->
                    viewModel.openExternalEncryptedMediaDataSource(route.folderUri, target.encryptedFileName)
                },
                decryptToEphemeralFile = { target ->
                    viewModel.decryptExternalFileToEphemeralCache(route.folderUri, target.encryptedFileName)
                },
                secureDeleteEphemeralFile = viewModel::secureDeleteEphemeralFile,
                onBack = { navController.popBackStack() },
                onToggleFavorite = {},
                onMoveFile = { _, _ -> },
                onDeleteFile = {},
                onExportFile = { _, _, _ -> },
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                isExternalReadOnly = true,
                onRestoreExternalFile = { target ->
                    val entry = uiState.externalVaultState.entries.firstOrNull {
                        it.backupFileName == target.encryptedFileName
                    }
                    if (entry != null) {
                        viewModel.restoreExternalEntry(route.folderUri, entry) { restored ->
                            if (restored != null) {
                                navController.navigate(RouteSecureViewer(restored.id)) {
                                    popUpTo(RouteExternalVault) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }
            )
        }

        composable<RouteSettings> {
            SettingsScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onBiometricToggle = viewModel::setBiometricEnabled,
                onAutoLockToggle = viewModel::setAutoLockOnBackground,
                onBlockScreenshotsToggle = viewModel::setBlockScreenshots,
                onDeleteOriginalToggle = viewModel::setDeleteOriginalOnImport,
                onAddSourceFolder = viewModel::addSourceFolderUri,
                onRemoveSourceFolder = viewModel::removeSourceFolderUri,
                getSourceFolderLabel = viewModel::getSourceFolderDisplayName,
                onExternalVaultFolderSelected = viewModel::setExternalVaultFolder,
                onOpenExternalVault = { navController.navigate(RouteExternalVault) },
                onMarkLaunchingExternalPicker = viewModel::markLaunchingExternalPicker,
                onClearExternalPickerFlag = viewModel::clearExternalPickerFlag,
                onThemeChange = viewModel::setThemePreference,
                onChangePin = viewModel::changePin,
                onBiometricError = viewModel::emitMessage
            )
        }
    }
}
