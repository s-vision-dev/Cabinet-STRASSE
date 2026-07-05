package jp.viastrasse.cabinet.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File

object ThumbnailGenerator {
    fun createImageThumbnail(source: File, outputDir: File): File {
        require(source.exists() && source.isFile) { "Source image does not exist." }
        outputDir.mkdirs()
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Image could not be decoded.")
        val maxSize = 360
        val scale = minOf(
            maxSize.toFloat() / bitmap.width.coerceAtLeast(1),
            maxSize.toFloat() / bitmap.height.coerceAtLeast(1),
            1f,
        )
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val output = uniqueFile(outputDir, "${source.nameWithoutExtension.ifBlank { "thumbnail" }}.jpg")
        output.outputStream().buffered().use { stream ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, stream)
        }
        if (thumbnail !== bitmap) {
            thumbnail.recycle()
        }
        bitmap.recycle()
        return output
    }

    fun createPdfFirstPageThumbnail(source: File, outputDir: File): File {
        require(source.exists() && source.isFile) { "Source PDF does not exist." }
        outputDir.mkdirs()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages." }
                renderer.openPage(0).use { page ->
                    val maxSize = 480
                    val scale = minOf(
                        maxSize.toFloat() / page.width.coerceAtLeast(1),
                        maxSize.toFloat() / page.height.coerceAtLeast(1),
                        1f,
                    )
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return saveJpeg(bitmap, outputDir, "${source.nameWithoutExtension.ifBlank { "pdf" }}-page1.jpg")
                }
            }
        }
    }

    fun createVideoThumbnail(source: File, outputDir: File): File {
        require(source.exists() && source.isFile) { "Source video does not exist." }
        outputDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Video frame could not be decoded.")
            saveJpeg(scaleBitmap(bitmap, 480), outputDir, "${source.nameWithoutExtension.ifBlank { "video" }}-frame.jpg")
        } finally {
            retriever.release()
        }
    }

    fun createSupportedThumbnail(source: File, mimeType: String, outputDir: File): File {
        val lowerName = source.name.lowercase()
        return when {
            mimeType.startsWith("image/") -> createImageThumbnail(source, outputDir)
            mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> createPdfFirstPageThumbnail(source, outputDir)
            mimeType.startsWith("video/") -> createVideoThumbnail(source, outputDir)
            else -> error("This file type does not support thumbnails.")
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val scale = minOf(
            maxSize.toFloat() / bitmap.width.coerceAtLeast(1),
            maxSize.toFloat() / bitmap.height.coerceAtLeast(1),
            1f,
        )
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true).also {
                bitmap.recycle()
            }
        }
    }

    private fun saveJpeg(bitmap: Bitmap, outputDir: File, displayName: String): File {
        val output = uniqueFile(outputDir, displayName)
        output.outputStream().buffered().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, stream)
        }
        bitmap.recycle()
        return output
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = if (extension.isBlank()) {
                File(directory, "$base-$index")
            } else {
                File(directory, "$base-$index.$extension")
            }
            index += 1
        }
        return candidate
    }
}
