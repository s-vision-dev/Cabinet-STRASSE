package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import jp.viastrasse.cabinetstrasse.backup.BackupWorker
import jp.viastrasse.cabinetstrasse.data.CabinetItemSummary
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import jp.viastrasse.cabinetstrasse.data.StorageProviderAccountSummary
import jp.viastrasse.cabinetstrasse.preview.OcrTextRecognizer
import jp.viastrasse.cabinetstrasse.preview.PreviewWorker
import jp.viastrasse.cabinetstrasse.preview.ThumbnailGenerator
import jp.viastrasse.cabinetstrasse.ui.CabinetDashboardView
import jp.viastrasse.cabinetstrasse.ui.DeviceFileEntry
import jp.viastrasse.cabinetstrasse.ui.DocumentFileEntry
import jp.viastrasse.cabinetstrasse.ui.FileListDisplayPreference
import jp.viastrasse.cabinetstrasse.ui.FileListOptions
import jp.viastrasse.cabinetstrasse.ui.FileListSort
import jp.viastrasse.cabinetstrasse.ui.LocalFileEntry
import jp.viastrasse.cabinetstrasse.watch.FolderWatchWorker
import java.io.File
import java.net.URL
import java.net.URLConnection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var repository: CabinetRepository
    private lateinit var dashboardView: CabinetDashboardView
    private var pendingVersionItemId: String? = null
    private var isDashboardVisible: Boolean = true
    private var systemBackCallback: Any? = null
    private var pendingPublicDirectoryType: String? = null
    private var fileListOptions: FileListOptions = FileListOptions()
    private var currentDocumentParents: List<DocumentFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CabinetRepository(applicationContext)
        dashboardView = CabinetDashboardView(this)
        setContentView(dashboardView)
        registerSystemBackCallback()
        FolderWatchWorker.enqueuePeriodic(applicationContext)
        BackupWorker.enqueuePeriodic(applicationContext)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = systemBackCallback as? OnBackInvokedCallback
            if (callback != null) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        val pendingDirectory = pendingPublicDirectoryType
        if (pendingDirectory != null && canReadPublicDirectories()) {
            pendingPublicDirectoryType = null
            openPublicDirectory(pendingDirectory)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use explicit in-app navigation until this Activity migrates to OnBackPressedDispatcher.")
    override fun onBackPressed() {
        if (handleSystemBack()) {
            return
        }
        super.onBackPressed()
    }

    private fun registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                if (!handleSystemBack()) {
                    moveTaskToBack(true)
                }
            }
            systemBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        }
    }

    private fun handleSystemBack(): Boolean {
        if (!isDashboardVisible) {
            renderDashboard()
            return true
        }
        return false
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data
        if (uri?.scheme == "strasse" && uri.host == "cabinet") {
            when (uri.pathSegments.firstOrNull()) {
                "open" -> uri.pathSegments.getOrNull(1)?.let(::openDetail) ?: renderDashboard()
                "search" -> openSearch(uri.getQueryParameter("q").orEmpty())
                "inbox" -> openMode("Inbox")
                "collection" -> uri.pathSegments.getOrNull(1)
                    ?.let { openMode("collection:$it") }
                    ?: openMode("Collection")
                "smart" -> uri.pathSegments.getOrNull(1)
                    ?.let { openMode("smart:$it") }
                    ?: openMode("Smart Folder")
                "reference" -> handleReferenceDeepLink(uri)
                "add" -> handleAddDeepLink(uri)
                else -> renderDashboard()
            }
        } else {
            renderDashboard()
        }
    }

    private fun handleAddDeepLink(uri: Uri) {
        val url = uri.getQueryParameter("url").orEmpty()
        val fileUri = uri.getQueryParameter("fileUri").orEmpty()
        if (fileUri.isNotBlank()) {
            handleAddFileDeepLink(uri, Uri.parse(fileUri))
            return
        }
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

    private fun handleAddFileDeepLink(requestUri: Uri, fileUri: Uri) {
        runCatching {
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val displayName = sanitizeFileName(
                requestUri.getQueryParameter("displayName").orEmpty()
                    .ifBlank { displayNameForUri(fileUri) },
            )
            val destination = uniqueDestination(inboxDir, displayName)
            when (fileUri.scheme) {
                "content" -> {
                    contentResolver.openInputStream(fileUri).use { input ->
                        requireNotNull(input) { "ファイルを開けませんでした" }
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    destination
                }
                "file", null -> {
                    val source = if (fileUri.scheme == "file") File(requireNotNull(fileUri.path)) else File(fileUri.toString())
                    require(source.exists() && source.isFile) { "ファイルが見つかりません" }
                    source.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    destination
                }
                else -> error("対応していないURIです: ${fileUri.scheme}")
            }
        }.onSuccess { file ->
            runCatching {
                repository.registerFile(
                    path = file.absolutePath,
                    displayName = file.name,
                    mimeType = requestUri.getQueryParameter("mimeType").orEmpty().ifBlank { mimeTypeFor(file) },
                    size = file.length(),
                    sourceKind = requestUri.getQueryParameter("sourceKind").orEmpty().ifBlank { "deeplink-file" },
                    note = requestUri.getQueryParameter("note").orEmpty().ifBlank { "Deep Linkから保存" },
                )
            }.onSuccess { item ->
                PreviewWorker.enqueue(applicationContext)
                Toast.makeText(this, "ファイルを保存しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
                openDetail(item.id)
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "ファイルを登録できませんでした", Toast.LENGTH_SHORT).show()
                renderDashboard()
            }
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ファイルを保存できませんでした", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun handleReferenceDeepLink(uri: Uri) {
        if (uri.pathSegments.getOrNull(1) != "add") {
            renderDashboard()
            return
        }
        val itemId = uri.getQueryParameter("itemId").orEmpty()
        if (itemId.isBlank()) {
            Toast.makeText(this, "参照追加先のitemIdが必要です", Toast.LENGTH_SHORT).show()
            renderDashboard()
            return
        }
        val referenceType = uri.getQueryParameter("type").orEmpty().ifBlank { "external" }
        val sourceApp = uri.getQueryParameter("sourceApp").orEmpty().ifBlank { "STRASSE" }
        val sourceId = uri.getQueryParameter("sourceId").orEmpty().ifBlank { "deeplink-${System.currentTimeMillis()}" }
        val title = uri.getQueryParameter("title").orEmpty().ifBlank { "$sourceApp 参照" }
        val referenceUri = uri.getQueryParameter("uri").orEmpty().ifBlank { "strasse://${sourceApp.lowercase()}/open/$sourceId" }
        val note = uri.getQueryParameter("note").orEmpty().ifBlank { "Deep Linkから参照追加" }
        runCatching {
            repository.addReference(
                itemId = itemId,
                referenceType = referenceType,
                sourceApp = sourceApp,
                sourceId = sourceId,
                title = title,
                uri = referenceUri,
                note = note,
            )
        }.onSuccess {
            Toast.makeText(this, "参照を追加しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "参照を追加できませんでした", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun renderDashboard() {
        isDashboardVisible = true
        runCatching {
            repository.dashboard()
        }.onSuccess { dashboard ->
            dashboardView.render(
                dashboard,
                { mode -> openMode(mode.name) },
                ::openDetail,
                ::openFolderPicker,
                { openSearch("") },
                ::toggleFavoriteFromSummary,
                ::moveSummaryToTrash,
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
        if (requestCode == REQUEST_OPEN_SD_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            rememberSdTreeUri(uri)
            openDocumentTreeRoot(uri)
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
        if (mode == "SDCard") {
            openSdCardExplorer()
            return
        }
        if (mode == "Downloads") {
            openPublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return
        }
        if (mode == "Documents") {
            openPublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return
        }
        if (mode == "Pictures") {
            openPublicDirectory(Environment.DIRECTORY_PICTURES)
            return
        }
        if (mode == "Movies") {
            openPublicDirectory(Environment.DIRECTORY_MOVIES)
            return
        }
        if (mode == "Music") {
            openPublicDirectory(Environment.DIRECTORY_MUSIC)
            return
        }
        runCatching {
            repository.mode(mode)
        }.onSuccess {
            isDashboardVisible = false
            val displayedMode = it.copy(items = applyCabinetItemListOptions(it.items))
            dashboardView.renderMode(
                mode = displayedMode,
                displayMode = displayModePreference(),
                fontPreference = fileListDisplayPreference(),
                listOptions = fileListOptions,
                showItemList = it.items.isNotEmpty() || fileListOptions.hasActiveFilter,
                onListOptionsChanged = { options ->
                    fileListOptions = options
                    openMode(mode)
                },
                onBack = ::renderDashboard,
                onItemSelected = ::openDetail,
                onCollectionSelected = { collection -> openMode("collection:${collection.id}") },
                onSmartFolderSelected = { folder -> openMode("smart:${folder.id}") },
                onToggleFavorite = ::toggleFavoriteFromSummary,
                onMoveTrash = ::moveSummaryToTrash,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyCabinetItemListOptions(items: List<CabinetItemSummary>): List<CabinetItemSummary> {
        val cutoff = fileListOptions.periodDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        return items
            .asSequence()
            .filter { item ->
                val updatedAt = cabinetItemUpdatedAtMillis(item)
                cutoff == null || updatedAt == null || updatedAt >= cutoff
            }
            .filter { item ->
                fileListOptions.nameQuery.isBlank() ||
                    item.displayName.contains(fileListOptions.nameQuery, ignoreCase = true) ||
                    item.title.contains(fileListOptions.nameQuery, ignoreCase = true)
            }
            .filter { item ->
                fileListOptions.extensionQuery.isBlank() ||
                    cabinetItemExtension(item).equals(fileListOptions.extensionQuery.trimStart('.'), ignoreCase = true)
            }
            .toList()
            .let { sortCabinetItems(it) }
    }

    private fun sortCabinetItems(items: List<CabinetItemSummary>): List<CabinetItemSummary> {
        return when (fileListOptions.sort) {
            FileListSort.DATE_DESC -> items.sortedWith(compareByDescending<CabinetItemSummary> { cabinetItemUpdatedAtMillis(it) ?: Long.MIN_VALUE })
            FileListSort.DATE_ASC -> items.sortedWith(compareBy<CabinetItemSummary> { cabinetItemUpdatedAtMillis(it) ?: Long.MAX_VALUE })
            FileListSort.NAME_ASC -> items.sortedBy { it.displayName.ifBlank { it.title }.lowercase() }
            FileListSort.NAME_DESC -> items.sortedByDescending { it.displayName.ifBlank { it.title }.lowercase() }
            FileListSort.EXT_ASC -> items.sortedWith(compareBy<CabinetItemSummary> { cabinetItemExtension(it).lowercase() }.thenBy { it.displayName.ifBlank { it.title }.lowercase() })
            FileListSort.EXT_DESC -> items.sortedWith(compareByDescending<CabinetItemSummary> { cabinetItemExtension(it).lowercase() }.thenBy { it.displayName.ifBlank { it.title }.lowercase() })
        }
    }

    private fun cabinetItemExtension(item: CabinetItemSummary): String {
        return extensionOf(item.displayName.ifBlank { item.title })
    }

    private fun cabinetItemUpdatedAtMillis(item: CabinetItemSummary): Long? {
        val value = item.updatedAt.trim()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse {
                runCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }
                    .getOrElse {
                        runCatching {
                            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        }.getOrNull()
                    }
            }
    }

    private fun explorerRoots(): List<File> {
        return buildList {
            add(filesDir)
            getExternalFilesDirs(null).filterNotNull().forEach { add(it) }
            add(Environment.getExternalStorageDirectory())
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            add(File(filesDir, "inbox").apply { mkdirs() })
            add(File(filesDir, "archives").apply { mkdirs() })
            add(File(filesDir, "extracted").apply { mkdirs() })
        }.filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    private fun openPublicDirectory(directoryType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openLocalExplorer(Environment.getExternalStoragePublicDirectory(directoryType))
            return
        }
        val publicDirectory = Environment.getExternalStoragePublicDirectory(directoryType)
        if (canReadPublicDirectories()) {
            openLocalExplorer(publicDirectory)
            return
        }
        val title = when (directoryType) {
            Environment.DIRECTORY_DOWNLOADS -> "Downloads"
            Environment.DIRECTORY_DOCUMENTS -> "Documents"
            Environment.DIRECTORY_PICTURES -> "Pictures"
            Environment.DIRECTORY_MOVIES -> "Movies"
            Environment.DIRECTORY_MUSIC -> "Music"
            else -> directoryType
        }
        val entries = applyDeviceFileListOptions(queryDeviceFiles(directoryType))
        if (entries.isEmpty()) {
            promptAllFilesAccess(directoryType)
        }
        isDashboardVisible = false
        dashboardView.renderDeviceFiles(
            title = title,
            location = directoryType,
            entries = entries,
            displayMode = displayModePreference(),
            fontPreference = fileListDisplayPreference(),
            listOptions = fileListOptions,
            onListOptionsChanged = { options ->
                fileListOptions = options
                openPublicDirectory(directoryType)
            },
            onBack = ::renderDashboard,
            onOpenFile = { entry -> openDeviceFile(entry, entries) },
            onRegisterFile = { entry -> registerDeviceFile(entry, title) },
            onOpenLocation = ::openDeviceFileLocation,
        )
    }

    private fun canReadPublicDirectories(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun openSdCardExplorer() {
        val roots = savedSdTreeUris().mapNotNull { saved ->
            val uri = Uri.parse(saved)
            DocumentFile.fromTreeUri(this, uri)?.takeIf { it.isDirectory }?.let { document ->
                uri to document
            }
        }
        storeSdTreeUris(roots.map { it.first.toString() })
        renderSdCardRootList(roots.map { (_, document) -> toDocumentFileEntry(document).asSdRootEntry() })
    }

    private fun openSdCardPicker() {
        AlertDialog.Builder(this)
            .setMessage(
                "Androidの制約により、ストレージのルートは選択できません。\n" +
                    "内部ストレージまたはSDカード内のフォルダを選択してください。",
            )
            .setPositiveButton("フォルダを選択") { _, _ -> launchSdCardPicker() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun launchSdCardPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_SD_TREE)
    }

    private fun renderSdCardRootList(roots: List<DocumentFileEntry>) {
        val entries = roots.sortedBy { it.name.lowercase() }
        isDashboardVisible = false
        dashboardView.renderDocumentTree(
            title = "SDカード",
            location = "追加済みフォルダ",
            entries = entries,
            displayMode = displayModePreference(),
            fontPreference = fileListDisplayPreference(),
            listOptions = fileListOptions,
            sectionTitle = "追加済みフォルダ",
            emptyMessage = "追加済みフォルダはありません。フォルダを追加してください。",
            showListControls = false,
            onListOptionsChanged = { options ->
                fileListOptions = options
                openSdCardExplorer()
            },
            onBack = ::renderDashboard,
            onParent = null,
            onOpenDirectory = { entry -> openDocumentDirectory(entry.document, emptyList()) },
            onOpenFile = {},
            onRegisterFile = {},
            onChooseRoot = ::openSdCardPicker,
        )
    }

    private fun openDocumentTreeRoot(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: error("SDカードを開けませんでした")
        openDocumentDirectory(root, emptyList())
    }

    private fun openDocumentDirectory(directory: DocumentFile, parents: List<DocumentFile>) {
        require(directory.isDirectory) { "フォルダを開けませんでした" }
        currentDocumentParents = parents
        val rawEntries = directory.listFiles()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            .map(::toDocumentFileEntry)
        val entries = applyDocumentFileListOptions(rawEntries)
        isDashboardVisible = false
        dashboardView.renderDocumentTree(
            title = "SDカード",
            location = directory.name ?: directory.uri.toString(),
            entries = entries,
            displayMode = displayModePreference(),
            fontPreference = fileListDisplayPreference(),
            listOptions = fileListOptions,
            onListOptionsChanged = { options ->
                fileListOptions = options
                openDocumentDirectory(directory, parents)
            },
            onBack = ::renderDashboard,
            onParent = parents.lastOrNull()?.let { parent ->
                { openDocumentDirectory(parent, parents.dropLast(1)) }
            } ?: ::openSdCardExplorer,
            parentLabel = if (parents.isEmpty()) "追加済みフォルダ一覧へ戻る" else "親フォルダへ移動",
            onOpenDirectory = { entry -> openDocumentDirectory(entry.document, parents + directory) },
            onOpenFile = { entry ->
                openViewer(
                    path = entry.uri,
                    mimeType = entry.mimeType.ifBlank { "*/*" },
                    title = entry.name,
                    navigationItems = documentViewerNavigation(entries),
                )
            },
            onRegisterFile = { entry -> registerDocumentFile(entry, directory) },
            onChooseRoot = ::openSdCardPicker,
        )
    }

    private fun DocumentFileEntry.asSdRootEntry(): DocumentFileEntry {
        val fallback = document.uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { name }
        val displayName = name.ifBlank { fallback }.ifBlank { "選択フォルダ" }
        return copy(
            name = displayName,
            sizeLabel = "フォルダ",
            updatedLabel = if (updatedAtMillis > 0L) updatedLabel else "追加済み",
        )
    }

    private fun savedSdTreeUris(): List<String> {
        val prefs = appPrefs()
        val current = prefs.getString(KEY_SD_TREE_URIS, null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        val legacy = prefs.getString(KEY_SD_TREE_URI, null).orEmpty().trim()
        return (current + legacy).filter { it.isNotBlank() }.distinct()
    }

    private fun rememberSdTreeUri(uri: Uri) {
        storeSdTreeUris((savedSdTreeUris() + uri.toString()).distinct())
    }

    private fun storeSdTreeUris(uris: List<String>) {
        appPrefs().edit()
            .putString(KEY_SD_TREE_URIS, uris.distinct().joinToString("\n"))
            .remove(KEY_SD_TREE_URI)
            .apply()
    }

    private fun toDocumentFileEntry(document: DocumentFile): DocumentFileEntry {
        val name = document.name.orEmpty().ifBlank { document.uri.lastPathSegment ?: "document" }
        val updatedAtMillis = document.lastModified()
        val updated = if (updatedAtMillis > 0L) {
            Instant.ofEpochMilli(updatedAtMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } else {
            "更新日時なし"
        }
        val isDirectory = document.isDirectory
        val size = if (isDirectory) document.listFiles().size.toLong() else document.length().coerceAtLeast(0L)
        val mimeType = if (isDirectory) {
            "folder"
        } else {
            document.type.orEmpty().ifBlank {
                mimeTypeForExtension(extensionOf(name)) ?: "application/octet-stream"
            }
        }
        return DocumentFileEntry(
            document = document,
            uri = document.uri.toString(),
            name = name,
            isDirectory = isDirectory,
            mimeType = mimeType,
            size = size,
            sizeLabel = if (isDirectory) "$size items" else readableSize(size),
            updatedLabel = updated,
            updatedAtMillis = updatedAtMillis,
            extension = extensionOf(name),
        )
    }

    private fun applyDocumentFileListOptions(entries: List<DocumentFileEntry>): List<DocumentFileEntry> {
        val cutoff = fileListOptions.periodDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        return entries
            .asSequence()
            .filter { cutoff == null || it.updatedAtMillis <= 0L || it.updatedAtMillis >= cutoff }
            .filter { fileListOptions.nameQuery.isBlank() || it.name.contains(fileListOptions.nameQuery, ignoreCase = true) }
            .filter { fileListOptions.extensionQuery.isBlank() || it.extension.equals(fileListOptions.extensionQuery.trimStart('.'), ignoreCase = true) }
            .toList()
            .let { sortDocumentEntries(it) }
    }

    private fun sortDocumentEntries(entries: List<DocumentFileEntry>): List<DocumentFileEntry> {
        return when (fileListOptions.sort) {
            FileListSort.DATE_DESC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenByDescending { it.updatedAtMillis })
            FileListSort.DATE_ASC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenBy { it.updatedAtMillis })
            FileListSort.NAME_ASC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            FileListSort.NAME_DESC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenByDescending { it.name.lowercase() })
            FileListSort.EXT_ASC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenBy { it.extension.lowercase() }.thenBy { it.name.lowercase() })
            FileListSort.EXT_DESC -> entries.sortedWith(compareBy<DocumentFileEntry> { !it.isDirectory }.thenByDescending { it.extension.lowercase() }.thenBy { it.name.lowercase() })
        }
    }

    private fun registerDocumentFile(entry: DocumentFileEntry, currentDirectory: DocumentFile) {
        runCatching {
            require(!entry.isDirectory) { "登録対象ファイルが見つかりません" }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, sanitizeFileName(entry.name))
            contentResolver.openInputStream(Uri.parse(entry.uri)).use { input ->
                requireNotNull(input) { "ファイルを開けませんでした" }
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            repository.registerFile(
                path = destination.absolutePath,
                displayName = destination.name,
                mimeType = entry.mimeType,
                size = destination.length(),
                sourceKind = "saf-explorer",
                note = "SDカード/外部ストレージから登録",
            )
        }.onSuccess {
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "登録しました: ${entry.name}", Toast.LENGTH_SHORT).show()
            openDocumentDirectory(currentDirectory, currentDocumentParents)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "登録できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptAllFilesAccess(directoryType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        pendingPublicDirectoryType = directoryType
        AlertDialog.Builder(this)
            .setTitle("端末フォルダへのアクセス")
            .setMessage("DownloadsやDocumentsをファイルマネージャーとして表示するには、Cabinet-STRASSEにすべてのファイルへのアクセスを許可してください。")
            .setPositiveButton("設定を開く") { _, _ ->
                val uri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                runCatching { startActivity(intent) }
                    .onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openLocalExplorer(directory: File) {
        runCatching {
            require(directory.exists() && directory.isDirectory) { "フォルダを開けませんでした" }
            val rawEntries = directory.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map(::toLocalFileEntry)
                .orEmpty()
            applyLocalFileListOptions(rawEntries)
        }.onSuccess { entries ->
            isDashboardVisible = false
            val rootPaths = explorerRoots().map { it.absolutePath }.toSet()
            val parent = directory.parentFile?.takeIf { directory.absolutePath !in rootPaths }
            dashboardView.renderLocalExplorer(
                currentDirectory = directory,
                entries = entries,
                displayMode = displayModePreference(),
                fontPreference = fileListDisplayPreference(),
                listOptions = fileListOptions,
                onListOptionsChanged = { options ->
                    fileListOptions = options
                    openLocalExplorer(directory)
                },
                onBack = ::renderDashboard,
                onParent = parent?.let { { openLocalExplorer(it) } },
                onOpenDirectory = ::openLocalExplorer,
                onOpenFile = { file ->
                    openViewer(
                        path = file.absolutePath,
                        mimeType = mimeTypeFor(file),
                        title = file.name,
                        navigationItems = localViewerNavigation(entries),
                    )
                },
                onRegisterFile = { file -> registerLocalExplorerFile(file, directory) },
                onRenameEntry = { file -> showRenameExplorerEntryDialog(file, directory) },
                onCopyEntry = { file -> copyExplorerEntryToInbox(file, directory) },
                onMoveEntry = { file -> moveExplorerEntryToInbox(file, directory) },
                onDuplicateEntry = { file -> duplicateExplorerEntry(file, directory) },
                onCreateFolder = { showCreateExplorerFolderDialog(directory) },
                onDeleteEntry = { file -> deleteExplorerEntry(file, directory) },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Explorerを開けませんでした", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun queryDeviceFiles(directoryType: String): List<DeviceFileEntry> {
        val externalVolume = MediaStore.VOLUME_EXTERNAL
        val collection = when (directoryType) {
            Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(externalVolume)
            Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(externalVolume)
            Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(externalVolume)
            else -> MediaStore.Files.getContentUri(externalVolume)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = when (directoryType) {
            Environment.DIRECTORY_DOWNLOADS -> "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            Environment.DIRECTORY_DOCUMENTS -> "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            else -> null
        }
        val selectionArgs = when (directoryType) {
            Environment.DIRECTORY_DOWNLOADS -> arrayOf("%${Environment.DIRECTORY_DOWNLOADS}%")
            Environment.DIRECTORY_DOCUMENTS -> arrayOf("%${Environment.DIRECTORY_DOCUMENTS}%")
            else -> null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        return runCatching {
            val entries = mutableListOf<DeviceFileEntry>()
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { "untitled" }
                    val mimeType = cursor.getString(mimeIndex).orEmpty().ifBlank { "application/octet-stream" }
                    val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    val modifiedSeconds = cursor.getLong(modifiedIndex)
                    val updated = Instant.ofEpochSecond(modifiedSeconds)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    val location = cursor.getString(pathIndex).orEmpty().ifBlank { directoryType }
                    entries += DeviceFileEntry(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        mimeType = mimeType,
                        size = size,
                        sizeLabel = readableSize(size),
                        updatedLabel = updated,
                        location = location,
                        updatedAtMillis = modifiedSeconds * 1000L,
                        extension = extensionOf(name),
                    )
                    if (entries.size >= 100) break
                }
            }
            entries
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: "端末ファイルを取得できませんでした", Toast.LENGTH_SHORT).show()
            emptyList()
        }
    }

    private fun openDeviceFile(entry: DeviceFileEntry, navigationEntries: List<DeviceFileEntry> = listOf(entry)) {
        runCatching {
            openViewer(
                path = entry.uri,
                mimeType = entry.mimeType.ifBlank { "*/*" },
                title = entry.name,
                navigationItems = deviceViewerNavigation(navigationEntries),
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ファイルを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDeviceFile(entry: DeviceFileEntry, sourceKind: String) {
        runCatching {
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, sanitizeFileName(entry.name))
            contentResolver.openInputStream(Uri.parse(entry.uri)).use { input ->
                requireNotNull(input) { "ファイルを開けませんでした" }
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            repository.registerFile(
                path = destination.absolutePath,
                displayName = destination.name,
                mimeType = entry.mimeType,
                size = destination.length(),
                sourceKind = "device-$sourceKind",
                note = "端末ファイルマネージャーから登録",
            )
        }.onSuccess { item ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, "登録しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "登録できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDeviceFileLocation(entry: DeviceFileEntry) {
        if (canReadPublicDirectories()) {
            val directory = File(Environment.getExternalStorageDirectory(), entry.location.trimStart('/'))
            if (directory.exists() && directory.isDirectory) {
                openLocalExplorer(directory)
                return
            }
        }
        val root = entry.location.trim('/').substringBefore('/')
        val directoryType = when (root) {
            Environment.DIRECTORY_DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
            Environment.DIRECTORY_DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
            Environment.DIRECTORY_PICTURES -> Environment.DIRECTORY_PICTURES
            Environment.DIRECTORY_MOVIES -> Environment.DIRECTORY_MOVIES
            Environment.DIRECTORY_MUSIC -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        openPublicDirectory(directoryType)
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
            updatedAtMillis = file.lastModified(),
            extension = extensionOf(file.name),
        )
    }

    private fun applyLocalFileListOptions(entries: List<LocalFileEntry>): List<LocalFileEntry> {
        val cutoff = fileListOptions.periodDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        return entries
            .asSequence()
            .filter { cutoff == null || it.updatedAtMillis >= cutoff }
            .filter { fileListOptions.nameQuery.isBlank() || it.name.contains(fileListOptions.nameQuery, ignoreCase = true) }
            .filter { fileListOptions.extensionQuery.isBlank() || it.extension.equals(fileListOptions.extensionQuery.trimStart('.'), ignoreCase = true) }
            .toList()
            .let { sortLocalEntries(it) }
    }

    private fun applyDeviceFileListOptions(entries: List<DeviceFileEntry>): List<DeviceFileEntry> {
        val cutoff = fileListOptions.periodDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        return entries
            .asSequence()
            .filter { cutoff == null || it.updatedAtMillis >= cutoff }
            .filter { fileListOptions.nameQuery.isBlank() || it.name.contains(fileListOptions.nameQuery, ignoreCase = true) }
            .filter { fileListOptions.extensionQuery.isBlank() || it.extension.equals(fileListOptions.extensionQuery.trimStart('.'), ignoreCase = true) }
            .toList()
            .let { sortDeviceEntries(it) }
    }

    private fun sortLocalEntries(entries: List<LocalFileEntry>): List<LocalFileEntry> {
        return when (fileListOptions.sort) {
            FileListSort.DATE_DESC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenByDescending { it.updatedAtMillis })
            FileListSort.DATE_ASC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenBy { it.updatedAtMillis })
            FileListSort.NAME_ASC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            FileListSort.NAME_DESC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenByDescending { it.name.lowercase() })
            FileListSort.EXT_ASC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenBy { it.extension.lowercase() }.thenBy { it.name.lowercase() })
            FileListSort.EXT_DESC -> entries.sortedWith(compareBy<LocalFileEntry> { !it.isDirectory }.thenByDescending { it.extension.lowercase() }.thenBy { it.name.lowercase() })
        }
    }

    private fun sortDeviceEntries(entries: List<DeviceFileEntry>): List<DeviceFileEntry> {
        return when (fileListOptions.sort) {
            FileListSort.DATE_DESC -> entries.sortedByDescending { it.updatedAtMillis }
            FileListSort.DATE_ASC -> entries.sortedBy { it.updatedAtMillis }
            FileListSort.NAME_ASC -> entries.sortedBy { it.name.lowercase() }
            FileListSort.NAME_DESC -> entries.sortedByDescending { it.name.lowercase() }
            FileListSort.EXT_ASC -> entries.sortedWith(compareBy<DeviceFileEntry> { it.extension.lowercase() }.thenBy { it.name.lowercase() })
            FileListSort.EXT_DESC -> entries.sortedWith(compareByDescending<DeviceFileEntry> { it.extension.lowercase() }.thenBy { it.name.lowercase() })
        }
    }

    private fun extensionOf(name: String): String {
        return name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
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

    private fun showCreateExplorerFolderDialog(directory: File) {
        showTextDialog("新規フォルダ作成", "フォルダ名", "作成") { folderName ->
            createExplorerFolder(directory, folderName)
        }
    }

    private fun createExplorerFolder(directory: File, folderName: String) {
        runCatching {
            val folder = uniqueDirectory(directory, sanitizeFileName(folderName))
            check(folder.mkdirs()) { "フォルダを作成できませんでした" }
            folder
        }.onSuccess {
            Toast.makeText(this, "フォルダを作成しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(directory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "フォルダを作成できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameExplorerEntryDialog(file: File, currentDirectory: File) {
        showTextDialog("名前変更", "新しい名前", "変更") { newName ->
            renameExplorerEntry(file, currentDirectory, newName)
        }
    }

    private fun renameExplorerEntry(file: File, currentDirectory: File, newName: String) {
        runCatching {
            require(file.exists()) { "名前変更対象が見つかりません" }
            val parent = file.parentFile ?: currentDirectory
            val destination = uniqueDestination(parent, sanitizeFileName(newName))
            check(file.renameTo(destination)) { "名前を変更できませんでした" }
            destination
        }.onSuccess {
            Toast.makeText(this, "名前を変更しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "名前を変更できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyExplorerEntryToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { "コピー対象が見つかりません" }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            if (file.isDirectory) {
                check(file.copyRecursively(destination, overwrite = false)) { "フォルダをコピーできませんでした" }
            } else {
                file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, "Inboxへコピーしました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "コピーできませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveExplorerEntryToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { "移動対象が見つかりません" }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            if (!file.renameTo(destination)) {
                if (file.isDirectory) {
                    check(file.copyRecursively(destination, overwrite = false)) { "フォルダを移動できませんでした" }
                    check(file.deleteRecursively()) { "移動元フォルダを削除できませんでした" }
                } else {
                    file.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    check(file.delete()) { "移動元ファイルを削除できませんでした" }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, "Inboxへ移動しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "移動できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun duplicateExplorerEntry(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { "複製対象が見つかりません" }
            val parent = file.parentFile ?: currentDirectory
            val destination = uniqueDestination(parent, duplicateDisplayName(file.name))
            if (file.isDirectory) {
                check(file.copyRecursively(destination, overwrite = false)) { "フォルダを複製できませんでした" }
            } else {
                file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, "複製しました: ${it.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "複製できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteExplorerEntry(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { "削除対象が見つかりません" }
            val deleted = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            check(deleted) { "削除できませんでした" }
        }.onSuccess {
            Toast.makeText(this, "削除しました: ${file.name}", Toast.LENGTH_SHORT).show()
            openLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "削除できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeFor(file: File): String {
        return URLConnection.guessContentTypeFromName(file.name)
            ?: mimeTypeForExtension(extensionOf(file.name))
            ?: "application/octet-stream"
    }

    private fun mimeTypeForExtension(extension: String): String? {
        return when (extension.lowercase()) {
            "txt", "text", "log", "ini", "conf", "cfg", "properties", "env" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "tsv" -> "text/tab-separated-values"
            "json", "jsonl" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm", "xhtml" -> "text/html"
            "css" -> "text/css"
            "js", "mjs", "cjs" -> "application/javascript"
            "yml", "yaml" -> "application/x-yaml"
            "toml" -> "text/plain"
            "rtf" -> "application/rtf"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "avif" -> "image/avif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp", "3gpp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "mid", "midi" -> "audio/midi"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xls" -> "application/vnd.ms-excel"
            "doc" -> "application/msword"
            "ppt" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            else -> null
        }
    }

    private fun readableSize(size: Long): String {
        if (size < 1024L) return "${size}B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return "%.2f%s".format(value, units[unitIndex])
    }

    private fun openSearch(query: String) {
        runCatching {
            repository.search(query)
        }.onSuccess {
            isDashboardVisible = false
            dashboardView.renderSearch(
                it,
                ::renderDashboard,
                ::openSearch,
                ::openDetail,
                ::toggleFavoriteFromSummary,
                ::moveSummaryToTrash,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "検索できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDetail(itemId: String) {
        runCatching {
            repository.detail(itemId)
        }.onSuccess { detail ->
            isDashboardVisible = false
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
                    showTextDialog("タグを追加", "タグ名", "追加") { tagName ->
                        addTag(itemId, tagName)
                    }
                },
                onRemoveTag = { tagId ->
                    removeTag(itemId, tagId)
                },
                onAddCollection = {
                    showTextDialog("Collectionへ追加", "Collection名", "追加") { collectionTitle ->
                        addToCollection(itemId, collectionTitle)
                    }
                },
                onRemoveCollection = { collectionId ->
                    removeFromCollection(itemId, collectionId)
                },
                onMoveTrash = {
                    moveToTrash(itemId)
                },
                onOpen = {
                    openProtectedAwareItem(itemId, detail)
                },
                onShare = {
                    shareProtectedAwareItem(detail)
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
                    showReferenceDialog(itemId, "mail", "Mail-STRASSE", "strasse://mail/open/")
                },
                onAddTaskReference = {
                    showReferenceDialog(itemId, "task", "Task-STRASSE", "strasse://task/open/")
                },
                onAddAtelierReference = {
                    showReferenceDialog(itemId, "atelier", "Atelier-STRASSE", "strasse://atelier/open/")
                },
                onOpenReference = ::openReferenceUri,
                onAddMemo = {
                    showMemoDialog(itemId)
                },
                onAddProtectedMemo = {
                    showProtectedMemoDialog(itemId)
                },
                onUnlockProtectedMemos = {
                    showUnlockProtectedMemosDialog(itemId)
                },
                onAddOcrText = {
                    showOcrTextDialog(itemId)
                },
                onRunImageOcr = {
                    runImageOcr(itemId, detail.path, detail.item.mimeType)
                },
                onCacheRemoteFile = {
                    cacheRemoteFile(itemId, detail)
                },
                onGenerateThumbnail = {
                    generateThumbnail(itemId, detail.path, detail.item.mimeType)
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
                    showTextDialog("タグを追加", "タグ名", "追加") { tagName ->
                        addTag(itemId, tagName)
                    }
                },
                onRemoveTag = { tagId ->
                    removeTag(itemId, tagId)
                },
                onAddCollection = {
                    showTextDialog("Collectionへ追加", "Collection名", "追加") { collectionTitle ->
                        addToCollection(itemId, collectionTitle)
                    }
                },
                onRemoveCollection = { collectionId ->
                    removeFromCollection(itemId, collectionId)
                },
                onMoveTrash = {
                    moveToTrash(itemId)
                },
                onOpen = {
                    openProtectedAwareItem(itemId, it)
                },
                onShare = {
                    shareProtectedAwareItem(it)
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
                    showReferenceDialog(itemId, "mail", "Mail-STRASSE", "strasse://mail/open/")
                },
                onAddTaskReference = {
                    showReferenceDialog(itemId, "task", "Task-STRASSE", "strasse://task/open/")
                },
                onAddAtelierReference = {
                    showReferenceDialog(itemId, "atelier", "Atelier-STRASSE", "strasse://atelier/open/")
                },
                onOpenReference = ::openReferenceUri,
                onAddMemo = {
                    showMemoDialog(itemId)
                },
                onAddProtectedMemo = {
                    showProtectedMemoDialog(itemId)
                },
                onUnlockProtectedMemos = {
                    showUnlockProtectedMemosDialog(itemId)
                },
                onAddOcrText = {
                    showOcrTextDialog(itemId)
                },
                onRunImageOcr = {
                    runImageOcr(itemId, it.path, it.item.mimeType)
                },
                onCacheRemoteFile = {
                    cacheRemoteFile(itemId, it)
                },
                onGenerateThumbnail = {
                    generateThumbnail(itemId, it.path, it.item.mimeType)
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

    private fun toggleFavoriteFromSummary(item: jp.viastrasse.cabinetstrasse.data.CabinetItemSummary) {
        runCatching {
            repository.updateItemFlags(item.id, !item.isFavorite, item.isUnsorted)
        }.onSuccess {
            Toast.makeText(this, if (item.isFavorite) "お気に入りを解除しました" else "お気に入りに追加しました", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "お気に入りを更新できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveSummaryToTrash(item: jp.viastrasse.cabinetstrasse.data.CabinetItemSummary) {
        runCatching {
            repository.moveToTrash(item.id)
        }.onSuccess {
            Toast.makeText(this, "ゴミ箱へ移動しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "ゴミ箱へ移動できませんでした", Toast.LENGTH_SHORT).show()
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

    private fun removeTag(itemId: String, tagId: String) {
        runCatching {
            repository.removeTag(itemId, tagId)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "タグを外せませんでした", Toast.LENGTH_SHORT).show()
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

    private fun removeFromCollection(itemId: String, collectionId: String) {
        runCatching {
            repository.removeFromCollection(itemId, collectionId)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Collectionから外せませんでした", Toast.LENGTH_SHORT).show()
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

    private fun openViewer(
        path: String,
        mimeType: String,
        title: String,
        navigationItems: List<ViewerNavigationItem> = emptyList(),
    ) {
        startActivity(
            Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_PATH, path)
                putExtra(ViewerActivity.EXTRA_MIME_TYPE, mimeType)
                putExtra(ViewerActivity.EXTRA_TITLE, title)
                if (navigationItems.isNotEmpty()) {
                    putStringArrayListExtra(
                        ViewerActivity.EXTRA_NAVIGATION_PATHS,
                        ArrayList(navigationItems.map { it.path }),
                    )
                    putStringArrayListExtra(
                        ViewerActivity.EXTRA_NAVIGATION_MIME_TYPES,
                        ArrayList(navigationItems.map { it.mimeType }),
                    )
                    putStringArrayListExtra(
                        ViewerActivity.EXTRA_NAVIGATION_TITLES,
                        ArrayList(navigationItems.map { it.title }),
                    )
                    putExtra(
                        ViewerActivity.EXTRA_NAVIGATION_INDEX,
                        navigationItems.indexOfFirst { it.path == path }.coerceAtLeast(0),
                    )
                }
            },
        )
    }

    private fun localViewerNavigation(entries: List<LocalFileEntry>): List<ViewerNavigationItem> {
        return entries
            .filterNot { it.isDirectory }
            .map { entry ->
                ViewerNavigationItem(
                    path = entry.file.absolutePath,
                    mimeType = mimeTypeFor(entry.file),
                    title = entry.file.name,
                )
            }
    }

    private fun deviceViewerNavigation(entries: List<DeviceFileEntry>): List<ViewerNavigationItem> {
        return entries.map { entry ->
            ViewerNavigationItem(
                path = entry.uri,
                mimeType = entry.mimeType.ifBlank { "*/*" },
                title = entry.name,
            )
        }
    }

    private fun documentViewerNavigation(entries: List<DocumentFileEntry>): List<ViewerNavigationItem> {
        return entries
            .filterNot { it.isDirectory }
            .map { entry ->
                ViewerNavigationItem(
                    path = entry.uri,
                    mimeType = entry.mimeType.ifBlank { "*/*" },
                    title = entry.name,
                )
            }
    }

    private fun openRegisteredItem(itemId: String, path: String, mimeType: String, title: String) {
        runCatching {
            repository.markOpened(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "閲覧日時を更新できませんでした", Toast.LENGTH_SHORT).show()
        }
        openViewer(path, mimeType, title)
    }

    private fun openProtectedAwareItem(
        itemId: String,
        detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail,
    ) {
        runAfterProtectionCheck(detail) {
            openRegisteredItem(itemId, detail.path, detail.item.mimeType, detail.item.title)
        }
    }

    private fun shareProtectedAwareItem(detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        runAfterProtectionCheck(detail) {
            shareItem(detail)
        }
    }

    private fun runAfterProtectionCheck(
        detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail,
        action: () -> Unit,
    ) {
        if (!detail.isProtected) {
            action()
            return
        }
        showPinDialog(
            title = "保護資料のPIN確認",
            positiveLabel = "確認",
        ) { pin ->
            runCatching {
                repository.verifySecurityPin(pin)
            }.onSuccess { verified ->
                if (verified) {
                    action()
                } else {
                    Toast.makeText(this, "PINが一致しません", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: "PINを確認できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareItem(detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        runCatching {
            if (detail.item.sourceKind == "url" || detail.item.sourceKind == "remote") {
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

    private fun cacheRemoteFile(itemId: String, detail: jp.viastrasse.cabinetstrasse.data.CabinetItemDetail) {
        val remote = detail.remote
        if (remote == null || remote.webUrl.isBlank()) {
            Toast.makeText(this, "Web URL付きのリモート参照だけキャッシュできます", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "リモートファイルを取得しています", Toast.LENGTH_SHORT).show()
        thread(name = "cabinet-remote-cache") {
            runCatching {
                val cacheDir = File(filesDir, "remote-cache").apply { mkdirs() }
                val destination = uniqueDestination(cacheDir, sanitizeFileName(remote.displayName))
                URL(remote.webUrl).openStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                repository.markRemoteFileCached(itemId, destination.absolutePath, destination.length())
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "リモートファイルをキャッシュしました", Toast.LENGTH_SHORT).show()
                    openDetail(itemId)
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "リモートファイルを取得できませんでした", Toast.LENGTH_SHORT).show()
                }
            }
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

    private fun openReferenceUri(uri: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "参照を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReferenceDialog(itemId: String, referenceType: String, sourceApp: String, uriPrefix: String) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        val sourceIdInput = darkInput("参照ID")
        val titleInput = darkInput("表示名")
        val uriInput = darkInput("URI").apply {
            setText(uriPrefix)
        }
        container.addView(sourceIdInput)
        container.addView(titleInput)
        container.addView(uriInput)
        AlertDialog.Builder(this)
            .setTitle("$sourceApp 参照を追加")
            .setView(container)
            .setPositiveButton("追加") { _, _ ->
                val sourceId = sourceIdInput.text.toString().trim().ifBlank { "manual-${System.currentTimeMillis()}" }
                val title = titleInput.text.toString().trim().ifBlank { "$sourceApp 参照" }
                val uri = uriInput.text.toString().trim().ifBlank { "$uriPrefix$sourceId" }
                addReference(itemId, referenceType, sourceApp, sourceId, title, uri)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showProtectedMemoDialog(itemId: String) {
        showTextDialog("保護メモを追加", "メモ本文", "追加", multiline = true) { body ->
            addProtectedMemo(itemId, body)
        }
    }

    private fun showUnlockProtectedMemosDialog(itemId: String) {
        showPinDialog(
            title = "保護メモを表示",
            positiveLabel = "表示",
        ) { pin ->
            openUnlockedDetail(itemId, pin)
        }
    }

    private fun openUnlockedDetail(itemId: String, pin: String) {
        runCatching {
            repository.detailUnlocked(itemId, pin)
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
                    showTextDialog("タグを追加", "タグ名", "追加") { tagName ->
                        addTag(itemId, tagName)
                    }
                },
                onRemoveTag = { tagId ->
                    removeTag(itemId, tagId)
                },
                onAddCollection = {
                    showTextDialog("Collectionへ追加", "Collection名", "追加") { collectionTitle ->
                        addToCollection(itemId, collectionTitle)
                    }
                },
                onRemoveCollection = { collectionId ->
                    removeFromCollection(itemId, collectionId)
                },
                onMoveTrash = {
                    moveToTrash(itemId)
                },
                onOpen = {
                    openProtectedAwareItem(itemId, detail)
                },
                onShare = {
                    shareProtectedAwareItem(detail)
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
                    showReferenceDialog(itemId, "mail", "Mail-STRASSE", "strasse://mail/open/")
                },
                onAddTaskReference = {
                    showReferenceDialog(itemId, "task", "Task-STRASSE", "strasse://task/open/")
                },
                onAddAtelierReference = {
                    showReferenceDialog(itemId, "atelier", "Atelier-STRASSE", "strasse://atelier/open/")
                },
                onOpenReference = ::openReferenceUri,
                onAddMemo = {
                    showMemoDialog(itemId)
                },
                onAddProtectedMemo = {
                    showProtectedMemoDialog(itemId)
                },
                onUnlockProtectedMemos = {
                    showUnlockProtectedMemosDialog(itemId)
                },
                onAddOcrText = {
                    showOcrTextDialog(itemId)
                },
                onRunImageOcr = {
                    runImageOcr(itemId, detail.path, detail.item.mimeType)
                },
                onCacheRemoteFile = {
                    cacheRemoteFile(itemId, detail)
                },
                onGenerateThumbnail = {
                    generateThumbnail(itemId, detail.path, detail.item.mimeType)
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
            Toast.makeText(this, error.message ?: "保護メモを表示できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMemoDialog(itemId: String) {
        showTextDialog("メモを追加", "メモ本文", "追加", multiline = true) { body ->
            addMemo(itemId, body)
        }
    }

    private fun showOcrTextDialog(itemId: String) {
        showTextDialog("OCR本文を追加", "抽出テキスト", "追加", multiline = true) { body ->
            addOcrText(itemId, body)
        }
    }

    private fun addMemo(itemId: String, body: String) {
        runCatching {
            repository.addMemo(itemId, body, false)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "メモを追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addOcrText(itemId: String, body: String) {
        runCatching {
            repository.addOcrText(itemId, body, "manual")
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "OCR本文を追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runImageOcr(itemId: String, path: String, mimeType: String) {
        val file = File(path)
        val isPdf = mimeType == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true)
        if ((!mimeType.startsWith("image/") && !isPdf) || !file.exists()) {
            Toast.makeText(this, "画像またはPDFだけOCRを実行できます", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "OCRを実行しています", Toast.LENGTH_SHORT).show()
        val onSuccess: (String) -> Unit = { text ->
            if (text.isBlank()) {
                Toast.makeText(this, "文字を検出できませんでした", Toast.LENGTH_SHORT).show()
            } else {
                saveRecognizedText(itemId, text, if (isPdf) "mlkit-pdf-first-page" else "mlkit-japanese")
            }
        }
        val onFailure: (Throwable) -> Unit = { error ->
            Toast.makeText(this, error.message ?: "OCRを実行できませんでした", Toast.LENGTH_SHORT).show()
        }
        if (isPdf) {
            OcrTextRecognizer.recognizePdfFirstPage(file, onSuccess, onFailure)
        } else {
            OcrTextRecognizer.recognizeImage(this, file, onSuccess, onFailure)
        }
    }

    private fun saveRecognizedText(itemId: String, text: String, source: String) {
        runCatching {
            repository.addOcrText(itemId, text, source)
        }.onSuccess {
            Toast.makeText(this, "OCR本文を保存しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "OCR本文を保存できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateThumbnail(itemId: String, path: String, mimeType: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "サムネイル対象ファイルが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val thumbnail = ThumbnailGenerator.createSupportedThumbnail(file, mimeType, File(filesDir, "thumbnails"))
            repository.setItemThumbnail(itemId, thumbnail.absolutePath)
        }.onSuccess {
            Toast.makeText(this, "サムネイルを生成しました", Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "サムネイルを生成できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addProtectedMemo(itemId: String, body: String) {
        runCatching {
            repository.addMemo(itemId, body, true)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "保護メモを追加できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTextDialog(
        title: String,
        hint: String,
        positiveLabel: String,
        multiline: Boolean = false,
        onText: (String) -> Unit,
    ) {
        val input = darkInput(hint).apply {
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    onText(value)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun darkInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setTextColor(jp.viastrasse.cabinetstrasse.theme.CabinetColors.TextPrimary)
            setHintTextColor(jp.viastrasse.cabinetstrasse.theme.CabinetColors.TextSecondary)
            setBackgroundColor(jp.viastrasse.cabinetstrasse.theme.CabinetColors.SurfaceAlt)
            setPadding(24, 18, 24, 18)
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
                        note = "選択フォルダから取り込み",
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

    private fun duplicateDisplayName(displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        return if (extension.isBlank()) {
            "$base-copy"
        } else {
            "$base-copy.$extension"
        }
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
            isDashboardVisible = false
            dashboardView.renderSettings(
                settings = settings,
                duplicateReport = duplicateReport,
                onBack = ::renderDashboard,
                onExportBackup = ::exportBackup,
                onRunBackupNow = ::runBackupNow,
                onImportBackup = ::openBackupPicker,
                onRestoreLatestBackup = ::restoreLatestLocalBackup,
                onSetPin = ::showSetPinDialog,
                onVerifyPin = ::showVerifyPinDialog,
                onConfigureProvider = ::showStorageProviderDialog,
                onAddRemoteFile = ::showRemoteFileDialog,
                onOpenProvider = { provider -> openMode("provider:${provider.id}") },
                onCreateSmartFolder = ::showCreateSmartFolderDialog,
                onDuplicateItemSelected = ::openDetail,
                displayMode = displayModePreference(),
                fontPreference = fileListDisplayPreference(),
                onDisplayModeSelected = ::updateDisplayModePreference,
                onFontPreferenceChanged = ::updateFileListDisplayPreference,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "設定を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayModePreference(): String {
        val value = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DISPLAY_MODE, DISPLAY_MODE_COMPACT)
            .orEmpty()
        return when (value) {
            DISPLAY_MODE_COMPACT, DISPLAY_MODE_ELEGANT -> value
            else -> DISPLAY_MODE_COMPACT
        }
    }

    private fun updateDisplayModePreference(displayMode: String) {
        val normalized = when (displayMode) {
            DISPLAY_MODE_COMPACT, DISPLAY_MODE_ELEGANT -> displayMode
            else -> DISPLAY_MODE_COMPACT
        }
        getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, normalized)
            .apply()
        Toast.makeText(this, "表示設定を保存しました", Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun fileListDisplayPreference(): FileListDisplayPreference {
        val prefs = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
        return FileListDisplayPreference(
            fromFontSize = prefs.getInt(
                KEY_FILE_LIST_FROM_FONT_SIZE,
                FileListDisplayPreference.DEFAULT_FONT_SIZE,
            ).coerceIn(FileListDisplayPreference.MIN_FONT_SIZE, FileListDisplayPreference.MAX_FONT_SIZE),
            subjectFontSize = prefs.getInt(
                KEY_FILE_LIST_SUBJECT_FONT_SIZE,
                FileListDisplayPreference.DEFAULT_FONT_SIZE,
            ).coerceIn(FileListDisplayPreference.MIN_FONT_SIZE, FileListDisplayPreference.MAX_FONT_SIZE),
            bodyFontSize = prefs.getInt(
                KEY_FILE_LIST_BODY_FONT_SIZE,
                FileListDisplayPreference.DEFAULT_FONT_SIZE,
            ).coerceIn(FileListDisplayPreference.MIN_FONT_SIZE, FileListDisplayPreference.MAX_FONT_SIZE),
        )
    }

    private fun updateFileListDisplayPreference(preference: FileListDisplayPreference) {
        getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(
                KEY_FILE_LIST_FROM_FONT_SIZE,
                preference.fromFontSize.coerceIn(
                    FileListDisplayPreference.MIN_FONT_SIZE,
                    FileListDisplayPreference.MAX_FONT_SIZE,
                ),
            )
            .putInt(
                KEY_FILE_LIST_SUBJECT_FONT_SIZE,
                preference.subjectFontSize.coerceIn(
                    FileListDisplayPreference.MIN_FONT_SIZE,
                    FileListDisplayPreference.MAX_FONT_SIZE,
                ),
            )
            .putInt(
                KEY_FILE_LIST_BODY_FONT_SIZE,
                preference.bodyFontSize.coerceIn(
                    FileListDisplayPreference.MIN_FONT_SIZE,
                    FileListDisplayPreference.MAX_FONT_SIZE,
                ),
            )
            .apply()
        Toast.makeText(this, "文字サイズを保存しました", Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun appPrefs() = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)

    private fun showStorageProviderDialog(provider: StorageProviderAccountSummary) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        val accountInput = darkInput("アカウント名").apply {
            setText(provider.accountName)
        }
        val statusInput = darkInput("接続状態: connected / offline / error / not_configured").apply {
            setText(provider.connectionStatus.ifBlank { "not_configured" })
        }
        container.addView(accountInput)
        container.addView(statusInput)
        AlertDialog.Builder(this)
            .setTitle("${provider.displayName} を設定")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    repository.updateStorageProvider(
                        providerId = provider.id,
                        accountName = accountInput.text.toString(),
                        connectionStatus = statusInput.text.toString(),
                    )
                }.onSuccess {
                    Toast.makeText(this, "Provider設定を保存しました", Toast.LENGTH_SHORT).show()
                    openSettings()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Provider設定を保存できませんでした", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showCreateSmartFolderDialog() {
        val presets = arrayOf("pdf", "image", "url", "remote", "ocr", "protected", "favorites", "unsorted", "trash")
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        val titleInput = darkInput("Smart Folder名")
        val presetInput = darkInput("preset: ${presets.joinToString(" / ")}").apply {
            setText("pdf")
        }
        container.addView(titleInput)
        container.addView(presetInput)
        AlertDialog.Builder(this)
            .setTitle("Smart Folderを作成")
            .setView(container)
            .setPositiveButton("作成") { _, _ ->
                createSmartFolder(
                    title = titleInput.text.toString().trim().ifBlank { "Smart Folder" },
                    preset = presetInput.text.toString().trim().ifBlank { "pdf" },
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun createSmartFolder(title: String, preset: String) {
        runCatching {
            repository.createSmartFolder(title, preset)
        }.onSuccess {
            Toast.makeText(this, "Smart Folderを作成しました", Toast.LENGTH_SHORT).show()
            openSettings()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "Smart Folderを作成できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRemoteFileDialog(provider: StorageProviderAccountSummary) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 12, 32, 0)
        }
        val remotePathInput = darkInput("リモートパス")
        val displayNameInput = darkInput("表示名")
        val mimeTypeInput = darkInput("MIME type").apply {
            setText("application/octet-stream")
        }
        val sizeInput = darkInput("サイズ byte").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        val remoteIdInput = darkInput("リモートID")
        val webUrlInput = darkInput("Web URL")
        val noteInput = darkInput("メモ").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        container.addView(remotePathInput)
        container.addView(displayNameInput)
        container.addView(mimeTypeInput)
        container.addView(sizeInput)
        container.addView(remoteIdInput)
        container.addView(webUrlInput)
        container.addView(noteInput)
        AlertDialog.Builder(this)
            .setTitle("${provider.displayName} 参照を追加")
            .setView(container)
            .setPositiveButton("追加") { _, _ ->
                val remotePath = remotePathInput.text.toString().trim()
                val displayName = displayNameInput.text.toString().trim()
                    .ifBlank { remotePath.substringAfterLast('/').ifBlank { provider.displayName } }
                registerRemoteFile(
                    provider = provider,
                    remoteFileId = remoteIdInput.text.toString().trim(),
                    remotePath = remotePath,
                    displayName = displayName,
                    mimeType = mimeTypeInput.text.toString().trim().ifBlank { "application/octet-stream" },
                    size = sizeInput.text.toString().trim().toLongOrNull() ?: 0L,
                    webUrl = webUrlInput.text.toString().trim(),
                    note = noteInput.text.toString().trim().ifBlank { "Storage Providerから登録" },
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun registerRemoteFile(
        provider: StorageProviderAccountSummary,
        remoteFileId: String,
        remotePath: String,
        displayName: String,
        mimeType: String,
        size: Long,
        webUrl: String,
        note: String,
    ) {
        runCatching {
            repository.registerRemoteFile(
                providerId = provider.id,
                remoteFileId = remoteFileId,
                remotePath = remotePath,
                displayName = displayName,
                mimeType = mimeType,
                size = size,
                webUrl = webUrl,
                note = note,
            )
        }.onSuccess { item ->
            Toast.makeText(this, "リモート参照を登録しました: ${item.displayName}", Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "リモート参照を登録できませんでした", Toast.LENGTH_SHORT).show()
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

    private fun runBackupNow() {
        BackupWorker.enqueueNow(applicationContext)
        Toast.makeText(this, "定期バックアップを実行キューへ追加しました", Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun restoreLatestLocalBackup() {
        runCatching {
            repository.restoreLatestLocalBackup()
        }.onSuccess {
            Toast.makeText(this, "最新ローカルバックアップを復元しました", Toast.LENGTH_SHORT).show()
            openSettings()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "最新ローカルバックアップを復元できませんでした", Toast.LENGTH_SHORT).show()
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
        val input = darkInput("PIN").apply {
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
        private const val REQUEST_OPEN_SD_TREE = 2404
        private const val APP_PREFS_NAME = "cabinet-app-settings"
        private const val KEY_SD_TREE_URI = "sd_tree_uri"
        private const val KEY_SD_TREE_URIS = "sd_tree_uris"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_FILE_LIST_FROM_FONT_SIZE = "file_list_from_font_size"
        private const val KEY_FILE_LIST_SUBJECT_FONT_SIZE = "file_list_subject_font_size"
        private const val KEY_FILE_LIST_BODY_FONT_SIZE = "file_list_body_font_size"
        private const val DISPLAY_MODE_COMPACT = "compact"
        private const val DISPLAY_MODE_ELEGANT = "elegant"
    }

    private data class ViewerNavigationItem(
        val path: String,
        val mimeType: String,
        val title: String,
    )
}
