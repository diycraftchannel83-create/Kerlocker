package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ExternalVaultEntry
import com.example.data.MediaCategory
import com.example.data.VaultFileFilter
import com.example.ui.VaultUiState
import com.example.ui.components.AesBadgePill
import com.example.ui.components.VaultCategoryFilterBar
import com.example.ui.components.categoryIcon
import com.example.ui.components.categoryLabel
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalVaultScreen(
    uiState: VaultUiState,
    onBack: () -> Unit,
    onFolderSelected: (String?) -> Unit,
    onInspectFolder: (folderUri: String, recoveryPin: String?) -> Unit,
    onImportRecoveryKeyFile: (folderUri: String, keyFileUri: String, pin: String) -> Unit,
    onRestoreEntry: (folderUri: String, entry: ExternalVaultEntry) -> Unit,
    onRestoreAll: (folderUri: String) -> Unit,
    onOpenExternalEntry: (folderUri: String, entry: ExternalVaultEntry) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    loadExternalThumbnail: suspend (folderUri: String, thumbName: String) -> Bitmap?
) {
    val context = LocalContext.current
    val folderUri = uiState.securityPrefs.externalVaultFolderUri
    val extState = uiState.externalVaultState
    var recoveryPinInput by remember { mutableStateOf("") }
    var pendingKeyFileUri by remember { mutableStateOf<String?>(null) }
    var keyFilePinInput by remember { mutableStateOf("") }
    var selectedFilter by remember(folderUri) { mutableStateOf(VaultFileFilter.ALL_FILES) }
    val externalCounts = remember(extState.entries) {
        mapOf(
            VaultFileFilter.ALL_FILES to extState.entries.size,
            VaultFileFilter.IMAGE to extState.entries.count { it.category == MediaCategory.IMAGE },
            VaultFileFilter.VIDEO to extState.entries.count { it.category == MediaCategory.VIDEO },
            VaultFileFilter.DOCUMENT to extState.entries.count { it.category == MediaCategory.DOCUMENT },
            VaultFileFilter.AUDIO to extState.entries.count { it.category == MediaCategory.AUDIO },
            VaultFileFilter.OTHER to extState.entries.count { it.category == MediaCategory.OTHER },
            VaultFileFilter.FAVORITES to extState.entries.count { it.isFavorite }
        )
    }
    val filteredEntries = remember(extState.entries, selectedFilter) {
        extState.entries.filter { selectedFilter.matches(it.category, it.isFavorite) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        onClearExternalPickerFlag()
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onFolderSelected(treeUri.toString())
        }
    }

    val recoveryKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { fileUri: Uri? ->
        onClearExternalPickerFlag()
        if (fileUri != null) {
            keyFilePinInput = recoveryPinInput
            pendingKeyFileUri = fileUri.toString()
        }
    }

    LaunchedEffect(folderUri) {
        if (folderUri != null) {
            onInspectFolder(folderUri, null)
        }
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
                title = { Text("Folder Tujuan & Pemulihan") },
                actions = {
                    if (folderUri != null) {
                        IconButton(
                            onClick = { onInspectFolder(folderUri, null) },
                            enabled = !extState.isScanning && !extState.isRestoring
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Pindai Ulang Folder")
                        }
                    }
                }
            )
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Destination Folder Connection Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Folder Tujuan Terenkripsi",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                AesBadgePill(text = "AES-256-GCM")
                            }

                            Text(
                                text = "Semua file yang dimasukkan ke brankas otomatis dienkripsi menjadi .vault di dalam subfolder Locker (tersembunyi dari Galeri via .nomedia) dan hanya bisa dibaca atau dipulihkan melalui aplikasi ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (folderUri != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Lokasi Terhubung:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = extState.folderDisplayName.ifBlank {
                                                uiState.securityPrefs.externalVaultFolderName ?: "Folder Locker"
                                            },
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onMarkLaunchingExternalPicker()
                                        folderPickerLauncher.launch(null)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("pick_destination_folder_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CreateNewFolder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (folderUri == null) "Pilih Folder Tujuan" else "Ganti Folder")
                                }
                                if (folderUri != null) {
                                    TextButton(onClick = { onFolderSelected(null) }) {
                                        Text("Putuskan")
                                    }
                                }
                            }
                        }
                    }
                }

                if (folderUri != null) {
                    if (extState.isScanning) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = "Memindai file terenkripsi .vault...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (extState.needsRecoveryPin) {
                        val lockedCount = extState.entries.count { !it.canDecrypt }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Key,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Kunci Enkripsi Berbeda Terdeteksi",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = if (extState.hasRecoveryKey) {
                                            "Terdapat $lockedCount file dari instalasi atau kunci sebelumnya. Masukkan 6 digit PIN yang digunakan saat file tersebut dikunci, atau impor file cadangan vault_recovery.key."
                                        } else {
                                            "Terdapat $lockedCount file dengan kunci berbeda namun file vault_recovery.key lama belum ditemukan di folder ini. Pilih file cadangan vault_recovery.key beserta PIN-nya untuk membuka kunci."
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (extState.hasRecoveryKey) {
                                        OutlinedTextField(
                                            value = recoveryPinInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all(Char::isDigit)) recoveryPinInput = it
                                            },
                                            label = { Text("PIN Kunci Lama (6 digit)") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            singleLine = true,
                                            isError = extState.wrongRecoveryPin,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("external_recovery_pin_input")
                                        )
                                        if (extState.wrongRecoveryPin) {
                                            Text(
                                                text = "PIN tidak cocok dengan kunci pemulihan yang ada di folder ini. Coba PIN lama lainnya atau impor file .key cadangan.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Button(
                                            onClick = { onInspectFolder(folderUri, recoveryPinInput) },
                                            enabled = recoveryPinInput.length == 6,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("unlock_external_pin_button")
                                        ) {
                                            Text("Buka Kunci dengan PIN Lama")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onMarkLaunchingExternalPicker()
                                            recoveryKeyFileLauncher.launch(arrayOf("*/*"))
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("import_recovery_key_file_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.UploadFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Impor File Kunci (.key / .json)")
                                    }
                                }
                            }
                        }
                    }

                    if (!extState.isScanning) {
                        val recoverableReady = extState.entries.count { it.canDecrypt && !it.alreadyInLocalVault }
                        val lockedCount = extState.entries.count { !it.canDecrypt }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "File Terenkripsi di Folder (${extState.entries.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            recoverableReady > 0 && lockedCount > 0 ->
                                                "$recoverableReady siap dipulihkan • $lockedCount butuh PIN kunci lama"
                                            recoverableReady > 0 ->
                                                "$recoverableReady file siap dipulihkan ke brankas aplikasi"
                                            lockedCount > 0 ->
                                                "$lockedCount file masih terkunci kunci enkripsi berbeda"
                                            extState.entries.isNotEmpty() ->
                                                "Semua file sudah tersinkron dengan brankas lokal"
                                            else ->
                                                "Belum ada file .vault di folder ini"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (recoverableReady > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { onRestoreAll(folderUri) },
                                        enabled = !extState.isRestoring,
                                        modifier = Modifier.testTag("restore_all_external_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Restore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (extState.isRestoring) "Memulihkan..." else "Pulihkan ($recoverableReady)")
                                    }
                                }
                            }
                        }

                        if (extState.entries.isNotEmpty()) {
                            item {
                                VaultCategoryFilterBar(
                                    selectedFilter = selectedFilter,
                                    counts = externalCounts,
                                    onFilterSelected = { selectedFilter = it },
                                    includeOverviewChip = false
                                )
                            }
                        }

                        if (extState.entries.isEmpty()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Text(
                                            text = "Belum Ada File .vault",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Setiap file baru yang Anda kunci dari Galeri akan otomatis dienkripsi ke folder tujuan ini.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (filteredEntries.isEmpty()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Tidak Ada ${selectedFilter.label} di Folder",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedButton(onClick = { selectedFilter = VaultFileFilter.ALL_FILES }) {
                                            Text("Tampilkan Semua (${extState.entries.size})")
                                        }
                                    }
                                }
                            }
                        } else {
                            items(filteredEntries, key = { it.backupFileName }) { entry ->
                                ExternalVaultEntryCard(
                                    folderUri = folderUri,
                                    entry = entry,
                                    canDecrypt = entry.canDecrypt,
                                    isRestoring = extState.isRestoring,
                                    loadThumbnail = loadExternalThumbnail,
                                    onReadInApp = { onOpenExternalEntry(folderUri, entry) },
                                    onRestore = { onRestoreEntry(folderUri, entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (folderUri != null && pendingKeyFileUri != null) {
        AlertDialog(
            onDismissRequest = { pendingKeyFileUri = null },
            title = { Text("PIN File Kunci Pemulihan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Masukkan 6 digit PIN yang digunakan untuk membuat file vault_recovery.key tersebut.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = keyFilePinInput,
                        onValueChange = {
                            if (it.length <= 6 && it.all(Char::isDigit)) keyFilePinInput = it
                        },
                        label = { Text("PIN Kunci (6 digit)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val picked = pendingKeyFileUri
                        pendingKeyFileUri = null
                        if (picked != null && keyFilePinInput.length == 6) {
                            onImportRecoveryKeyFile(folderUri, picked, keyFilePinInput)
                        }
                    },
                    enabled = keyFilePinInput.length == 6
                ) {
                    Text("Buka Kunci")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKeyFileUri = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun ExternalVaultEntryCard(
    folderUri: String,
    entry: ExternalVaultEntry,
    canDecrypt: Boolean,
    isRestoring: Boolean,
    loadThumbnail: suspend (String, String) -> Bitmap?,
    onReadInApp: () -> Unit,
    onRestore: () -> Unit
) {
    var thumbBitmap by remember(folderUri, entry.thumbFileName, canDecrypt) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(folderUri, entry.thumbFileName, canDecrypt) {
        val thumbName = entry.thumbFileName
        if (canDecrypt && thumbName != null) {
            thumbBitmap = loadThumbnail(folderUri, thumbName)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = thumbBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = entry.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = categoryIcon(entry.category),
                            contentDescription = categoryLabel(entry.category),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            entry.albumName?.let {
                                append(it)
                                append(" • ")
                            }
                            append(formatFileSize(entry.sizeBytes))
                            append(" • ")
                            append(categoryLabel(entry.category))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formatTimestamp(entry.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                        if (entry.alreadyInLocalVault) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Sudah ada di brankas lokal",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Tersinkron",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (canDecrypt) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReadInApp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Baca di App")
                    }
                    Button(
                        onClick = onRestore,
                        enabled = !isRestoring,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (entry.alreadyInLocalVault) Icons.Rounded.DownloadDone else Icons.Rounded.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (entry.alreadyInLocalVault) "Buka / Sinkron" else "Pulihkan")
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Kunci enkripsi berbeda — masukkan PIN lama atau impor file .key di atas untuk memulihkan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
