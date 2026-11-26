package com.example.data

import kotlinx.serialization.Serializable

@Serializable
data class VaultBackupAlbum(
    val id: String,
    val name: String,
    val iconKey: String,
    val colorHex: Long,
    val isPinned: Boolean = false,
    val createdAt: Long = 0L,
    val sortOrder: Int = 0
)

@Serializable
data class VaultBackupFile(
    val id: String,
    val albumId: String,
    val originalName: String,
    val mimeType: String,
    val categoryName: String,
    val originalSizeBytes: Long,
    val hasThumbnail: Boolean = false,
    val createdAt: Long = 0L,
    val isFavorite: Boolean = false
)

@Serializable
data class VaultBackupManifest(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val albums: List<VaultBackupAlbum> = emptyList(),
    val files: List<VaultBackupFile> = emptyList()
)
