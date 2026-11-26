package com.example

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.biometric.findFragmentActivity
import com.example.crypto.MasterKeyProvider
import com.example.crypto.VaultCryptoEngine
import com.example.crypto.VaultKeyManager
import com.example.data.AlbumEntity
import com.example.data.GallerySortOrder
import com.example.data.MediaCategory
import com.example.data.MediaUriResolver
import com.example.data.VaultBackupAlbum
import com.example.data.VaultBackupFile
import com.example.data.VaultBackupManifest
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import com.example.data.VaultFileFilter
import com.example.ui.components.GalleryNavigationHelper
import com.example.ui.components.ImageAdjustmentParams
import com.example.ui.components.ImageAdjustmentProcessor
import com.example.ui.components.SlideAxis
import com.example.ui.components.SlideNavigationAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private class InMemoryMasterKeyProvider : MasterKeyProvider {
        private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply {
            init(256)
        }.generateKey()

        override fun getOrCreateMasterKey(): SecretKey = masterKey
        override fun isHardwareBacked(): Boolean = false
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Locker", appName)
    }

    @Test
    fun `portable vault master key survives reinstall simulation via recovery pin`() {
        val device1Keystore = InMemoryMasterKeyProvider()
        val keyManager1 = VaultKeyManager(device1Keystore)
        val portableKey = keyManager1.generatePortableMasterKey()
        val recoveryBundle = keyManager1.wrapWithPin(portableKey, "135790")

        val engine1 = VaultCryptoEngine(device1Keystore, portableMasterKeyProvider = { portableKey })
        val plainBytes = Random(77).nextBytes(120_000)
        val encryptedFile = File.createTempFile("portable_v2_", ".vault")
        try {
            engine1.encryptToFile(ByteArrayInputStream(plainBytes), encryptedFile)

            // Simulate fresh install with a brand new Android Keystore key
            val device2Keystore = InMemoryMasterKeyProvider()
            val keyManager2 = VaultKeyManager(device2Keystore)
            val unwrappedKey = keyManager2.unwrapWithPin(recoveryBundle, "135790")
            val engine2 = VaultCryptoEngine(device2Keystore, portableMasterKeyProvider = { unwrappedKey })

            val decrypted = engine2.decryptBytes(encryptedFile)
            assertArrayEquals(plainBytes, decrypted)
        } finally {
            encryptedFile.delete()
        }
    }

    @Test(expected = AEADBadTagException::class)
    fun `wrong recovery pin rejects unwrapping portable vault key`() {
        val keyManager = VaultKeyManager(InMemoryMasterKeyProvider())
        val portableKey = keyManager.generatePortableMasterKey()
        val bundle = keyManager.wrapWithPin(portableKey, "246810")
        keyManager.unwrapWithPin(bundle, "000000")
    }

    @Test
    fun `encrypted manifest roundtrip preserves album and file metadata`() {
        val keystore = InMemoryMasterKeyProvider()
        val keyManager = VaultKeyManager(keystore)
        val portableKey = keyManager.generatePortableMasterKey()
        val engine = VaultCryptoEngine(keystore, portableMasterKeyProvider = { portableKey })

        val manifest = VaultBackupManifest(
            version = 1,
            updatedAt = 123456789L,
            albums = listOf(
                VaultBackupAlbum(
                    id = "alb-1",
                    name = "Foto Rahasia",
                    iconKey = "photo",
                    colorHex = 0xFF10B981L,
                    isPinned = true
                )
            ),
            files = listOf(
                VaultBackupFile(
                    id = "file-1",
                    albumId = "alb-1",
                    originalName = "liburan_pribadi.jpg",
                    mimeType = "image/jpeg",
                    categoryName = "IMAGE",
                    originalSizeBytes = 204800L,
                    hasThumbnail = true,
                    createdAt = 123456789L,
                    isFavorite = true
                )
            )
        )

        val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encryptedManifest = engine.encryptBytes(jsonCodec.encodeToString(manifest).toByteArray())
        val decryptedManifest = jsonCodec.decodeFromString<VaultBackupManifest>(
            engine.decryptBytes(encryptedManifest).toString(Charsets.UTF_8)
        )
        assertEquals("Foto Rahasia", decryptedManifest.albums.first().name)
        assertEquals("liburan_pribadi.jpg", decryptedManifest.files.first().originalName)
        assertTrue(decryptedManifest.files.first().isFavorite)
    }

    @Test
    fun `media uri resolver handles photo picker and media documents uris`() {
        val pickerUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000000456")
        assertTrue(MediaUriResolver.isPhotoPickerUri(pickerUri))
        val extractedId = MediaUriResolver.extractNumericPickerMediaId(pickerUri)
        assertEquals(1000000456L, extractedId)
        val resolvedImage = MediaUriResolver.buildMediaStoreUriForId(extractedId!!, "image/jpeg")
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() + "/1000000456", resolvedImage.toString())

        val mediaDocResolved = MediaUriResolver.parseMediaDocumentsId("video:9876")
        assertNotNull(mediaDocResolved)
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString() + "/9876", mediaDocResolved.toString())

        val normalMediaUri = Uri.parse("content://media/external/images/media/42")
        assertFalse(MediaUriResolver.isPhotoPickerUri(normalMediaUri))
    }

    @Test
    fun `vault file filter organizes photos videos documents audio and favorites`() {
        val photo = VaultFileEntity(
            id = "f-img",
            albumId = "a1",
            originalName = "pantai.jpg",
            mimeType = "image/jpeg",
            category = MediaCategory.IMAGE,
            originalSizeBytes = 100L,
            encryptedFileName = "f-img.vlt",
            encryptedThumbName = null,
            isFavorite = true
        )
        val video = VaultFileEntity(
            id = "f-vid",
            albumId = "a1",
            originalName = "klip.mp4",
            mimeType = "video/mp4",
            category = MediaCategory.VIDEO,
            originalSizeBytes = 200L,
            encryptedFileName = "f-vid.vlt",
            encryptedThumbName = null
        )
        val doc = VaultFileEntity(
            id = "f-doc",
            albumId = "a2",
            originalName = "kontrak.pdf",
            mimeType = "application/pdf",
            category = MediaCategory.DOCUMENT,
            originalSizeBytes = 300L,
            encryptedFileName = "f-doc.vlt",
            encryptedThumbName = null
        )
        val audio = VaultFileEntity(
            id = "f-aud",
            albumId = "a2",
            originalName = "rekaman.m4a",
            mimeType = "audio/mp4",
            category = MediaCategory.AUDIO,
            originalSizeBytes = 400L,
            encryptedFileName = "f-aud.vlt",
            encryptedThumbName = null
        )
        val all = listOf(photo, video, doc, audio)

        val counts = VaultFileFilter.computeCounts(all)
        assertEquals(4, counts[VaultFileFilter.ALL_FILES])
        assertEquals(1, counts[VaultFileFilter.IMAGE])
        assertEquals(1, counts[VaultFileFilter.VIDEO])
        assertEquals(1, counts[VaultFileFilter.DOCUMENT])
        assertEquals(1, counts[VaultFileFilter.AUDIO])
        assertEquals(1, counts[VaultFileFilter.FAVORITES])

        assertEquals(listOf("f-img"), all.filter { VaultFileFilter.IMAGE.matchesFile(it) }.map { it.id })
        assertEquals(listOf("f-vid"), all.filter { VaultFileFilter.VIDEO.matchesFile(it) }.map { it.id })
        assertEquals(listOf("f-doc"), all.filter { VaultFileFilter.DOCUMENT.matchesFile(it) }.map { it.id })
        assertEquals(listOf("f-img"), all.filter { VaultFileFilter.FAVORITES.matchesFile(it) }.map { it.id })
    }

    @Test
    fun `moving photo and video updates target album in database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.vaultDao()
            val albumSource = AlbumEntity(id = "alb-src", name = "Foto Pribadi", iconKey = "photo", colorHex = 0xFF10B981L)
            val albumDest = AlbumEntity(id = "alb-dst", name = "Arsip Rahasia", iconKey = "lock", colorHex = 0xFF06B6D4L)
            dao.insertAlbums(listOf(albumSource, albumDest))

            val photo = VaultFileEntity(
                id = "photo-1",
                albumId = "alb-src",
                originalName = "selfie.jpg",
                mimeType = "image/jpeg",
                category = MediaCategory.IMAGE,
                originalSizeBytes = 1024L,
                encryptedFileName = "photo-1.vlt",
                encryptedThumbName = "photo-1.thm"
            )
            val video = VaultFileEntity(
                id = "video-1",
                albumId = "alb-src",
                originalName = "liburan.mp4",
                mimeType = "video/mp4",
                category = MediaCategory.VIDEO,
                originalSizeBytes = 4096L,
                encryptedFileName = "video-1.vlt",
                encryptedThumbName = "video-1.thm"
            )
            dao.insertFile(photo)
            dao.insertFile(video)

            dao.moveFilesToAlbum(listOf("photo-1", "video-1"), "alb-dst")

            assertTrue(dao.getFilesInAlbumSync("alb-src").isEmpty())
            val moved = dao.getFilesInAlbumSync("alb-dst")
            assertEquals(2, moved.size)
            assertTrue(moved.all { it.albumId == "alb-dst" })
        } finally {
            db.close()
        }
    }

    @Test
    fun `gallery navigation resolves 4-way slide gestures and circular indices`() {
        val threshold = 64f
        // Slide right -> NEXT
        val right = GalleryNavigationHelper.resolveSwipeAction(dragX = 120f, dragY = 10f, thresholdPx = threshold)
        assertEquals(SlideNavigationAction.NEXT, right.action)
        assertEquals(SlideAxis.HORIZONTAL, right.axis)

        // Slide left -> PREVIOUS
        val left = GalleryNavigationHelper.resolveSwipeAction(dragX = -120f, dragY = -15f, thresholdPx = threshold)
        assertEquals(SlideNavigationAction.PREVIOUS, left.action)
        assertEquals(SlideAxis.HORIZONTAL, left.axis)

        // Slide up -> NEXT
        val up = GalleryNavigationHelper.resolveSwipeAction(dragX = 8f, dragY = -140f, thresholdPx = threshold)
        assertEquals(SlideNavigationAction.NEXT, up.action)
        assertEquals(SlideAxis.VERTICAL, up.axis)

        // Slide down -> PREVIOUS
        val down = GalleryNavigationHelper.resolveSwipeAction(dragX = -10f, dragY = 140f, thresholdPx = threshold)
        assertEquals(SlideNavigationAction.PREVIOUS, down.action)
        assertEquals(SlideAxis.VERTICAL, down.axis)

        // Small movement below threshold -> NONE
        val small = GalleryNavigationHelper.resolveSwipeAction(dragX = 20f, dragY = -30f, thresholdPx = threshold)
        assertEquals(SlideNavigationAction.NONE, small.action)

        // Circular index stepping
        assertEquals(1, GalleryNavigationHelper.nextIndex(0, 3))
        assertEquals(0, GalleryNavigationHelper.nextIndex(2, 3))
        assertEquals(2, GalleryNavigationHelper.previousIndex(0, 3))
        assertEquals(1, GalleryNavigationHelper.previousIndex(2, 3))
    }

    @Test
    fun `chunked 256 KiB encryption roundtrip and random access seek across chunk boundary succeed`() {
        val keystore = InMemoryMasterKeyProvider()
        val keyManager = VaultKeyManager(keystore)
        val portableKey = keyManager.generatePortableMasterKey()
        val engine = VaultCryptoEngine(keystore, portableMasterKeyProvider = { portableKey })

        // ~600 KiB spans 3 chunks at 256 KiB chunk size
        val plainBytes = Random(99).nextBytes(600 * 1024 + 123)
        val encryptedFile = File.createTempFile("chunk_256k_", ".vault")
        try {
            val written = engine.encryptToFile(ByteArrayInputStream(plainBytes), encryptedFile)
            assertEquals(plainBytes.size.toLong(), written)

            val header = engine.readHeader(encryptedFile)
            assertEquals(VaultCryptoEngine.DEFAULT_CHUNK_SIZE, header.chunkSize)
            assertEquals(plainBytes.size.toLong(), header.totalPlaintextSize)

            val decrypted = engine.decryptBytes(encryptedFile)
            assertArrayEquals(plainBytes, decrypted)

            // Random access across the 256 KiB chunk boundary (offset 256*1024 - 100, length 500)
            engine.openEncryptedMediaDataSource(encryptedFile).use { mds ->
                assertEquals(plainBytes.size.toLong(), mds.size)
                val seekOffset = VaultCryptoEngine.DEFAULT_CHUNK_SIZE - 100
                val readLen = 500
                val slice = ByteArray(readLen)
                val actualRead = mds.readAt(seekOffset.toLong(), slice, 0, readLen)
                assertEquals(readLen, actualRead)
                assertArrayEquals(
                    plainBytes.copyOfRange(seekOffset, seekOffset + readLen),
                    slice
                )
            }
        } finally {
            encryptedFile.delete()
        }
    }

    @Test
    fun `recovery key ring preserves multiple install keys and unwraps matching pins`() {
        val keystore = InMemoryMasterKeyProvider()
        val keyManager = VaultKeyManager(keystore)
        val preBrandingKey = keyManager.generatePortableMasterKey()
        val currentKey = keyManager.generatePortableMasterKey()

        val oldBundleJson = keyManager.wrapWithPin(preBrandingKey, "112233")
        val newBundleJson = keyManager.wrapWithPin(currentKey, "654321")

        val combinedBundles = keyManager.parseRecoveryBundles(oldBundleJson) +
            keyManager.parseRecoveryBundles(newBundleJson)
        val ringJson = keyManager.encodeRecoveryRing(combinedBundles)

        val parsedRing = keyManager.parseRecoveryBundles(ringJson)
        assertEquals(2, parsedRing.size)

        val unlockedOld = keyManager.tryUnwrapAllWithPin(parsedRing, "112233")
        assertEquals(1, unlockedOld.size)
        assertArrayEquals(preBrandingKey.encoded, unlockedOld.first().encoded)

        val unlockedNew = keyManager.tryUnwrapAllWithPin(parsedRing, "654321")
        assertEquals(1, unlockedNew.size)
        assertArrayEquals(currentKey.encoded, unlockedNew.first().encoded)

        val wrongPin = keyManager.tryUnwrapAllWithPin(parsedRing, "000000")
        assertTrue(wrongPin.isEmpty())
    }

    @Test
    fun `crypto engine decrypts mixed pre-branding and current key files via key ring`() {
        val oldKeystore = InMemoryMasterKeyProvider()
        val newKeystore = InMemoryMasterKeyProvider()

        val oldKeyManager = VaultKeyManager(oldKeystore)
        val preBrandingKey = oldKeyManager.generatePortableMasterKey()
        val oldEngine = VaultCryptoEngine(oldKeystore, portableMasterKeyProvider = { preBrandingKey })

        val newKeyManager = VaultKeyManager(newKeystore)
        val currentKey = newKeyManager.generatePortableMasterKey()
        newKeyManager.setActiveVaultKey(currentKey)
        val newEngine = VaultCryptoEngine(
            masterKeyProvider = newKeystore,
            portableMasterKeyProvider = { newKeyManager.getActiveVaultKey() },
            fallbackPortableKeysProvider = { newKeyManager.getLegacyVaultKeys() }
        )

        val oldPlain = "pre-branding secret payload".toByteArray()
        val newPlain = "locker secret payload".toByteArray()

        val oldCipher = File.createTempFile("old_key_", ".vault")
        val newCipher = File.createTempFile("new_key_", ".vault")
        try {
            oldEngine.encryptToFile(ByteArrayInputStream(oldPlain), oldCipher)
            newEngine.encryptToFile(ByteArrayInputStream(newPlain), newCipher)

            // Before unlocking old key, newEngine cannot read oldCipher
            assertFalse(newEngine.canDecryptPrefix(oldCipher.readBytes().copyOf(VaultCryptoEngine.FIXED_HEADER_SIZE)))
            assertTrue(newEngine.canDecryptPrefix(newCipher.readBytes().copyOf(VaultCryptoEngine.FIXED_HEADER_SIZE)))

            // Add pre-branding key into key ring
            newKeyManager.addLegacyVaultKey(preBrandingKey)

            assertTrue(newEngine.canDecryptPrefix(oldCipher.readBytes().copyOf(VaultCryptoEngine.FIXED_HEADER_SIZE)))
            assertArrayEquals(oldPlain, newEngine.decryptBytes(oldCipher))
            assertArrayEquals(newPlain, newEngine.decryptBytes(newCipher))

            // Verify active-key flag distinguishes files needing re-encryption
            assertFalse(newEngine.readHeader(oldCipher).unwrappedWithActivePortableKey)
            assertTrue(newEngine.readHeader(newCipher).unwrappedWithActivePortableKey)
        } finally {
            oldCipher.delete()
            newCipher.delete()
        }
    }

    @Test
    fun `preview sizing preserves photo and video aspect ratios without stretching to window`() {
        // 16:9 landscape video inside 1080x2000 portrait viewport -> fits width (1080x607.5)
        val landscapeVideo = GalleryNavigationHelper.calculateFittedPreviewSize(
            containerWidth = 1080f,
            containerHeight = 2000f,
            mediaWidth = 1920,
            mediaHeight = 1080,
            allowUpscale = true
        )
        assertEquals(1080f, landscapeVideo.width, 0.5f)
        assertEquals(607.5f, landscapeVideo.height, 0.5f)

        // 9:16 portrait video inside 1920x1000 landscape viewport -> fits height (562.5x1000)
        val portraitVideo = GalleryNavigationHelper.calculateFittedPreviewSize(
            containerWidth = 1920f,
            containerHeight = 1000f,
            mediaWidth = 1080,
            mediaHeight = 1920,
            allowUpscale = true
        )
        assertEquals(562.5f, portraitVideo.width, 0.5f)
        assertEquals(1000f, portraitVideo.height, 0.5f)

        // Small 400x300 photo inside 1080x2000 viewport with allowUpscale=false -> stays at native 400x300
        val smallPhoto = GalleryNavigationHelper.calculateFittedPreviewSize(
            containerWidth = 1080f,
            containerHeight = 2000f,
            mediaWidth = 400,
            mediaHeight = 300,
            allowUpscale = false
        )
        assertEquals(400f, smallPhoto.width, 0.1f)
        assertEquals(300f, smallPhoto.height, 0.1f)
    }

    @Test
    fun `batch export decrypts multiple selected files and removes them from vault`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val exportDir = File(context.cacheDir, "test_batch_export_${System.nanoTime()}").apply { mkdirs() }
        try {
            val dao = db.vaultDao()
            val album = AlbumEntity(id = "alb-batch", name = "Utama", iconKey = "photo", colorHex = 0xFF10B981L)
            dao.insertAlbum(album)

            val keystore = InMemoryMasterKeyProvider()
            val keyManager = VaultKeyManager(keystore)
            val portableKey = keyManager.generatePortableMasterKey()
            keyManager.setActiveVaultKey(portableKey)
            val engine = VaultCryptoEngine(keystore, portableMasterKeyProvider = { keyManager.getActiveVaultKey() })
            val storage = com.example.data.VaultStorageManager(context, engine, keyManager)

            val blobsDir = File(context.filesDir, "secure_vault/blobs").apply { mkdirs() }
            val entities = (1..2).map { idx ->
                val id = "batch-file-$idx-${System.nanoTime()}"
                val payload = "payload-data-$idx".toByteArray()
                val blob = File(blobsDir, "$id.vlt")
                engine.encryptToFile(ByteArrayInputStream(payload), blob)
                VaultFileEntity(
                    id = id,
                    albumId = album.id,
                    originalName = "doc_$idx.txt",
                    mimeType = "text/plain",
                    category = MediaCategory.DOCUMENT,
                    originalSizeBytes = payload.size.toLong(),
                    encryptedFileName = blob.name,
                    encryptedThumbName = null
                )
            }
            entities.forEach { dao.insertFile(it) }

            entities.forEachIndexed { idx, entity ->
                val outFile = File(exportDir, entity.originalName)
                storage.decryptToUri(entity, Uri.fromFile(outFile))
                assertEquals("payload-data-${idx + 1}", outFile.readText())
                storage.deleteEncryptedAssets(entity)
            }
            dao.deleteFilesByIds(entities.map { it.id })

            assertTrue(dao.getFilesInAlbumSync(album.id).isEmpty())
            assertTrue(entities.none { File(blobsDir, it.encryptedFileName).exists() })
        } finally {
            exportDir.deleteRecursively()
            db.close()
        }
    }

    @Test
    fun `gallery sort order sorts files by date name and size consistently`() {
        val oldestLarge = VaultFileEntity(
            id = "id-1",
            albumId = "alb-1",
            originalName = "zebra.jpg",
            mimeType = "image/jpeg",
            category = MediaCategory.IMAGE,
            originalSizeBytes = 9_000L,
            encryptedFileName = "1.vlt",
            encryptedThumbName = null,
            createdAt = 100L
        )
        val middleSmall = VaultFileEntity(
            id = "id-2",
            albumId = "alb-1",
            originalName = "alpha.jpg",
            mimeType = "image/jpeg",
            category = MediaCategory.IMAGE,
            originalSizeBytes = 1_000L,
            encryptedFileName = "2.vlt",
            encryptedThumbName = null,
            createdAt = 200L
        )
        val newestMedium = VaultFileEntity(
            id = "id-3",
            albumId = "alb-1",
            originalName = "mango.mp4",
            mimeType = "video/mp4",
            category = MediaCategory.VIDEO,
            originalSizeBytes = 5_000L,
            encryptedFileName = "3.vlt",
            encryptedThumbName = null,
            createdAt = 300L
        )
        val items = listOf(oldestLarge, middleSmall, newestMedium)

        assertEquals(listOf("id-3", "id-2", "id-1"), GallerySortOrder.DATE_DESC.sortFiles(items).map { it.id })
        assertEquals(listOf("id-1", "id-2", "id-3"), GallerySortOrder.DATE_ASC.sortFiles(items).map { it.id })
        assertEquals(listOf("id-2", "id-3", "id-1"), GallerySortOrder.NAME_ASC.sortFiles(items).map { it.id })
        assertEquals(listOf("id-1", "id-3", "id-2"), GallerySortOrder.SIZE_DESC.sortFiles(items).map { it.id })
    }

    @Test
    fun `image adjustment processor applies brightness contrast levels and clarity preset`() {
        val identityLut = ImageAdjustmentProcessor.buildRgbLut(ImageAdjustmentParams())
        for (i in 0..255) {
            assertEquals(i, identityLut[i])
        }

        val clarityLut = ImageAdjustmentProcessor.buildRgbLut(ImageAdjustmentParams.CLARITY_PRESET)
        // Dark shadow below blackLevel (0.06 * 255 = ~15) gets crushed closer to 0 (with +0.04 brightness lift)
        assertTrue(clarityLut[0] in 0..25)
        // Bright highlight near whiteLevel (0.94 * 255 = ~240) reaches 255
        assertEquals(255, clarityLut[250])

        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(128, 128, 128))
        val brightened = ImageAdjustmentProcessor.applyAdjustmentsToBitmap(
            bmp,
            ImageAdjustmentParams(brightness = 0.25f)
        )
        val centerPixel = brightened.getPixel(2, 2)
        assertTrue(Color.red(centerPixel) > 180)
        assertTrue(Color.green(centerPixel) > 180)
        assertTrue(Color.blue(centerPixel) > 180)

        val pngBytes = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val (editedPngBytes, editedMime) = ImageAdjustmentProcessor.applyAdjustmentsToImageBytes(
            imageBytes = pngBytes,
            mimeType = "image/png",
            originalName = "sample.png",
            params = ImageAdjustmentParams.CLARITY_PRESET
        )
        assertEquals("image/png", editedMime)
        assertNotNull(BitmapFactory.decodeByteArray(editedPngBytes, 0, editedPngBytes.size))

        assertEquals(
            "liburan_edit.jpg",
            ImageAdjustmentProcessor.buildEditedCopyFileName("liburan.jpg", "image/jpeg")
        )
        assertEquals(
            "liburan_edit.jpg",
            ImageAdjustmentProcessor.buildEditedCopyFileName("liburan_edit.jpg", "image/jpeg")
        )
        assertEquals(
            "diagram_edit.png",
            ImageAdjustmentProcessor.buildEditedCopyFileName("diagram.png", "image/png")
        )
        bmp.recycle()
        brightened.recycle()
    }

    @Test
    fun `save adjusted vault image supports saving as new copy and overwriting original`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keystore = InMemoryMasterKeyProvider()
        val keyManager = VaultKeyManager(keystore)
        val portableKey = keyManager.generatePortableMasterKey()
        keyManager.setActiveVaultKey(portableKey)
        val engine = VaultCryptoEngine(keystore, portableMasterKeyProvider = { keyManager.getActiveVaultKey() })
        val storage = com.example.data.VaultStorageManager(context, engine, keyManager)

        val srcBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(100, 120, 140))
        }
        val origJpeg = ByteArrayOutputStream().use { out ->
            srcBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        srcBitmap.recycle()

        val blobsDir = File(context.filesDir, "secure_vault/blobs").apply { mkdirs() }
        val origId = "edit-src-${System.nanoTime()}"
        val origBlob = File(blobsDir, "$origId.vlt")
        engine.encryptToFile(ByteArrayInputStream(origJpeg), origBlob)
        val origEntity = VaultFileEntity(
            id = origId,
            albumId = "alb-edit",
            originalName = "pemandangan.jpg",
            mimeType = "image/jpeg",
            category = MediaCategory.IMAGE,
            originalSizeBytes = origJpeg.size.toLong(),
            encryptedFileName = origBlob.name,
            encryptedThumbName = null,
            createdAt = 1000L
        )

        val (editedBytes, targetMime) = ImageAdjustmentProcessor.applyAdjustmentsToImageBytes(
            imageBytes = origJpeg,
            mimeType = origEntity.mimeType,
            originalName = origEntity.originalName,
            params = ImageAdjustmentParams(brightness = 0.2f, contrast = 1.2f)
        )

        // 1. Save as new copy
        val copyEntity = storage.saveAdjustedVaultImage(
            sourceFile = origEntity,
            newPlainBytes = editedBytes,
            targetMimeType = targetMime,
            overwriteOriginal = false
        )
        try {
            assertFalse(copyEntity.id == origEntity.id)
            assertEquals("pemandangan_edit.jpg", copyEntity.originalName)
            assertNotNull(copyEntity.encryptedThumbName)
            assertArrayEquals(editedBytes, storage.decryptToBytes(copyEntity))
            assertArrayEquals(origJpeg, storage.decryptToBytes(origEntity))

            // 2. Overwrite original
            val updatedOrig = storage.saveAdjustedVaultImage(
                sourceFile = origEntity,
                newPlainBytes = editedBytes,
                targetMimeType = targetMime,
                overwriteOriginal = true
            )
            assertEquals(origEntity.id, updatedOrig.id)
            assertEquals(origEntity.encryptedFileName, updatedOrig.encryptedFileName)
            assertArrayEquals(editedBytes, storage.decryptToBytes(updatedOrig))
        } finally {
            runCatching { storage.deleteEncryptedAssets(copyEntity) }
            runCatching { storage.deleteEncryptedAssets(origEntity) }
        }
    }

    @Test
    fun `findFragmentActivity unwraps nested ContextWrappers`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertSame(activity, wrapped.findFragmentActivity())

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertNull(appContext.findFragmentActivity())
    }
}
