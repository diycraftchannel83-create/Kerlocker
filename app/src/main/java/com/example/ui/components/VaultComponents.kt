package com.example.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AlbumWithStats
import com.example.data.GallerySortOrder
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.ui.theme.JetBrainsMonoFamily
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
}

fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))
    return sdf.format(Date(millis))
}

fun categoryIcon(category: MediaCategory): ImageVector = when (category) {
    MediaCategory.IMAGE -> Icons.Rounded.Photo
    MediaCategory.VIDEO -> Icons.Rounded.Videocam
    MediaCategory.DOCUMENT -> Icons.Rounded.Description
    MediaCategory.AUDIO -> Icons.Rounded.AudioFile
    MediaCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

fun categoryLabel(category: MediaCategory): String = when (category) {
    MediaCategory.IMAGE -> "Foto"
    MediaCategory.VIDEO -> "Video"
    MediaCategory.DOCUMENT -> "Dokumen"
    MediaCategory.AUDIO -> "Audio"
    MediaCategory.OTHER -> "Lainnya"
}

fun filterIcon(filter: VaultFileFilter): ImageVector = when (filter) {
    VaultFileFilter.OVERVIEW -> Icons.Rounded.Folder
    VaultFileFilter.ALL_FILES -> Icons.Rounded.Apps
    VaultFileFilter.IMAGE -> Icons.Rounded.Photo
    VaultFileFilter.VIDEO -> Icons.Rounded.Videocam
    VaultFileFilter.DOCUMENT -> Icons.Rounded.Description
    VaultFileFilter.AUDIO -> Icons.Rounded.AudioFile
    VaultFileFilter.OTHER -> Icons.Rounded.InsertDriveFile
    VaultFileFilter.FAVORITES -> Icons.Rounded.Favorite
}

@Composable
fun VaultCategoryFilterBar(
    selectedFilter: VaultFileFilter,
    counts: Map<VaultFileFilter, Int>,
    onFilterSelected: (VaultFileFilter) -> Unit,
    includeOverviewChip: Boolean,
    modifier: Modifier = Modifier
) {
    val otherCount = counts[VaultFileFilter.OTHER] ?: 0
    val orderedFilters = buildList {
        if (includeOverviewChip) {
            add(VaultFileFilter.OVERVIEW)
        }
        add(VaultFileFilter.ALL_FILES)
        add(VaultFileFilter.IMAGE)
        add(VaultFileFilter.VIDEO)
        add(VaultFileFilter.DOCUMENT)
        add(VaultFileFilter.AUDIO)
        add(VaultFileFilter.FAVORITES)
        if (otherCount > 0 || selectedFilter == VaultFileFilter.OTHER) {
            add(VaultFileFilter.OTHER)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        orderedFilters.forEach { filter ->
            val isSelected = selectedFilter == filter
            val count = counts[filter]
            val chipLabel = if (filter == VaultFileFilter.OVERVIEW || count == null) {
                filter.label
            } else {
                "${filter.label} ($count)"
            }
            FilterChip(
                selected = isSelected,
                onClick = {
                    val next = if (includeOverviewChip && isSelected && filter != VaultFileFilter.OVERVIEW) {
                        VaultFileFilter.OVERVIEW
                    } else {
                        filter
                    }
                    onFilterSelected(next)
                },
                leadingIcon = {
                    Icon(
                        imageVector = filterIcon(filter),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = chipLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag("filter_chip_${filter.name}")
            )
        }
    }
}

@Composable
fun EncryptedThumbnailBox(
    file: VaultFileEntity,
    loadThumbnail: suspend (VaultFileEntity) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.id, file.encryptedThumbName) {
        if (file.encryptedThumbName != null) {
            bitmap = loadThumbnail(file)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = file.originalName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (file.category == MediaCategory.VIDEO) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = "Video terenkripsi",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        } else {
            Icon(
                imageVector = categoryIcon(file.category),
                contentDescription = categoryLabel(file.category),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }

        // Subtle AES lock badge in top-right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
fun AesBadgePill(
    text: String = "AES-256-GCM",
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultActionSheet(
    showCreateAlbumOption: Boolean = true,
    albums: List<AlbumWithStats> = emptyList(),
    selectedAlbumId: String? = null,
    onSelectAlbum: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit,
    onCreateAlbum: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tambah ke Brankas Terenkripsi",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "File dienkripsi dengan AES-256-GCM ke brankas & folder tujuan, lalu otomatis dihapus dari galeri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (albums.isNotEmpty() && onSelectAlbum != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Simpan ke Album:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(albums.size) { index ->
                            val item = albums[index]
                            val isSelected = item.album.id == selectedAlbumId
                            val accent = Color(item.album.colorHex)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isSelected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onSelectAlbum(item.album.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = AlbumIconRegistry.iconForKey(item.album.iconKey),
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = item.album.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SheetActionItem(
                icon = Icons.Rounded.PhotoLibrary,
                title = "Kunci Foto & Video (Galeri)",
                subtitle = "Pilih cepat dari galeri tanpa izin storage luas",
                accent = MaterialTheme.colorScheme.primary,
                testTag = "sheet_pick_media",
                onClick = {
                    onDismiss()
                    onPickMedia()
                }
            )

            SheetActionItem(
                icon = Icons.Rounded.UploadFile,
                title = "Kunci via Pengelola File (Hapus Langsung)",
                subtitle = "Foto, video, PDF & dokumen — file sumber langsung terhapus",
                accent = MaterialTheme.colorScheme.tertiary,
                testTag = "sheet_pick_documents",
                onClick = {
                    onDismiss()
                    onPickDocuments()
                }
            )

            if (showCreateAlbumOption) {
                SheetActionItem(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Buat Album Baru",
                    subtitle = "Kelompokkan file dengan ikon & warna kustom",
                    accent = MaterialTheme.colorScheme.secondary,
                    testTag = "sheet_create_album",
                    onClick = {
                        onDismiss()
                        onCreateAlbum()
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AlbumPickerDialog(
    title: String,
    albums: List<AlbumWithStats>,
    excludedAlbumId: String? = null,
    onDismiss: () -> Unit,
    onAlbumSelected: (AlbumWithStats) -> Unit,
    onCreateNewAlbum: (() -> Unit)? = null
) {
    val available = albums.filter { it.album.id != excludedAlbumId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (available.isEmpty()) {
                        Text(
                            text = if (onCreateNewAlbum != null) {
                                "Belum ada album tujuan lain. Buat album baru untuk memindahkan file ini."
                            } else {
                                "Belum ada album tujuan lain. Buat album baru terlebih dahulu."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        available.forEach { item ->
                            val color = Color(item.album.colorHex)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { onAlbumSelected(item) }
                                    .padding(12.dp)
                                    .testTag("move_to_album_${item.album.id}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(color.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AlbumIconRegistry.iconForKey(item.album.iconKey),
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.album.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.fileCount} file • ${formatFileSize(item.totalSizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (onCreateNewAlbum != null) {
                    OutlinedButton(
                        onClick = onCreateNewAlbum,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("move_create_new_album_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buat Album Baru & Pindahkan")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun BatchExportDialog(
    fileCount: Int,
    onDismiss: () -> Unit,
    onExportToDeviceGallery: (removeFromVault: Boolean) -> Unit,
    onChooseDestinationFolder: (removeFromVault: Boolean) -> Unit
) {
    var removeFromVault by remember { mutableStateOf(true) }
    val countLabel = if (fileCount == 1) "1 File" else "$fileCount File"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Keluarkan $countLabel dari Brankas?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "File akan didekripsi kembali ke format normal agar dapat diakses di Galeri atau Pengelola File perangkat.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hapus dari Brankas setelah dikeluarkan",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = removeFromVault,
                        onCheckedChange = { removeFromVault = it },
                        modifier = Modifier.testTag("batch_export_remove_switch")
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Button(
                            onClick = { onExportToDeviceGallery(removeFromVault) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("batch_export_gallery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Simpan ke Galeri / Unduhan")
                        }
                    }
                    OutlinedButton(
                        onClick = { onChooseDestinationFolder(removeFromVault) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("batch_export_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pilih Folder Tujuan...")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun VaultSortMenuButton(
    currentSort: GallerySortOrder,
    onSortSelected: (GallerySortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { expanded = true }
                .testTag("sort_order_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "Urutkan galeri",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = currentSort.shortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GallerySortOrder.entries.forEach { order ->
                val isSelected = order == currentSort
                DropdownMenuItem(
                    text = {
                        Text(
                            text = order.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSortSelected(order)
                    },
                    modifier = Modifier.testTag("sort_option_${order.name}")
                )
            }
        }
    }
}

