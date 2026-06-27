package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import jp.viastrasse.cabinetstrasse.preview.PreviewWorker
import jp.viastrasse.cabinetstrasse.ui.CabinetDashboardView
import java.io.File

class MainActivity : Activity() {
    private lateinit var repository: CabinetRepository
    private lateinit var dashboardView: CabinetDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CabinetRepository(applicationContext)
        dashboardView = CabinetDashboardView(this)
        setContentView(dashboardView)
        renderDashboard()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderDashboard()
    }

    private fun renderDashboard() {
        runCatching {
            repository.dashboard()
        }.onSuccess { dashboard ->
            dashboardView.render(
                dashboard,
                { mode -> openMode(mode.name) },
                ::openDetail,
                ::openFolderPicker,
            )
        }.onFailure { error ->
            dashboardView.renderError(error.message ?: "Cabinet core の初期化に失敗しました。")
        }
    }

    @Deprecated("Used for platform SAF interop without ActivityX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            importFolder(uri)
        }
    }

    private fun openMode(mode: String) {
        if (mode == "Search") {
            openSearch("")
            return
        }
        if (mode == "Settings") {
            openSettings()
            return
        }
        runCatching {
            repository.mode(mode)
        }.onSuccess {
            dashboardView.renderMode(it, ::renderDashboard, ::openDetail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSearch(query: String) {
        runCatching {
            repository.search(query)
        }.onSuccess {
            dashboardView.renderSearch(it, ::renderDashboard, ::openSearch, ::openDetail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "検索できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDetail(itemId: String) {
        runCatching {
            repository.detail(itemId)
        }.onSuccess { detail ->
            dashboardView.renderDetail(
                detail = detail,
                onBack = ::renderDashboard,
                onProcessPreview = {
                    processPreviewQueue()
                    openDetail(itemId)
                },
                onToggleFavorite = {
                    updateItemFlags(
                        itemId = itemId,
                        isFavorite = !detail.item.isFavorite,
                        isUnsorted = detail.item.isUnsorted,
                    )
                },
                onMarkSorted = {
                    updateItemFlags(
                        itemId = itemId,
                        isFavorite = detail.item.isFavorite,
                        isUnsorted = false,
                    )
                },
                onAddTag = {
                    addTag(itemId, "参考資料")
                },
                onAddCollection = {
                    addToCollection(itemId, "未整理から確認")
                },
                onMoveTrash = {
                    moveToTrash(itemId)
                },
                onOpen = {
                    openViewer(detail.path, detail.item.mimeType, detail.item.title)
                },
                onDuplicate = {
                    duplicateItem(itemId)
                },
                onRename = {
                    renameItem(itemId, renamedName(detail.item.displayName))
                },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "詳細を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateItemFlags(itemId: String, isFavorite: Boolean, isUnsorted: Boolean) {
        runCatching {
            repository.updateItemFlags(itemId, isFavorite, isUnsorted)
        }.onSuccess {
            dashboardView.renderDetail(
                detail = it,
                onBack = ::renderDashboard,
                onProcessPreview = {
                    processPreviewQueue()
                    openDetail(itemId)
                },
                onToggleFavorite = {
                    updateItemFlags(itemId, !it.item.isFavorite, it.item.isUnsorted)
                },
                onMarkSorted = {
                    updateItemFlags(itemId, it.item.isFavorite, false)
                },
                onAddTag = {
                    addTag(itemId, "参考資料")
                },
                onAddCollection = {
                    addToCollection(itemId, "未整理から確認")
                },
                onMoveTrash = {
                    moveToTrash(itemId)
                },
                onOpen = {
                    openViewer(it.path, it.item.mimeType, it.item.title)
                },
                onDuplicate = {
                    duplicateItem(itemId)
                },
                onRename = {
                    renameItem(itemId, renamedName(it.item.displayName))
                },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "資料を更新できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTag(itemId: String, tagName: String) {
        runCatching {
            repository.addTag(itemId, tagName)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "タグを追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToCollection(itemId: String, collectionTitle: String) {
        runCatching {
            repository.addToCollection(itemId, collectionTitle)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Collectionへ追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveToTrash(itemId: String) {
        runCatching {
            repository.moveToTrash(itemId)
        }.onSuccess {
            Toast.makeText(this, "ゴミ箱へ移動しました", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ゴミ箱へ移動できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun duplicateItem(itemId: String) {
        runCatching {
            repository.duplicateItem(itemId)
        }.onSuccess { item ->
            Toast.makeText(this, "複製しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "複製できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameItem(itemId: String, newDisplayName: String) {
        runCatching {
            repository.renameItem(itemId, newDisplayName)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "名前変更できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renamedName(displayName: String): String {
        val dotIndex = displayName.lastIndexOf('.')
        return if (dotIndex > 0) {
            val stem = displayName.substring(0, dotIndex)
            val extension = displayName.substring(dotIndex)
            "$stem-renamed$extension"
        } else {
            "$displayName-renamed"
        }
    }

    private fun openViewer(path: String, mimeType: String, title: String) {
        startActivity(
            Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_PATH, path)
                putExtra(ViewerActivity.EXTRA_MIME_TYPE, mimeType)
                putExtra(ViewerActivity.EXTRA_TITLE, title)
            },
        )
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_TREE)
    }

    private fun importFolder(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
        var imported = 0
        root.listFiles()
            .filter { it.isFile }
            .forEach { document ->
                runCatching {
                    val displayName = sanitizeFileName(document.name ?: "document")
                    val destination = uniqueDestination(inboxDir, displayName)
                    contentResolver.openInputStream(document.uri).use { input ->
                        requireNotNull(input) { "Input stream is null." }
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                    repository.registerFile(
                        path = destination.absolutePath,
                        displayName = displayName,
                        mimeType = document.type ?: "application/octet-stream",
                        size = destination.length(),
                        sourceKind = "saf-folder",
                        note = "SAFフォルダから取り込み",
                    )
                }.onSuccess {
                    imported += 1
                }
            }
        if (imported > 0) {
            PreviewWorker.enqueue(applicationContext)
        }
        Toast.makeText(this, "${imported}件を取り込みました", Toast.LENGTH_SHORT).show()
        renderDashboard()
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
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "document" }
    }

    private fun processPreviewQueue() {
        runCatching {
            PreviewWorker.enqueue(applicationContext)
            repository.processPreviewQueue()
        }.onSuccess { report ->
            Toast.makeText(
                this,
                "Preview processed: ${report.processed}, remaining: ${report.remaining}",
                Toast.LENGTH_SHORT,
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "プレビュー処理に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        runCatching {
            repository.settings()
        }.onSuccess { settings ->
            dashboardView.renderSettings(
                settings = settings,
                onBack = ::renderDashboard,
                onExportBackup = ::exportBackup,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "設定を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportBackup() {
        runCatching {
            repository.exportBackup()
        }.onSuccess { file ->
            Toast.makeText(this, "Backup: ${file.name}", Toast.LENGTH_SHORT).show()
            openSettings()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "バックアップに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_OPEN_TREE = 2401
    }
}
