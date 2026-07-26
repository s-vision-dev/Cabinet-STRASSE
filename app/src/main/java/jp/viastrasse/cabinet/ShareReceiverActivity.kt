package jp.viastrasse.cabinet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import jp.viastrasse.cabinet.data.CabinetFiles
import jp.viastrasse.cabinet.data.CabinetItemSummary
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.preview.PreviewWorker
import jp.viastrasse.cabinet.ui.CabinetMetrics
import jp.viastrasse.cabinet.ui.cabinetInput
import java.io.File

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showShareOptions(intent)
    }

    private fun showShareOptions(intent: Intent) {
        val tagInput = darkInput(getString(R.string.share_show_share_options)).apply {
            setText(intent.getStringExtra(EXTRA_TAG).orEmpty())
        }
        val collectionInput = darkInput("Collection").apply {
            setText(intent.getStringExtra(EXTRA_COLLECTION).orEmpty())
        }
        val memoInput = darkInput(getString(R.string.label_memo)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
        }
        val spacing = (CabinetMetrics.SPACE_SM * resources.displayMetrics.density).toInt()
        val padding = (CabinetMetrics.SPACE_XL * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, spacing, padding, 0)
            listOf(tagInput, collectionInput, memoInput).forEachIndexed { index, input ->
                addView(
                    input,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) topMargin = spacing },
                )
            }
        }
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.share_show_share_options_2))
            .setView(container)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
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
            .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> finish() }
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
                    // note は取り込み経路の記録。入力されたメモは applyOptions が Memo として保存する。
                    note = "Android共有から保存",
                )
                applyOptions(repository, item, options)
            }.onSuccess {
                PreviewWorker.enqueue(applicationContext)
                Toast.makeText(this, getString(R.string.share_handle_share_2), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, getString(R.string.share_handle_share_3), Toast.LENGTH_SHORT).show()
            }
        } else {
            val imported = importSharedFiles(intent, repository, options)
            val message = if (imported.isNotEmpty()) {
                getString(R.string.share_handle_share_4, imported.size)
            } else {
                getString(R.string.share_handle_share_5)
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
            Intent.ACTION_SEND_MULTIPLE -> sharedStreamList(intent)
            else -> listOfNotNull(sharedStream(intent))
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
                    // note は取り込み経路の記録。入力されたメモは applyOptions が Memo として保存する。
                    note = "Android共有からInboxへ保存",
                )
                applyOptions(repository, item, options)
                item
            }.onSuccess {
                imported += it
            }
        }
        return imported
    }

    /**
     * `getParcelableExtra(String, Class)` は API 33 以降にしか無い。
     * minSdk は 26 のため、旧APIへのフォールバックが必要。
     */
    @Suppress("DEPRECATION")
    private fun sharedStream(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedStreamList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
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

    private fun darkInput(hintText: String): EditText = cabinetInput(hintText)

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

    private fun uniqueDestination(directory: File, displayName: String): File =
        CabinetFiles.uniqueDestination(directory, displayName)

    private fun sanitizeFileName(value: String): String =
        CabinetFiles.sanitizeFileName(value, fallback = "shared-file")

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
        private const val EXTRA_TAG = "jp.viastrasse.cabinet.extra.TAG"
        private const val EXTRA_COLLECTION = "jp.viastrasse.cabinet.extra.COLLECTION"
        private const val EXTRA_NOTE = "jp.viastrasse.cabinet.extra.NOTE"
    }
}
