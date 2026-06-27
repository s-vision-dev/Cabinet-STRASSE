package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import jp.viastrasse.cabinetstrasse.preview.PreviewWorker
import jp.viastrasse.cabinetstrasse.ui.CabinetDashboardView
import jp.viastrasse.cabinetstrasse.ui.LocalFileEntry
import jp.viastrasse.cabinetstrasse.watch.FolderWatchWorker
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var repository: CabinetRepository
    private lateinit var dashboardView: CabinetDashboardView
    private var pendingVersionItemId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CabinetRepository(applicationContext)
        dashboardView = CabinetDashboardView(this)
        setContentView(dashboardView)
        FolderWatchWorker.enqueuePeriodic(applicationContext)
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data
        if (uri?.scheme == "strasse" && uri.host == "cabinet") {
            when (uri.pathSegments.firstOrNull()) {
                "open" -> uri.pathSegments.getOrNull(1)?.let(::openDetail) ?: renderDashboard()
                "search" -> openSearch(uri.getQueryParameter("q").orEmpty())
                "inbox" -> openMode("Inbox")
                "collection" -> openMode("Collection")
                "add" -> handleAddDeepLink(uri)
                else -> renderDashboard()
            }
        } else {
            renderDashboard()
        }
    }

    private fun handleAddDeepLink(uri: Uri) {
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.isBlank()) {
            openFolderPicker()
            return
        }
        runCatching {
            repository.registerUrl(
                url = url,
                title = uri.getQueryParameter("title").orEmpty().ifBlank { url },
                note = uri.getQueryParameter("note").orEmpty().ifBlank { "Deep Linkから保存" },
            )
        }.onSuccess { item ->
            Toast.makeText(this, "URLを保存しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "URLを保存できませんでした", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
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
            return
        }
        if (requestCode == REQUEST_OPEN_BACKUP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importBackup(uri)
            return
        }
        if (requestCode == REQUEST_ADD_VERSION && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importVersionFile(uri)
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
        if (mode == "Explorer") {
            openLocalExplorer(explorerRoots().first())
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

    private fun explorerRoots(): List<File> {
        return buildList {
            add(filesDir)
            getExternalFilesDirs(null).filterNotNull().forEach { add(it) }
            add(File(filesDir, "inbox").apply { mkdirs() })
            add(File(filesDir, "archives").apply { mkdirs() })
            add(File(filesDir, "extracted").apply { mkdirs() })
        }.distinctBy { it.absolutePath }
    }

    private fun openLocalExplorer(directory: File) {
        runCatching {
            require(directory.exists() && directory.isDirectory) { "フォルダを開けませんでした" }
            directory.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map(::toLocalFileEntry)
                .orEmpty()
        }.onSuccess { entries ->
            val rootPaths = explorerRoots().map { it.absolutePath }.toSet()
            val parent = directory.parentFile?.takeIf { directory.absolutePath !in rootPaths }
            dashboardView.renderLocalExplorer(
                currentDirectory = directory,
                entries = entries,
                onBack = ::renderDashboard,
                onParent = parent?.let { { openLocalExplorer(it) } },
                onOpenDirectory = ::openLocalExplorer,
                onOpenFile = { file -> openViewer(file.absolutePath, mimeTypeFor(file), file.name) },
                onRegisterFile = { file -> registerLocalExplorerFile(file, directory) },
                onCopyFile = { file -> copyExplorerFileToInbox(file, directory) },
                onMoveFile = { file -> moveExplorerFileToInbox(file, directory) },
                onCreateFolder = { createExplorerFolder(directory) },
                onDeleteFile = { file -> deleteExplorerFile(file, directory) },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Explorerを開けませんでした", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun toLocalFileEntry(file: File): LocalFileEntry {
        val updated = Instant.ofEpochMilli(file.lastModified())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return LocalFileEntry(
            file = file,
            name = file.name.ifBlank { file.absolutePath },
            isDirectory = file.isDirectory,
            kind = if (file.isDirectory) "folder" else mimeTypeFor(file),
            sizeLabel = if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else readableSize(file.length()),
            updatedLabel = updated,
        )
    }

    private fun registerLocalExplorerFile(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists() && file.isFile) { "登録対象ファイルが見つかりません" }
            repository.registerFile(
                path = file.absolutePath,
                displayName = file.name,
                mimeType = mimeTypeFor(file),
                size = file.length(),
                sourceKind = "local-explorer",
                note = "Explorerから登録",
            )
        }.onSuccess { item ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "登録しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "登録できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createExplorerFolder(directory: File) {
        runCatching {
            val folder = uniqueDirectory(directory, "New Folder")
            check(folder.mkdirs()) { "フォルダを作成できませんでした" }
            folder
        }.onSuccess {
            Toast.makeText(this, "フォルダを作成しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(directory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "フォルダを作成できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyExplorerFileToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists() && file.isFile) { "コピー対象ファイルが見つかりません" }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            file.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, "Inboxへコピーしました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "コピーできませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveExplorerFileToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists() && file.isFile) { "移動対象ファイルが見つかりません" }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            if (!file.renameTo(destination)) {
                file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(file.delete()) { "移動元ファイルを削除できませんでした" }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, "Inboxへ移動しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "移動できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteExplorerFile(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists() && file.isFile) { "削除対象ファイルが見つかりません" }
            check(file.delete()) { "ファイルを削除できませんでした" }
        }.onSuccess {
            Toast.makeText(this, "削除しました: ${file.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "削除できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeFor(file: File): String {
        return URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }

    private fun readableSize(size: Long): String {
        if (size < 1024L) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return "%.1f %s".format(value, units[unitIndex])
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
                onShare = {
                    shareItem(detail)
                },
                onDuplicate = {
                    duplicateItem(itemId)
                },
                onRename = {
                    renameItem(itemId, renamedName(detail.item.displayName))
                },
                onAddVersion = {
                    openVersionFilePicker(itemId)
                },
                onSetCurrentVersion = { versionId ->
                    setCurrentVersion(itemId, versionId)
                },
                onCreateZip = {
                    createZip(detail)
                },
                onExtractZip = {
                    extractZip(detail)
                },
                onAddMailReference = {
                    addReference(itemId, "mail", "Mail-STRASSE", "manual-mail", "関連メール", "strasse://mail/open/manual-mail")
                },
                onAddTaskReference = {
                    addReference(itemId, "task", "Task-STRASSE", "manual-task", "関連Task", "strasse://task/open/manual-task")
                },
                onAddAtelierReference = {
                    addReference(itemId, "atelier", "Atelier-STRASSE", "manual-atelier", "関連Atelier", "strasse://atelier/open/manual-atelier")
                },
                onAddProtectedMemo = {
                    addProtectedMemo(itemId)
                },
                onProtectItem = {
                    protectItem(itemId)
                },
                onRestoreFromTrash = {
                    restoreFromTrash(itemId)
                },
                onDeletePermanently = {
                    deletePermanently(itemId)
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
                onShare = {
                    shareItem(it)
                },
                onDuplicate = {
                    duplicateItem(itemId)
                },
                onRename = {
                    renameItem(itemId, renamedName(it.item.displayName))
                },
                onAddVersion = {
                    openVersionFilePicker(itemId)
                },
                onSetCurrentVersion = { versionId ->
                    setCurrentVersion(itemId, versionId)
                },
                onCreateZip = {
                    createZip(it)
                },
                onExtractZip = {
                    extractZip(it)
                },
                onAddMailReference = {
                    addReference(itemId, "mail", "Mail-STRASSE", "manual-mail", "関連メール", "strasse://mail/open/manual-mail")
                },
                onAddTaskReference = {
                    addReference(itemId, "task", "Task-STRASSE", "manual-task", "関連Task", "strasse://task/open/manual-task")
                },
                onAddAtelierReference = {
                    addReference(itemId, "atelier", "Atelier-STRASSE", "manual-atelier", "関連Atelier", "strasse://atelier/open/manual-atelier")
                },
                onAddProtectedMemo = {
                    addProtectedMemo(itemId)
                },
                onProtectItem = {
                    protectItem(itemId)
                },
                onRestoreFromTrash = {
                    restoreFromTrash(itemId)
                },
                onDeletePermanently = {
                    deletePermanently(itemId)
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

    private fun restoreFromTrash(itemId: String) {
        runCatching {
            repository.restoreFromTrash(itemId)
        }.onSuccess {
            Toast.makeText(this, "復元しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "復元できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePermanently(itemId: String) {
        runCatching {
            repository.deletePermanently(itemId)
        }.onSuccess {
            Toast.makeText(this, "完全削除しました", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "完全削除できませんでした", Toast.LENGTH_SHORT).show()
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

    private fun openVersionFilePicker(itemId: String) {
        pendingVersionItemId = itemId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ADD_VERSION)
    }

    private fun importVersionFile(uri: Uri) {
        val itemId = pendingVersionItemId.also { pendingVersionItemId = null }
        if (itemId.isNullOrBlank()) {
            Toast.makeText(this, "バージョン追加先が見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val displayName = sanitizeFileName(displayNameForUri(uri))
            val versionsDir = File(filesDir, "versions").apply { mkdirs() }
            val destination = uniqueDestination(versionsDir, displayName)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "ファイルを開けませんでした" }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            repository.addVersion(
                itemId = itemId,
                path = destination.absolutePath,
                displayName = destination.name,
                mimeType = contentResolver.getType(uri) ?: mimeTypeFor(destination),
                size = destination.length(),
                note = "詳細画面から追加",
            )
        }.onSuccess {
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "新しいバージョンを追加しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "バージョンを追加できませんでした", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }
    }

    private fun setCurrentVersion(itemId: String, versionId: String) {
        runCatching {
            repository.setCurrentVersion(itemId, versionId)
        }.onSuccess {
            Toast.makeText(this, "最新版を切り替えました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "最新版を切り替えられませんでした", Toast.LENGTH_SHORT).show()
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

    private fun shareItem(detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        runCatching {
            if (detail.item.sourceKind == "url") {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, detail.path)
                    putExtra(Intent.EXTRA_TITLE, detail.item.title)
                }
                startActivity(Intent.createChooser(intent, detail.item.title))
                return
            }
            val file = File(detail.path)
            require(file.exists()) { "共有対象ファイルが見つかりません" }
            val uri = FileProvider.getUriForFile(
                this,
                "jp.viastrasse.cabinetstrasse.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = detail.item.mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, detail.item.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, detail.item.title))
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "共有できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createZip(detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        runCatching {
            repository.createZipFromItem(detail)
        }.onSuccess { item ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "ZIPを作成しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ZIPを作成できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractZip(detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        runCatching {
            repository.extractZipItem(detail)
        }.onSuccess { items ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "${items.size}件を解凍しました", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ZIPを解凍できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addReference(
        itemId: String,
        referenceType: String,
        sourceApp: String,
        sourceId: String,
        title: String,
        uri: String,
    ) {
        runCatching {
            repository.addReference(
                itemId = itemId,
                referenceType = referenceType,
                sourceApp = sourceApp,
                sourceId = sourceId,
                title = title,
                uri = uri,
                note = "Cabinet詳細画面から手動追加",
            )
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "参照を追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addProtectedMemo(itemId: String) {
        runCatching {
            repository.addMemo(itemId, "保護メモ: パスワードや機密補足をここへ保存", true)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "保護メモを追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun protectItem(itemId: String) {
        runCatching {
            repository.setItemProtected(itemId, true)
        }.onSuccess {
            Toast.makeText(this, "保護領域へ追加しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "保護領域へ追加できませんでした", Toast.LENGTH_SHORT).show()
        }
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
        FolderWatchWorker.rememberFolder(applicationContext, treeUri, root)
        FolderWatchWorker.enqueuePeriodic(applicationContext)
        FolderWatchWorker.enqueueNow(applicationContext)
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

    private fun uniqueDirectory(directory: File, displayName: String): File {
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$displayName-$index")
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "document" }
    }

    private fun displayNameForUri(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "version-file"
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
            repository.settings() to repository.duplicateReport()
        }.onSuccess { (settings, duplicateReport) ->
            dashboardView.renderSettings(
                settings = settings,
                duplicateReport = duplicateReport,
                onBack = ::renderDashboard,
                onExportBackup = ::exportBackup,
                onImportBackup = ::openBackupPicker,
                onSetPin = ::showSetPinDialog,
                onVerifyPin = ::showVerifyPinDialog,
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

    private fun showSetPinDialog() {
        showPinDialog(
            title = "PINを設定",
            positiveLabel = "設定",
        ) { pin ->
            runCatching {
                repository.setSecurityPin(pin)
            }.onSuccess {
                Toast.makeText(this, "PINを設定しました", Toast.LENGTH_SHORT).show()
                openSettings()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "PINを設定できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVerifyPinDialog() {
        showPinDialog(
            title = "PINを確認",
            positiveLabel = "確認",
        ) { pin ->
            runCatching {
                repository.verifySecurityPin(pin)
            }.onSuccess { verified ->
                Toast.makeText(this, if (verified) "PINは一致しました" else "PINが一致しません", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "PINを確認できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPinDialog(title: String, positiveLabel: String, onPin: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel) { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openBackupPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        startActivityForResult(intent, REQUEST_OPEN_BACKUP)
    }

    private fun importBackup(uri: Uri) {
        runCatching {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
                requireNotNull(reader) { "バックアップファイルを開けませんでした" }.readText()
            }
            repository.importBackup(json)
        }.onSuccess {
            Toast.makeText(this, "バックアップを復元しました", Toast.LENGTH_SHORT).show()
            openSettings()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "バックアップ復元に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_OPEN_TREE = 2401
        private const val REQUEST_OPEN_BACKUP = 2402
        private const val REQUEST_ADD_VERSION = 2403
    }
}
