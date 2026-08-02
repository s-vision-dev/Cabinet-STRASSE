package jp.viastrasse.cabinet.data

import android.content.Context
import jp.viastrasse.cabinet.core.CabinetNative
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CabinetRepository(context: Context) {
    private val databasePath = resolveDatabaseFile(context).absolutePath
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
    private val archiveDir = File(context.filesDir, "archives").apply { mkdirs() }
    private val extractDir = File(context.filesDir, "extracted").apply { mkdirs() }

    fun dashboard(): CabinetDashboard {
        return CabinetJsonParser.dashboard(CabinetNative.dashboardJson(databasePath))
    }

    fun search(query: String): SearchResponse {
        return CabinetJsonParser.search(CabinetNative.searchJson(databasePath, query))
    }

    fun mode(mode: String): ModeResponse {
        return CabinetJsonParser.mode(CabinetNative.modeJson(databasePath, mode))
    }

    fun detail(itemId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.itemDetailJson(databasePath, itemId))
    }

    fun detailUnlocked(itemId: String, pin: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.itemDetailUnlockedJson(databasePath, itemId, pin))
    }

    fun processPreviewQueue(limit: Long = 20): PreviewProcessReport {
        return CabinetJsonParser.previewReport(CabinetNative.processPreviewQueueJson(databasePath, limit))
    }

    fun settings(): SettingsSnapshot {
        return CabinetJsonParser.settings(CabinetNative.settingsJson(databasePath))
    }

    fun updateStorageProvider(
        providerId: String,
        configuration: StorageProviderConfiguration,
    ): SettingsSnapshot {
        val configurationJson = JSONObject()
            .put("accountName", configuration.accountName)
            .put("endpointUrl", configuration.endpointUrl)
            .put("username", configuration.username)
            .put("remoteRoot", configuration.remoteRoot)
            .put("domain", configuration.domain)
            .put("cachePolicy", configuration.cachePolicy)
        return CabinetJsonParser.settings(
            CabinetNative.updateStorageProviderJson(databasePath, providerId, configurationJson.toString()),
        )
    }

    fun createSmartFolder(title: String, preset: String): SettingsSnapshot {
        return CabinetJsonParser.settings(CabinetNative.createSmartFolderJson(databasePath, title, preset))
    }

    fun eventsJson(limit: Long = 100): String {
        return CabinetNative.eventsJson(databasePath, limit)
    }

    fun duplicateReport(): DuplicateReport {
        return CabinetJsonParser.duplicateReport(CabinetNative.duplicateReportJson(databasePath))
    }

    fun exportBackup(): File {
        val json = CabinetNative.backupExportJson(databasePath)
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(backupDir, "$BACKUP_FILE_PREFIX$timestamp.json")
        file.writeText(json, Charsets.UTF_8)
        pruneBackups(maxGenerations = 10)
        return file
    }

    fun importBackup(backupJson: String): SettingsSnapshot {
        val result = JSONObject(CabinetNative.backupImportJson(databasePath, backupJson))
        result.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return settings()
    }

    /**
     * 最新のローカルバックアップを復元する。
     * バックアップが1件も無い場合は [NoSuchElementException] を投げる。
     * 文言は Context を持つ UI 層で組み立てる（このクラスは表示文言を持たない）。
     */
    fun restoreLatestLocalBackup(): SettingsSnapshot {
        val backup = backupDir
            .listFiles { file -> isCabinetBackupFile(file) }
            ?.maxByOrNull { it.lastModified() }
            ?: throw NoSuchElementException("No local backup file exists.")
        return importBackup(backup.readText(Charsets.UTF_8))
    }

    fun updateItemFlags(itemId: String, isFavorite: Boolean, isUnsorted: Boolean): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.updateItemFlagsJson(databasePath, itemId, isFavorite, isUnsorted),
        )
    }

    fun markOpened(itemId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.markItemOpenedJson(databasePath, itemId))
    }

    fun addTag(itemId: String, tagName: String, color: String = "#607D8B"): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.addTagToItemJson(databasePath, itemId, tagName, color),
        )
    }

    fun removeTag(itemId: String, tagId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.removeTagFromItemJson(databasePath, itemId, tagId))
    }

    fun addToCollection(itemId: String, collectionTitle: String): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.addItemToCollectionJson(databasePath, itemId, collectionTitle),
        )
    }

    fun removeFromCollection(itemId: String, collectionId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.removeItemFromCollectionJson(databasePath, itemId, collectionId),
        )
    }

    fun moveToTrash(itemId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.moveItemToTrashJson(databasePath, itemId))
    }

    fun restoreFromTrash(itemId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.restoreItemFromTrashJson(databasePath, itemId))
    }

    fun deletePermanently(itemId: String): Boolean {
        val json = CabinetNative.deleteItemPermanentlyJson(databasePath, itemId)
        return json.contains("\"deleted\":true")
    }

    fun duplicateItem(itemId: String): CabinetItemSummary {
        val json = CabinetNative.duplicateItemJson(databasePath, itemId)
        return CabinetJsonParser.search("""{"query":"","results":[$json]}""").results.first()
    }

    fun renameItem(itemId: String, newDisplayName: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.renameItemJson(databasePath, itemId, newDisplayName))
    }

    fun addVersion(
        itemId: String,
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        note: String,
    ): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.addVersionJson(databasePath, itemId, path, displayName, mimeType, size, note),
        )
    }

    fun setCurrentVersion(itemId: String, versionId: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.setCurrentVersionJson(databasePath, itemId, versionId))
    }

    fun addReference(
        itemId: String,
        referenceType: String,
        sourceApp: String,
        sourceId: String,
        title: String,
        uri: String,
        note: String,
    ): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.addReferenceJson(
                databasePath,
                itemId,
                referenceType,
                sourceApp,
                sourceId,
                title,
                uri,
                note,
            ),
        )
    }

    fun addMemo(itemId: String, body: String, isProtected: Boolean): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.addMemoJson(databasePath, itemId, body, isProtected))
    }

    fun addOcrText(itemId: String, body: String, source: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.addOcrTextJson(databasePath, itemId, body, source))
    }

    fun setItemThumbnail(itemId: String, thumbnailPath: String): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.setItemThumbnailJson(databasePath, itemId, thumbnailPath))
    }

    fun setSecurityPin(pin: String): SettingsSnapshot {
        return CabinetJsonParser.settings(CabinetNative.setSecurityPinJson(databasePath, pin))
    }

    fun verifySecurityPin(pin: String): Boolean {
        val result = JSONObject(CabinetNative.verifySecurityPinJson(databasePath, pin))
        result.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return result.optBoolean("verified")
    }

    fun setItemProtected(itemId: String, isProtected: Boolean): CabinetItemDetail {
        return CabinetJsonParser.detail(CabinetNative.setItemProtectedJson(databasePath, itemId, isProtected))
    }

    fun createZipFromItem(detail: CabinetItemDetail): CabinetItemSummary {
        val source = File(detail.path)
        require(source.exists()) { "Source file does not exist." }
        val zipName = "${source.nameWithoutExtension.ifBlank { detail.item.displayName }}.zip"
        val zipFile = uniqueFile(archiveDir, zipName)
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(source.name))
            source.inputStream().buffered().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
        return registerFile(
            path = zipFile.absolutePath,
            displayName = zipFile.name,
            mimeType = "application/zip",
            size = zipFile.length(),
            sourceKind = "archive",
            note = "Cabinetで圧縮作成",
        )
    }

    fun extractZipItem(detail: CabinetItemDetail): List<CabinetItemSummary> {
        val source = File(detail.path)
        require(source.exists()) { "ZIP file does not exist." }
        val destinationDir = uniqueDirectory(extractDir, source.nameWithoutExtension.ifBlank { "zip" })
        destinationDir.mkdirs()
        val results = mutableListOf<CabinetItemSummary>()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val safeName = sanitizeZipEntry(entry.name)
                if (!entry.isDirectory && safeName.isNotBlank()) {
                    val outputFile = uniqueFile(destinationDir, safeName.substringAfterLast('/'))
                    outputFile.outputStream().buffered().use { output -> zip.copyTo(output) }
                    results += registerFile(
                        path = outputFile.absolutePath,
                        displayName = outputFile.name,
                        // 決め打ちにすると解凍後の資料が種別不明のまま残るため、
                        // ファイル名から推定する。
                        mimeType = URLConnection.guessContentTypeFromName(outputFile.name)
                            ?: "application/octet-stream",
                        size = outputFile.length(),
                        sourceKind = "zip-extract",
                        note = "CabinetでZIP解凍",
                    )
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return results
    }

    fun registerUrl(url: String, title: String, note: String): CabinetItemSummary {
        val json = CabinetNative.registerUrlJson(databasePath, url, title, note)
        return CabinetJsonParser.search("""{"query":"","results":[$json]}""").results.first()
    }

    fun registerFile(
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        sourceKind: String,
        note: String,
    ): CabinetItemSummary {
        val json = CabinetNative.registerFileJson(
            databasePath,
            path,
            displayName,
            mimeType,
            size,
            sourceKind,
            note,
        )
        return CabinetJsonParser.search("""{"query":"","results":[$json]}""").results.first()
    }

    fun registerMailAttachment(
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        hash: String,
        metadata: JSONObject,
    ): MailAttachmentRegistrationResult {
        val result = JSONObject(
            CabinetNative.registerMailAttachmentJson(
                databasePath,
                path,
                displayName,
                mimeType,
                size,
                hash,
                metadata.toString(),
            ),
        )
        result.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return MailAttachmentRegistrationResult(
            itemId = result.getString("item_id"),
            created = result.optBoolean("created"),
            retainedFile = result.optBoolean("retained_file"),
        )
    }

    fun registerRemoteFile(
        providerId: String,
        remoteFileId: String,
        remotePath: String,
        displayName: String,
        mimeType: String,
        size: Long,
        webUrl: String,
        note: String,
    ): CabinetItemSummary {
        val json = CabinetNative.registerRemoteFileJson(
            databasePath,
            providerId,
            remoteFileId,
            remotePath,
            displayName,
            mimeType,
            size,
            webUrl,
            note,
        )
        return CabinetJsonParser.search("""{"query":"","results":[$json]}""").results.first()
    }

    fun markRemoteFileCached(itemId: String, cachedFilePath: String, size: Long): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.markRemoteFileCachedJson(databasePath, itemId, cachedFilePath, size),
        )
    }

    private fun uniqueFile(directory: File, displayName: String): File =
        CabinetFiles.uniqueDestination(directory, displayName)

    private fun uniqueDirectory(directory: File, displayName: String): File =
        CabinetFiles.uniqueDirectory(directory, displayName)

    private fun pruneBackups(maxGenerations: Int) {
        backupDir.listFiles { file -> isCabinetBackupFile(file) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxGenerations)
            ?.forEach { it.delete() }
    }

    private fun sanitizeZipEntry(name: String): String {
        return name.replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun isCabinetBackupFile(file: File): Boolean {
        return file.isFile &&
            file.name.endsWith(".json") &&
            (file.name.startsWith(BACKUP_FILE_PREFIX) || file.name.startsWith(LEGACY_BACKUP_FILE_PREFIX))
    }

    private fun resolveDatabaseFile(context: Context): File {
        val current = File(context.filesDir, DATABASE_FILE_NAME)
        val legacy = File(context.filesDir, LEGACY_DATABASE_FILE_NAME)
        if (!current.exists() && legacy.exists()) {
            val moved = legacy.renameTo(current)
            if (!moved && current.parentFile?.canWrite() == true) {
                legacy.copyTo(current, overwrite = false)
            }
        }
        return current
    }

    companion object {
        private const val DATABASE_FILE_NAME = "cabinet.db"
        private const val LEGACY_DATABASE_FILE_NAME = "cabinet-strasse.db"
        private const val BACKUP_FILE_PREFIX = "cabinet-backup-"
        private const val LEGACY_BACKUP_FILE_PREFIX = "cabinet-strasse-backup-"
    }
}
