package jp.viastrasse.cabinet.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.OpenableColumns
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.preview.PreviewWorker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

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
        require(uri.authority == EVENTS_AUTHORITY && uri.pathSegments.isEmpty()) { "Unsupported events URI." }
        val payload = values?.getAsString("payload_json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        val eventType = valueText(values, payload, "event_type")
        if (eventType.isBlank()) return null
        if (eventType == MAIL_ATTACHMENT_EVENT) {
            return saveMailAttachment(values, payload)
        }
        return runCatching {
            val repository = CabinetRepository(context)
            when (eventType) {
                "Task.ResultSaveRequested",
                "Home.QuickSaveRequested" -> saveRequestedPayload(repository, payload, values)
                "Atelier.ReferenceAddRequested",
                "Task.AttachmentAddRequested" -> addReferenceRequestedPayload(repository, payload, values)
                else -> null
            }
        }.getOrNull()
    }

    private fun saveMailAttachment(values: ContentValues?, payload: JSONObject): Uri {
        val context = requireNotNull(context)
        val caller = callingPackage
        require(caller == MAIL_PACKAGE) { "Mail attachment requests must come from $MAIL_PACKAGE." }

        val contractVersion = valueLong(values, payload, "contractVersion")?.toInt() ?: 1
        require(contractVersion == 1 || contractVersion == 2) {
            "Unsupported Mail attachment contract version: $contractVersion"
        }
        val fileUriText = requiredText(values, payload, "fileUri")
        val fileUri = Uri.parse(fileUriText)
        require(fileUri.scheme == "content") { "fileUri must use the content scheme." }
        require(
            context.checkUriPermission(
                fileUri,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED,
        ) { "The caller did not grant read access to fileUri." }

        val displayName = sanitizeFileName(requiredText(values, payload, "displayName"))
        val mimeType = requiredText(values, payload, "mimeType")
        val sourceApp = requiredText(values, payload, "sourceApp")
        require(sourceApp == "Mail by VIASTRASSE") { "sourceApp is not supported." }
        val sourceId = requiredText(values, payload, "sourceId")
        val title = requiredText(values, payload, "title")
        val referenceType = requiredText(values, payload, "referenceType")
        require(referenceType == "mail") { "referenceType must be mail." }

        val documentDate = valueText(values, payload, "documentDate")
        val dueDate = valueText(values, payload, "dueDate")
        require(isOptionalIsoDate(documentDate)) { "documentDate must use yyyy-MM-dd." }
        require(isOptionalIsoDate(dueDate)) { "dueDate must use yyyy-MM-dd." }
        val tags = parseTags(valueText(values, payload, "tagsJson"))
        val expectedSize = valueLong(values, payload, "sizeBytes")
        require(expectedSize == null || expectedSize >= 0L) { "sizeBytes must not be negative." }
        require(expectedSize == null || expectedSize <= MAX_ATTACHMENT_BYTES) { "The attachment exceeds the Cabinet size limit." }
        val expectedHash = valueText(values, payload, "sha256").lowercase()
        require(expectedHash.isBlank() || SHA256_PATTERN.matches(expectedHash)) { "sha256 is invalid." }

        val inboxDir = File(context.filesDir, "inbox").apply { mkdirs() }
        val temporary = File(inboxDir, ".incoming-${UUID.randomUUID()}")
        var destination: File? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            val identityToken = Binder.clearCallingIdentity()
            try {
                context.contentResolver.openInputStream(fileUri).use { input ->
                    requireNotNull(input) { "fileUri could not be opened." }
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copiedBytes += read
                            require(copiedBytes <= MAX_ATTACHMENT_BYTES) { "The attachment exceeds the Cabinet size limit." }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken)
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(expectedSize == null || expectedSize == copiedBytes) { "Attachment size verification failed." }
            require(expectedHash.isBlank() || expectedHash == actualHash) { "Attachment hash verification failed." }

            destination = uniqueDestination(inboxDir, displayName)
            require(temporary.renameTo(destination)) { "The attachment could not be moved into Cabinet storage." }
            val metadata = JSONObject()
                .put("contractVersion", contractVersion)
                .put("sourceApp", sourceApp)
                .put("sourceId", sourceId)
                .put("title", title)
                .put("referenceType", referenceType)
                .put("note", valueText(values, payload, "note"))
                .put("documentType", valueText(values, payload, "documentType"))
                .put("relatedParty", valueText(values, payload, "relatedParty"))
                .put("documentDate", documentDate)
                .put("dueDate", dueDate)
                .put("tags", JSONArray(tags))
                .put("sourceUri", valueText(values, payload, "sourceUri"))
                .put("emailSubject", valueText(values, payload, "emailSubject"))
                .put("emailSender", valueText(values, payload, "emailSender"))
                .put("emailReceivedAt", valueText(values, payload, "emailReceivedAt"))
                .put("emailAccount", valueText(values, payload, "emailAccount"))
                .put("messageId", valueText(values, payload, "messageId"))
            val result = CabinetRepository(context).registerMailAttachment(
                path = destination.absolutePath,
                displayName = displayName,
                mimeType = mimeType,
                size = copiedBytes,
                hash = actualHash,
                metadata = metadata,
            )
            if (!result.retainedFile) {
                destination.delete()
            }
            if (result.created) {
                PreviewWorker.enqueue(context)
            }
            return Uri.parse("viastrasse-cabinet://open/${result.itemId}")
        } catch (error: Throwable) {
            destination?.delete()
            throw error
        } finally {
            temporary.delete()
        }
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
        return value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f\u007f\\/:*?"<>|]"""), "_")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "event-file" }
    }

    private fun requiredText(values: ContentValues?, payload: JSONObject, key: String): String {
        return valueText(values, payload, key).also { require(it.isNotBlank()) { "$key is required." } }
    }

    private fun valueText(values: ContentValues?, payload: JSONObject, key: String): String {
        return values?.getAsString(key).orEmpty().ifBlank { payload.optString(key) }.trim()
    }

    private fun valueLong(values: ContentValues?, payload: JSONObject, key: String): Long? {
        if (values?.containsKey(key) == true) {
            return values.getAsLong(key)
        }
        return if (payload.has(key) && !payload.isNull(key)) payload.optLong(key) else null
    }

    private fun parseTags(tagsJson: String): List<String> {
        if (tagsJson.isBlank()) return emptyList()
        val array = JSONArray(tagsJson)
        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val tag = array.optString(index).trim()
                if (tag.isNotBlank() && seen.add(tag.lowercase())) add(tag)
            }
        }
    }

    private fun isOptionalIsoDate(value: String): Boolean {
        return value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess
    }

    private data class EventFileInfo(
        val displayName: String,
        val mimeType: String,
    )

    companion object {
        private const val CONTRACT_VERSION = "2"
        private const val EVENTS_AUTHORITY = "jp.viastrasse.cabinet.provider.events"
        private const val MAIL_ATTACHMENT_EVENT = "Mail.AttachmentSaveRequested"
        private const val MAIL_PACKAGE = "jp.viastrasse.mail"
        private const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        private const val MAX_FILE_NAME_LENGTH = 180
        private val SHA256_PATTERN = Regex("""[0-9a-f]{64}""")
    }
}
