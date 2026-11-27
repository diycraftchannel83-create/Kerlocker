package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.DuplicateDetector
import com.example.data.DuplicateGroup
import com.example.data.VaultFileEntity
import com.example.ui.VaultUiState
import com.example.ui.components.EncryptedThumbnailBox
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateTrackerScreen(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onScanDuplicates: () -> Unit,
    onDeleteDuplicateFiles: (List<String>) -> Unit,
    onOpenFile: (String) -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    val scanState = uiState.duplicateScanState
    val groups = scanState.groups
    val albumNameById = remember(uiState.albums) {
        uiState.albums.associate { it.album.id to it.album.name }
    }

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        onScanDuplicates()
    }

    // Keep selected IDs aligned with existing duplicate groups
    LaunchedEffect(groups) {
        val validIds = groups.flatMap { it.files.map { f -> f.id } }.toSet()
        selectedIds = selectedIds.intersect(validIds)
    }

    val totalDuplicateExtras = remember(groups) { groups.sumOf { it.duplicateCount } }
    val totalReclaimableBytes = remember(groups) { groups.sumOf { it.reclaimableBytes } }
    val selectedBytes = remember(groups, selectedIds) {
        groups.flatMap { it.files }
            .filter { it.id in selectedIds }
            .sumOf { it.originalSizeBytes }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Pelacak Duplikat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                scanState.isScanning -> "Memeriksa hash SHA-256..."
                                groups.isEmpty() && scanState.hasScanned -> "Tidak ada duplikat ditemukan"
                                else -> "${groups.size} grup • $totalDuplicateExtras salinan berlebih"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (groups.isNotEmpty() && !scanState.isScanning) {
                        TextButton(
                            onClick = {
                                selectedIds = if (selectedIds.isNotEmpty()) {
                                    emptySet()
                                } else {
                                    DuplicateDetector.selectRecommendedCopiesToDelete(groups)
                                }
                            },
                            modifier = Modifier.testTag("auto_select_duplicates_button")
                        ) {
                            Text(if (selectedIds.isNotEmpty()) "Batal Pilih" else "Pilih Otomatis")
                        }
                    }
                    IconButton(
                        onClick = onScanDuplicates,
                        enabled = !scanState.isScanning,
                        modifier = Modifier.testTag("rescan_duplicates_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Pindai Ulang Duplikat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedIds.size} salinan dipilih",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Hemat ${formatFileSize(selectedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(
                            onClick = { pendingDeleteIds = selectedIds.toList() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("delete_selected_duplicates_button")
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hapus Terpilih")
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    DuplicateSummaryCard(
                        groupCount = groups.size,
                        extraCount = totalDuplicateExtras,
                        reclaimableBytes = totalReclaimableBytes,
                        isScanning = scanState.isScanning,
                        scannedFiles = scanState.scannedFiles,
                        totalFilesToHash = scanState.totalFilesToHash,
                        hasSelection = selectedIds.isNotEmpty(),
                        onAutoSelect = {
                            selectedIds = DuplicateDetector.selectRecommendedCopiesToDelete(groups)
                        }
                    )
                }

                if (scanState.isScanning && groups.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Memindai Duplikat Konten (SHA-256)...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Memeriksa kesamaan byte terenkripsi tanpa mengekspos file ke luar brankas.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (scanState.hasScanned && groups.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Brankas Bebas Duplikat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tidak ditemukan file dengan konten identik. Penyimpanan brankas Anda sudah efisien.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = onScanDuplicates) {
                                    Text("Pindai Ulang")
                                }
                            }
                        }
                    }
                } else {
                    items(groups, key = { it.contentHash }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            albumNameById = albumNameById,
                            selectedIds = selectedIds,
                            onToggleSelect = { file ->
                                val alreadySelected = file.id in selectedIds
                                if (alreadySelected) {
                                    selectedIds = selectedIds - file.id
                                } else {
                                    // Always preserve at least 1 copy in every group
                                    val selectedInGroupCount = group.files.count { it.id in selectedIds }
                                    if (selectedInGroupCount < group.files.size - 1) {
                                        selectedIds = selectedIds + file.id
                                    }
                                }
                            },
                            onDeleteSingle = { file ->
                                pendingDeleteIds = listOf(file.id)
                            },
                            onOpenFile = onOpenFile,
                            loadThumbnail = loadThumbnail
                        )
                    }
                }
            }
        }
    }

    pendingDeleteIds?.let { idsToDelete ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            title = {
                Text(
                    if (idsToDelete.size == 1) "Hapus 1 File Duplikat?" else "Hapus ${idsToDelete.size} File Duplikat?"
                )
            },
            text = {
                Text(
                    "Salinan duplikat yang dipilih akan dihapus permanen dari brankas. Minimal satu salinan asli tetap tersimpan aman."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteDuplicateFiles(idsToDelete)
                        selectedIds = selectedIds - idsToDelete.toSet()
                        pendingDeleteIds = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("duplicate_delete_confirm_button")
                ) {
                    Text("Hapus Permanen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteIds = null },
                    modifier = Modifier.testTag("duplicate_delete_cancel_button")
                ) {
                    Text("Batalkan")
                }
            }
        )
    }
}

@Composable
private fun DuplicateSummaryCard(
    groupCount: Int,
    extraCount: Int,
    reclaimableBytes: Long,
    isScanning: Boolean,
    scannedFiles: Int,
    totalFilesToHash: Int,
    hasSelection: Boolean,
    onAutoSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Deteksi Duplikat SHA-256",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pilih salah satu salinan untuk dihapus sambil mempertahankan aslinya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isScanning) {
                LinearProgressIndicator(
                    progress = {
                        if (totalFilesToHash > 0) scannedFiles.toFloat() / totalFilesToHash.toFloat() else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "$groupCount",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Grup Duplikat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column {
                        Text(
                            text = "$extraCount",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "File Berlebih",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatFileSize(reclaimableBytes),
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Ruang Dapat Dihemat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (groupCount > 0 && !hasSelection) {
                    Button(
                        onClick = onAutoSelect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pilih Semua Duplikat (Simpan 1 Salinan)")
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    albumNameById: Map<String, String>,
    selectedIds: Set<String>,
    onToggleSelect: (VaultFileEntity) -> Unit,
    onDeleteSingle: (VaultFileEntity) -> Unit,
    onOpenFile: (String) -> Unit,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?
) {
    val selectedInGroup = group.files.count { it.id in selectedIds }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duplicate_group_${group.contentHash.take(8)}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${group.files.size} File Identik",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${formatFileSize(group.fileSizeBytes)} / file • Hash ${group.contentHash.take(10)}…",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Hemat ${formatFileSize(group.reclaimableBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            group.files.forEachIndexed { index, file ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val isSelected = file.id in selectedIds
                val canSelectThis = isSelected || selectedInGroup < group.files.size - 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile(file.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { if (canSelectThis) onToggleSelect(file) },
                        enabled = canSelectThis,
                        modifier = Modifier.testTag("duplicate_checkbox_${file.id}")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    EncryptedThumbnailBox(
                        file = file,
                        loadThumbnail = loadThumbnail,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = file.originalName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
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
                        Text(
                            text = "${albumNameById[file.albumId] ?: "Brankas"} • ${formatTimestamp(file.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (index == 0) {
                            Text(
                                text = "Disarankan disimpan (utama)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = { onDeleteSingle(file) },
                        modifier = Modifier.testTag("duplicate_delete_single_${file.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Hapus salinan ini",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
