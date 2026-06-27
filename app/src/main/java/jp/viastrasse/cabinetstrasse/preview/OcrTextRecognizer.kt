package jp.viastrasse.cabinetstrasse.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File

object OcrTextRecognizer {
    fun recognizeImage(
        context: Context,
        file: File,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        runCatching {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        }.onSuccess { image ->
            val recognizer = TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build(),
            )
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    onSuccess(result.text.trim())
                }
                .addOnFailureListener { error ->
                    onFailure(error)
                }
                .addOnCompleteListener {
                    recognizer.close()
                }
        }.onFailure(onFailure)
    }

    fun recognizePdfFirstPage(
        file: File,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "PDF page is empty." }
                    renderer.openPage(0).use { page ->
                        val width = page.width.coerceAtLeast(1) * 2
                        val height = page.height.coerceAtLeast(1) * 2
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }.onSuccess { bitmap ->
            val recognizer = TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build(),
            )
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    onSuccess(result.text.trim())
                }
                .addOnFailureListener { error ->
                    onFailure(error)
                }
                .addOnCompleteListener {
                    recognizer.close()
                    bitmap.recycle()
                }
        }.onFailure(onFailure)
    }
}
