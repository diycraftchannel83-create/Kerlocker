package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.AlbumWithStats
import com.example.data.MediaCategory
import com.example.data.VaultFileEntity
import com.example.ui.components.AesBadgePill
import com.example.ui.components.AlbumEditorDialog
import com.example.ui.components.AlbumPickerDialog
import com.example.ui.components.GalleryNavigationHelper
import com.example.ui.components.ImageAdjustmentParams
import com.example.ui.components.ImageAdjustmentProcessor
import com.example.ui.components.SlideAxis
import com.example.ui.components.SlideGestureOutcome
import com.example.ui.components.SlideNavigationAction
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureViewerScreen(
    file: VaultFileEntity?,
    galleryFiles: List<VaultFileEntity> = listOfNotNull(file),
    autoStartSlideshow: Boolean = false,
    allAlbums: List<AlbumWithStats>,
    vaultInternalPath: String,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource,
    decryptToEphemeralFile: suspend (VaultFileEntity) -> File,
    secureDeleteEphemeralFile: (File) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: (VaultFileEntity) -> Unit,
    onMoveFile: (String, String) -> Unit,
    onCreateAlbumAndMoveFile: ((fileId: String, name: String, iconKey: String, colorHex: Long, isPinned: Boolean) -> Unit)? = null,
    onDeleteFile: (String) -> Unit,
    onExportFile: (VaultFileEntity, Uri, Boolean) -> Unit,
    onMarkLaunchingExternalPicker: () -> Unit,
    onClearExternalPickerFlag: () -> Unit,
    isExternalReadOnly: Boolean = false,
    onRestoreExternalFile: ((VaultFileEntity) -> Unit)? = null,
    onSaveEditedImage: ((
        file: VaultFileEntity,
        params: ImageAdjustmentParams,
        overwriteOriginal: Boolean,
        onComplete: (VaultFileEntity?) -> Unit
    ) -> Unit)? = null
) {
    val effectiveGallery = remember(galleryFiles, file) {
        when {
            galleryFiles.isNotEmpty() -> galleryFiles
            file != null -> listOf(file)
            else -> emptyList()
        }
    }
    var activeFileId by rememberSaveable(file?.id) {
        mutableStateOf(file?.id ?: effectiveGallery.firstOrNull()?.id.orEmpty())
    }
    val currentIndex = remember(effectiveGallery, activeFileId) {
        val idx = effectiveGallery.indexOfFirst { it.id == activeFileId }
        if (idx >= 0) idx else 0
    }
    val activeFile = effectiveGallery.getOrNull(currentIndex) ?: file

    if (activeFile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                TextButton(onClick = onBack) {
                    Text("Kembali")
                }
            }
        }
        return
    }

    var isSlideshowPlaying by rememberSaveable(file?.id) {
        mutableStateOf(autoStartSlideshow && effectiveGallery.size > 1)
    }
    var slideProgress by remember { mutableFloatStateOf(0f) }
    var lastSlideOutcome by remember {
        mutableStateOf(SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.HORIZONTAL))
    }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCreateAlbumOnMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExportChoiceDialog by remember { mutableStateOf(false) }
    var removeAfterExport by remember { mutableStateOf(true) }

    var isEditingImage by remember(activeFile.id) { mutableStateOf(false) }
    var adjustments by remember(activeFile.id) { mutableStateOf(ImageAdjustmentParams()) }
    var editTab by remember(activeFile.id) { mutableStateOf(ImageEditTab.BASIC) }
    var showSaveEditChoiceDialog by remember { mutableStateOf(false) }
    var isSavingImageEdit by remember { mutableStateOf(false) }
    var imageReloadToken by remember(activeFile.id) { mutableIntStateOf(0) }

    val currentAlbumName = remember(allAlbums, activeFile.albumId) {
        allAlbums.firstOrNull { it.album.id == activeFile.albumId }?.album?.name
    }

    val canEditActiveImage = !isExternalReadOnly &&
        activeFile.category == MediaCategory.IMAGE &&
        onSaveEditedImage != null

    BackHandler(enabled = isEditingImage && !isSavingImageEdit) {
        isEditingImage = false
        adjustments = ImageAdjustmentParams()
    }

    val navigateToSlide: (SlideGestureOutcome) -> Unit = { outcome ->
        if (!isEditingImage && effectiveGallery.size > 1 && outcome.action != SlideNavigationAction.NONE) {
            val targetIdx = when (outcome.action) {
                SlideNavigationAction.NEXT -> GalleryNavigationHelper.nextIndex(currentIndex, effectiveGallery.size)
                SlideNavigationAction.PREVIOUS -> GalleryNavigationHelper.previousIndex(currentIndex, effectiveGallery.size)
                SlideNavigationAction.NONE -> currentIndex
            }
            lastSlideOutcome = outcome
            slideProgress = 0f
            activeFileId = effectiveGallery[targetIdx].id
        }
    }

    // Automatic slideshow advancement when playing
    LaunchedEffect(
        isSlideshowPlaying,
        isEditingImage,
        activeFile.id,
        effectiveGallery.size,
        showInfoDialog,
        showMoveDialog,
        showDeleteConfirm,
        showExportChoiceDialog,
        showCreateAlbumOnMoveDialog,
        showSaveEditChoiceDialog
    ) {
        val anyDialogOpen = showInfoDialog || showMoveDialog || showDeleteConfirm ||
            showExportChoiceDialog || showCreateAlbumOnMoveDialog || showSaveEditChoiceDialog
        if (!isSlideshowPlaying || isEditingImage || effectiveGallery.size <= 1 || anyDialogOpen) {
            slideProgress = 0f
            return@LaunchedEffect
        }
        if (activeFile.category == MediaCategory.VIDEO) {
            // Video advances automatically on completion
            slideProgress = 0f
            return@LaunchedEffect
        }
        val slideDurationMs = 3500L
        val tickMs = 50L
        var elapsed = 0L
        slideProgress = 0f
        while (elapsed < slideDurationMs && isSlideshowPlaying) {
            delay(tickMs)
            elapsed += tickMs
            slideProgress = (elapsed.toFloat() / slideDurationMs).coerceIn(0f, 1f)
        }
        if (isSlideshowPlaying && effectiveGallery.size > 1) {
            navigateToSlide(SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.HORIZONTAL))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        onClearExternalPickerFlag()
        if (uri != null) {
            onExportFile(activeFile, uri, removeAfterExport)
            if (removeAfterExport) {
                if (effectiveGallery.size <= 1) {
                    onBack()
                } else {
                    val nextIdx = GalleryNavigationHelper.nextIndex(currentIndex, effectiveGallery.size)
                    activeFileId = effectiveGallery[nextIdx].id
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color(0xFF07090D),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = activeFile.originalName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                if (effectiveGallery.size > 1) {
                                    append("${currentIndex + 1}/${effectiveGallery.size} • ")
                                }
                                append("${formatFileSize(activeFile.originalSizeBytes)} • ${activeFile.encryptionAlgorithm}")
                            },
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (canEditActiveImage) {
                        IconButton(
                            onClick = {
                                if (isEditingImage) {
                                    isEditingImage = false
                                    adjustments = ImageAdjustmentParams()
                                } else {
                                    isSlideshowPlaying = false
                                    isEditingImage = true
                                }
                            },
                            enabled = !isSavingImageEdit,
                            modifier = Modifier.testTag("viewer_adjust_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = "Sesuaikan Kecerahan, Kontras & Levels",
                                tint = if (isEditingImage) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                    if (!isExternalReadOnly && !isEditingImage) {
                        IconButton(onClick = { onToggleFavorite(activeFile) }) {
                            Icon(
                                imageVector = if (activeFile.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Favorit",
                                tint = if (activeFile.isFavorite) Color(0xFFF43F5E) else Color.White
                            )
                        }
                    }
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "Info Enkripsi",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            )
        },
        bottomBar = {
            if (!isEditingImage) {
                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = Color(0xFF10141B),
                    contentColor = Color.White
                ) {
                    if (isExternalReadOnly) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dibaca via dekripsi dalam aplikasi",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                            if (onRestoreExternalFile != null) {
                                Button(onClick = { onRestoreExternalFile(activeFile) }) {
                                    Text("Pulihkan ke Brankas")
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(
                                onClick = { showMoveDialog = true }
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pindah", color = Color.White)
                            }
                            TextButton(onClick = { showExportChoiceDialog = true }) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Keluarkan", color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hapus", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val density = LocalDensity.current
        val swipeThresholdPx = remember(density) { with(density) { 64.dp.toPx() } }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = activeFile,
                    contentKey = { it.id },
                    transitionSpec = {
                        val animDuration = 240
                        if (lastSlideOutcome.axis == SlideAxis.HORIZONTAL) {
                            if (lastSlideOutcome.action == SlideNavigationAction.NEXT) {
                                (slideInHorizontally(tween(animDuration)) { -it / 3 } + fadeIn(tween(animDuration))) togetherWith
                                    (slideOutHorizontally(tween(animDuration)) { it / 3 } + fadeOut(tween(animDuration)))
                            } else {
                                (slideInHorizontally(tween(animDuration)) { it / 3 } + fadeIn(tween(animDuration))) togetherWith
                                    (slideOutHorizontally(tween(animDuration)) { -it / 3 } + fadeOut(tween(animDuration)))
                            }
                        } else {
                            if (lastSlideOutcome.action == SlideNavigationAction.NEXT) {
                                (slideInVertically(tween(animDuration)) { it / 3 } + fadeIn(tween(animDuration))) togetherWith
                                    (slideOutVertically(tween(animDuration)) { -it / 3 } + fadeOut(tween(animDuration)))
                            } else {
                                (slideInVertically(tween(animDuration)) { -it / 3 } + fadeIn(tween(animDuration))) togetherWith
                                    (slideOutVertically(tween(animDuration)) { it / 3 } + fadeOut(tween(animDuration)))
                            }
                        }
                    },
                    label = "gallery_slide_transition",
                    modifier = Modifier.fillMaxSize()
                ) { currentSlideFile ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        currentSlideFile.category == MediaCategory.IMAGE -> {
                            val activeEdit = isEditingImage && currentSlideFile.id == activeFile.id
                            ZoomableEncryptedImageViewer(
                                file = currentSlideFile,
                                reloadToken = if (currentSlideFile.id == activeFile.id) imageReloadToken else 0,
                                adjustments = if (activeEdit) adjustments else ImageAdjustmentParams(),
                                isEditing = activeEdit,
                                decryptBytes = decryptBytes,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            )
                        }
                        currentSlideFile.category == MediaCategory.VIDEO -> {
                            FourWaySwipeContainer(
                                key = currentSlideFile.id,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            ) {
                                EncryptedVideoPlayer(
                                    file = currentSlideFile,
                                    openMediaDataSource = openMediaDataSource,
                                    onVideoCompleted = {
                                        if (isSlideshowPlaying && effectiveGallery.size > 1) {
                                            navigateToSlide(
                                                SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.HORIZONTAL)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        currentSlideFile.category == MediaCategory.AUDIO -> {
                            FourWaySwipeContainer(
                                key = currentSlideFile.id,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            ) {
                                EncryptedAudioPlayer(
                                    file = currentSlideFile,
                                    openMediaDataSource = openMediaDataSource
                                )
                            }
                        }
                        currentSlideFile.mimeType == "application/pdf" ||
                            currentSlideFile.originalName.endsWith(".pdf", ignoreCase = true) -> {
                            HorizontalSwipeContainer(
                                key = currentSlideFile.id,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            ) {
                                EncryptedPdfViewer(
                                    file = currentSlideFile,
                                    decryptToEphemeralFile = decryptToEphemeralFile,
                                    secureDeleteEphemeralFile = secureDeleteEphemeralFile
                                )
                            }
                        }
                        isTextReadable(currentSlideFile) -> {
                            HorizontalSwipeContainer(
                                key = currentSlideFile.id,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            ) {
                                EncryptedTextViewer(file = currentSlideFile, decryptBytes = decryptBytes)
                            }
                        }
                        else -> {
                            FourWaySwipeContainer(
                                key = currentSlideFile.id,
                                swipeThresholdPx = swipeThresholdPx,
                                onSwipeNavigate = navigateToSlide
                            ) {
                                EncryptedGenericFileCard(
                                    file = currentSlideFile,
                                    onExportClick = { showExportChoiceDialog = true }
                                )
                            }
                        }
                    }
                }
            }
            }

            AnimatedVisibility(visible = isEditingImage) {
                ImageAdjustmentDock(
                    params = adjustments,
                    activeTab = editTab,
                    isSaving = isSavingImageEdit,
                    onTabChange = { editTab = it },
                    onParamsChange = { adjustments = it },
                    onApplyClarityPreset = { adjustments = ImageAdjustmentParams.CLARITY_PRESET },
                    onReset = { adjustments = ImageAdjustmentParams() },
                    onCancel = {
                        isEditingImage = false
                        adjustments = ImageAdjustmentParams()
                    },
                    onSaveClick = { showSaveEditChoiceDialog = true }
                )
            }

            if (!isEditingImage) {
                // Slideshow & Previous/Next Control Dock
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GallerySlideshowControlsBar(
                        currentIndex = currentIndex,
                        totalCount = effectiveGallery.size,
                        isSlideshowPlaying = isSlideshowPlaying,
                        slideProgress = slideProgress,
                        onPreviousClick = {
                            navigateToSlide(SlideGestureOutcome(SlideNavigationAction.PREVIOUS, SlideAxis.HORIZONTAL))
                        },
                        onToggleSlideshow = {
                            if (effectiveGallery.size > 1) {
                                isSlideshowPlaying = !isSlideshowPlaying
                            }
                        },
                        onNextClick = {
                            navigateToSlide(SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.HORIZONTAL))
                        }
                    )
                }
            }
        }
    }

    if (showSaveEditChoiceDialog && onSaveEditedImage != null) {
        AlertDialog(
            onDismissRequest = { if (!isSavingImageEdit) showSaveEditChoiceDialog = false },
            title = { Text("Simpan Perubahan Gambar?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pilih cara menyimpan hasil edit kecerahan, kontras, dan levels ke brankas terenkripsi:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            isSavingImageEdit = true
                            showSaveEditChoiceDialog = false
                            onSaveEditedImage(activeFile, adjustments, false) { saved ->
                                isSavingImageEdit = false
                                if (saved != null) {
                                    isEditingImage = false
                                    adjustments = ImageAdjustmentParams()
                                    activeFileId = saved.id
                                }
                            }
                        },
                        enabled = !isSavingImageEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_image_as_copy_button")
                    ) {
                        Text("Simpan sebagai Salinan Baru")
                    }
                    OutlinedButton(
                        onClick = {
                            isSavingImageEdit = true
                            showSaveEditChoiceDialog = false
                            onSaveEditedImage(activeFile, adjustments, true) { saved ->
                                isSavingImageEdit = false
                                if (saved != null) {
                                    isEditingImage = false
                                    adjustments = ImageAdjustmentParams()
                                    imageReloadToken++
                                }
                            }
                        },
                        enabled = !isSavingImageEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_image_overwrite_button")
                    ) {
                        Text("Timpa Gambar Asli")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showSaveEditChoiceDialog = false },
                    enabled = !isSavingImageEdit
                ) {
                    Text("Batal")
                }
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Detail Keamanan File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AesBadgePill(text = "AES-256-GCM • AUTHENTICATED")
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoLine("Nama Asli", activeFile.originalName)
                    if (currentAlbumName != null) {
                        InfoLine("Album Saat Ini", currentAlbumName)
                    }
                    InfoLine("Tipe MIME", activeFile.mimeType)
                    InfoLine("Ukuran Asli", formatFileSize(activeFile.originalSizeBytes))
                    InfoLine("Terkunci Pada", formatTimestamp(activeFile.createdAt))
                    InfoLine("ID Blob Terenkripsi", activeFile.encryptedFileName)
                    InfoLine("Lokasi Terisolasi", "$vaultInternalPath/blobs/")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Tutup") }
            }
        )
    }

    if (showExportChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showExportChoiceDialog = false },
            title = { Text("Dekripsi & Simpan File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Pilih lokasi di perangkat untuk menyimpan hasil dekripsi \"${activeFile.originalName}\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hapus dari Brankas setelah diekspor",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = removeAfterExport,
                            onCheckedChange = { removeAfterExport = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportChoiceDialog = false
                    onMarkLaunchingExternalPicker()
                    exportLauncher.launch(activeFile.originalName)
                }) {
                    Text("Pilih Lokasi Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportChoiceDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showMoveDialog) {
        AlbumPickerDialog(
            title = "Pindahkan ke Album:",
            albums = allAlbums,
            excludedAlbumId = activeFile.albumId,
            onDismiss = { showMoveDialog = false },
            onAlbumSelected = { target ->
                onMoveFile(activeFile.id, target.album.id)
                showMoveDialog = false
            },
            onCreateNewAlbum = if (onCreateAlbumAndMoveFile != null) {
                {
                    showMoveDialog = false
                    showCreateAlbumOnMoveDialog = true
                }
            } else null
        )
    }

    if (showCreateAlbumOnMoveDialog && onCreateAlbumAndMoveFile != null) {
        AlbumEditorDialog(
            onDismiss = { showCreateAlbumOnMoveDialog = false },
            onConfirm = { name, iconKey, colorHex, isPinned ->
                onCreateAlbumAndMoveFile(activeFile.id, name, iconKey, colorHex, isPinned)
                showCreateAlbumOnMoveDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus Permanen?") },
            text = { Text("\"${activeFile.originalName}\" akan dihapus permanen dari penyimpanan terenkripsi.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        val deletedId = activeFile.id
                        val remaining = effectiveGallery.filterNot { it.id == deletedId }
                        onDeleteFile(deletedId)
                        if (remaining.isEmpty()) {
                            onBack()
                        } else {
                            val nextIdx = currentIndex.coerceAtMost(remaining.lastIndex)
                            activeFileId = remaining[nextIdx].id
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun GallerySlideshowControlsBar(
    currentIndex: Int,
    totalCount: Int,
    isSlideshowPlaying: Boolean,
    slideProgress: Float,
    onPreviousClick: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canCycle = totalCount > 1
    Surface(
        modifier = modifier.clip(RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF0E141E).copy(alpha = 0.88f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSlideshowPlaying) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                Color.White.copy(alpha = 0.16f)
            }
        ),
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.width(280.dp)) {
            if (isSlideshowPlaying) {
                LinearProgressIndicator(
                    progress = { slideProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onPreviousClick,
                    enabled = canCycle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("viewer_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Sebelumnya",
                        tint = if (canCycle) Color.White else Color.White.copy(alpha = 0.35f)
                    )
                }

                FilledIconButton(
                    onClick = onToggleSlideshow,
                    enabled = canCycle,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isSlideshowPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                        },
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = Color.White.copy(alpha = 0.14f),
                        disabledContentColor = Color.White.copy(alpha = 0.38f)
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("viewer_slideshow_play_button")
                ) {
                    Icon(
                        imageVector = if (isSlideshowPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isSlideshowPlaying) "Jeda Slideshow" else "Putar Slideshow"
                    )
                }

                IconButton(
                    onClick = onNextClick,
                    enabled = canCycle,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("viewer_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Berikutnya",
                        tint = if (canCycle) Color.White else Color.White.copy(alpha = 0.35f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${currentIndex + 1}/$totalCount",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isSlideshowPlaying) "Slideshow" else "Geser 4 arah",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSlideshowPlaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White.copy(alpha = 0.65f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FourWaySwipeContainer(
    key: String,
    swipeThresholdPx: Float,
    onSwipeNavigate: (SlideGestureOutcome) -> Unit,
    content: @Composable () -> Unit
) {
    var totalDrag by remember(key) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(key) {
                detectDragGestures(
                    onDragStart = { totalDrag = Offset.Zero },
                    onDragCancel = { totalDrag = Offset.Zero },
                    onDragEnd = {
                        val outcome = GalleryNavigationHelper.resolveSwipeAction(
                            dragX = totalDrag.x,
                            dragY = totalDrag.y,
                            thresholdPx = swipeThresholdPx
                        )
                        totalDrag = Offset.Zero
                        if (outcome.action != SlideNavigationAction.NONE) {
                            onSwipeNavigate(outcome)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun HorizontalSwipeContainer(
    key: String,
    swipeThresholdPx: Float,
    onSwipeNavigate: (SlideGestureOutcome) -> Unit,
    content: @Composable () -> Unit
) {
    var totalDragX by remember(key) { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDragCancel = { totalDragX = 0f },
                    onDragEnd = {
                        val outcome = GalleryNavigationHelper.resolveSwipeAction(
                            dragX = totalDragX,
                            dragY = 0f,
                            thresholdPx = swipeThresholdPx
                        )
                        totalDragX = 0f
                        if (outcome.action != SlideNavigationAction.NONE) {
                            onSwipeNavigate(outcome)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ZoomableEncryptedImageViewer(
    file: VaultFileEntity,
    reloadToken: Int = 0,
    adjustments: ImageAdjustmentParams = ImageAdjustmentParams(),
    isEditing: Boolean = false,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray,
    swipeThresholdPx: Float,
    onSwipeNavigate: (SlideGestureOutcome) -> Unit
) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    var previewBaseBitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    var adjustedPreviewBitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }
    var scale by remember(file.id) { mutableFloatStateOf(1f) }
    var offset by remember(file.id) { mutableStateOf(Offset.Zero) }
    var targetDragOffset by remember(file.id) { mutableStateOf(Offset.Zero) }
    val animatedDragOffset by animateOffsetAsState(
        targetValue = targetDragOffset,
        animationSpec = spring(),
        label = "image_drag_feedback"
    )

    LaunchedEffect(file.id, reloadToken) {
        errorMsg = null
        bitmap = null
        previewBaseBitmap = null
        adjustedPreviewBitmap = null
        try {
            val bytes = decryptBytes(file)
            val decoded = try {
                withContext(Dispatchers.Default) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 2048 || bounds.outHeight / sampleSize > 2048) {
                        sampleSize *= 2
                    }
                    val decoded = try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    } catch (_: OutOfMemoryError) {
                        val fallbackOpts = BitmapFactory.Options().apply {
                            inSampleSize = (sampleSize * 2).coerceAtLeast(2)
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOpts)
                    }
                    decoded?.let { ImageAdjustmentProcessor.applyExifOrientation(it, bytes) }
                }
            } finally {
                bytes.fill(0)
            }
            if (decoded != null) {
                bitmap = decoded
                previewBaseBitmap = withContext(Dispatchers.Default) {
                    ImageAdjustmentProcessor.createPreviewBitmap(decoded, maxDimension = 960)
                }
            } else {
                errorMsg = "Format gambar tidak didukung"
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            errorMsg = t.localizedMessage ?: "Gagal mendekripsi gambar"
        }
    }

    LaunchedEffect(previewBaseBitmap, isEditing, adjustments) {
        val base = previewBaseBitmap
        if (!isEditing || base == null || adjustments.isDefault()) {
            adjustedPreviewBitmap = null
            return@LaunchedEffect
        }
        adjustedPreviewBitmap = withContext(Dispatchers.Default) {
            ImageAdjustmentProcessor.applyAdjustmentsToBitmap(base, adjustments)
        }
    }

    when {
        errorMsg != null -> Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        bitmap == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        else -> {
            val loadedBitmap = bitmap!!
            val displayBitmap = if (isEditing && adjustedPreviewBitmap != null) {
                adjustedPreviewBitmap!!
            } else {
                loadedBitmap
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(file.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(file.id, isEditing) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var totalDrag = Offset.Zero
                            var wasMultiTouch = false
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    wasMultiTouch = true
                                    targetDragOffset = Offset.Zero
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                    scale = newScale
                                    offset = if (newScale > 1.02f) {
                                        Offset(offset.x + panChange.x, offset.y + panChange.y)
                                    } else {
                                        Offset.Zero
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pressedCount == 1 && !wasMultiTouch) {
                                    val pan = event.calculatePan()
                                    if (scale > 1.02f) {
                                        offset = Offset(offset.x + pan.x, offset.y + pan.y)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (!isEditing) {
                                        totalDrag += pan
                                        targetDragOffset = Offset(totalDrag.x * 0.42f, totalDrag.y * 0.42f)
                                        if (totalDrag.getDistance() > 8f) {
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (!isEditing && !wasMultiTouch && scale <= 1.02f) {
                                val outcome = GalleryNavigationHelper.resolveSwipeAction(
                                    dragX = totalDrag.x,
                                    dragY = totalDrag.y,
                                    thresholdPx = swipeThresholdPx
                                )
                                if (outcome.action != SlideNavigationAction.NONE) {
                                    onSwipeNavigate(outcome)
                                }
                            }
                            targetDragOffset = Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val fittedSize = GalleryNavigationHelper.calculateFittedPreviewSize(
                    containerWidth = constraints.maxWidth.toFloat(),
                    containerHeight = constraints.maxHeight.toFloat(),
                    mediaWidth = loadedBitmap.width,
                    mediaHeight = loadedBitmap.height,
                    allowUpscale = false
                )
                val fittedWidthDp = with(density) { fittedSize.width.toDp() }
                val fittedHeightDp = with(density) { fittedSize.height.toDp() }

                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = file.originalName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(fittedWidthDp)
                        .height(fittedHeightDp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = if (scale > 1.02f) offset.x else animatedDragOffset.x,
                            translationY = if (scale > 1.02f) offset.y else animatedDragOffset.y
                        )
                )
            }
        }
    }
}

private enum class ImageEditTab(val label: String) {
    BASIC("Dasar"),
    LEVELS("Levels")
}

@Composable
private fun ImageAdjustmentDock(
    params: ImageAdjustmentParams,
    activeTab: ImageEditTab,
    isSaving: Boolean,
    onTabChange: (ImageEditTab) -> Unit,
    onParamsChange: (ImageAdjustmentParams) -> Unit,
    onApplyClarityPreset: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSaveClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color(0xFF0E141E).copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = activeTab == ImageEditTab.BASIC,
                        onClick = { onTabChange(ImageEditTab.BASIC) },
                        label = { Text(ImageEditTab.BASIC.label) },
                        modifier = Modifier.testTag("image_edit_tab_basic")
                    )
                    FilterChip(
                        selected = activeTab == ImageEditTab.LEVELS,
                        onClick = { onTabChange(ImageEditTab.LEVELS) },
                        label = { Text(ImageEditTab.LEVELS.label) },
                        modifier = Modifier.testTag("image_edit_tab_levels")
                    )
                    OutlinedButton(
                        onClick = onApplyClarityPreset,
                        enabled = !isSaving,
                        modifier = Modifier.testTag("image_edit_clarity_preset")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Perjelas")
                    }
                }
                IconButton(
                    onClick = onReset,
                    enabled = !isSaving && !params.isDefault(),
                    modifier = Modifier.testTag("image_edit_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset Pengaturan",
                        tint = if (params.isDefault()) Color.White.copy(alpha = 0.35f) else Color.White
                    )
                }
            }

            if (activeTab == ImageEditTab.BASIC) {
                AdjustmentSliderRow(
                    label = "Kecerahan",
                    valueText = "${if (params.brightness >= 0f) "+" else ""}${(params.brightness * 100f).roundToInt()}%",
                    value = params.brightness,
                    valueRange = -0.6f..0.6f,
                    onValueChange = { onParamsChange(params.copy(brightness = it)) },
                    testTag = "slider_brightness"
                )
                AdjustmentSliderRow(
                    label = "Kontras",
                    valueText = "%.2fx".format(params.contrast),
                    value = params.contrast,
                    valueRange = 0.5f..2.2f,
                    onValueChange = { onParamsChange(params.copy(contrast = it)) },
                    testTag = "slider_contrast"
                )
            } else {
                AdjustmentSliderRow(
                    label = "Bayangan (Black)",
                    valueText = "${(params.blackLevel * 100f).roundToInt()}%",
                    value = params.blackLevel,
                    valueRange = 0f..0.40f,
                    onValueChange = { nextBlack ->
                        val safeBlack = nextBlack.coerceAtMost(params.whiteLevel - 0.1f)
                        onParamsChange(params.copy(blackLevel = safeBlack))
                    },
                    testTag = "slider_black_level"
                )
                AdjustmentSliderRow(
                    label = "Midtone (Gamma)",
                    valueText = "%.2f".format(params.midtoneGamma),
                    value = params.midtoneGamma,
                    valueRange = 0.45f..2.20f,
                    onValueChange = { onParamsChange(params.copy(midtoneGamma = it)) },
                    testTag = "slider_midtone_gamma"
                )
                AdjustmentSliderRow(
                    label = "Sorotan (White)",
                    valueText = "${(params.whiteLevel * 100f).roundToInt()}%",
                    value = params.whiteLevel,
                    valueRange = 0.60f..1.0f,
                    onValueChange = { nextWhite ->
                        val safeWhite = nextWhite.coerceAtLeast(params.blackLevel + 0.1f)
                        onParamsChange(params.copy(whiteLevel = safeWhite))
                    },
                    testTag = "slider_white_level"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier.testTag("image_edit_cancel_button")
                ) {
                    Text("Batal", color = Color.White.copy(alpha = 0.8f))
                }
                Button(
                    onClick = onSaveClick,
                    enabled = !isSaving && !params.isDefault(),
                    modifier = Modifier.testTag("image_edit_save_button")
                ) {
                    Text(if (isSaving) "Menyimpan..." else "Simpan")
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Text(
                text = valueText,
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun EncryptedVideoPlayer(
    file: VaultFileEntity,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource,
    onVideoCompleted: () -> Unit = {}
) {
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var videoWidth by remember(file.id) { mutableIntStateOf(0) }
    var videoHeight by remember(file.id) { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var dataSource by remember { mutableStateOf<MediaDataSource?>(null) }
    var surfaceHolder by remember(file.id) { mutableStateOf<SurfaceHolder?>(null) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }

    DisposableEffect(file.id) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            runCatching { dataSource?.close() }
            mediaPlayer = null
            dataSource = null
            surfaceHolder = null
        }
    }

    LaunchedEffect(file.id, surfaceHolder) {
        val holder = surfaceHolder ?: return@LaunchedEffect
        errorMsg = null
        var openedDs: MediaDataSource? = null
        var player: MediaPlayer? = null
        try {
            openedDs = withContext(Dispatchers.IO) { openMediaDataSource(file) }
            dataSource = openedDs
            player = MediaPlayer().apply {
                setDisplay(holder)
                setDataSource(openedDs)
                setOnVideoSizeChangedListener { _, width, height ->
                    if (width > 0 && height > 0) {
                        videoWidth = width
                        videoHeight = height
                    }
                }
                setOnPreparedListener { mp ->
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                        videoWidth = mp.videoWidth
                        videoHeight = mp.videoHeight
                    }
                    runCatching { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                    durationMs = mp.duration.coerceAtLeast(1)
                    mp.start()
                    isPlaying = true
                }
                setOnCompletionListener {
                    isPlaying = false
                    onVideoCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    errorMsg = "Gagal memutar video ($what/$extra)"
                    isPlaying = false
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (ce: kotlinx.coroutines.CancellationException) {
            runCatching { player?.release() }
            runCatching { openedDs?.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { player?.release() }
            runCatching { openedDs?.close() }
            errorMsg = t.localizedMessage ?: "Gagal memutar video terenkripsi"
        }
    }

    LaunchedEffect(mediaPlayer, isPlaying) {
        while (mediaPlayer != null && isPlaying) {
            runCatching {
                positionMs = mediaPlayer?.currentPosition ?: 0
            }
            delay(300L)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (errorMsg != null) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
            } else {
                val density = LocalDensity.current
                val hasDimensions = videoWidth > 0 && videoHeight > 0
                val fittedSize = GalleryNavigationHelper.calculateFittedPreviewSize(
                    containerWidth = constraints.maxWidth.toFloat(),
                    containerHeight = constraints.maxHeight.toFloat(),
                    mediaWidth = videoWidth,
                    mediaHeight = videoHeight,
                    allowUpscale = true
                )
                val surfaceModifier = if (hasDimensions) {
                    Modifier
                        .width(with(density) { fittedSize.width.toDp() })
                        .height(with(density) { fittedSize.height.toDp() })
                } else {
                    Modifier.size(2.dp)
                }

                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    surfaceHolder = holder
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    surfaceHolder = null
                                    runCatching { mediaPlayer?.release() }
                                    runCatching { dataSource?.close() }
                                    mediaPlayer = null
                                    dataSource = null
                                    isPlaying = false
                                }
                            })
                        }
                    },
                    modifier = surfaceModifier
                )

                if (!hasDimensions) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Surface(color = Color.Black.copy(alpha = 0.75f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val mp = mediaPlayer ?: return@IconButton
                    runCatching {
                        if (mp.isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            mp.start()
                            isPlaying = true
                        }
                    }
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Jeda" else "Putar",
                        tint = Color.White
                    )
                }
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { frac ->
                        val target = (frac * durationMs).toInt()
                        positionMs = target
                        runCatching { mediaPlayer?.seekTo(target) }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun EncryptedAudioPlayer(
    file: VaultFileEntity,
    openMediaDataSource: (VaultFileEntity) -> MediaDataSource
) {
    var isPlaying by remember(file.id) { mutableStateOf(false) }
    var durationMs by remember(file.id) { mutableIntStateOf(0) }
    var positionMs by remember(file.id) { mutableIntStateOf(0) }
    var mediaPlayer by remember(file.id) { mutableStateOf<MediaPlayer?>(null) }
    var dataSource by remember(file.id) { mutableStateOf<MediaDataSource?>(null) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }

    DisposableEffect(file.id) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            runCatching { dataSource?.close() }
            mediaPlayer = null
            dataSource = null
        }
    }

    LaunchedEffect(file.id) {
        errorMsg = null
        var openedDs: MediaDataSource? = null
        var player: MediaPlayer? = null
        try {
            openedDs = withContext(Dispatchers.IO) { openMediaDataSource(file) }
            dataSource = openedDs
            player = MediaPlayer().apply {
                setDataSource(openedDs)
                setOnPreparedListener { mp ->
                    durationMs = mp.duration.coerceAtLeast(1)
                }
                setOnCompletionListener { isPlaying = false }
                setOnErrorListener { _, what, extra ->
                    errorMsg = "Gagal memutar audio ($what/$extra)"
                    isPlaying = false
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (ce: kotlinx.coroutines.CancellationException) {
            runCatching { player?.release() }
            runCatching { openedDs?.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { player?.release() }
            runCatching { openedDs?.close() }
            errorMsg = t.localizedMessage ?: "Gagal membuka audio terenkripsi"
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            runCatching { positionMs = mediaPlayer?.currentPosition ?: 0 }
            delay(300L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp)
            )
        }
        Text(file.originalName, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { frac ->
                val target = (frac * durationMs).toInt()
                positionMs = target
                runCatching { mediaPlayer?.seekTo(target) }
            }
        )
        Text(
            text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
        Button(
            onClick = {
                val mp = mediaPlayer ?: return@Button
                runCatching {
                    if (mp.isPlaying) {
                        mp.pause()
                        isPlaying = false
                    } else {
                        mp.start()
                        isPlaying = true
                    }
                }
            }
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPlaying) "Jeda" else "Putar Audio")
        }
    }
}

@Composable
private fun EncryptedPdfViewer(
    file: VaultFileEntity,
    decryptToEphemeralFile: suspend (VaultFileEntity) -> File,
    secureDeleteEphemeralFile: (File) -> Unit
) {
    var pages by remember(file.id) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var errorMsg by remember(file.id) { mutableStateOf<String?>(null) }
    var tempFile by remember(file.id) { mutableStateOf<File?>(null) }

    DisposableEffect(file.id) {
        onDispose {
            tempFile?.let { secureDeleteEphemeralFile(it) }
            tempFile = null
            pages = emptyList()
        }
    }

    LaunchedEffect(file.id) {
        errorMsg = null
        pages = emptyList()
        try {
            val decrypted = decryptToEphemeralFile(file)
            tempFile = decrypted
            val renderedPages = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(decrypted, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val rendered = mutableListOf<Bitmap>()
                        val maxPages = minOf(renderer.pageCount, 30)
                        for (i in 0 until maxPages) {
                            renderer.openPage(i).use { page ->
                                val bmp = Bitmap.createBitmap(
                                    (page.width * 2).coerceAtMost(1400),
                                    (page.height * 2).coerceAtMost(2000),
                                    Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                rendered.add(bmp)
                            }
                        }
                        rendered
                    }
                }
            }
            pages = renderedPages
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            errorMsg = "Gagal merender PDF: ${t.localizedMessage}"
        }
    }

    when {
        errorMsg != null -> Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        pages.isEmpty() -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pages.size) { index ->
                    Image(
                        bitmap = pages[index].asImageBitmap(),
                        contentDescription = "Halaman ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EncryptedTextViewer(
    file: VaultFileEntity,
    decryptBytes: suspend (VaultFileEntity) -> ByteArray
) {
    var content by remember(file.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.id) {
        runCatching {
            val bytes = decryptBytes(file)
            val text = bytes.decodeToString()
            bytes.fill(0)
            text
        }.onSuccess { content = it }
    }

    val text = content
    if (text == null) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    } else {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF11161F)
        ) {
            Text(
                text = text,
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE2E8F0),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun EncryptedGenericFileCard(
    file: VaultFileEntity,
    onExportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = file.originalName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "File terenkripsi AES-256-GCM (${formatFileSize(file.originalSizeBytes)}). Dekripsi & simpan untuk membukanya dengan aplikasi eksternal.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f)
        )
        Button(onClick = onExportClick) {
            Icon(Icons.Rounded.LockOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Dekripsi & Simpan")
        }
    }
}

private fun isTextReadable(file: VaultFileEntity): Boolean {
    val ext = file.originalName.substringAfterLast('.', "").lowercase()
    return file.mimeType.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "xml", "log")
}

private fun formatMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
