package jp.viastrasse.cabinetstrasse.data

import android.content.Context
import jp.viastrasse.cabinetstrasse.core.CabinetNative
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class CabinetRepository(context: Context) {
    private val databasePath = File(context.filesDir, "cabinet-strasse.db").absolutePath
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }

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

    fun processPreviewQueue(limit: Long = 20): PreviewProcessReport {
        return CabinetJsonParser.previewReport(CabinetNative.processPreviewQueueJson(databasePath, limit))
    }

    fun settings(): SettingsSnapshot {
        return CabinetJsonParser.settings(CabinetNative.settingsJson(databasePath))
    }

    fun exportBackup(): File {
        val json = CabinetNative.backupExportJson(databasePath)
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(backupDir, "cabinet-strasse-backup-$timestamp.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    fun updateItemFlags(itemId: String, isFavorite: Boolean, isUnsorted: Boolean): CabinetItemDetail {
        return CabinetJsonParser.detail(
            CabinetNative.updateItemFlagsJson(databasePath, itemId, isFavorite, isUnsorted),
        )
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
}
