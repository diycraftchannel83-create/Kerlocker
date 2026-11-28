package com.example.ui.screens

import android.graphics.Bitmap
import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.GallerySortOrder
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.ui.VaultUiState
import com.example.ui.components.AesBadgePill
import com.example.ui.components.AlbumEditorDialog
import com.example.ui.components.AlbumIconRegistry
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.BatchExportDialog
import com.example.ui.components.EncryptedThumbnailBox
import com.example.ui.components.VaultActionSheet
import com.example.ui.components.VaultCategoryFilterBar
import com.example.ui.components.VaultSortMenuButton
import com.example.ui.components.categoryLabel
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHomeScreen(
    uiState: VaultUiState,
    onCategorySelected: (VaultFileFilter) -> Unit,
    onAlbumClick: (String) -> Unit,
    onFileClick: (String, VaultFileFilter) -> Unit,
    onStartSlideshow: (String, VaultFileFilter) -> Unit,
    onCreateAlbum: (String, String, Long, Boolean) -> Unit,
    onUpdateAlbum: (AlbumEntity) -> Unit,
    onDeleteAlbum: (albumId: String, moveToAlbumId: String?) -> Unit,
    onMoveFiles: (List<String>, String) -> Unit,
    onCreateAlbumAndMoveFiles: (List<String>, String, String, Long, Boolean) -> Unit,
    onToggleFavorite: (VaultFileEntity) -> Unit,
    onDeleteFiles: (List<String>) -> Unit,
    onExportFilesToDeviceGallery: (List<String>, Boolean) -> Unit,
    onExportFilesToFolder: (List<String>, Uri, Boolean) -> Unit,
    onImportUris: (List<Uri>, albumId: String) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    onLockNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExternalVault: () -> Unit,
    onOpenDuplicateTracker: () -> Unit = {},
    onScanGallery: () -> Unit = {},
    onReuseCachedGalleryScan: () -> Unit = {},
    cachedGalleryScanCount: Int = 0,
    cachedGalleryScanNames: List<String> = emptyList(),
    onSortOrderChange: (GallerySortOrder) -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var showGalleryScanResults by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<AlbumEntity?>(null) }
    var deletingAlbum by remember { mutableStateOf<AlbumWithStats?>(null) }
    var showMoveOnDeletePicker by remember { mutableStateOf<AlbumWithStats?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var movingFileIds by remember { mutableStateOf<List<String>?>(null) }
    var movingExcludedAlbumId by remember { mutableStateOf<String?>(null) }
    var pendingCreateAlbumForMoveIds by remember { mutableStateOf<List<String>?>(null) }
    var deletingFileIds by remember { mutableStateOf<List<String>?>(null) }
    var exportFileIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingFolderExport by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    var pendingTargetAlbumId by remember(uiState.albums) {
        mutableStateOf(uiState.albums.firstOrNull()?.album?.id)
    }

    val targetAlbumId = pendingTargetAlbumId ?: uiState.albums.firstOrNull()?.album?.id

    val galleryScanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) onScanGallery()
    }
    val requestGalleryScanPermissions = {
        val permissions = if (Build.VERSION.SDK_INT >= 34) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        galleryScanPermissionLauncher.launch(permissions)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onClearExternalPickerFlag()
        val albumId = targetAlbumId
        if (uris.isNotEmpty() && albumId != null) {
            onImportUris(uris, albumId)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onClearExternalPickerFlag()
        val albumId = targetAlbumId
        if (uris.isNotEmpty() && albumId != null) {
            onImportUris(uris, albumId)
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        onClearExternalPickerFlag()
        val pending = pendingFolderExport
        pendingFolderExport = null
        if (treeUri != null && pending != null) {
            onExportFilesToFolder(pending.first, treeUri, pending.second)
            selectedFileIds = emptySet()
            isSelectionMode = false
        }
    }

    val totalFiles = uiState.allFiles.size
    val totalBytes = remember(uiState.allFiles) { uiState.allFiles.sumOf { it.originalSizeBytes } }
    val filterCounts = remember(uiState.allFiles) { VaultFileFilter.computeCounts(uiState.allFiles) }
    val sortOrder = uiState.securityPrefs.gallerySortOrder

    val sortedAllFiles = remember(uiState.allFiles, sortOrder) {
        sortOrder.sortFiles(uiState.allFiles)
    }
    val filteredFiles = remember(sortedAllFiles, uiState.selectedFilter) {
        sortedAllFiles.filter { file -> uiState.selectedFilter.matchesFile(file) }
    }
    val albumNameById = remember(uiState.albums) {
        uiState.albums.associate { it.album.id to it.album.name }
    }
    val inSelectionBar = isSelectionMode || selectedFileIds.isNotEmpty()

    BackHandler(enabled = inSelectionBar) {
        isSelectionMode = false
        selectedFileIds = emptySet()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (inSelectionBar) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedFileIds = emptySet()
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Batal pilih")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedFileIds.size} dipilih",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (filteredFiles.isNotEmpty()) {
                            IconButton(onClick = {
                                selectedFileIds = if (selectedFileIds.size == filteredFiles.size) {
                                    emptySet()
                                } else {
                                    filteredFiles.map { it.id }.toSet()
                                }
                            }) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = "Pilih Semua")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Locker",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = requestGalleryScanPermissions,
                            modifier = Modifier.testTag("gallery_scan_button")
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Scan galeri")
                        }
                        IconButton(
                            onClick = onReuseCachedGalleryScan,
                            modifier = Modifier.testTag("gallery_scan_reuse_button")
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = "Gunakan hasil scan tersimpan")
                        }
                        IconButton(
                            onClick = onOpenDuplicateTracker,
                            modifier = Modifier.testTag("duplicate_tracker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileCopy,
                                contentDescription = "Lacak File Duplikat"
                            )
                        }
                        IconButton(
                            onClick = onOpenExternalVault,
                            modifier = Modifier.testTag("external_vault_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderSpecial,
                                contentDescription = "Folder Tujuan & Pemulihan"
                            )
                        }
                        IconButton(
                            onClick = onLockNow,
                            modifier = Modifier.testTag("lock_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = "Kunci Brankas Sekarang"
                            )
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Pengaturan Keamanan"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        floatingActionButton = {
            if (!inSelectionBar) {
                ExtendedFloatingActionButton(
                    onClick = { showActionSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Kunci File") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_vault")
                )
            }
        },
        bottomBar = {
            if (inSelectionBar) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                movingFileIds = selectedFileIds.toList()
                                movingExcludedAlbumId = null
                            },
                            enabled = selectedFileIds.isNotEmpty(),
                            modifier = Modifier.testTag("home_selection_move_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pindah")
                        }
                        TextButton(
                            onClick = { exportFileIds = selectedFileIds.toList() },
                            enabled = selectedFileIds.isNotEmpty(),
                            modifier = Modifier.testTag("home_selection_export_button")
                        ) {
                            Icon(Icons.Rounded.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Keluarkan")
                        }
                        TextButton(
                            onClick = { deletingFileIds = selectedFileIds.toList() },
                            enabled = selectedFileIds.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hapus")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (cachedGalleryScanCount > 0) {
                    item(key = "gallery_scan_cache_banner") {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { showGalleryScanResults = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Indeks Scan Galeri Tersimpan", fontWeight = FontWeight.SemiBold)
                                    Text("$cachedGalleryScanCount media • gunakan untuk recovery tanpa scan ulang", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { showGalleryScanResults = true }) { Text("Lihat") }
                                TextButton(onClick = onReuseCachedGalleryScan) { Text("Recovery") }
                            }
                        }
                    }
                }
                // Import progress banner
                uiState.importProgress?.let { progress ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = progress.currentFileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.total > 0) {
                                            progress.processed.toFloat() / progress.total.toFloat()
                                        } else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Proses berjalan di latar belakang — Anda dapat keluar atau membuka menu lain.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                // Security Hero Status Card
                item {
                    VaultHeroCard(
                        totalFiles = totalFiles,
                        totalBytes = totalBytes,
                        albumCount = uiState.albums.size,
                        isHardwareBacked = uiState.isHardwareBackedKeystore,
                        biometricEnabled = uiState.securityPrefs.biometricEnabled
                    )
                }

                // Destination Folder & Recovery Quick Status Card
                item {
                    DestinationFolderBannerCard(
                        folderDisplayName = uiState.securityPrefs.externalVaultFolderName,
                        isConnected = uiState.securityPrefs.externalVaultFolderUri != null,
                        deleteOriginalEnabled = uiState.securityPrefs.deleteOriginalOnImport,
                        onOpenExternalVault = onOpenExternalVault
                    )
                }

                // Category filtering bar for quick navigation across Photos, Videos, Documents, Audio, Favorites
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Filter Kategori Brankas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        VaultCategoryFilterBar(
                            selectedFilter = uiState.selectedFilter,
                            counts = filterCounts,
                            onFilterSelected = { next ->
                                isSelectionMode = false
                                selectedFileIds = emptySet()
                                onCategorySelected(next)
                            },
                            includeOverviewChip = true
                        )
                    }
                }

                // If filtering by category / all files / favorites, display matching files directly
                if (uiState.selectedFilter != VaultFileFilter.OVERVIEW) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${uiState.selectedFilter.label} (${filteredFiles.size} file)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Ketuk menu ⋮ atau Pilih untuk aksi massal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (filteredFiles.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (inSelectionBar) {
                                        IconButton(onClick = {
                                            selectedFileIds = if (selectedFileIds.size == filteredFiles.size) {
                                                emptySet()
                                            } else {
                                                filteredFiles.map { it.id }.toSet()
                                            }
                                        }) {
                                            Icon(Icons.Rounded.SelectAll, contentDescription = "Pilih Semua")
                                        }
                                    } else {
                                        VaultSortMenuButton(
                                            currentSort = sortOrder,
                                            onSortSelected = onSortOrderChange
                                        )
                                        if (filteredFiles.size > 1) {
                                            IconButton(
                                                onClick = { onStartSlideshow(filteredFiles.first().id, uiState.selectedFilter) },
                                                modifier = Modifier.testTag("home_slideshow_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Slideshow,
                                                    contentDescription = "Putar Slideshow",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        TextButton(
                                            onClick = { isSelectionMode = true },
                                            modifier = Modifier.testTag("home_select_mode_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Checklist,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pilih")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (filteredFiles.isEmpty()) {
                        item {
                            EmptySearchCard(
                                categoryLabel = uiState.selectedFilter.label,
                                onShowAll = { onCategorySelected(VaultFileFilter.ALL_FILES) }
                            )
                        }
                    } else {
                        items(filteredFiles, key = { it.id }) { file ->
                            val isSelected = file.id in selectedFileIds
                            VaultFileRowCard(
                                file = file,
                                albumName = albumNameById[file.albumId] ?: "Brankas",
                                loadThumbnail = loadThumbnail,
                                isSelected = isSelected,
                                onClick = {
                                    if (inSelectionBar) {
                                        selectedFileIds = if (isSelected) selectedFileIds - file.id else selectedFileIds + file.id
                                    } else {
                                        onFileClick(file.id, uiState.selectedFilter)
                                    }
                                },
                                onMoveToAlbum = if (!inSelectionBar) {
                                    {
                                        movingFileIds = listOf(file.id)
                                        movingExcludedAlbumId = file.albumId
                                    }
                                } else null,
                                onToggleFavorite = if (!inSelectionBar) {
                                    { onToggleFavorite(file) }
                                } else null,
                                onExport = if (!inSelectionBar) {
                                    { exportFileIds = listOf(file.id) }
                                } else null,
                                onDelete = if (!inSelectionBar) {
                                    { deletingFileIds = listOf(file.id) }
                                } else null
                            )
                        }
                    }
                } else {
                    // Albums section header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Folder Brankas",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Folder terisolasi & terenkripsi AES-256",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showCreateAlbumDialog = true },
                                modifier = Modifier.testTag("new_album_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CreateNewFolder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Album Baru")
                            }
                        }
                    }

                    // 2-column adaptive rows of album cards
                    val albumRows = uiState.albums.chunked(2)
                    items(albumRows) { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { albumStats ->
                                AlbumCard(
                                    albumWithStats = albumStats,
                                    onClick = { onAlbumClick(albumStats.album.id) },
                                    onEditClick = { editingAlbum = albumStats.album },
                                    onDeleteClick = { deletingAlbum = albumStats },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Recent / sorted locked files section
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "File Terkunci (${sortOrder.shortLabel})",
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (sortedAllFiles.isNotEmpty()) {
                                VaultSortMenuButton(
                                    currentSort = sortOrder,
                                    onSortSelected = onSortOrderChange
                                )
                            }
                        }
                    }

                    if (!uiState.isPrefsLoaded) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }
                    } else if (sortedAllFiles.isEmpty()) {
                        item {
                            EmptyVaultWelcomeCard(
                                onPickMedia = {
                                    onMarkLaunchingExternalPicker()
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onPickDocuments = {
                                    onMarkLaunchingExternalPicker()
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    } else {
                        items(sortedAllFiles.take(8), key = { it.id }) { file ->
                            VaultFileRowCard(
                                file = file,
                                albumName = albumNameById[file.albumId] ?: "Brankas",
                                loadThumbnail = loadThumbnail,
                                onClick = { onFileClick(file.id, VaultFileFilter.ALL_FILES) },
                                onMoveToAlbum = {
                                    movingFileIds = listOf(file.id)
                                    movingExcludedAlbumId = file.albumId
                                },
                                onToggleFavorite = { onToggleFavorite(file) },
                                onExport = { exportFileIds = listOf(file.id) },
                                onDelete = { deletingFileIds = listOf(file.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGalleryScanResults) {
        AlertDialog(
            onDismissRequest = { showGalleryScanResults = false },
            title = { Text("Hasil Scan Galeri Tersimpan") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Total: $cachedGalleryScanCount media. Daftar ini disimpan untuk recovery tanpa scan ulang.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    cachedGalleryScanNames.forEachIndexed { index, name ->
                        Text("${index + 1}. $name", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showGalleryScanResults = false; onReuseCachedGalleryScan() }) { Text("Recovery dari indeks") }
            },
            dismissButton = { TextButton(onClick = { showGalleryScanResults = false }) { Text("Tutup") } }
        )
    }

    if (showActionSheet) {
        VaultActionSheetWithTargetAlbum(
            albums = uiState.albums,
            selectedAlbumId = targetAlbumId,
            onSelectAlbum = { pendingTargetAlbumId = it },
            onDismiss = { showActionSheet = false },
            onPickMedia = {
                onMarkLaunchingExternalPicker()
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickDocuments = {
                onMarkLaunchingExternalPicker()
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
            onCreateAlbum = { showCreateAlbumDialog = true }
        )
    }

    if (showCreateAlbumDialog) {
        AlbumEditorDialog(
            onDismiss = { showCreateAlbumDialog = false },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onCreateAlbum(name, iconKey, colorHex, isPinned)
                showCreateAlbumDialog = false
            }
        )
    }

    editingAlbum?.let { album ->
        AlbumEditorDialog(
            existingAlbum = album,
            onDismiss = { editingAlbum = null },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onUpdateAlbum(
                    album.copy(
                        name = name,
                        iconKey = iconKey,
                        colorHex = colorHex,
                        isPinned = isPinned
                    )
                )
                editingAlbum = null
            }
        )
    }

    deletingAlbum?.let { target ->
        val hasOtherAlbums = uiState.albums.size > 1
        AlertDialog(
            onDismissRequest = { deletingAlbum = null },
            title = { Text("Hapus Album \"${target.album.name}\"?") },
            text = {
                Text(
                    if (target.fileCount > 0) {
                        "Album ini berisi ${target.fileCount} file terenkripsi. Anda dapat memindahkan file ke album lain atau menghapus semuanya secara permanen."
                    } else {
                        "Album kosong ini akan dihapus dari brankas."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAlbum(target.album.id, null)
                        deletingAlbum = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (target.fileCount > 0) "Hapus Permanen" else "Hapus")
                }
            },
            dismissButton = {
                Row {
                    if (target.fileCount > 0 && hasOtherAlbums) {
                        TextButton(onClick = {
                            showMoveOnDeletePicker = target
                            deletingAlbum = null
                        }) {
                            Text("Pindah File")
                        }
                    }
                    TextButton(onClick = { deletingAlbum = null }) {
                        Text("Batal")
                    }
                }
            }
        )
    }

    showMoveOnDeletePicker?.let { source ->
        AlbumPickerDialog(
            title = "Pindahkan ${source.fileCount} File ke Album:",
            albums = uiState.albums,
            excludedAlbumId = source.album.id,
            onDismiss = { showMoveOnDeletePicker = null },
            onAlbumSelected = { destination ->
                onDeleteAlbum(source.album.id, destination.album.id)
                showMoveOnDeletePicker = null
            }
        )
    }

    movingFileIds?.let { ids ->
        val labelCount = if (ids.size == 1) "File" else "${ids.size} File"
        AlbumPickerDialog(
            title = "Pindahkan $labelCount ke Album:",
            albums = uiState.albums,
            excludedAlbumId = movingExcludedAlbumId,
            onDismiss = {
                movingFileIds = null
                movingExcludedAlbumId = null
            },
            onAlbumSelected = { destination ->
                onMoveFiles(ids, destination.album.id)
                movingFileIds = null
                movingExcludedAlbumId = null
                selectedFileIds = emptySet()
                isSelectionMode = false
            },
            onCreateNewAlbum = {
                pendingCreateAlbumForMoveIds = ids
                movingFileIds = null
                movingExcludedAlbumId = null
            }
        )
    }

    pendingCreateAlbumForMoveIds?.let { ids ->
        AlbumEditorDialog(
            onDismiss = { pendingCreateAlbumForMoveIds = null },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onCreateAlbumAndMoveFiles(ids, name, iconKey, colorHex, isPinned)
                pendingCreateAlbumForMoveIds = null
                selectedFileIds = emptySet()
                isSelectionMode = false
            }
        )
    }

    deletingFileIds?.let { ids ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = { Text("Hapus ${ids.size} File Permanen?") },
            text = { Text("File terenkripsi yang dihapus tidak dapat dipulihkan lagi.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFiles(ids)
                        deletingFileIds = null
                        selectedFileIds = emptySet()
                        isSelectionMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus Permanen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingFileIds = null }) {
                    Text("Batalkan")
                }
            }
        )
    }

    exportFileIds?.let { ids ->
        BatchExportDialog(
            fileCount = ids.size,
            onDismiss = { exportFileIds = null },
            onExportToDeviceGallery = { removeFromVault ->
                onExportFilesToDeviceGallery(ids, removeFromVault)
                exportFileIds = null
                selectedFileIds = emptySet()
                isSelectionMode = false
            },
            onChooseDestinationFolder = { removeFromVault ->
                exportFileIds = null
                pendingFolderExport = ids to removeFromVault
                onMarkLaunchingExternalPicker()
                exportFolderLauncher.launch(null)
            }
        )
    }
}

@Composable
private fun VaultActionSheetWithTargetAlbum(
    albums: List<AlbumWithStats>,
    selectedAlbumId: String?,
    onSelectAlbum: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit,
    onCreateAlbum: () -> Unit
) {
    VaultActionSheet(
        showCreateAlbumOption = true,
        albums = albums,
        selectedAlbumId = selectedAlbumId,
        onSelectAlbum = onSelectAlbum,
        onDismiss = onDismiss,
        onPickMedia = onPickMedia,
        onPickDocuments = onPickDocuments,
        onCreateAlbum = onCreateAlbum
    )
}

@Composable
private fun VaultHeroCard(
    totalFiles: Int,
    totalBytes: Long,
    albumCount: Int,
    isHardwareBacked: Boolean,
    biometricEnabled: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(22.dp)
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_vault_shield_hero_1795646836203),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .alpha(0.24f)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AesBadgePill(
                    text = if (isHardwareBacked) "AES-256-GCM • HW KEYSTORE" else "AES-256-GCM • TERENKRIPSI"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Tersembunyi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroStatBlock(label = "File Terkunci", value = totalFiles.toString())
                HeroStatBlock(label = "Album", value = albumCount.toString())
                HeroStatBlock(label = "Ukuran Brankas", value = formatFileSize(totalBytes))
            }

            Text(
                text = if (biometricEnabled) {
                    "Sidik jari aktif • Penyimpanan internal privat & tidak terindeks galeri."
                } else {
                    "PIN aktif • Penyimpanan internal privat & tidak terindeks galeri."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DestinationFolderBannerCard(
    folderDisplayName: String?,
    isConnected: Boolean,
    deleteOriginalEnabled: Boolean,
    onOpenExternalVault: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenExternalVault)
            .testTag("destination_folder_banner"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConnected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isConnected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.FolderSpecial else Icons.Rounded.Restore,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) {
                        "Folder Tujuan: ${folderDisplayName ?: "Locker"}"
                    } else {
                        "Atur Folder Tujuan & Pemulihan"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(
                            if (isConnected) {
                                "File .vault tersinkron & terbaca hanya di app"
                            } else {
                                "Enkripsi ke folder pilihan & pulihkan file lama"
                            }
                        )
                        if (deleteOriginalEnabled) {
                            append(" • Hapus galeri otomatis")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (isConnected) "Kelola" else "Pilih",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HeroStatBlock(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AlbumCard(
    albumWithStats: AlbumWithStats,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val album = albumWithStats.album
    val accent = Color(album.colorHex)
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AlbumIconRegistry.iconForKey(album.iconKey),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu Album",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Kustomisasi Album") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onEditClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus Album") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (album.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Disematkan",
                        tint = accent,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${albumWithStats.fileCount} item",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Text(
                    text = formatFileSize(albumWithStats.totalSizeBytes),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun VaultFileRowCard(
    file: VaultFileEntity,
    albumName: String?,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onMoveToAlbum: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasMenuActions = onMoveToAlbum != null || onToggleFavorite != null || onExport != null || onDelete != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("vault_file_row_${file.id}"),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = if (hasMenuActions) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EncryptedThumbnailBox(
                file = file,
                loadThumbnail = loadThumbnail,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = file.originalName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (file.isFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Favorit",
                            tint = Color(0xFFF43F5E),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        if (albumName != null) {
                            append(albumName)
                            append(" • ")
                        }
                        append(formatFileSize(file.originalSizeBytes))
                        append(" • ")
                        append(categoryLabel(file.category))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(file.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (hasMenuActions) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag("file_row_menu_${file.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Opsi File",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onMoveToAlbum != null) {
                            DropdownMenuItem(
                                text = { Text("Pindahkan ke Album") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onMoveToAlbum()
                                },
                                modifier = Modifier.testTag("menu_move_file_${file.id}")
                            )
                        }
                        if (onToggleFavorite != null) {
                            DropdownMenuItem(
                                text = { Text(if (file.isFavorite) "Hapus dari Favorit" else "Tandai Favorit") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (file.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onToggleFavorite()
                                }
                            )
                        }
                        if (onExport != null) {
                            DropdownMenuItem(
                                text = { Text("Keluarkan dari Brankas") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.LockOpen, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                },
                                modifier = Modifier.testTag("menu_export_file_${file.id}")
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Hapus Permanen") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultWelcomeCard(
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "Brankas Masih Kosong",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tambahkan foto, video, atau dokumen rahasia. Semua file dienkripsi AES-256-GCM dan disembunyikan dari galeri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickMedia,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Foto/Video")
                }
                OutlinedButton(
                    onClick = onPickDocuments,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dokumen")
                }
            }
        }
    }
}

@Composable
private fun EmptySearchCard(
    categoryLabel: String,
    onShowAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Belum ada file di kategori $categoryLabel",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "File yang Anda kunci ke kategori $categoryLabel akan muncul di sini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onShowAll) {
                Text("Tampilkan Semua File")
            }
        }
    }
}
