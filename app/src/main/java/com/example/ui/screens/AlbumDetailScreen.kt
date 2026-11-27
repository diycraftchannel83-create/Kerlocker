package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.AlbumEntity
import com.example.data.AlbumWithStats
import com.example.data.GallerySortOrder
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.ui.ImportProgressState
import com.example.ui.components.AlbumEditorDialog
import com.example.ui.components.AlbumIconRegistry
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.BatchExportDialog
import com.example.ui.components.EncryptedThumbnailBox
import com.example.ui.components.VaultActionSheet
import com.example.ui.components.VaultCategoryFilterBar
import com.example.ui.components.VaultSortMenuButton
import com.example.ui.components.formatFileSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    album: AlbumEntity?,
    files: List<VaultFileEntity>?,
    allAlbums: List<AlbumWithStats>,
    sortOrder: GallerySortOrder = GallerySortOrder.DATE_DESC,
    onSortOrderChange: (GallerySortOrder) -> Unit = {},
    importProgress: ImportProgressState?,
    onBack: () -> Unit,
    onFileClick: (String, VaultFileFilter) -> Unit,
    onStartSlideshow: (String, VaultFileFilter) -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onUpdateAlbum: (AlbumEntity) -> Unit,
    onMoveFiles: (List<String>, String) -> Unit,
    onCreateAlbumAndMoveFiles: (List<String>, String, String, Long, Boolean) -> Unit,
    onToggleFavorite: (VaultFileEntity) -> Unit,
    onDeleteFiles: (List<String>) -> Unit,
    onExportFile: (VaultFileEntity, Uri, Boolean) -> Unit,
    onExportFilesToDeviceGallery: (List<String>, Boolean) -> Unit,
    onExportFilesToFolder: (List<String>, Uri, Boolean) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    val isFilesLoaded = files != null
    val resolvedFiles = files.orEmpty()
    var isGridView by remember { mutableStateOf(true) }
    var selectedFilter by remember(albumId) { mutableStateOf(VaultFileFilter.ALL_FILES) }
    var isSelectionMode by remember(albumId) { mutableStateOf(false) }
    var selectedIds by remember(albumId) { mutableStateOf<Set<String>>(emptySet()) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showEditAlbumDialog by remember { mutableStateOf(false) }
    var moveTargetIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingCreateAlbumForMoveIds by remember { mutableStateOf<List<String>?>(null) }
    var deleteTargetIds by remember { mutableStateOf<List<String>?>(null) }
    var exportTargetIds by remember { mutableStateOf<List<String>?>(null) }
    var pendingFolderExport by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    var pendingExportFile by remember { mutableStateOf<VaultFileEntity?>(null) }

    val filterCounts = remember(resolvedFiles) { VaultFileFilter.computeCounts(resolvedFiles) }
    val filteredFiles = remember(resolvedFiles, selectedFilter, sortOrder) {
        sortOrder.sortFiles(resolvedFiles.filter { selectedFilter.matchesFile(it) })
    }
    val inSelectionMode = isSelectionMode || selectedIds.isNotEmpty()
    val accentColor = album?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary

    BackHandler(enabled = inSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        onClearExternalPickerFlag()
        if (uris.isNotEmpty()) onImportUris(uris)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onClearExternalPickerFlag()
        if (uris.isNotEmpty()) onImportUris(uris)
    }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        onClearExternalPickerFlag()
        val fileToExport = pendingExportFile
        pendingExportFile = null
        if (uri != null && fileToExport != null) {
            onExportFile(fileToExport, uri, true)
            selectedIds = emptySet()
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
            selectedIds = emptySet()
            isSelectionMode = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (inSelectionMode) {
                                isSelectionMode = false
                                selectedIds = emptySet()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (inSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Kembali"
                        )
                    }
                },
                title = {
                    if (inSelectionMode) {
                        Text("${selectedIds.size} dipilih")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (album != null) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accentColor.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AlbumIconRegistry.iconForKey(album.iconKey),
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column {
                                Text(
                                    text = album?.name ?: "Album",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (!isFilesLoaded) {
                                        "Memuat isi album..."
                                    } else if (selectedFilter == VaultFileFilter.ALL_FILES) {
                                        "${resolvedFiles.size} file • ${formatFileSize(resolvedFiles.sumOf { it.originalSizeBytes })}"
                                    } else {
                                        "${filteredFiles.size} dari ${resolvedFiles.size} file • ${selectedFilter.label}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (inSelectionMode) {
                        if (filteredFiles.isNotEmpty()) {
                            IconButton(onClick = {
                                selectedIds = if (selectedIds.size == filteredFiles.size) {
                                    emptySet()
                                } else {
                                    filteredFiles.map { it.id }.toSet()
                                }
                            }) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = "Pilih Semua")
                            }
                        }
                    } else {
                        if (filteredFiles.size > 1) {
                            IconButton(
                                onClick = { onStartSlideshow(filteredFiles.first().id, selectedFilter) },
                                modifier = Modifier.testTag("album_slideshow_button")
                            ) {
                                Icon(Icons.Rounded.Slideshow, contentDescription = "Putar Slideshow")
                            }
                        }
                        if (filteredFiles.isNotEmpty()) {
                            IconButton(
                                onClick = { isSelectionMode = true },
                                modifier = Modifier.testTag("album_select_mode_button")
                            ) {
                                Icon(Icons.Rounded.Checklist, contentDescription = "Pilih Beberapa File")
                            }
                        }
                        IconButton(onClick = { isGridView = !isGridView }) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                contentDescription = "Ganti Tampilan"
                            )
                        }
                        if (album != null) {
                            IconButton(onClick = { showEditAlbumDialog = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit Album")
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showActionSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Tambah File") },
                    containerColor = accentColor,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("album_add_file_fab")
                )
            }
        },
        bottomBar = {
            if (inSelectionMode) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(
                            onClick = { moveTargetIds = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.testTag("album_selection_move_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pindah")
                        }
                        TextButton(
                            onClick = { exportTargetIds = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.testTag("album_selection_export_button")
                        ) {
                            Icon(Icons.Rounded.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Keluarkan")
                        }
                        TextButton(
                            onClick = { deleteTargetIds = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty(),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            importProgress?.let { progress ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(progress.currentFileName, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enkripsi berjalan di latar belakang • Anda tetap dapat beralih aplikasi.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (resolvedFiles.isNotEmpty()) {
                VaultCategoryFilterBar(
                    selectedFilter = selectedFilter,
                    counts = filterCounts,
                    onFilterSelected = { next ->
                        selectedFilter = next
                        selectedIds = emptySet()
                        isSelectionMode = false
                    },
                    includeOverviewChip = false,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredFiles.size} item • Urutan: ${sortOrder.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VaultSortMenuButton(
                        currentSort = sortOrder,
                        onSortSelected = onSortOrderChange
                    )
                }
            }

            if (!isFilesLoaded) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else if (resolvedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Album Ini Masih Kosong",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tekan tombol di bawah untuk mengunci dan menyembunyikan foto, video, atau dokumen ke dalam album ini.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                onMarkLaunchingExternalPicker()
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Foto & Video")
                            }
                            OutlinedButton(onClick = {
                                onMarkLaunchingExternalPicker()
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Dokumen")
                            }
                        }
                    }
                }
            } else if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Tidak Ada ${selectedFilter.label} di Album Ini",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Coba pilih kategori lain atau tampilkan seluruh isi album.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { selectedFilter = VaultFileFilter.ALL_FILES }) {
                            Text("Tampilkan Semua (${files.size})")
                        }
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredFiles, key = { it.id }) { file ->
                        val isSelected = file.id in selectedIds
                        var showGridItemMenu by remember(file.id) { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) accentColor else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (inSelectionMode) {
                                            selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                        } else {
                                            onFileClick(file.id, selectedFilter)
                                        }
                                    },
                                    onLongClick = {
                                        selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                    }
                                )
                        ) {
                            EncryptedThumbnailBox(
                                file = file,
                                loadThumbnail = loadThumbnail,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = file.originalName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            if (inSelectionMode) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) accentColor else Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                )
                            } else {
                                Box(modifier = Modifier.align(Alignment.TopStart)) {
                                    IconButton(
                                        onClick = { showGridItemMenu = true },
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .size(34.dp)
                                            .testTag("grid_item_menu_${file.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = "Opsi File",
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(2.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showGridItemMenu,
                                        onDismissRequest = { showGridItemMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Pindahkan ke Album") },
                                            leadingIcon = {
                                                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                                            },
                                            onClick = {
                                                showGridItemMenu = false
                                                moveTargetIds = listOf(file.id)
                                            },
                                            modifier = Modifier.testTag("grid_move_file_${file.id}")
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (file.isFavorite) "Hapus dari Favorit" else "Tandai Favorit") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (file.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                showGridItemMenu = false
                                                onToggleFavorite(file)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Keluarkan dari Brankas") },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.LockOpen, contentDescription = null)
                                            },
                                            onClick = {
                                                showGridItemMenu = false
                                                pendingExportFile = file
                                                onMarkLaunchingExternalPicker()
                                                exportDocumentLauncher.launch(file.originalName)
                                            }
                                        )
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
                                                showGridItemMenu = false
                                                deleteTargetIds = listOf(file.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredFiles, key = { it.id }) { file ->
                        val isSelected = file.id in selectedIds
                        VaultFileRowCard(
                            file = file,
                            albumName = null,
                            loadThumbnail = loadThumbnail,
                            isSelected = isSelected,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (isSelected) selectedIds - file.id else selectedIds + file.id
                                } else {
                                    onFileClick(file.id, selectedFilter)
                                }
                            },
                            onMoveToAlbum = if (!inSelectionMode) {
                                { moveTargetIds = listOf(file.id) }
                            } else null,
                            onToggleFavorite = if (!inSelectionMode) {
                                { onToggleFavorite(file) }
                            } else null,
                            onExport = if (!inSelectionMode) {
                                { exportTargetIds = listOf(file.id) }
                            } else null,
                            onDelete = if (!inSelectionMode) {
                                { deleteTargetIds = listOf(file.id) }
                            } else null
                        )
                    }
                }
            }
        }
    }

    if (showActionSheet) {
        VaultActionSheet(
            showCreateAlbumOption = false,
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
            }
        )
    }

    if (showEditAlbumDialog && album != null) {
        AlbumEditorDialog(
            existingAlbum = album,
            onDismiss = { showEditAlbumDialog = false },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onUpdateAlbum(album.copy(name = name, iconKey = iconKey, colorHex = colorHex, isPinned = isPinned))
                showEditAlbumDialog = false
            }
        )
    }

    moveTargetIds?.let { ids ->
        val labelCount = if (ids.size == 1) "File" else "${ids.size} File"
        AlbumPickerDialog(
            title = "Pindahkan $labelCount ke:",
            albums = allAlbums,
            excludedAlbumId = album?.id,
            onDismiss = { moveTargetIds = null },
            onAlbumSelected = { destination ->
                onMoveFiles(ids, destination.album.id)
                selectedIds = emptySet()
                isSelectionMode = false
                moveTargetIds = null
            },
            onCreateNewAlbum = {
                pendingCreateAlbumForMoveIds = ids
                moveTargetIds = null
            }
        )
    }

    pendingCreateAlbumForMoveIds?.let { ids ->
        AlbumEditorDialog(
            onDismiss = { pendingCreateAlbumForMoveIds = null },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onCreateAlbumAndMoveFiles(ids, name, iconKey, colorHex, isPinned)
                pendingCreateAlbumForMoveIds = null
                selectedIds = emptySet()
                isSelectionMode = false
            }
        )
    }

    deleteTargetIds?.let { ids ->
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
                        selectedIds = emptySet()
                        isSelectionMode = false
                        deleteTargetIds = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus Permanen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetIds = null }) {
                    Text("Batalkan")
                }
            }
        )
    }

    exportTargetIds?.let { ids ->
        BatchExportDialog(
            fileCount = ids.size,
            onDismiss = { exportTargetIds = null },
            onExportToDeviceGallery = { removeFromVault ->
                onExportFilesToDeviceGallery(ids, removeFromVault)
                exportTargetIds = null
                selectedIds = emptySet()
                isSelectionMode = false
            },
            onChooseDestinationFolder = { removeFromVault ->
                exportTargetIds = null
                pendingFolderExport = ids to removeFromVault
                onMarkLaunchingExternalPicker()
                exportFolderLauncher.launch(null)
            }
        )
    }
}
