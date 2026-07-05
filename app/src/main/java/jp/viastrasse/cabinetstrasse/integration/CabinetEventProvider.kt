package jp.viastrasse.cabinet.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.preview.PreviewWorker
import org.json.JSONObject
import java.io.File
import java.net.URLConnection

class CabinetEventProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "event_type",
                "item_id",
                "payload_json",
                "created_at",
                "deep_link",
                "contract_version",
                "source_app",
                "ack_uri",
            ),
        )
        val context = context ?: return cursor
        val limit = uri.getQueryParameter("limit")?.toLongOrNull() ?: 100L
        val eventTypeFilter = uri.getQueryParameter("event_type")
            ?: selectionArgs?.getOrNull(0)
        val itemIdFilter = uri.getQueryParameter("item_id")
            ?: selectionArgs?.getOrNull(1)
        runCatching {
            JSONObject(CabinetRepository(context).eventsJson(limit))
        }.onSuccess { root ->
            root.optJSONArray("events")?.let { events ->
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val eventType = event.optString("event_type")
                    val itemId = event.optString("item_id")
                    if (!eventTypeFilter.isNullOrBlank() && eventType != eventTypeFilter) {
                        continue
                    }
                    if (!itemIdFilter.isNullOrBlank() && itemId != itemIdFilter) {
                        continue
                    }
                    cursor.addRow(
                        arrayOf<Any>(
                            event.optString("id"),
                            eventType,
                            itemId,
                            event.optJSONObject("payload")?.toString().orEmpty(),
                            event.optString("created_at"),
                            if (itemId.isBlank()) "" else "viastrasse-cabinet://open/$itemId",
                            CONTRACT_VERSION,
                            event.optJSONObject("payload")?.optString("source_app").orEmpty(),
                            "content://jp.viastrasse.cabinet.provider.events",
                        ),
                    )
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.viastrasse.cabinet.event"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val eventType = values?.getAsString("event_type").orEmpty()
        if (eventType.isBlank()) return null
        val payload = values?.getAsString("payload_json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        return runCatching {
            val repository = CabinetRepository(context)
            when (eventType) {
                "Mail.AttachmentSaveRequested",
                "Task.ResultSaveRequested",
                "Home.QuickSaveRequested" -> saveRequestedPayload(repository, payload, values)
                "Atelier.ReferenceAddRequested",
                "Task.AttachmentAddRequested" -> addReferenceRequestedPayload(repository, payload, values)
                else -> null
            }
        }.getOrNull()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun saveRequestedPayload(
        repository: CabinetRepository,
        payload: JSONObject,
        values: ContentValues?,
    ): Uri? {
        val context = requireNotNull(context)
        val url = values?.getAsString("url").orEmpty().ifBlank { payload.optString("url") }
        if (url.isNotBlank()) {
            val item = repository.registerUrl(
                url = url,
                title = values?.getAsString("title").orEmpty()
                    .ifBlank { payload.optString("title") }
                    .ifBlank { url },
                note = values?.getAsString("note").orEmpty()
                    .ifBlank { payload.optString("note") }
                    .ifBlank { "STRASSE EventからURL保存" },
            )
            attachSourceReference(repository, item.id, payload, values, url)
            return Uri.parse("viastrasse-cabinet://open/${item.id}")
        }

        val fileUriText = values?.getAsString("fileUri").orEmpty().ifBlank { payload.optString("fileUri") }
        if (fileUriText.isBlank()) return null
        val fileUri = Uri.parse(fileUriText)
        val info = queryOpenable(fileUri, payload, values)
        val inboxDir = File(context.filesDir, "inbox").apply { mkdirs() }
        val destination = uniqueDestination(inboxDir, info.displayName)
        when (fileUri.scheme) {
            "content" -> {
                context.contentResolver.openInputStream(fileUri).use { input ->
                    requireNotNull(input) { "Input stream is null." }
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            "file", null -> {
                val source = if (fileUri.scheme == "file") File(requireNotNull(fileUri.path)) else File(fileUriText)
                require(source.exists() && source.isFile) { "Source file does not exist." }
                source.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            else -> error("Unsupported fileUri scheme: ${fileUri.scheme}")
        }
        val item = repository.registerFile(
            path = destination.absolutePath,
            displayName = info.displayName,
            mimeType = info.mimeType,
            size = destination.length(),
            sourceKind = values?.getAsString("sourceKind").orEmpty()
                .ifBlank { payload.optString("sourceKind") }
                .ifBlank { "strasse-event" },
            note = values?.getAsString("note").orEmpty()
                .ifBlank { payload.optString("note") }
                .ifBlank { "STRASSE Eventからファイル保存" },
        )
        attachSourceReference(repository, item.id, payload, values, fileUriText)
        PreviewWorker.enqueue(context)
        return Uri.parse("viastrasse-cabinet://open/${item.id}")
    }

    private fun addReferenceRequestedPayload(
        repository: CabinetRepository,
        payload: JSONObject,
        values: ContentValues?,
    ): Uri? {
        val itemId = values?.getAsString("itemId").orEmpty().ifBlank { payload.optString("itemId") }
        if (itemId.isBlank()) return null
        val sourceApp = values?.getAsString("sourceApp").orEmpty()
            .ifBlank { payload.optString("sourceApp") }
            .ifBlank { payload.optString("source_app") }
            .ifBlank { "STRASSE" }
        val sourceId = values?.getAsString("sourceId").orEmpty()
            .ifBlank { payload.optString("sourceId") }
            .ifBlank { payload.optString("source_id") }
            .ifBlank { "event-${System.currentTimeMillis()}" }
        repository.addReference(
            itemId = itemId,
            referenceType = values?.getAsString("referenceType").orEmpty()
                .ifBlank { payload.optString("referenceType") }
                .ifBlank { appKey(sourceApp) },
            sourceApp = sourceApp,
            sourceId = sourceId,
            title = values?.getAsString("title").orEmpty()
                .ifBlank { payload.optString("title") }
                .ifBlank { "$sourceApp reference" },
            uri = values?.getAsString("uri").orEmpty()
                .ifBlank { payload.optString("uri") }
                .ifBlank { "viastrasse-${appKey(sourceApp)}://open/$sourceId" },
            note = values?.getAsString("note").orEmpty()
                .ifBlank { payload.optString("note") }
                .ifBlank { "STRASSE Eventから参照追加" },
        )
        return Uri.parse("viastrasse-cabinet://open/$itemId")
    }

    private fun attachSourceReference(
        repository: CabinetRepository,
        itemId: String,
        payload: JSONObject,
        values: ContentValues?,
        fallbackUri: String,
    ) {
        val sourceApp = values?.getAsString("sourceApp").orEmpty()
            .ifBlank { payload.optString("sourceApp") }
            .ifBlank { payload.optString("source_app") }
        if (sourceApp.isBlank()) return
        val sourceId = values?.getAsString("sourceId").orEmpty()
            .ifBlank { payload.optString("sourceId") }
            .ifBlank { payload.optString("source_id") }
            .ifBlank { "event-${System.currentTimeMillis()}" }
        repository.addReference(
            itemId = itemId,
            referenceType = values?.getAsString("referenceType").orEmpty()
                .ifBlank { payload.optString("referenceType") }
                .ifBlank { appKey(sourceApp) },
            sourceApp = sourceApp,
            sourceId = sourceId,
            title = values?.getAsString("sourceTitle").orEmpty()
                .ifBlank { payload.optString("sourceTitle") }
                .ifBlank { payload.optString("title") }
                .ifBlank { "$sourceApp source" },
            uri = values?.getAsString("sourceUri").orEmpty()
                .ifBlank { payload.optString("sourceUri") }
                .ifBlank { payload.optString("uri") }
                .ifBlank { fallbackUri },
            note = values?.getAsString("referenceNote").orEmpty()
                .ifBlank { payload.optString("referenceNote") }
                .ifBlank { "STRASSE Eventから自動関連付け" },
        )
    }

    private fun appKey(sourceApp: String): String {
        val normalized = sourceApp.trim().lowercase()
        return when {
            normalized.startsWith("mail") -> "mail"
            normalized.startsWith("home") -> "home"
            normalized.startsWith("task") -> "task"
            normalized.startsWith("notify") -> "notify"
            normalized.startsWith("atelier") -> "atelier"
            normalized.startsWith("cabinet") -> "cabinet"
            else -> normalized
                .replace(" by viastrasse", "")
                .replace("-strasse", "")
                .replace(Regex("[^a-z0-9-]"), "-")
                .trim('-')
                .ifBlank { "external" }
        }
    }

    private fun queryOpenable(uri: Uri, payload: JSONObject, values: ContentValues?): EventFileInfo {
        val context = requireNotNull(context)
        var displayName = values?.getAsString("displayName").orEmpty()
            .ifBlank { payload.optString("displayName") }
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
            .ifBlank { "event-file" }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) displayName = cursor.getString(index).orEmpty().ifBlank { displayName }
            }
        }
        val sanitizedName = sanitizeFileName(displayName)
        val mimeType = values?.getAsString("mimeType").orEmpty()
            .ifBlank { payload.optString("mimeType") }
            .ifBlank { context.contentResolver.getType(uri).orEmpty() }
            .ifBlank { URLConnection.guessContentTypeFromName(sanitizedName).orEmpty() }
            .ifBlank { "application/octet-stream" }
        return EventFileInfo(sanitizedName, mimeType)
    }

    private fun uniqueDestination(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = if (extension.isBlank()) File(directory, "$base-$index") else File(directory, "$base-$index.$extension")
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "event-file" }
    }

    private data class EventFileInfo(
        val displayName: String,
        val mimeType: String,
    )

    companion object {
        private const val CONTRACT_VERSION = "1.0"
    }
}
