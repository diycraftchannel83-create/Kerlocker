package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow

data class ImageAdjustmentParams(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val blackLevel: Float = 0f,
    val midtoneGamma: Float = 1f,
    val whiteLevel: Float = 1f
) {
    fun isDefault(): Boolean =
        abs(brightness) < 0.005f &&
            abs(contrast - 1f) < 0.005f &&
            abs(blackLevel) < 0.005f &&
            abs(midtoneGamma - 1f) < 0.005f &&
            abs(whiteLevel - 1f) < 0.005f

    companion object {
        val CLARITY_PRESET = ImageAdjustmentParams(
            brightness = 0.04f,
            contrast = 1.28f,
            blackLevel = 0.06f,
            midtoneGamma = 1.15f,
            whiteLevel = 0.94f
        )
    }
}

object ImageAdjustmentProcessor {

    fun buildRgbLut(params: ImageAdjustmentParams): IntArray {
        val lut = IntArray(256)
        val clampedBlack = params.blackLevel.coerceIn(0f, 0.85f)
        val clampedWhite = params.whiteLevel.coerceIn(clampedBlack + 0.05f, 1f)
        val span = (clampedWhite - clampedBlack).coerceAtLeast(0.05f)
        val gamma = params.midtoneGamma.coerceIn(0.25f, 3.0f)
        val exponent = 1.0 / gamma.toDouble()
        val contrast = params.contrast.coerceIn(0.2f, 3.5f)
        val brightness = params.brightness.coerceIn(-1f, 1f)

        for (i in 0..255) {
            val normalized = i / 255f
            val leveled = ((normalized - clampedBlack) / span).coerceIn(0f, 1f)
            val gammaAdjusted = if (abs(gamma - 1f) < 0.001f) {
                leveled
            } else {
                leveled.toDouble().pow(exponent).toFloat()
            }
            val contrasted = (gammaAdjusted - 0.5f) * contrast + 0.5f
            val brightened = (contrasted + brightness).coerceIn(0f, 1f)
            lut[i] = (brightened * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    fun applyAdjustmentsToBitmap(
        source: Bitmap,
        params: ImageAdjustmentParams
    ): Bitmap {
        val width = source.width
        val height = source.height
        val working = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (params.isDefault()) {
            return if (working != source) working else working.copy(Bitmap.Config.ARGB_8888, true)
        }

        val lut = buildRgbLut(params)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stripRows = 128
        val rowBuffer = IntArray(width * minOf(stripRows, height))

        var y = 0
        while (y < height) {
            val rowsToRead = minOf(stripRows, height - y)
            working.getPixels(rowBuffer, 0, width, 0, y, width, rowsToRead)
            val totalPixels = rowsToRead * width
            for (idx in 0 until totalPixels) {
                val px = rowBuffer[idx]
                val a = px and -0x1000000
                val r = lut[(px ushr 16) and 0xFF]
                val g = lut[(px ushr 8) and 0xFF]
                val b = lut[px and 0xFF]
                rowBuffer[idx] = a or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(rowBuffer, 0, width, 0, y, width, rowsToRead)
            y += rowsToRead
        }
        if (working != source) {
            working.recycle()
        }
        return output
    }

    fun createPreviewBitmap(source: Bitmap, maxDimension: Int = 960): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDimension && h <= maxDimension) {
            return if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = w.toFloat() / h.toFloat()
        val targetW: Int
        val targetH: Int
        if (ratio >= 1f) {
            targetW = maxDimension
            targetH = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            targetH = maxDimension
            targetW = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        return if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (copy != scaled && scaled != source) scaled.recycle()
            copy
        }
    }

    fun applyAdjustmentsToImageBytes(
        imageBytes: ByteArray,
        mimeType: String,
        originalName: String,
        params: ImageAdjustmentParams
    ): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 4096 || bounds.outHeight / sampleSize > 4096) {
            sampleSize *= 2
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: OutOfMemoryError) {
            BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = (sampleSize * 2).coerceAtLeast(2)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: throw IllegalStateException("Format gambar tidak dapat diproses")

        val oriented = applyExifOrientation(decoded, imageBytes)
        val adjusted = applyAdjustmentsToBitmap(oriented, params)
        if (oriented != decoded) decoded.recycle()
        if (adjusted != oriented) oriented.recycle()

        val isPng = mimeType.equals("image/png", ignoreCase = true) ||
            originalName.endsWith(".png", ignoreCase = true)
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val targetMime = if (isPng) "image/png" else "image/jpeg"
        val quality = if (isPng) 100 else 94

        val out = ByteArrayOutputStream()
        try {
            if (!adjusted.compress(format, quality, out)) {
                throw IllegalStateException("Gagal mengompresi gambar hasil edit")
            }
            return out.toByteArray() to targetMime
        } finally {
            adjusted.recycle()
        }
    }

    fun applyExifOrientation(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ByteArrayInputStream(imageBytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return runCatching {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            transformed
        }.getOrDefault(bitmap)
    }

    fun buildEditedCopyFileName(originalName: String, targetMimeType: String): String {
        val fallbackExt = if (targetMimeType.equals("image/png", ignoreCase = true)) "png" else "jpg"
        val dotIdx = originalName.lastIndexOf('.')
        val stem = if (dotIdx > 0) originalName.substring(0, dotIdx) else originalName
        val cleanStem = stem.removeSuffix("_edit").ifBlank { "LockerImage" }
        val ext = if (targetMimeType.equals("image/png", ignoreCase = true)) {
            "png"
        } else if (dotIdx > 0) {
            val rawExt = originalName.substring(dotIdx + 1).lowercase()
            if (rawExt in setOf("jpg", "jpeg")) rawExt else fallbackExt
        } else {
            fallbackExt
        }
        return "${cleanStem}_edit.$ext"
    }
}
