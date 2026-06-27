package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import jp.viastrasse.cabinetstrasse.data.CabinetItemSummary
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import jp.viastrasse.cabinetstrasse.preview.PreviewWorker
import jp.viastrasse.cabinetstrasse.theme.CabinetColors
import java.io.File

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showShareOptions(intent)
    }

    private fun showShareOptions(intent: Intent) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        val tagInput = darkInput("タグ").apply {
            setText(intent.getStringExtra(EXTRA_TAG).orEmpty())
        }
        val collectionInput = darkInput("Collection").apply {
            setText(intent.getStringExtra(EXTRA_COLLECTION).orEmpty())
        }
        val memoInput = darkInput("メモ").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
        }
        container.addView(tagInput)
        container.addView(collectionInput)
        container.addView(memoInput)
        AlertDialog.Builder(this)
            .setTitle("Cabinet Inboxへ保存")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                handleShare(
                    intent,
                    ShareOptions(
                        tag = tagInput.text.toString().trim(),
                        collection = collectionInput.text.toString().trim(),
                        memo = memoInput.text.toString().trim(),
                    ),
                )
                finish()
            }
            .setNegativeButton("キャンセル") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun handleShare(intent: Intent, options: ShareOptions) {
        val repository = CabinetRepository(applicationContext)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.startsWith("http://") || sharedText.startsWith("https://")) {
            runCatching {
                val item = repository.registerUrl(
                    url = sharedText,
                    title = intent.getStringExtra(Intent.EXTRA_TITLE)?.ifBlank { null } ?: sharedText,
                    note = options.memo.ifBlank { "Android共有から保存" },
                )
                applyOptions(repository, item, options)
            }.onSuccess {
                PreviewWorker.enqueue(applicationContext)
                Toast.makeText(this, "Cabinet Inbox に保存しました", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Cabinet への保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            val imported = importSharedFiles(intent, repository, options)
            val message = if (imported.isNotEmpty()) {
                "${imported.size}件を Cabinet Inbox に保存しました"
            } else {
                "共有内容を保存できませんでした"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSharedFiles(
        intent: Intent,
        repository: CabinetRepository,
        options: ShareOptions,
    ): List<CabinetItemSummary> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            else -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
        }
        val imported = mutableListOf<CabinetItemSummary>()
        uris.forEach { uri ->
            runCatching {
                val info = queryOpenable(uri)
                val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
                val destination = uniqueDestination(inboxDir, info.displayName)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Input stream is null." }
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                val item = repository.registerFile(
                    path = destination.absolutePath,
                    displayName = info.displayName,
                    mimeType = info.mimeType,
                    size = destination.length(),
                    sourceKind = "android-share",
                    note = options.memo.ifBlank { "Android共有からInboxへ保存" },
                )
                applyOptions(repository, item, options)
                item
            }.onSuccess {
                imported += it
            }
        }
        return imported
    }

    private fun applyOptions(repository: CabinetRepository, item: CabinetItemSummary, options: ShareOptions) {
        if (options.tag.isNotBlank()) {
            repository.addTag(item.id, options.tag)
        }
        if (options.collection.isNotBlank()) {
            repository.addToCollection(item.id, options.collection)
        }
        if (options.memo.isNotBlank()) {
            repository.addMemo(item.id, options.memo, false)
        }
    }

    private fun darkInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(CabinetColors.TextPrimary)
            setHintTextColor(CabinetColors.TextSecondary)
            setBackgroundColor(CabinetColors.SurfaceAlt)
            setPadding(24, 18, 24, 18)
        }
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

    private data class ShareOptions(
        val tag: String,
        val collection: String,
        val memo: String,
    )

    companion object {
        private const val EXTRA_TAG = "jp.viastrasse.cabinetstrasse.extra.TAG"
        private const val EXTRA_COLLECTION = "jp.viastrasse.cabinetstrasse.extra.COLLECTION"
        private const val EXTRA_NOTE = "jp.viastrasse.cabinetstrasse.extra.NOTE"
    }
}
