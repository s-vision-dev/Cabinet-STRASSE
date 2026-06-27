package jp.viastrasse.cabinetstrasse.preview

import android.content.Context
import android.net.Uri
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
}
