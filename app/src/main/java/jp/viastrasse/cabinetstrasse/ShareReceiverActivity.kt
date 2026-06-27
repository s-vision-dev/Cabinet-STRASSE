package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import java.io.File

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        finish()
    }

    private fun handleShare(intent: Intent) {
        val repository = CabinetRepository(applicationContext)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.startsWith("http://") || sharedText.startsWith("https://")) {
            runCatching {
                repository.registerUrl(
                    url = sharedText,
                    title = intent.getStringExtra(Intent.EXTRA_TITLE)?.ifBlank { null } ?: sharedText,
                    note = "Android共有から保存",
                )
            }.onSuccess {
                Toast.makeText(this, "Cabinet Inbox に保存しました", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Cabinet への保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            val imported = importSharedFiles(intent, repository)
            val message = if (imported > 0) {
                "${imported}件を Cabinet Inbox に保存しました"
            } else {
                "共有内容を保存できませんでした"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSharedFiles(intent: Intent, repository: CabinetRepository): Int {
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            else -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
        }
        var imported = 0
        uris.forEach { uri ->
            runCatching {
                val info = queryOpenable(uri)
                val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
                val destination = uniqueDestination(inboxDir, info.displayName)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Input stream is null." }
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                repository.registerFile(
                    path = destination.absolutePath,
                    displayName = info.displayName,
                    mimeType = info.mimeType,
                    size = destination.length(),
                    sourceKind = "android-share",
                    note = "Android共有からInboxへ保存",
                )
            }.onSuccess {
                imported += 1
            }
        }
        return imported
    }

    private fun queryOpenable(uri: Uri): SharedFileInfo {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        val mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
        return SharedFileInfo(displayName = sanitizeFileName(displayName), mimeType = mimeType, size = size)
    }

    private fun uniqueDestination(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (extension.isBlank()) {
                "$base-$index"
            } else {
                "$base-$index.$extension"
            }
            candidate = File(directory, nextName)
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "shared-file" }
    }

    private data class SharedFileInfo(
        val displayName: String,
        val mimeType: String,
        val size: Long,
    )
}
