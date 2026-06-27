package jp.viastrasse.cabinetstrasse.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
