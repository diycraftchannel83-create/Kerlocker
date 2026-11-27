package com.example.crypto

import android.media.MediaDataSource
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-throughput Chunked Authenticated AES-256-GCM Encryption Engine.
 *
 * File Envelope Format ("BVLT" v1 / v2):
 * - Magic: 4 bytes ("BVLT")
 * - Version: 1 byte (1 = Keystore-wrapped DEK, 2 = Portable VaultMasterKey-wrapped DEK)
 * - WrappedKeyIvLength: 1 byte (12)
 * - WrappedKeyIv: 12 bytes
 * - WrappedDekLength: 2 bytes (48 bytes = 32B AES-256 DEK + 16B GCM tag)
 * - WrappedDek: 48 bytes
 * - ChunkSize: 4 bytes (256 KiB for new files; backward-compatible with legacy 64 KiB files)
 * - TotalPlaintextSize: 8 bytes (Long)
 * Total Header Size = 80 bytes.
 *
 * Followed by sequential chunks:
 * - CiphertextLength: 4 bytes
 * - ChunkIv: 12 bytes (unique per chunk via 4-byte random file nonce prefix + 8-byte chunk counter)
 * - ChunkCiphertextAndTag: (plaintextChunkLen + 16) bytes with AAD = 8-byte big-endian chunkIndex
 */
class VaultCryptoEngine(
    private val masterKeyProvider: MasterKeyProvider,
    private val portableMasterKeyProvider: () -> SecretKey? = { null },
    private val fallbackPortableKeysProvider: () -> List<SecretKey> = { emptyList() }
) {
    companion object {
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
        private const val VERSION_KEYSTORE: Byte = 1
        private const val VERSION_PORTABLE: Byte = 2
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val DEK_BYTES = 32 // AES-256
        const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256 KiB
        const val IO_BUFFER_SIZE = 256 * 1024
        const val FIXED_HEADER_SIZE = 4 + 1 + 1 + 12 + 2 + 48 + 4 + 8 // 80 bytes
    }

    private val secureRandom = SecureRandom()

    data class EncryptedFileHeader(
        val version: Byte,
        val dek: SecretKey,
        val chunkSize: Int,
        val totalPlaintextSize: Long,
        val headerByteLength: Int,
        val unwrappedWithActivePortableKey: Boolean = false
    )

    private fun generateFileDek(): ByteArray {
        val dekBytes = ByteArray(DEK_BYTES)
        secureRandom.nextBytes(dekBytes)
        return dekBytes
    }

    private fun wrapDek(rawDek: ByteArray, overrideKey: SecretKey?): Triple<Byte, ByteArray, ByteArray> {
        val portableKey = overrideKey ?: portableMasterKeyProvider()
        val (version, wrappingKey) = if (portableKey != null) {
            VERSION_PORTABLE to portableKey
        } else {
            VERSION_KEYSTORE to masterKeyProvider.getOrCreateMasterKey()
        }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val wrapped = cipher.doFinal(rawDek)
        val iv = cipher.iv
        return Triple(version, iv, wrapped)
    }

    private fun buildCandidatePortableKeys(overrideKey: SecretKey?): List<SecretKey> {
        val rawCandidates = buildList {
            overrideKey?.let(::add)
            portableMasterKeyProvider()?.let(::add)
            addAll(fallbackPortableKeysProvider())
        }
        val unique = mutableListOf<SecretKey>()
        val seenRaw = mutableListOf<ByteArray>()
        for (key in rawCandidates) {
            val raw = key.encoded ?: continue
            if (seenRaw.none { it.contentEquals(raw) }) {
                seenRaw.add(raw)
                unique.add(key)
            } else {
                raw.fill(0)
            }
        }
        seenRaw.forEach { it.fill(0) }
        return unique
    }

    private fun tryUnwrapWithKey(
        wrappingKey: SecretKey,
        iv: ByteArray,
        wrappedDek: ByteArray
    ): SecretKey {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, spec)
        val rawDek = cipher.doFinal(wrappedDek)
        return try {
            SecretKeySpec(rawDek, AES_ALGORITHM)
        } finally {
            rawDek.fill(0)
        }
    }

    private fun unwrapDek(
        version: Byte,
        iv: ByteArray,
        wrappedDek: ByteArray,
        overrideKey: SecretKey?
    ): Pair<SecretKey, Boolean> {
        val activePortable = portableMasterKeyProvider()
        val activePortableEncoded = activePortable?.encoded
        try {
            when (version) {
                VERSION_PORTABLE -> {
                    val candidates = buildCandidatePortableKeys(overrideKey)
                    if (candidates.isEmpty()) {
                        throw SecurityException("Kunci pemulihan brankas diperlukan untuk membuka file ini")
                    }
                    var lastAuthFailure: AEADBadTagException? = null
                    for (candidate in candidates) {
                        try {
                            val dek = tryUnwrapWithKey(candidate, iv, wrappedDek)
                            val candidateEncoded = candidate.encoded
                            val isExactActive = activePortableEncoded != null &&
                                candidateEncoded != null &&
                                candidateEncoded.contentEquals(activePortableEncoded)
                            candidateEncoded?.fill(0)
                            return dek to isExactActive
                        } catch (authEx: AEADBadTagException) {
                            lastAuthFailure = authEx
                        }
                    }
                    throw lastAuthFailure
                        ?: SecurityException("PIN pemulihan tidak cocok atau kunci enkripsi berbeda")
                }
                VERSION_KEYSTORE -> {
                    try {
                        val dek = tryUnwrapWithKey(masterKeyProvider.getOrCreateMasterKey(), iv, wrappedDek)
                        return dek to false
                    } catch (authEx: AEADBadTagException) {
                        for (candidate in buildCandidatePortableKeys(overrideKey)) {
                            val dek = runCatching { tryUnwrapWithKey(candidate, iv, wrappedDek) }.getOrNull()
                            if (dek != null) {
                                return dek to false
                            }
                        }
                        throw authEx
                    }
                }
                else -> throw SecurityException("Versi enkripsi tidak didukung: $version")
            }
        } finally {
            activePortableEncoded?.fill(0)
        }
    }

    private fun writeLongBigEndian(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = (value ushr 56).toByte()
        dst[offset + 1] = (value ushr 48).toByte()
        dst[offset + 2] = (value ushr 40).toByte()
        dst[offset + 3] = (value ushr 32).toByte()
        dst[offset + 4] = (value ushr 24).toByte()
        dst[offset + 5] = (value ushr 16).toByte()
        dst[offset + 6] = (value ushr 8).toByte()
        dst[offset + 7] = value.toByte()
    }

    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        totalPlaintextSize: Long,
        overrideKey: SecretKey? = null
    ): Long {
        val rawDek = generateFileDek()
        val dekSpec = SecretKeySpec(rawDek, AES_ALGORITHM)
        val (version, wrapIv, wrappedDek) = wrapDek(rawDek, overrideKey)
        rawDek.fill(0)

        val bufferedOut = if (output is BufferedOutputStream || output is ByteArrayOutputStream) {
            output
        } else {
            BufferedOutputStream(output, IO_BUFFER_SIZE)
        }
        val dos = DataOutputStream(bufferedOut)
        dos.write(MAGIC)
        dos.writeByte(version.toInt())
        dos.writeByte(wrapIv.size)
        dos.write(wrapIv)
        dos.writeShort(wrappedDek.size)
        dos.write(wrappedDek)
        dos.writeInt(DEFAULT_CHUNK_SIZE)
        val totalSizeOffset = (4 + 1 + 1 + wrapIv.size + 2 + wrappedDek.size + 4).toLong()
        dos.writeLong(totalPlaintextSize)

        val plainBuffer = ByteArray(DEFAULT_CHUNK_SIZE)
        val cipherBuffer = ByteArray(DEFAULT_CHUNK_SIZE + GCM_TAG_BYTES)
        val chunkIv = ByteArray(GCM_IV_BYTES)
        val ivPrefix = ByteArray(4)
        secureRandom.nextBytes(ivPrefix)
        System.arraycopy(ivPrefix, 0, chunkIv, 0, 4)

        val aadBytes = ByteArray(8)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        var chunkIndex = 0L

        try {
            while (true) {
                val bytesRead = readFullyUpTo(input, plainBuffer)
                if (bytesRead <= 0) break

                writeLongBigEndian(chunkIv, 4, chunkIndex)
                writeLongBigEndian(aadBytes, 0, chunkIndex)

                cipher.init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                cipher.updateAAD(aadBytes)
                val encryptedLen = cipher.doFinal(plainBuffer, 0, bytesRead, cipherBuffer, 0)

                dos.writeInt(encryptedLen)
                dos.write(chunkIv)
                dos.write(cipherBuffer, 0, encryptedLen)
                chunkIndex++
            }
            dos.flush()
        } finally {
            plainBuffer.fill(0)
        }
        return totalSizeOffset
    }

    data class EncryptFileResult(
        val totalPlaintextSize: Long,
        val sha256Hex: String
    )

    /**
     * Streams plaintext from [input] into [destFile] with AES-256-GCM chunked encryption
     * and records the exact total plaintext byte count in the file header while computing SHA-256.
     */
    fun encryptToFile(
        input: InputStream,
        destFile: File,
        overrideKey: SecretKey? = null
    ): Long = encryptToFileWithHash(input, destFile, overrideKey).totalPlaintextSize

    fun encryptToFileWithHash(
        input: InputStream,
        destFile: File,
        overrideKey: SecretKey? = null
    ): EncryptFileResult {
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val bufferedIn = if (input is BufferedInputStream || input is ByteArrayInputStream) {
            input
        } else {
            BufferedInputStream(input, IO_BUFFER_SIZE)
        }
        val countingInput = object : InputStream() {
            override fun read(): Int {
                val b = bufferedIn.read()
                if (b != -1) {
                    totalBytes++
                    digest.update(b.toByte())
                }
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = bufferedIn.read(b, off, len)
                if (n > 0) {
                    totalBytes += n
                    digest.update(b, off, n)
                }
                return n
            }
        }
        val totalSizeOffset = BufferedOutputStream(destFile.outputStream(), IO_BUFFER_SIZE).use { out ->
            encryptStream(countingInput, out, 0L, overrideKey)
        }
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(totalSizeOffset)
            raf.writeLong(totalBytes)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return EncryptFileResult(totalPlaintextSize = totalBytes, sha256Hex = hex)
    }

    fun computePlaintextSha256(
        encryptedFile: File,
        overrideKey: SecretKey? = null
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestOut = object : OutputStream() {
            override fun write(b: Int) {
                digest.update(b.toByte())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0) digest.update(b, off, len)
            }
        }
        BufferedInputStream(encryptedFile.inputStream(), IO_BUFFER_SIZE).use { input ->
            decryptStream(input, digestOut, overrideKey)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        overrideKey: SecretKey? = null
    ) {
        val bufferedIn = if (input is BufferedInputStream || input is ByteArrayInputStream) {
            input
        } else {
            BufferedInputStream(input, IO_BUFFER_SIZE)
        }
        val dis = DataInputStream(bufferedIn)
        val header = readHeader(dis, overrideKey)
        val chunkLimit = header.chunkSize.coerceIn(1024, 4 * 1024 * 1024)
        var encryptedBuf = ByteArray(chunkLimit + GCM_TAG_BYTES)
        var plainBuf = ByteArray(chunkLimit)
        val chunkIv = ByteArray(GCM_IV_BYTES)
        val aadBytes = ByteArray(8)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        var chunkIndex = 0L

        try {
            while (true) {
                val cipherLen = try {
                    dis.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (cipherLen <= GCM_TAG_BYTES || cipherLen > 8 * 1024 * 1024) {
                    throw SecurityException("Corrupted encrypted chunk length")
                }
                if (cipherLen > encryptedBuf.size) {
                    encryptedBuf = ByteArray(cipherLen)
                    plainBuf = ByteArray(cipherLen)
                }
                dis.readFully(chunkIv)
                dis.readFully(encryptedBuf, 0, cipherLen)

                writeLongBigEndian(aadBytes, 0, chunkIndex)
                cipher.init(Cipher.DECRYPT_MODE, header.dek, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                cipher.updateAAD(aadBytes)
                val plainLen = cipher.doFinal(encryptedBuf, 0, cipherLen, plainBuf, 0)
                output.write(plainBuf, 0, plainLen)
                chunkIndex++
            }
            output.flush()
        } finally {
            plainBuf.fill(0)
        }
    }

    fun encryptBytes(plainBytes: ByteArray, overrideKey: SecretKey? = null): ByteArray {
        val baos = ByteArrayOutputStream(plainBytes.size + 128)
        encryptStream(ByteArrayInputStream(plainBytes), baos, plainBytes.size.toLong(), overrideKey)
        return baos.toByteArray()
    }

    fun decryptBytes(encryptedFile: File, overrideKey: SecretKey? = null): ByteArray {
        val expectedOutSize = (encryptedFile.length() - FIXED_HEADER_SIZE).coerceIn(256L, 64L * 1024 * 1024).toInt()
        val baos = ByteArrayOutputStream(expectedOutSize)
        BufferedInputStream(encryptedFile.inputStream(), IO_BUFFER_SIZE).use { input ->
            decryptStream(input, baos, overrideKey)
        }
        return baos.toByteArray()
    }

    fun decryptBytes(encryptedData: ByteArray, overrideKey: SecretKey? = null): ByteArray {
        val baos = ByteArrayOutputStream(encryptedData.size)
        decryptStream(ByteArrayInputStream(encryptedData), baos, overrideKey)
        return baos.toByteArray()
    }

    fun readHeader(file: File, overrideKey: SecretKey? = null): EncryptedFileHeader {
        return BufferedInputStream(file.inputStream(), 4096).use { readHeader(DataInputStream(it), overrideKey) }
    }

    fun readHeaderFromPrefixBytes(prefixBytes: ByteArray, overrideKey: SecretKey? = null): EncryptedFileHeader {
        if (prefixBytes.size < FIXED_HEADER_SIZE) {
            throw SecurityException("Header file terenkripsi tidak lengkap")
        }
        return readHeader(DataInputStream(ByteArrayInputStream(prefixBytes)), overrideKey)
    }

    fun canDecryptPrefix(prefixBytes: ByteArray, overrideKey: SecretKey? = null): Boolean {
        return runCatching { readHeaderFromPrefixBytes(prefixBytes, overrideKey) }.isSuccess
    }

    private fun readHeader(dis: DataInputStream, overrideKey: SecretKey?): EncryptedFileHeader {
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw SecurityException("Format file brankas tidak valid")
        }
        val version = dis.readByte()
        if (version != VERSION_KEYSTORE && version != VERSION_PORTABLE) {
            throw SecurityException("Versi enkripsi tidak didukung: $version")
        }
        val wrapIvLen = dis.readUnsignedByte()
        val wrapIv = ByteArray(wrapIvLen)
        dis.readFully(wrapIv)
        val wrappedDekLen = dis.readUnsignedShort()
        val wrappedDek = ByteArray(wrappedDekLen)
        dis.readFully(wrappedDek)
        val chunkSize = dis.readInt()
        val totalPlaintextSize = dis.readLong()
        val (dek, unwrappedWithActive) = unwrapDek(version, wrapIv, wrappedDek, overrideKey)
        val headerBytes = 4 + 1 + 1 + wrapIvLen + 2 + wrappedDekLen + 4 + 8
        return EncryptedFileHeader(version, dek, chunkSize, totalPlaintextSize, headerBytes, unwrappedWithActive)
    }

    /**
     * Creates a zero-disk-leakage [MediaDataSource] for playing AES-256-GCM encrypted videos/audio
     * directly in memory with random-access seeking.
     */
    fun openEncryptedMediaDataSource(
        encryptedFile: File,
        overrideKey: SecretKey? = null
    ): MediaDataSource {
        val header = readHeader(encryptedFile, overrideKey)
        val raf = RandomAccessFile(encryptedFile, "r")
        return try {
            createChannelMediaDataSource(
                header = header,
                readAtOffset = { offset, dst, len ->
                    raf.seek(offset)
                    raf.readFully(dst, 0, len)
                },
                closeSource = { runCatching { raf.close() } }
            )
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    /**
     * Creates a zero-disk-leakage [MediaDataSource] directly from a [ParcelFileDescriptor]
     * (for streaming encrypted .vault files stored in a user-selected SAF destination folder).
     */
    fun openEncryptedMediaDataSource(
        pfd: ParcelFileDescriptor,
        overrideKey: SecretKey? = null
    ): MediaDataSource {
        val fis = FileInputStream(pfd.fileDescriptor)
        return try {
            val channel = fis.channel
            val headerBuf = ByteBuffer.allocate(FIXED_HEADER_SIZE)
            var readTotal = 0
            while (readTotal < FIXED_HEADER_SIZE) {
                val n = channel.read(headerBuf, readTotal.toLong())
                if (n <= 0) break
                readTotal += n
            }
            if (readTotal < FIXED_HEADER_SIZE) {
                throw EOFException("Header file terenkripsi tidak lengkap")
            }
            val header = readHeader(DataInputStream(ByteArrayInputStream(headerBuf.array())), overrideKey)
            createChannelMediaDataSource(
                header = header,
                readAtOffset = { offset, dst, len ->
                    val bb = ByteBuffer.wrap(dst, 0, len)
                    var pos = offset
                    while (bb.hasRemaining()) {
                        val n = channel.read(bb, pos)
                        if (n <= 0) throw EOFException("Unexpected EOF while reading encrypted chunk")
                        pos += n
                    }
                },
                closeSource = {
                    runCatching { fis.close() }
                    runCatching { pfd.close() }
                }
            )
        } catch (t: Throwable) {
            runCatching { fis.close() }
            runCatching { pfd.close() }
            throw t
        }
    }

    private fun createChannelMediaDataSource(
        header: EncryptedFileHeader,
        readAtOffset: (Long, ByteArray, Int) -> Unit,
        closeSource: () -> Unit
    ): MediaDataSource {
        val chunkStride = 4L + GCM_IV_BYTES + header.chunkSize + GCM_TAG_BYTES
        val maxFrameBytes = 4 + GCM_IV_BYTES + header.chunkSize + GCM_TAG_BYTES

        return object : MediaDataSource() {
            private var cachedChunkIdx: Long = -1L
            private var cachedChunkPlain: ByteArray = ByteArray(0)
            private val lock = Any()
            private val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            private var frameBuf = ByteArray(maxFrameBytes)
            private val aadBytes = ByteArray(8)
            private val lenBuf = ByteArray(4)

            override fun getSize(): Long = header.totalPlaintextSize

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= header.totalPlaintextSize) return -1
                if (size == 0) return 0

                val bytesToRead = minOf(size.toLong(), header.totalPlaintextSize - position).toInt()
                var totalCopied = 0
                var currentPos = position

                synchronized(lock) {
                    while (totalCopied < bytesToRead) {
                        val chunkIdx = currentPos / header.chunkSize
                        val inChunkOffset = (currentPos % header.chunkSize).toInt()
                        val plainChunk = loadChunk(chunkIdx)
                        val available = plainChunk.size - inChunkOffset
                        if (available <= 0) break

                        val copyLen = minOf(available, bytesToRead - totalCopied)
                        System.arraycopy(plainChunk, inChunkOffset, buffer, offset + totalCopied, copyLen)
                        totalCopied += copyLen
                        currentPos += copyLen
                    }
                }
                return if (totalCopied == 0) -1 else totalCopied
            }

            private fun loadChunk(chunkIdx: Long): ByteArray {
                if (chunkIdx == cachedChunkIdx) return cachedChunkPlain

                val fileOffset = header.headerByteLength.toLong() + chunkIdx * chunkStride
                readAtOffset(fileOffset, lenBuf, 4)
                val cipherLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                    ((lenBuf[1].toInt() and 0xFF) shl 16) or
                    ((lenBuf[2].toInt() and 0xFF) shl 8) or
                    (lenBuf[3].toInt() and 0xFF)
                if (cipherLen <= GCM_TAG_BYTES || cipherLen > 8 * 1024 * 1024) {
                    throw SecurityException("Corrupted encrypted chunk length")
                }
                val ivAndCipherLen = GCM_IV_BYTES + cipherLen
                if (ivAndCipherLen > frameBuf.size) {
                    frameBuf = ByteArray(ivAndCipherLen)
                }
                readAtOffset(fileOffset + 4L, frameBuf, ivAndCipherLen)

                writeLongBigEndian(aadBytes, 0, chunkIdx)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    header.dek,
                    GCMParameterSpec(GCM_TAG_BITS, frameBuf, 0, GCM_IV_BYTES)
                )
                cipher.updateAAD(aadBytes)
                val plain = cipher.doFinal(frameBuf, GCM_IV_BYTES, cipherLen)

                cachedChunkPlain.fill(0)
                cachedChunkIdx = chunkIdx
                cachedChunkPlain = plain
                return plain
            }

            override fun close() {
                synchronized(lock) {
                    cachedChunkPlain.fill(0)
                    cachedChunkPlain = ByteArray(0)
                    frameBuf.fill(0)
                    cachedChunkIdx = -1L
                    closeSource()
                }
            }
        }
    }

    private fun readFullyUpTo(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}

object PinCrypto {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verifyPin(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val actualHash = hashPin(pin, salt)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }
}
