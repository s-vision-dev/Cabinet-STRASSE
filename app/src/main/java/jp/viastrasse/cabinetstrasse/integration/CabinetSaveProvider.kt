package jp.viastrasse.cabinet.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.preview.PreviewWorker
import java.io.File
import java.net.URLConnection

class CabinetSaveProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return MatrixCursor(
            arrayOf(
                "capability",
                "required_values",
                "optional_values",
            ),
        ).apply {
            addRow(arrayOf("save_url", "url", "title,note"))
            addRow(arrayOf("save_file", "fileUri", "displayName,mimeType,note,sourceKind"))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.viastrasse.cabinet.save"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val fileUri = values?.getAsString("fileUri").orEmpty()
        if (fileUri.isNotBlank()) {
            return runCatching {
                val parsedFileUri = Uri.parse(fileUri)
                val info = queryOpenable(parsedFileUri, values)
                val inboxDir = File(context.filesDir, "inbox").apply { mkdirs() }
                val destination = uniqueDestination(inboxDir, info.displayName)
                when (parsedFileUri.scheme) {
                    "content" -> {
                        context.contentResolver.openInputStream(parsedFileUri).use { input ->
                            requireNotNull(input) { "Input stream is null." }
                            destination.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    }
                    "file", null -> {
                        val source = if (parsedFileUri.scheme == "file") {
                            File(requireNotNull(parsedFileUri.path))
                        } else {
                            File(fileUri)
                        }
                        require(source.exists() && source.isFile) { "Source file does not exist." }
                        source.inputStream().buffered().use { input ->
                            destination.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    }
                    else -> error("Unsupported fileUri scheme: ${parsedFileUri.scheme}")
                }
                val item = CabinetRepository(context).registerFile(
                    path = destination.absolutePath,
                    displayName = info.displayName,
                    mimeType = info.mimeType,
                    size = destination.length(),
                    sourceKind = values?.getAsString("sourceKind").orEmpty().ifBlank { "save-provider-file" },
                    note = values?.getAsString("note").orEmpty().ifBlank { "CabinetSaveProviderからファイル保存" },
                )
                PreviewWorker.enqueue(context)
                Uri.parse("viastrasse-cabinet://open/${item.id}")
            }.getOrNull()
        }
        val url = values?.getAsString("url").orEmpty()
        if (url.isBlank()) {
            return null
        }
        return runCatching {
            val item = CabinetRepository(context).registerUrl(
                url = url,
                title = values?.getAsString("title").orEmpty().ifBlank { url },
                note = values?.getAsString("note").orEmpty().ifBlank { "CabinetSaveProviderから保存" },
            )
            Uri.parse("viastrasse-cabinet://open/${item.id}")
        }.getOrNull()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun queryOpenable(uri: Uri, values: ContentValues?): SaveFileInfo {
        val context = requireNotNull(context)
        var displayName = values?.getAsString("displayName").orEmpty()
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
            .ifBlank { "saved-file" }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    displayName = cursor.getString(index).orEmpty().ifBlank { displayName }
                }
            }
        }
        val sanitizedName = sanitizeFileName(displayName)
        val mimeType = values?.getAsString("mimeType").orEmpty()
            .ifBlank { context.contentResolver.getType(uri).orEmpty() }
            .ifBlank { URLConnection.guessContentTypeFromName(sanitizedName).orEmpty() }
            .ifBlank { "application/octet-stream" }
        return SaveFileInfo(displayName = sanitizedName, mimeType = mimeType)
    }

    private fun uniqueDestination(directory: File, displayName: String): File {
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

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "saved-file" }
    }

    private data class SaveFileInfo(
        val displayName: String,
        val mimeType: String,
    )
}
