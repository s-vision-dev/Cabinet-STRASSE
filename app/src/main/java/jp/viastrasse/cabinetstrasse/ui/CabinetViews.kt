package jp.viastrasse.cabinetstrasse.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import jp.viastrasse.cabinetstrasse.data.CabinetCollectionSummary
import jp.viastrasse.cabinetstrasse.data.CabinetDashboard
import jp.viastrasse.cabinetstrasse.data.CabinetItemDetail
import jp.viastrasse.cabinetstrasse.data.CabinetItemSummary
import jp.viastrasse.cabinetstrasse.data.DuplicateReport
import jp.viastrasse.cabinetstrasse.data.ModeResponse
import jp.viastrasse.cabinetstrasse.data.SearchResponse
import jp.viastrasse.cabinetstrasse.data.SettingsSnapshot
import jp.viastrasse.cabinetstrasse.data.SmartFolderSummary
import jp.viastrasse.cabinetstrasse.theme.CabinetColors

class CabinetDashboardView(context: Context) : ScrollView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(28))
    }

    init {
        setBackgroundColor(CabinetColors.AppBackground)
        addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun render(
        dashboard: CabinetDashboard,
        onModeSelected: (CabinetMode) -> Unit,
        onItemSelected: (String) -> Unit,
        onImportFolder: () -> Unit,
    ) {
        content.removeAllViews()
        content.addView(title("Cabinet-STRASSE"))
        content.addView(subtitle("The Cabinet / Powered by VIASTRASSE"))
        content.addView(space(14))
        content.addView(
            modeGrid(
                listOf(
                    CabinetMode("Explorer", dashboard.explorerCount, "通常ファイルと取り込み資料"),
                    CabinetMode("Library", dashboard.libraryCount, "種別から資料を参照"),
                    CabinetMode("Collection", dashboard.collectionCount, "テーマ単位の資料群"),
                    CabinetMode("Inbox", dashboard.inboxCount, "未整理の一時保管"),
                    CabinetMode("Search", dashboard.favoriteCount, "検索とお気に入り"),
                    CabinetMode("Settings", dashboard.version, "Provider / Backup / Security"),
                ),
                onModeSelected,
            ),
        )
        content.addView(command("SAFフォルダから取り込む", onImportFolder))
        content.addView(section("最近の資料"))
        dashboard.recentItems.forEach { content.addView(itemRow(it, onItemSelected)) }
        content.addView(section("Collection"))
        dashboard.collections.forEach { content.addView(collectionRow(it)) }
        content.addView(section("Smart Folder"))
        dashboard.smartFolders.forEach { content.addView(smartFolderRow(it)) }
    }

    fun renderError(message: String) {
        content.removeAllViews()
        content.addView(title("Cabinet-STRASSE"))
        content.addView(errorText(message))
    }

    fun renderMode(mode: ModeResponse, onBack: () -> Unit, onItemSelected: (String) -> Unit) {
        content.removeAllViews()
        content.addView(command("← Cabinet", onBack))
        content.addView(title(mode.title))
        content.addView(subtitle(mode.mode))
        if (mode.items.isNotEmpty()) {
            content.addView(section("資料"))
            mode.items.forEach { content.addView(itemRow(it, onItemSelected)) }
        }
        if (mode.collections.isNotEmpty()) {
            content.addView(section("Collection"))
            mode.collections.forEach { content.addView(collectionRow(it)) }
        }
        if (mode.smartFolders.isNotEmpty()) {
            content.addView(section("Smart Folder"))
            mode.smartFolders.forEach { content.addView(smartFolderRow(it)) }
        }
    }

    fun renderSearch(
        response: SearchResponse,
        onBack: () -> Unit,
        onSearch: (String) -> Unit,
        onItemSelected: (String) -> Unit,
    ) {
        content.removeAllViews()
        content.addView(command("← Cabinet", onBack))
        content.addView(title("Search"))
        val input = android.widget.EditText(context).apply {
            setText(response.query)
            hint = "Cabinet内を検索"
            setTextColor(CabinetColors.TextPrimary)
            setHintTextColor(CabinetColors.TextSecondary)
            setSingleLine(true)
            setBackgroundColor(CabinetColors.SurfaceAlt)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(input)
        content.addView(command("検索", onClick = { onSearch(input.text.toString()) }))
        content.addView(section("検索結果"))
        response.results.forEach { content.addView(itemRow(it, onItemSelected)) }
    }

    fun renderDetail(
        detail: CabinetItemDetail,
        onBack: () -> Unit,
        onProcessPreview: () -> Unit,
        onToggleFavorite: () -> Unit,
        onMarkSorted: () -> Unit,
        onAddTag: () -> Unit,
        onAddCollection: () -> Unit,
        onMoveTrash: () -> Unit,
        onOpen: () -> Unit,
        onShare: () -> Unit,
        onDuplicate: () -> Unit,
        onRename: () -> Unit,
        onCreateZip: () -> Unit,
        onExtractZip: () -> Unit,
        onAddMailReference: () -> Unit,
        onAddTaskReference: () -> Unit,
        onAddAtelierReference: () -> Unit,
        onAddProtectedMemo: () -> Unit,
        onProtectItem: () -> Unit,
    ) {
        content.removeAllViews()
        content.addView(command("← 戻る", onBack))
        content.addView(title(detail.item.title))
        content.addView(subtitle("${detail.item.mimeType} / ${detail.item.sourceKind}"))
        content.addView(panel {
            addView(label("保存場所", 13, true, CabinetColors.Accent))
            addView(label(detail.path, 12, false, CabinetColors.TextSecondary))
            addView(label("Hash", 13, true, CabinetColors.Accent))
            addView(label(detail.hash, 11, false, CabinetColors.TextSecondary))
            if (detail.note.isNotBlank()) {
                addView(label("メモ", 13, true, CabinetColors.Accent))
                addView(label(detail.note, 13, false, CabinetColors.TextSecondary))
            }
        })
        content.addView(command("開く", onOpen))
        content.addView(command("共有", onShare))
        content.addView(command("複製", onDuplicate))
        content.addView(command("名前変更", onRename))
        content.addView(command("ZIPを作成", onCreateZip))
        if (detail.item.mimeType == "application/zip" || detail.item.displayName.endsWith(".zip")) {
            content.addView(command("ZIPを解凍", onExtractZip))
        }
        content.addView(section("Tags"))
        if (detail.tags.isEmpty()) {
            content.addView(label("タグはまだありません", 13, false, CabinetColors.TextSecondary))
        } else {
            content.addView(label(detail.tags.joinToString(" / ") { it.name }, 13, false, CabinetColors.TextSecondary))
        }
        content.addView(section("Collections"))
        if (detail.collections.isEmpty()) {
            content.addView(label("Collectionには未登録です", 13, false, CabinetColors.TextSecondary))
        } else {
            detail.collections.forEach { collection -> content.addView(collectionRow(collection)) }
        }
        content.addView(command(if (detail.item.isFavorite) "お気に入りを解除" else "お気に入りに追加", onToggleFavorite))
        if (detail.item.isUnsorted) {
            content.addView(command("未整理を解除", onMarkSorted))
        }
        content.addView(command("タグを追加", onAddTag))
        content.addView(command("Collectionへ追加", onAddCollection))
        content.addView(command("Mail参照を追加", onAddMailReference))
        content.addView(command("Task参照を追加", onAddTaskReference))
        content.addView(command("Atelier参照を追加", onAddAtelierReference))
        content.addView(command("保護領域へ追加", onProtectItem))
        content.addView(command("保護メモを追加", onAddProtectedMemo))
        content.addView(command("プレビューキューを処理", onProcessPreview))
        content.addView(command("ゴミ箱へ移動", onMoveTrash))
        content.addView(section("Preview"))
        detail.previews.forEach { preview ->
            content.addView(panel {
                addView(label("${preview.previewType} / ${preview.status}", 14, true))
                addView(label(preview.summaryText.ifBlank { "プレビュー本文は未生成です" }, 13, false, CabinetColors.TextSecondary))
            })
        }
        content.addView(section("Version"))
        if (detail.versions.isEmpty()) {
            content.addView(label("この資料にはバージョン情報がありません", 13, false, CabinetColors.TextSecondary))
        } else {
            detail.versions.forEach { version ->
                content.addView(panel {
                    addView(label("v${version.versionNumber} ${version.displayName}", 14, true))
                    addView(label(if (version.isCurrent) "Current" else "Old", 12, false, CabinetColors.TextSecondary))
                    if (version.note.isNotBlank()) addView(label(version.note, 12, false, CabinetColors.TextSecondary))
                })
            }
        }
        content.addView(section("Reference"))
        if (detail.references.isEmpty()) {
            content.addView(label("関連参照はまだありません", 13, false, CabinetColors.TextSecondary))
        } else {
            detail.references.forEach { reference ->
                content.addView(panel {
                    addView(label("${reference.referenceType} / ${reference.sourceApp}", 14, true))
                    addView(label(reference.title, 13, false, CabinetColors.TextSecondary))
                    addView(label(reference.uri, 12, false, CabinetColors.TextSecondary))
                })
            }
        }
    }

    fun renderSettings(
        settings: SettingsSnapshot,
        duplicateReport: DuplicateReport,
        onBack: () -> Unit,
        onExportBackup: () -> Unit,
        onImportBackup: () -> Unit,
        onSetPin: () -> Unit,
        onVerifyPin: () -> Unit,
    ) {
        content.removeAllViews()
        content.addView(command("← Cabinet", onBack))
        content.addView(title("Settings"))
        content.addView(subtitle("Storage Provider / Backup / Security"))
        content.addView(section("Backup"))
        content.addView(panel {
            addView(label("Items: ${settings.backup.itemCount}", 14, true))
            addView(label("Collections: ${settings.backup.collectionCount}", 13, false, CabinetColors.TextSecondary))
            addView(label("Tags: ${settings.backup.tagCount}", 13, false, CabinetColors.TextSecondary))
            addView(label("Previews: ${settings.backup.previewCount}", 13, false, CabinetColors.TextSecondary))
            addView(label("Last snapshot: ${settings.backup.exportedAt}", 12, false, CabinetColors.TextSecondary))
        })
        content.addView(command("バックアップを書き出す", onExportBackup))
        content.addView(command("バックアップを復元", onImportBackup))
        content.addView(section("Security"))
        content.addView(panel {
            addView(label("PIN: ${if (settings.security.pinEnabled) "有効" else "未設定"}", 14, true))
            addView(label("Protected items: ${settings.security.protectedItemCount}", 13, false, CabinetColors.TextSecondary))
            addView(label("Protected memos: ${settings.security.protectedMemoCount}", 13, false, CabinetColors.TextSecondary))
        })
        content.addView(command("PINを設定", onSetPin))
        content.addView(command("PINを確認", onVerifyPin))
        content.addView(section("Duplicate"))
        if (duplicateReport.groups.isEmpty()) {
            content.addView(label("完全一致の重複候補はありません", 13, false, CabinetColors.TextSecondary))
        } else {
            duplicateReport.groups.forEach { group ->
                content.addView(panel {
                    addView(label("${group.items.size} items / ${group.size} bytes", 14, true))
                    addView(label(group.hash, 11, false, CabinetColors.TextSecondary))
                    group.items.forEach { item ->
                        addView(label(item.displayName, 12, false, CabinetColors.TextSecondary))
                    }
                })
            }
        }
        content.addView(section("Storage Provider"))
        settings.providers.forEach { provider ->
            content.addView(panel {
                addView(label(provider.displayName, 15, true))
                addView(label("${provider.providerType} / ${provider.authType}", 12, false, CabinetColors.TextSecondary))
                addView(label(provider.connectionStatus, 12, false, CabinetColors.TextSecondary))
            })
        }
    }

    private fun modeGrid(modes: List<CabinetMode>, onModeSelected: (CabinetMode) -> Unit): LinearLayout {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        modes.chunked(2).forEach { rowModes ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            rowModes.forEach { mode ->
                row.addView(modeTile(mode, onModeSelected))
            }
            if (rowModes.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    private fun modeTile(mode: CabinetMode, onModeSelected: (CabinetMode) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CabinetColors.Brand)
            isClickable = true
            setOnClickListener { onModeSelected(mode) }
            addView(label(mode.name, 18, true))
            addView(label(mode.count.toString(), 24, true))
            addView(label(mode.description, 12, false, CabinetColors.TextSecondary))
            layoutParams = LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }

    private fun itemRow(item: CabinetItemSummary, onItemSelected: (String) -> Unit): View {
        return panel {
            isClickable = true
            setOnClickListener { onItemSelected(item.id) }
            addView(label(item.title, 16, true))
            addView(label("${item.displayName} / ${item.mimeType} / ${item.sourceKind}", 12, false, CabinetColors.TextSecondary))
            addView(label(item.summaryText, 13, false, CabinetColors.TextSecondary))
        }
    }

    private fun collectionRow(collection: CabinetCollectionSummary): View {
        return panel {
            addView(label(collection.title, 15, true))
            addView(label("${collection.itemCount} items", 12, false, CabinetColors.TextSecondary))
        }
    }

    private fun smartFolderRow(folder: SmartFolderSummary): View {
        return panel {
            addView(label(folder.title, 15, true))
            addView(label("${folder.itemCount} items / ${folder.condition}", 12, false, CabinetColors.TextSecondary))
        }
    }

    private fun panel(block: LinearLayout.() -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CabinetColors.Surface)
            block()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp(4), 0, dp(6))
            }
        }
    }

    private fun title(text: String) = label(text, 28, true).apply {
        gravity = Gravity.START
    }

    private fun subtitle(text: String) = label(text, 13, false, CabinetColors.Accent)

    private fun section(text: String) = label(text, 18, true).apply {
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun errorText(text: String) = label(text, 14, false, CabinetColors.Warning).apply {
        setPadding(0, dp(16), 0, 0)
    }

    private fun command(text: String, onClick: () -> Unit): TextView {
        return label(text, 15, true, CabinetColors.Accent).apply {
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun label(
        text: String,
        sp: Int,
        bold: Boolean,
        color: Int = CabinetColors.TextPrimary,
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun space(height: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

data class CabinetMode(
    val name: String,
    val count: Long,
    val description: String,
)
