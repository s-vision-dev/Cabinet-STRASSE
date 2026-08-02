package jp.viastrasse.cabinet

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ContentUris
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import jp.viastrasse.cabinet.backup.BackupWorker
import jp.viastrasse.cabinet.data.CabinetFiles
import jp.viastrasse.cabinet.data.CabinetItemDetail
import jp.viastrasse.cabinet.data.CabinetItemSummary
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.data.StorageProviderAccountSummary
import jp.viastrasse.cabinet.data.StorageProviderConfiguration
import jp.viastrasse.cabinet.preview.OcrTextRecognizer
import jp.viastrasse.cabinet.preview.PreviewWorker
import jp.viastrasse.cabinet.preview.ThumbnailGenerator
import jp.viastrasse.cabinet.data.QuickAccessEntry
import jp.viastrasse.cabinet.data.QuickAccessStore
import jp.viastrasse.cabinet.data.QuickAccessType
import jp.viastrasse.cabinet.ui.CabinetDashboardView
import jp.viastrasse.cabinet.ui.CabinetMetrics
import jp.viastrasse.cabinet.ui.DISPLAY_MODE_COMPACT
import jp.viastrasse.cabinet.ui.normalizeDisplayMode
import jp.viastrasse.cabinet.ui.cabinetInput
import jp.viastrasse.cabinet.ui.DeviceFileEntry
import jp.viastrasse.cabinet.ui.DocumentFileEntry
import jp.viastrasse.cabinet.ui.FileListDisplayPreference
import jp.viastrasse.cabinet.ui.FileListOptions
import jp.viastrasse.cabinet.ui.FileListSort
import jp.viastrasse.cabinet.ui.LocalFileEntry
import jp.viastrasse.cabinet.ui.SettingsTab
import jp.viastrasse.cabinet.theme.CabinetColors
import jp.viastrasse.cabinet.watch.FolderWatchWorker
import jp.viastrasse.cabinet.storage.OAuthController
import jp.viastrasse.cabinet.storage.OAuthProviderConfig
import jp.viastrasse.cabinet.storage.RemoteStorageEntry
import jp.viastrasse.cabinet.storage.SecureCredentialStore
import jp.viastrasse.cabinet.storage.StorageProviderFactory
import jp.viastrasse.family.ui.ViastrasseFamilyLauncher
import jp.viastrasse.view.contract.ViastrasseViewLauncher
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
    private var currentDocumentTreeUri: Uri? = null
    private var currentDocumentRelativePath: List<String> = emptyList()
    private var currentDocumentProviderTitle: String = ""
    private var currentDocumentRootBack: (() -> Unit)? = null
    private var currentDocumentChooseRoot: (() -> Unit)? = null
    private var pendingStorageProviderId: String? = null
    private var currentLocalParents: List<File> = emptyList()
    private var currentLocalRootBack: () -> Unit = ::renderDashboard
    private var pendingApkInstallUri: Uri? = null

    /** このアプリについてを表示している間の戻り先。設定へ1段戻すために持つ。 */
    private var settingsBackTarget: (() -> Unit)? = null

    /** 設定画面で選択中のタブ。操作後の再描画で同じタブに留まるために保持する。 */
    private var settingsTab: SettingsTab = SettingsTab.APPEARANCE

    /**
     * いまの画面で「1つ上の階層へ」戻る処理。
     *
     * フォルダを掘り下げている間は親フォルダを開く処理が入る。システムの
     * 戻る操作はこれを優先して使い、階層を1段ずつ上がる。階層を持たない画面
     * では null にしておき、従来どおりホームへ戻す。
     */
    private var navigateUpTarget: (() -> Unit)? = null

    /** クイックアクセスの登録内容。表示設定と同じくアプリ設定に保存する。 */
    private val quickAccessStore: QuickAccessStore by lazy {
        QuickAccessStore(appPrefs())
    }

    /** クイックアクセスのタイル編集モード中か。 */
    private var quickAccessEditing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CabinetRepository(applicationContext)
        dashboardView = CabinetDashboardView(this)
        dashboardView.onOpenSettings = ::openSettings
        dashboardView.onShowItemDetail = ::openDetail
        dashboardView.onAddQuickAccess = ::addQuickAccessEntry
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
        val apkUri = pendingApkInstallUri
        if (apkUri != null && canRequestApkInstall()) {
            pendingApkInstallUri = null
            launchApkInstaller(apkUri)
        }
        val pendingDirectory = pendingPublicDirectoryType
        if (pendingDirectory != null && canReadPublicDirectories()) {
            pendingPublicDirectoryType = null
            openPublicDirectory(pendingDirectory)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
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
        // タイル編集中は、まず編集モードを抜ける。
        if (quickAccessEditing) {
            finishQuickAccessEditing()
            return true
        }
        // フォルダを掘り下げている間は、まず親フォルダへ1段戻る。
        navigateUpTarget?.let { up ->
            up()
            return true
        }
        // 設定のサブ画面からはホームへ直帰せず、設定へ1段だけ戻る。
        settingsBackTarget?.let { back ->
            settingsBackTarget = null
            back()
            return true
        }
        if (!isDashboardVisible) {
            renderDashboard()
            return true
        }
        return false
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data
        val isLegacyCabinetLink = uri?.scheme == "strasse" && uri.host == "cabinet"
        val isCabinetLink = uri?.scheme == "viastrasse-cabinet"
        if (uri != null && (isLegacyCabinetLink || isCabinetLink)) {
            val command = if (isCabinetLink) uri.host else uri.pathSegments.firstOrNull()
            val argumentIndex = if (isCabinetLink) 0 else 1
            when (command) {
                "oauth" -> handleStorageOAuthCallback(uri)
                "open" -> uri.pathSegments.getOrNull(argumentIndex)?.let(::openDetail) ?: renderDashboard()
                "search" -> openSearch(uri.getQueryParameter("q").orEmpty())
                "inbox" -> openMode("Inbox")
                "collection" -> uri.pathSegments.getOrNull(argumentIndex)
                    ?.let { openMode("collection:$it") }
                    ?: openMode("Collection")
                "smart" -> uri.pathSegments.getOrNull(argumentIndex)
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
            Toast.makeText(this, getString(R.string.main_handle_add_deep_link, item.displayName), Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_handle_add_deep_link_2), Toast.LENGTH_SHORT).show()
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
                        requireNotNull(input) { getString(R.string.msg_file_open_failed) }
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    destination
                }
                "file", null -> {
                    val source = if (fileUri.scheme == "file") File(requireNotNull(fileUri.path)) else File(fileUri.toString())
                    require(source.exists() && source.isFile) { getString(R.string.main_handle_add_file_deep_link) }
                    source.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    destination
                }
                else -> error(getString(R.string.main_handle_add_file_deep_link_2, fileUri.scheme))
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
                Toast.makeText(this, getString(R.string.main_handle_add_file_deep_link_3, item.displayName), Toast.LENGTH_SHORT).show()
                openDetail(item.id)
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.main_handle_add_file_deep_link_4), Toast.LENGTH_SHORT).show()
                renderDashboard()
            }
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_handle_add_file_deep_link_5), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun handleReferenceDeepLink(uri: Uri) {
        val actionIndex = if (uri.scheme == "viastrasse-cabinet") 0 else 1
        if (uri.pathSegments.getOrNull(actionIndex) != "add") {
            renderDashboard()
            return
        }
        val itemId = uri.getQueryParameter("itemId").orEmpty()
        if (itemId.isBlank()) {
            Toast.makeText(this, getString(R.string.main_handle_reference_deep_link), Toast.LENGTH_SHORT).show()
            renderDashboard()
            return
        }
        val referenceType = uri.getQueryParameter("type").orEmpty().ifBlank { "external" }
        val sourceApp = uri.getQueryParameter("sourceApp").orEmpty().ifBlank { "STRASSE" }
        val sourceId = uri.getQueryParameter("sourceId").orEmpty().ifBlank { "deeplink-${System.currentTimeMillis()}" }
        val title = uri.getQueryParameter("title").orEmpty().ifBlank { "$sourceApp 参照" }
        val referenceUri = uri.getQueryParameter("uri").orEmpty().ifBlank { "viastrasse-${appKey(sourceApp)}://open/$sourceId" }
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
            Toast.makeText(this, getString(R.string.main_handle_reference_deep_link_3), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_reference_add_failed), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun renderDashboard() {
        showDashboardView()
        isDashboardVisible = true
        settingsBackTarget = null
        navigateUpTarget = null
        runCatching {
            repository.dashboard() to repository.settings().providers.filter { provider ->
                provider.providerType != "local" &&
                    (provider.connectionStatus == "connected" || provider.remoteRoot.startsWith("content://"))
            }
        }.onSuccess { (dashboard, connectedProviders) ->
            dashboardView.render(
                dashboard = dashboard,
                connectedProviders = connectedProviders,
                quickAccessEntries = quickAccessStore.load(),
                quickAccessEditing = quickAccessEditing,
                onModeSelected = { mode -> openMode(mode.name) },
                onItemSelected = ::openItemFromList,
                onImportFolder = ::openFolderPicker,
                onOpenSearch = { openSearch("") },
                onOpenProvider = { provider -> openStorageProvider(provider, ::renderDashboard) },
                onManageStorageProviders = ::openSettings,
                onToggleFavorite = ::toggleFavoriteFromSummary,
                onMoveTrash = ::moveSummaryToTrash,
                onOpenQuickAccess = ::openQuickAccessEntry,
                onQuickAccessTileAction = ::showQuickAccessMenu,
                onStartQuickAccessEditing = ::startQuickAccessEditing,
                onFinishQuickAccessEditing = ::finishQuickAccessEditing,
                onPlaceQuickAccess = ::placeQuickAccessEntry,
            )
        }.onFailure { error ->
            dashboardView.renderError(error.message ?: getString(R.string.main_render_dashboard))
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
        if (requestCode == REQUEST_STORAGE_PROVIDER_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val grantedFlags = if ((data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                } else {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                contentResolver.takePersistableUriPermission(
                    uri,
                    grantedFlags,
                )
                val providerId = requireNotNull(pendingStorageProviderId)
                val provider = repository.settings().providers.first { it.id == providerId }
                repository.updateStorageProvider(
                    providerId = provider.id,
                    configuration = provider.toConfiguration().copy(remoteRoot = uri.toString()),
                )
            }.onSuccess {
                pendingStorageProviderId = null
                Toast.makeText(this, getString(R.string.main_show_storage_provider_dialog_4), Toast.LENGTH_SHORT).show()
                // SAF から戻る経路なので、由来のタブを明示して復帰する。
                openSettings(SettingsTab.INTEGRATION)
            }.onFailure { error ->
                pendingStorageProviderId = null
                Toast.makeText(this, error.message ?: getString(R.string.main_show_storage_provider_dialog_5), Toast.LENGTH_SHORT).show()
            }
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
        val handledLocally = when (mode) {
            "Search" -> { openSearch(""); true }
            "Settings" -> { openSettings(); true }
            "Explorer" -> { openLocalExplorer(explorerEntryDirectory()); true }
            "SDCard" -> { openSdCardExplorer(); true }
            "Downloads" -> { openPublicDirectory(Environment.DIRECTORY_DOWNLOADS); true }
            "Documents" -> { openPublicDirectory(Environment.DIRECTORY_DOCUMENTS); true }
            "Pictures" -> { openPublicDirectory(Environment.DIRECTORY_PICTURES); true }
            "Movies" -> { openPublicDirectory(Environment.DIRECTORY_MOVIES); true }
            "Music" -> { openPublicDirectory(Environment.DIRECTORY_MUSIC); true }
            else -> false
        }
        if (handledLocally) return
        runCatching {
            repository.mode(mode)
        }.onSuccess {
            isDashboardVisible = false
            navigateUpTarget = null
            quickAccessEditing = false
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
                onItemSelected = ::openItemFromList,
                onCollectionSelected = { collection -> openMode("collection:${collection.id}") },
                onSmartFolderSelected = { folder -> openMode("smart:${folder.id}") },
                onToggleFavorite = ::toggleFavoriteFromSummary,
                onMoveTrash = ::moveSummaryToTrash,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_mode), Toast.LENGTH_SHORT).show()
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

    /**
     * Explorerを開いたときの初期フォルダ。
     *
     * `explorerRoots()` の先頭はアプリ専有領域（filesDir）であり、
     * ファイルマネージャーの入口としては端末の共有ストレージが期待される。
     * 権限が無く共有ストレージを一覧できない場合のみアプリ領域へ退避する。
     */
    private fun explorerEntryDirectory(): File {
        val shared = Environment.getExternalStorageDirectory()
        if (canReadPublicDirectories() && shared != null && shared.isDirectory) {
            return shared
        }
        return explorerRoots().first()
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
        navigateUpTarget = null
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
        renderSdCardRootList(
            roots.map { (treeUri, document) ->
                toDocumentFileEntry(document, treeUri.toString(), emptyList()).asSdRootEntry()
            },
        )
    }

    private fun openSdCardPicker() {
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setMessage(
                getString(R.string.main_open_sd_card_picker) +
                    getString(R.string.main_open_sd_card_picker_2),
            )
            .setPositiveButton(getString(R.string.main_open_sd_card_picker_3)) { _, _ -> launchSdCardPicker() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun launchSdCardPicker() {
        val intent = sdCardOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_SD_TREE)
    }

    private fun sdCardOpenDocumentTreeIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val storageManager = getSystemService(StorageManager::class.java) ?: return null
        return storageManager.storageVolumes
            .firstOrNull { volume -> volume.isRemovable }
            ?.createOpenDocumentTreeIntent()
    }

    private fun renderSdCardRootList(roots: List<DocumentFileEntry>) {
        val entries = roots.sortedBy { it.name.lowercase() }
        isDashboardVisible = false
        navigateUpTarget = null
        dashboardView.renderDocumentTree(
            title = getString(R.string.label_sd_card),
            location = getString(R.string.label_added_folders),
            entries = entries,
            displayMode = displayModePreference(),
            fontPreference = fileListDisplayPreference(),
            listOptions = fileListOptions,
            sectionTitle = getString(R.string.label_added_folders),
            emptyMessage = getString(R.string.main_render_sd_card_root_list),
            showListControls = false,
            onListOptionsChanged = { options ->
                fileListOptions = options
                openSdCardExplorer()
            },
            onBack = ::renderDashboard,
            onParent = null,
            onOpenDirectory = { entry -> openDocumentTreeRoot(Uri.parse(entry.treeUri)) },
            onOpenFile = {},
            onRegisterFile = {},
            onChooseRoot = ::openSdCardPicker,
        )
    }

    private fun openDocumentTreeRoot(
        treeUri: Uri,
        title: String = getString(R.string.label_sd_card),
        onRootBack: () -> Unit = ::openSdCardExplorer,
        onChooseRoot: () -> Unit = ::openSdCardPicker,
    ) {
        openDocumentTreePath(treeUri, emptyList(), title, onRootBack, onChooseRoot)
    }

    private fun openDocumentTreePath(
        treeUri: Uri,
        relativePath: List<String>,
        title: String,
        onRootBack: () -> Unit,
        onChooseRoot: () -> Unit,
    ) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: error(getString(R.string.main_open_document_tree_root))
        val parents = mutableListOf<DocumentFile>()
        var directory = root
        relativePath.forEach { segment ->
            val child = directory.listFiles().firstOrNull { candidate ->
                candidate.isDirectory && candidate.name == segment
            } ?: error(getString(R.string.quick_access_missing))
            parents += directory
            directory = child
        }
        currentDocumentTreeUri = treeUri
        currentDocumentProviderTitle = title
        currentDocumentRootBack = onRootBack
        currentDocumentChooseRoot = onChooseRoot
        openDocumentDirectory(directory, parents, relativePath)
    }

    private fun openDocumentDirectory(
        directory: DocumentFile,
        parents: List<DocumentFile>,
        relativePath: List<String>,
    ) {
        require(directory.isDirectory) { getString(R.string.msg_folder_open_failed) }
        currentDocumentParents = parents
        currentDocumentRelativePath = relativePath
        val rawEntries = directory.listFiles()
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            .map { document ->
                val entry = toDocumentFileEntry(
                    document,
                    currentDocumentTreeUri?.toString().orEmpty(),
                )
                entry.copy(relativePath = relativePath + entry.name)
            }
        val entries = applyDocumentFileListOptions(rawEntries)
        isDashboardVisible = false
        navigateUpTarget = parents.lastOrNull()?.let { parent ->
            { openDocumentDirectory(parent, parents.dropLast(1), relativePath.dropLast(1)) }
        } ?: currentDocumentRootBack
        dashboardView.renderDocumentTree(
            title = currentDocumentProviderTitle.ifBlank { getString(R.string.label_sd_card) },
            location = directory.name ?: directory.uri.toString(),
            entries = entries,
            displayMode = displayModePreference(),
            fontPreference = fileListDisplayPreference(),
            listOptions = fileListOptions,
            onListOptionsChanged = { options ->
                fileListOptions = options
                openDocumentDirectory(directory, parents, relativePath)
            },
            onBack = currentDocumentRootBack ?: ::renderDashboard,
            onParent = parents.lastOrNull()?.let { parent ->
                { openDocumentDirectory(parent, parents.dropLast(1), relativePath.dropLast(1)) }
            } ?: currentDocumentRootBack,
            parentLabel = if (parents.isEmpty()) getString(R.string.storage_provider_back_to_settings) else getString(R.string.action_move_to_parent_folder),
            onOpenDirectory = { entry -> openDocumentDirectory(entry.document, parents + directory, entry.relativePath) },
            onOpenFile = { entry ->
                openViewer(
                    path = entry.uri,
                    mimeType = entry.mimeType.ifBlank { "*/*" },
                    title = entry.name,
                    navigationItems = documentViewerNavigation(entries),
                )
            },
            onRegisterFile = { entry -> registerDocumentFile(entry, directory) },
            onChooseRoot = currentDocumentChooseRoot ?: ::openSdCardPicker,
        )
    }

    private fun DocumentFileEntry.asSdRootEntry(): DocumentFileEntry {
        val fallback = document.uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { name }
        val displayName = name.ifBlank { fallback }.ifBlank { getString(R.string.main_as_sd_root_entry) }
        return copy(
            name = displayName,
            sizeLabel = getString(R.string.main_as_sd_root_entry_2),
            updatedLabel = if (updatedAtMillis > 0L) updatedLabel else getString(R.string.main_as_sd_root_entry_3),
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

    private fun toDocumentFileEntry(
        document: DocumentFile,
        treeUri: String = "",
        relativePath: List<String> = emptyList(),
    ): DocumentFileEntry {
        val name = document.name.orEmpty().ifBlank { document.uri.lastPathSegment ?: "document" }
        val updatedAtMillis = document.lastModified()
        val updated = if (updatedAtMillis > 0L) {
            Instant.ofEpochMilli(updatedAtMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } else {
            getString(R.string.label_no_updated_at)
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
            treeUri = treeUri,
            relativePath = relativePath,
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
            require(!entry.isDirectory) { getString(R.string.msg_register_target_missing) }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, sanitizeFileName(entry.name))
            contentResolver.openInputStream(Uri.parse(entry.uri)).use { input ->
                requireNotNull(input) { getString(R.string.msg_file_open_failed) }
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
            Toast.makeText(this, getString(R.string.msg_registered_named, entry.name), Toast.LENGTH_SHORT).show()
            openDocumentDirectory(currentDirectory, currentDocumentParents, currentDocumentRelativePath)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_register_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptAllFilesAccess(directoryType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        pendingPublicDirectoryType = directoryType
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.main_prompt_all_files_access))
            .setMessage(getString(R.string.main_prompt_all_files_access_2))
            .setPositiveButton(getString(R.string.main_prompt_all_files_access_3)) { _, _ ->
                val uri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                runCatching { startActivity(intent) }
                    .onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun openLocalExplorer(
        directory: File,
        parents: List<File> = emptyList(),
        onRootBack: () -> Unit = ::renderDashboard,
    ) {
        runCatching {
            require(directory.exists() && directory.isDirectory) { getString(R.string.msg_folder_open_failed) }
            val rawEntries = directory.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.map(::toLocalFileEntry)
                .orEmpty()
            applyLocalFileListOptions(rawEntries)
        }.onSuccess { entries ->
            isDashboardVisible = false
            currentLocalParents = parents
            currentLocalRootBack = onRootBack
            val parentAction = parents.lastOrNull()?.let { parent ->
                { openLocalExplorer(parent, parents.dropLast(1), onRootBack) }
            }
            navigateUpTarget = parentAction ?: onRootBack
            dashboardView.renderLocalExplorer(
                currentDirectory = directory,
                entries = entries,
                displayMode = displayModePreference(),
                fontPreference = fileListDisplayPreference(),
                listOptions = fileListOptions,
                onListOptionsChanged = { options ->
                    fileListOptions = options
                    openLocalExplorer(directory, parents, onRootBack)
                },
                onBack = onRootBack,
                onParent = parentAction,
                onOpenDirectory = { child -> openLocalExplorer(child, parents + directory, onRootBack) },
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
            Toast.makeText(this, error.message ?: getString(R.string.main_open_local_explorer), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }
    }

    private fun refreshLocalExplorer(directory: File) {
        openLocalExplorer(directory, currentLocalParents, currentLocalRootBack)
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
            Toast.makeText(this, error.message ?: getString(R.string.main_query_device_files), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, error.message ?: getString(R.string.msg_file_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDeviceFile(entry: DeviceFileEntry, sourceKind: String) {
        runCatching {
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, sanitizeFileName(entry.name))
            contentResolver.openInputStream(Uri.parse(entry.uri)).use { input ->
                requireNotNull(input) { getString(R.string.msg_file_open_failed) }
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
            Toast.makeText(this, getString(R.string.msg_registered_named, item.displayName), Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_register_failed), Toast.LENGTH_SHORT).show()
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
            require(file.exists() && file.isFile) { getString(R.string.msg_register_target_missing) }
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
            Toast.makeText(this, getString(R.string.msg_registered_named, item.displayName), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_register_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateExplorerFolderDialog(directory: File) {
        showTextDialog(getString(R.string.main_show_create_explorer_folder_dialog), getString(R.string.main_show_create_explorer_folder_dialog_2), getString(R.string.action_create)) { folderName ->
            createExplorerFolder(directory, folderName)
        }
    }

    private fun createExplorerFolder(directory: File, folderName: String) {
        runCatching {
            val folder = uniqueDirectory(directory, sanitizeFileName(folderName))
            check(folder.mkdirs()) { getString(R.string.msg_folder_create_failed) }
            folder
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_create_explorer_folder, it.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(directory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_folder_create_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameExplorerEntryDialog(file: File, currentDirectory: File) {
        showTextDialog(getString(R.string.action_rename), getString(R.string.hint_new_name), getString(R.string.action_change)) { newName ->
            renameExplorerEntry(file, currentDirectory, newName)
        }
    }

    private fun renameExplorerEntry(file: File, currentDirectory: File, newName: String) {
        runCatching {
            require(file.exists()) { getString(R.string.main_rename_explorer_entry) }
            val parent = file.parentFile ?: currentDirectory
            val destination = uniqueDestination(parent, sanitizeFileName(newName))
            check(file.renameTo(destination)) { getString(R.string.msg_rename_failed) }
            destination
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_rename_explorer_entry_2, it.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_rename_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyExplorerEntryToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { getString(R.string.main_copy_explorer_entry_to_inbox) }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            if (file.isDirectory) {
                check(file.copyRecursively(destination, overwrite = false)) { getString(R.string.main_copy_explorer_entry_to_inbox_2) }
            } else {
                file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_copy_explorer_entry_to_inbox_3, it.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_copy_explorer_entry_to_inbox_4), Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveExplorerEntryToInbox(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { getString(R.string.main_move_explorer_entry_to_inbox) }
            val inboxDir = File(filesDir, "inbox").apply { mkdirs() }
            val destination = uniqueDestination(inboxDir, file.name)
            if (!file.renameTo(destination)) {
                if (file.isDirectory) {
                    check(file.copyRecursively(destination, overwrite = false)) { getString(R.string.main_move_explorer_entry_to_inbox_2) }
                    check(file.deleteRecursively()) { getString(R.string.main_move_explorer_entry_to_inbox_3) }
                } else {
                    file.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    check(file.delete()) { getString(R.string.main_move_explorer_entry_to_inbox_4) }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_move_explorer_entry_to_inbox_5, it.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_move_explorer_entry_to_inbox_6), Toast.LENGTH_SHORT).show()
        }
    }

    private fun duplicateExplorerEntry(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { getString(R.string.main_duplicate_explorer_entry) }
            val parent = file.parentFile ?: currentDirectory
            val destination = uniqueDestination(parent, duplicateDisplayName(file.name))
            if (file.isDirectory) {
                check(file.copyRecursively(destination, overwrite = false)) { getString(R.string.main_duplicate_explorer_entry_2) }
            } else {
                file.inputStream().buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            destination
        }.onSuccess {
            Toast.makeText(this, getString(R.string.msg_duplicated_named, it.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_duplicate_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteExplorerEntry(file: File, currentDirectory: File) {
        runCatching {
            require(file.exists()) { getString(R.string.main_delete_explorer_entry) }
            val deleted = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            check(deleted) { getString(R.string.msg_delete_failed) }
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_delete_explorer_entry_2, file.name), Toast.LENGTH_SHORT).show()
            refreshLocalExplorer(currentDirectory)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_delete_failed), Toast.LENGTH_SHORT).show()
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

    private fun readableSize(size: Long): String = CabinetFiles.readableSize(size)

    private fun openSearch(query: String) {
        runCatching {
            repository.search(query)
        }.onSuccess {
            isDashboardVisible = false
            navigateUpTarget = null
            quickAccessEditing = false
            dashboardView.renderSearch(
                it,
                ::renderDashboard,
                ::openSearch,
                ::openItemFromList,
                ::toggleFavoriteFromSummary,
                ::moveSummaryToTrash,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_search), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDetail(itemId: String) {
        runCatching {
            repository.detail(itemId)
        }.onSuccess { detail ->
            showDetail(itemId, detail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_detail), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openItemFromList(itemId: String) {
        runCatching {
            repository.detail(itemId)
        }.onSuccess { detail ->
            openProtectedAwareItem(itemId, detail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_file_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 詳細画面を描画する唯一の入口。
     *
     * 以前は openDetail / updateItemFlags / openUnlockedDetail が同じコールバック定義を
     * それぞれ持っており、片方だけ修正すると画面ごとに挙動がずれる状態だった。
     */
    private fun showDetail(itemId: String, detail: CabinetItemDetail) {
        isDashboardVisible = false
        navigateUpTarget = null
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
                showTextDialog(getString(R.string.main_show_detail), getString(R.string.main_show_detail_2), getString(R.string.action_add)) { tagName ->
                    addTag(itemId, tagName)
                }
            },
            onRemoveTag = { tagId ->
                removeTag(itemId, tagId)
            },
            onAddCollection = {
                showTextDialog(getString(R.string.main_show_detail_3), getString(R.string.main_show_detail_4), getString(R.string.action_add)) { collectionTitle ->
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
                showRenameItemDialog(itemId, detail.item.displayName)
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
                showReferenceDialog(itemId, "mail", "Mail by VIASTRASSE", "viastrasse-mail://message/")
            },
            onAddTaskReference = {
                showReferenceDialog(itemId, "task", "Task by VIASTRASSE", "viastrasse-task://open/")
            },
            onAddAtelierReference = {
                showReferenceDialog(itemId, "atelier", "Atelier by VIASTRASSE", "viastrasse-atelier://open/")
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
                confirmDeletePermanently(itemId, detail.item.displayName)
            },
        )
    }

    private fun updateItemFlags(itemId: String, isFavorite: Boolean, isUnsorted: Boolean) {
        runCatching {
            repository.updateItemFlags(itemId, isFavorite, isUnsorted)
        }.onSuccess { detail ->
            showDetail(itemId, detail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_update_item_flags), Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFavoriteFromSummary(item: jp.viastrasse.cabinet.data.CabinetItemSummary) {
        runCatching {
            repository.updateItemFlags(item.id, !item.isFavorite, item.isUnsorted)
        }.onSuccess {
            Toast.makeText(this, if (item.isFavorite) getString(R.string.main_toggle_favorite_from_summary) else getString(R.string.main_toggle_favorite_from_summary_2), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_toggle_favorite_from_summary_3), Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveSummaryToTrash(item: jp.viastrasse.cabinet.data.CabinetItemSummary) {
        runCatching {
            repository.moveToTrash(item.id)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_move_summary_to_trash, item.displayName), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_move_to_trash_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTag(itemId: String, tagName: String) {
        runCatching {
            repository.addTag(itemId, tagName)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_add_tag), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeTag(itemId: String, tagId: String) {
        runCatching {
            repository.removeTag(itemId, tagId)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_remove_tag), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToCollection(itemId: String, collectionTitle: String) {
        runCatching {
            repository.addToCollection(itemId, collectionTitle)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_add_to_collection), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFromCollection(itemId: String, collectionId: String) {
        runCatching {
            repository.removeFromCollection(itemId, collectionId)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_remove_from_collection), Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveToTrash(itemId: String) {
        runCatching {
            repository.moveToTrash(itemId)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_move_to_trash), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_move_to_trash_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreFromTrash(itemId: String) {
        runCatching {
            repository.restoreFromTrash(itemId)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_restore_from_trash), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_restore_from_trash_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePermanently(itemId: String) {
        runCatching {
            repository.deletePermanently(itemId)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_delete_permanently), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_delete_permanently_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun duplicateItem(itemId: String) {
        runCatching {
            repository.duplicateItem(itemId)
        }.onSuccess { item ->
            Toast.makeText(this, getString(R.string.msg_duplicated_named, item.displayName), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.msg_duplicate_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameItem(itemId: String, newDisplayName: String) {
        runCatching {
            repository.renameItem(itemId, newDisplayName)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_rename_item), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.main_import_version_file), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val displayName = sanitizeFileName(displayNameForUri(uri))
            val versionsDir = File(filesDir, "versions").apply { mkdirs() }
            val destination = uniqueDestination(versionsDir, displayName)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { getString(R.string.msg_file_open_failed) }
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
            Toast.makeText(this, getString(R.string.main_import_version_file_3), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_import_version_file_4), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }
    }

    private fun setCurrentVersion(itemId: String, versionId: String) {
        runCatching {
            repository.setCurrentVersion(itemId, versionId)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_set_current_version), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_set_current_version_2), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 資料の名前変更。以前は確認なしで `-renamed` を付けるだけだったため、
     * 現在の名前を初期値としたダイアログで入力させる。
     */
    private fun showRenameItemDialog(itemId: String, currentName: String) {
        val input = darkInput(getString(R.string.hint_new_name)).apply {
            setText(currentName)
            setSelection(currentName.substringBeforeLast('.', currentName).length)
        }
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.action_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.action_change)) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank() && value != currentName) {
                    renameItem(itemId, value)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun confirmDeletePermanently(itemId: String, displayName: String) {
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.action_delete_permanently))
            .setMessage(getString(R.string.main_confirm_delete_permanently, displayName))
            .setPositiveButton(getString(R.string.action_delete_permanently)) { _, _ -> deletePermanently(itemId) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun openViewer(
        path: String,
        mimeType: String,
        title: String,
        navigationItems: List<ViewerNavigationItem> = emptyList(),
        viewDisplayName: String = title,
    ) {
        if (isApkFile(path, mimeType, title)) {
            requestApkInstall(path)
            return
        }
        if (useViastrasseView() && ViastrasseViewLauncher.isInstalled(this)) {
            val sourceUri = when {
                path.startsWith("content://") -> Uri.parse(path)
                path.startsWith("file://") -> runCatching {
                    FileProvider.getUriForFile(
                        this,
                        "jp.viastrasse.cabinet.fileprovider",
                        java.io.File(requireNotNull(Uri.parse(path).path)),
                    )
                }.getOrNull()
                else -> runCatching {
                    FileProvider.getUriForFile(
                        this,
                        "jp.viastrasse.cabinet.fileprovider",
                        java.io.File(path),
                    )
                }.getOrNull()
            }
            if (sourceUri != null && ViastrasseViewLauncher.openDocument(
                    context = this,
                    uri = sourceUri,
                    mimeType = mimeType,
                    displayName = viewDisplayName,
                )
            ) {
                return
            }
        }
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

    private fun isApkFile(path: String, mimeType: String, title: String): Boolean {
        return mimeType == APK_MIME_TYPE ||
            extensionOf(title).equals("apk", ignoreCase = true) ||
            path.substringBefore('?').substringBefore('#').endsWith(".apk", ignoreCase = true)
    }

    private fun requestApkInstall(path: String) {
        val apkUri = runCatching {
            when {
                path.startsWith("content://") -> Uri.parse(path)
                path.startsWith("file://") -> Uri.parse(path).path?.let(::File)
                    ?.let { FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, it) }
                else -> FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, File(path))
            }
        }.getOrNull()
        if (apkUri == null) {
            Toast.makeText(this, getString(R.string.apk_install_file_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        if (!canRequestApkInstall()) {
            pendingApkInstallUri = apkUri
            AlertDialog.Builder(this, R.style.CabinetDialogTheme)
                .setTitle(R.string.apk_install_permission_title)
                .setMessage(R.string.apk_install_permission_message)
                .setPositiveButton(R.string.main_prompt_all_files_access_3) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> pendingApkInstallUri = null }
                .show()
            return
        }
        launchApkInstaller(apkUri)
    }

    private fun canRequestApkInstall(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    private fun launchApkInstaller(apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.apk_install_launch_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
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

    private fun openRegisteredItem(
        itemId: String,
        path: String,
        mimeType: String,
        title: String,
        viewDisplayName: String,
    ) {
        runCatching {
            repository.markOpened(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_registered_item), Toast.LENGTH_SHORT).show()
        }
        openViewer(
            path = path,
            mimeType = mimeType,
            title = title,
            viewDisplayName = viewDisplayName,
        )
    }

    private fun openProtectedAwareItem(
        itemId: String,
        detail: jp.viastrasse.cabinet.data.CabinetItemDetail,
    ) {
        runAfterProtectionCheck(detail) {
            if (detail.remote != null && (!detail.remote.isCached || !File(detail.path).isFile)) {
                downloadAndOpenRemoteItem(itemId, detail)
                return@runAfterProtectionCheck
            }
            openRegisteredItem(
                itemId = itemId,
                path = detail.path,
                mimeType = detail.item.mimeType,
                title = detail.item.title,
                viewDisplayName = detail.item.displayName,
            )
        }
    }

    private fun shareProtectedAwareItem(detail: jp.viastrasse.cabinet.data.CabinetItemDetail) {
        runAfterProtectionCheck(detail) {
            shareItem(detail)
        }
    }

    private fun runAfterProtectionCheck(
        detail: jp.viastrasse.cabinet.data.CabinetItemDetail,
        action: () -> Unit,
    ) {
        if (!detail.isProtected) {
            action()
            return
        }
        showPinDialog(
            title = getString(R.string.main_run_after_protection_check),
            positiveLabel = getString(R.string.action_verify),
        ) { pin ->
            runCatching {
                repository.verifySecurityPin(pin)
            }.onSuccess { verified ->
                if (verified) {
                    action()
                } else {
                    Toast.makeText(this, getString(R.string.msg_pin_mismatch), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.msg_pin_verify_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareItem(detail: jp.viastrasse.cabinet.data.CabinetItemDetail) {
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
            require(file.exists()) { getString(R.string.main_share_item) }
            val uri = FileProvider.getUriForFile(
                this,
                "jp.viastrasse.cabinet.fileprovider",
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
            Toast.makeText(this, error.message ?: getString(R.string.main_share_item_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheRemoteFile(itemId: String, detail: jp.viastrasse.cabinet.data.CabinetItemDetail) {
        val remote = detail.remote
        if (remote == null || remote.webUrl.isBlank()) {
            Toast.makeText(this, getString(R.string.main_cache_remote_file), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.main_cache_remote_file_2), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, getString(R.string.main_cache_remote_file_3), Toast.LENGTH_SHORT).show()
                    openDetail(itemId)
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.main_cache_remote_file_4), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createZip(detail: jp.viastrasse.cabinet.data.CabinetItemDetail) {
        runCatching {
            repository.createZipFromItem(detail)
        }.onSuccess { item ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, getString(R.string.main_create_zip, item.displayName), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_create_zip_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractZip(detail: jp.viastrasse.cabinet.data.CabinetItemDetail) {
        runCatching {
            repository.extractZipItem(detail)
        }.onSuccess { items ->
            PreviewWorker.enqueue(applicationContext)
            Toast.makeText(this, getString(R.string.main_extract_zip, items.size), Toast.LENGTH_SHORT).show()
            renderDashboard()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_extract_zip_2), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, error.message ?: getString(R.string.msg_reference_add_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReferenceUri(uri: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_reference_uri), Toast.LENGTH_SHORT).show()
        }
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

    private fun showReferenceDialog(itemId: String, referenceType: String, sourceApp: String, uriPrefix: String) {
        val sourceIdInput = darkInput(getString(R.string.main_show_reference_dialog))
        val titleInput = darkInput(getString(R.string.hint_display_name))
        val uriInput = darkInput("URI").apply {
            setText(uriPrefix)
        }
        val container = dialogContainer(sourceIdInput, titleInput, uriInput)
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.title_add_reference, sourceApp))
            .setView(container)
            .setPositiveButton(getString(R.string.action_add)) { _, _ ->
                val sourceId = sourceIdInput.text.toString().trim().ifBlank { "manual-${System.currentTimeMillis()}" }
                val title = titleInput.text.toString().trim().ifBlank { "$sourceApp 参照" }
                val uri = uriInput.text.toString().trim().ifBlank { "$uriPrefix$sourceId" }
                addReference(itemId, referenceType, sourceApp, sourceId, title, uri)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun showProtectedMemoDialog(itemId: String) {
        showTextDialog(getString(R.string.action_add_protected_memo), getString(R.string.hint_memo_body), getString(R.string.action_add), multiline = true) { body ->
            addProtectedMemo(itemId, body)
        }
    }

    private fun showUnlockProtectedMemosDialog(itemId: String) {
        showPinDialog(
            title = getString(R.string.action_show_protected_memo),
            positiveLabel = getString(R.string.action_show),
        ) { pin ->
            openUnlockedDetail(itemId, pin)
        }
    }

    private fun openUnlockedDetail(itemId: String, pin: String) {
        runCatching {
            repository.detailUnlocked(itemId, pin)
        }.onSuccess { detail ->
            showDetail(itemId, detail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_unlocked_detail), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMemoDialog(itemId: String) {
        showTextDialog(getString(R.string.action_add_memo), getString(R.string.hint_memo_body), getString(R.string.action_add), multiline = true) { body ->
            addMemo(itemId, body)
        }
    }

    private fun showOcrTextDialog(itemId: String) {
        showTextDialog(getString(R.string.action_add_ocr_text), getString(R.string.main_show_ocr_text_dialog), getString(R.string.action_add), multiline = true) { body ->
            addOcrText(itemId, body)
        }
    }

    private fun addMemo(itemId: String, body: String) {
        runCatching {
            repository.addMemo(itemId, body, false)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_add_memo), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addOcrText(itemId: String, body: String) {
        runCatching {
            repository.addOcrText(itemId, body, "manual")
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_add_ocr_text), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runImageOcr(itemId: String, path: String, mimeType: String) {
        val file = File(path)
        val isPdf = mimeType == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true)
        if ((!mimeType.startsWith("image/") && !isPdf) || !file.exists()) {
            Toast.makeText(this, getString(R.string.main_run_image_ocr), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.main_run_image_ocr_2), Toast.LENGTH_SHORT).show()
        val onSuccess: (String) -> Unit = { text ->
            if (text.isBlank()) {
                Toast.makeText(this, getString(R.string.main_run_image_ocr_3), Toast.LENGTH_SHORT).show()
            } else {
                saveRecognizedText(itemId, text, if (isPdf) "mlkit-pdf-first-page" else "mlkit-japanese")
            }
        }
        val onFailure: (Throwable) -> Unit = { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_run_image_ocr_4), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.main_save_recognized_text), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_save_recognized_text_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateThumbnail(itemId: String, path: String, mimeType: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.main_generate_thumbnail), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val thumbnail = ThumbnailGenerator.createSupportedThumbnail(file, mimeType, File(filesDir, "thumbnails"))
            repository.setItemThumbnail(itemId, thumbnail.absolutePath)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_generate_thumbnail_2), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_generate_thumbnail_3), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addProtectedMemo(itemId: String, body: String) {
        runCatching {
            repository.addMemo(itemId, body, true)
        }.onSuccess {
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_add_protected_memo), Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    onText(value)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    /** ダイアログ用の入力欄。見た目は [cabinetInput] に集約している。 */
    private fun darkInput(hintText: String): EditText = cabinetInput(hintText)

    /**
     * 複数入力を持つダイアログの器。
     * 以前は各ダイアログが px 直値の padding を持ち、入力欄も密着していた。
     */
    private fun dialogContainer(vararg inputs: android.view.View): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val horizontal = dpPx(CabinetMetrics.SPACE_XL)
            setPadding(horizontal, dpPx(CabinetMetrics.SPACE_SM), horizontal, 0)
            inputs.forEachIndexed { index, view ->
                addView(
                    view,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) topMargin = dpPx(CabinetMetrics.SPACE_SM)
                    },
                )
            }
        }
    }

    private fun dpPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun protectItem(itemId: String) {
        runCatching {
            repository.setItemProtected(itemId, true)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_protect_item), Toast.LENGTH_SHORT).show()
            openDetail(itemId)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_protect_item_2), Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, getString(R.string.main_import_folder_2, imported), Toast.LENGTH_SHORT).show()
        renderDashboard()
    }

    private fun uniqueDestination(directory: File, displayName: String): File =
        CabinetFiles.uniqueDestination(directory, displayName)

    private fun uniqueDirectory(directory: File, displayName: String): File =
        CabinetFiles.uniqueDirectory(directory, displayName)

    private fun duplicateDisplayName(displayName: String): String =
        CabinetFiles.duplicateDisplayName(displayName)

    private fun sanitizeFileName(value: String): String =
        CabinetFiles.sanitizeFileName(value)

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
            Toast.makeText(this, error.message ?: getString(R.string.main_process_preview_queue), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 設定画面を表示する。
     *
     * 設定変更後の再描画でも同じタブに留まるよう、選択中タブは [settingsTab] で
     * 保持する。引数で明示したときだけタブを切り替える。
     */
    private fun openSettings(tab: SettingsTab? = null) {
        if (tab != null) {
            settingsTab = tab
        }
        runCatching {
            validatedStorageProviderSettings() to repository.duplicateReport()
        }.onSuccess { (settings, duplicateReport) ->
            showDashboardView()
            isDashboardVisible = false
            settingsBackTarget = null
            navigateUpTarget = null
            quickAccessEditing = false
            dashboardView.renderSettings(
                settings = settings,
                duplicateReport = duplicateReport,
                selectedTab = settingsTab,
                onTabSelected = { selected -> openSettings(selected) },
                onBack = ::renderDashboard,
                onCreateSmartFolder = ::showCreateSmartFolderDialog,
                selectedProviderId = selectedStorageProviderId(settings.providers),
                displayMode = displayModePreference(),
                fontPreference = fileListDisplayPreference(),
                onDisplayModeSelected = ::updateDisplayModePreference,
                onFontPreferenceChanged = ::updateFileListDisplayPreference,
                viewInstalled = ViastrasseViewLauncher.isInstalled(this),
                useViastrasseView = useViastrasseView(),
                onUseViastrasseViewChanged = ::updateUseViastrasseView,
                onAbout = ::openAbout,
                onExportBackup = ::exportBackup,
                onRunBackupNow = ::runBackupNow,
                onImportBackup = ::openBackupPicker,
                onRestoreLatestBackup = ::restoreLatestLocalBackup,
                onSetPin = ::showSetPinDialog,
                onVerifyPin = ::showVerifyPinDialog,
                onDuplicateItemSelected = ::openItemFromList,
                onProviderSelected = ::updateSelectedStorageProvider,
                onOpenProvider = ::openStorageProvider,
                onConfigureProvider = ::showStorageProviderDialog,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_open_settings), Toast.LENGTH_SHORT).show()
        }
    }

    private fun validatedStorageProviderSettings(): jp.viastrasse.cabinet.data.SettingsSnapshot {
        var settings = repository.settings()
        val persistedUris = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val invalidProviders = settings.providers.filter { provider ->
            provider.remoteRoot.startsWith("content://") && provider.remoteRoot !in persistedUris
        }
        invalidProviders.forEach { provider ->
            repository.updateStorageProvider(
                provider.id,
                provider.toConfiguration().copy(remoteRoot = ""),
            )
        }
        if (invalidProviders.isNotEmpty()) {
            settings = repository.settings()
        }
        return settings
    }

    private fun openAbout() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        isDashboardVisible = false
        navigateUpTarget = null
        // ファミリー共通の全画面レイアウトなので、スクロール面ではなく
        // Activity のコンテンツごと差し替える。
        settingsBackTarget = ::openSettings
        setContentView(
            dashboardView.buildAboutView(
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                },
                onBack = ::openSettings,
                onOpenViastrasseFamily = {
                    ViastrasseFamilyLauncher.open(
                        context = this,
                        currentApp = "cabinet",
                    )
                },
            ),
        )
    }

    /** About から戻るときなど、通常のスクロール面へ表示を戻す。 */
    private fun showDashboardView() {
        if (dashboardView.parent == null) {
            setContentView(dashboardView)
        }
    }

    private fun displayModePreference(): String {
        val value = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DISPLAY_MODE, DISPLAY_MODE_COMPACT)
        return normalizeDisplayMode(value)
    }

    private fun updateDisplayModePreference(displayMode: String) {
        val normalized = normalizeDisplayMode(displayMode)
        getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, normalized)
            .apply()
        Toast.makeText(this, getString(R.string.main_update_display_mode_preference), Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun fileListDisplayPreference(): FileListDisplayPreference {
        val prefs = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
        fun fontSize(key: String, legacyKey: String): Int {
            val legacy = prefs.getInt(legacyKey, FileListDisplayPreference.DEFAULT_FONT_SIZE)
            return prefs.getInt(key, legacy).coerceIn(
                FileListDisplayPreference.MIN_FONT_SIZE,
                FileListDisplayPreference.MAX_FONT_SIZE,
            )
        }
        return FileListDisplayPreference(
            kindFontSize = fontSize(KEY_FILE_LIST_KIND_FONT_SIZE, LEGACY_KEY_FILE_LIST_FROM_FONT_SIZE),
            nameFontSize = fontSize(KEY_FILE_LIST_NAME_FONT_SIZE, LEGACY_KEY_FILE_LIST_SUBJECT_FONT_SIZE),
            metaFontSize = fontSize(KEY_FILE_LIST_META_FONT_SIZE, LEGACY_KEY_FILE_LIST_BODY_FONT_SIZE),
        )
    }

    private fun updateFileListDisplayPreference(preference: FileListDisplayPreference) {
        fun clamp(value: Int) = value.coerceIn(
            FileListDisplayPreference.MIN_FONT_SIZE,
            FileListDisplayPreference.MAX_FONT_SIZE,
        )
        getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_FILE_LIST_KIND_FONT_SIZE, clamp(preference.kindFontSize))
            .putInt(KEY_FILE_LIST_NAME_FONT_SIZE, clamp(preference.nameFontSize))
            .putInt(KEY_FILE_LIST_META_FONT_SIZE, clamp(preference.metaFontSize))
            .remove(LEGACY_KEY_FILE_LIST_FROM_FONT_SIZE)
            .remove(LEGACY_KEY_FILE_LIST_SUBJECT_FONT_SIZE)
            .remove(LEGACY_KEY_FILE_LIST_BODY_FONT_SIZE)
            .apply()
        Toast.makeText(this, getString(R.string.main_clamp), Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun appPrefs() = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE)

    private fun useViastrasseView(): Boolean = appPrefs().getBoolean(KEY_USE_VIASTRASSE_VIEW, false)

    private fun updateUseViastrasseView(enabled: Boolean) {
        appPrefs().edit().putBoolean(KEY_USE_VIASTRASSE_VIEW, enabled).apply()
        openSettings()
    }

    private fun selectedStorageProviderId(providers: List<StorageProviderAccountSummary>): String {
        val saved = appPrefs().getString(KEY_SELECTED_STORAGE_PROVIDER, null)
        return providers.firstOrNull { it.id == saved }?.id ?: providers.firstOrNull()?.id.orEmpty()
    }

    // ---------------------------------------------------------------------
    // クイックアクセス
    // ---------------------------------------------------------------------

    private fun addQuickAccessEntry(entry: QuickAccessEntry) {
        val current = quickAccessStore.load()
        if (current.any { it.isSameTarget(entry) }) {
            Toast.makeText(this, getString(R.string.quick_access_exists), Toast.LENGTH_SHORT).show()
            return
        }
        if (current.size >= QuickAccessStore.MAX_ENTRIES) {
            Toast.makeText(
                this,
                getString(R.string.quick_access_full, QuickAccessStore.MAX_ENTRIES),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        quickAccessStore.add(entry)
        Toast.makeText(this, getString(R.string.quick_access_added), Toast.LENGTH_SHORT).show()
        // TOP を表示中なら、その場でタイルを反映する。
        if (isDashboardVisible) renderDashboard()
    }

    private fun openQuickAccessEntry(entry: QuickAccessEntry) {
        // 別画面へ移るので、編集モードは持ち越さない。
        quickAccessEditing = false
        when (entry.type) {
            QuickAccessType.FOLDER -> {
                val directory = File(entry.target)
                if (!directory.isDirectory) {
                    notifyQuickAccessMissing(entry)
                    return
                }
                openLocalExplorer(directory)
            }
            QuickAccessType.FILE -> {
                val file = File(entry.target)
                if (!file.isFile) {
                    notifyQuickAccessMissing(entry)
                    return
                }
                openViewer(
                    path = file.absolutePath,
                    mimeType = mimeTypeFor(file),
                    title = file.name,
                    navigationItems = emptyList(),
                )
            }
            QuickAccessType.SAF_FOLDER -> {
                val treeUri = entry.treeUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                if (treeUri == null) {
                    notifyQuickAccessMissing(entry)
                    return
                }
                runCatching {
                    openDocumentTreePath(
                        treeUri = treeUri,
                        relativePath = entry.relativePath,
                        title = entry.label,
                        onRootBack = ::renderDashboard,
                        onChooseRoot = ::openSdCardPicker,
                    )
                }.onFailure {
                    notifyQuickAccessMissing(entry)
                }
            }
            QuickAccessType.SAF_FILE -> {
                val uri = Uri.parse(entry.target)
                val document = DocumentFile.fromSingleUri(this, uri)
                if (document?.isFile != true) {
                    notifyQuickAccessMissing(entry)
                    return
                }
                openViewer(
                    path = entry.target,
                    mimeType = document.type.orEmpty().ifBlank {
                        mimeTypeForExtension(extensionOf(entry.label)) ?: "application/octet-stream"
                    },
                    title = entry.label,
                    navigationItems = emptyList(),
                )
            }
            QuickAccessType.CONTENT_FILE -> {
                val uri = Uri.parse(entry.target)
                val canRead = runCatching {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (!canRead) {
                    notifyQuickAccessMissing(entry)
                    return
                }
                openViewer(
                    path = entry.target,
                    mimeType = entry.mimeType.ifBlank {
                        contentResolver.getType(uri).orEmpty().ifBlank {
                            mimeTypeForExtension(extensionOf(entry.label)) ?: "application/octet-stream"
                        }
                    },
                    title = entry.label,
                    navigationItems = emptyList(),
                )
            }
            QuickAccessType.REMOTE_FOLDER -> {
                val provider = connectedQuickAccessProvider(entry) ?: return
                openRemoteStorageDirectory(
                    provider = provider,
                    path = entry.target,
                    location = entry.remotePath.ifBlank { "/${entry.label}" },
                    parents = emptyList(),
                    onRootBack = ::renderDashboard,
                )
            }
            QuickAccessType.REMOTE_FILE -> {
                val provider = connectedQuickAccessProvider(entry) ?: return
                registerAndOpenRemoteFile(
                    provider,
                    RemoteStorageEntry(
                        id = entry.remoteId.ifBlank { entry.target },
                        path = entry.remotePath,
                        name = entry.label,
                        mimeType = entry.mimeType.ifBlank { "application/octet-stream" },
                        size = entry.size,
                        webUrl = entry.webUrl,
                    ),
                )
            }
            QuickAccessType.ITEM -> openItemFromList(entry.target)
        }
    }

    private fun connectedQuickAccessProvider(entry: QuickAccessEntry): StorageProviderAccountSummary? {
        val provider = repository.settings().providers.firstOrNull { it.id == entry.providerId }
        if (provider == null) {
            notifyQuickAccessMissing(entry)
            return null
        }
        if (provider.connectionStatus != "connected") {
            Toast.makeText(this, getString(R.string.storage_provider_not_connected, provider.displayName), Toast.LENGTH_SHORT).show()
            showStorageProviderDialog(provider)
            return null
        }
        return provider
    }

    /** 対象が消えている場合は、登録から外すところまで案内する。 */
    private fun notifyQuickAccessMissing(entry: QuickAccessEntry) {
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(entry.label)
            .setMessage(getString(R.string.quick_access_missing))
            .setPositiveButton(getString(R.string.quick_access_remove)) { _, _ ->
                removeQuickAccessEntry(entry)
            }
            .setNegativeButton(getString(R.string.action_close), null)
            .show()
    }

    private fun startQuickAccessEditing() {
        if (quickAccessEditing) return
        quickAccessEditing = true
        Toast.makeText(this, getString(R.string.quick_access_edit_started), Toast.LENGTH_SHORT).show()
        renderDashboard()
    }

    private fun finishQuickAccessEditing() {
        if (!quickAccessEditing) return
        quickAccessEditing = false
        renderDashboard()
    }

    /** 編集モード中にタイルをタップしたときの操作一覧。 */
    private fun showQuickAccessMenu(entry: QuickAccessEntry) {
        val actions = listOf(
            getString(R.string.quick_access_edit_done),
            getString(R.string.action_open),
            getString(R.string.quick_access_move),
            getString(R.string.quick_access_resize),
            getString(R.string.quick_access_rename),
            getString(R.string.quick_access_remove),
        )
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(entry.displayLabel)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> finishQuickAccessEditing()
                    1 -> {
                        finishQuickAccessEditing()
                        openQuickAccessEntry(entry)
                    }
                    2 -> Toast.makeText(this, R.string.quick_access_move_hint, Toast.LENGTH_SHORT).show()
                    3 -> showQuickAccessSizeDialog(entry)
                    4 -> showQuickAccessRenameDialog(entry)
                    else -> removeQuickAccessEntry(entry)
                }
            }
            .show()
    }

    private fun placeQuickAccessEntry(entry: QuickAccessEntry, column: Int, row: Int) {
        quickAccessStore.place(entry, column, row)
        renderDashboard()
    }

    private fun showQuickAccessRenameDialog(entry: QuickAccessEntry) {
        val input = EditText(this).apply {
            setText(entry.alias)
            hint = entry.label
            setSingleLine(true)
            selectAll()
        }
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(R.string.quick_access_rename)
            .setMessage(R.string.quick_access_rename_description)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                quickAccessStore.rename(entry, input.text.toString())
                renderDashboard()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showQuickAccessSizeDialog(entry: QuickAccessEntry) {
        val density = resources.displayMetrics.density
        val grid = GridLayout(this).apply {
            columnCount = QuickAccessEntry.GRID_COLUMNS
            rowCount = QuickAccessEntry.MAX_SPAN
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val dialog = AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.quick_access_resize_title, entry.displayLabel))
            .setView(grid)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        for (row in 0 until QuickAccessEntry.MAX_SPAN) {
            for (column in 0 until QuickAccessEntry.GRID_COLUMNS) {
                val columnSpan = column + 1
                val rowSpan = row + 1
                val selected = column < entry.columnSpan && row < entry.rowSpan
                grid.addView(
                    TextView(this).apply {
                        text = getString(R.string.quick_access_size_format, columnSpan, rowSpan)
                        gravity = Gravity.CENTER
                        setTextColor(if (selected) CabinetColors.AppBackground else CabinetColors.TextSecondary)
                        contentDescription = getString(R.string.quick_access_size_format, columnSpan, rowSpan)
                        background = GradientDrawable().apply {
                            cornerRadius = 6 * density
                            setColor(if (selected) CabinetColors.Accent else CabinetColors.SurfaceSunken)
                            setStroke((1 * density).toInt(), CabinetColors.HairlineStrong)
                        }
                        setOnClickListener {
                            quickAccessStore.resize(entry, columnSpan, rowSpan)
                            dialog.dismiss()
                            renderDashboard()
                        }
                        layoutParams = GridLayout.LayoutParams(
                            GridLayout.spec(row),
                            GridLayout.spec(column, 1f),
                        ).apply {
                            width = 0
                            height = (58 * density).toInt()
                            val margin = (3 * density).toInt()
                            setMargins(margin, margin, margin, margin)
                        }
                    },
                )
            }
        }
        dialog.show()
    }

    private fun removeQuickAccessEntry(entry: QuickAccessEntry) {
        quickAccessStore.remove(entry)
        Toast.makeText(this, getString(R.string.quick_access_removed), Toast.LENGTH_SHORT).show()
        renderDashboard()
    }

    private fun updateSelectedStorageProvider(provider: StorageProviderAccountSummary) {
        appPrefs().edit().putString(KEY_SELECTED_STORAGE_PROVIDER, provider.id).apply()
        openSettings()
    }

    private fun showStorageProviderDialog(provider: StorageProviderAccountSummary) {
        when (provider.providerType) {
            "local" -> {
                AlertDialog.Builder(this, R.style.CabinetDialogTheme)
                    .setTitle(getString(R.string.main_show_storage_provider_dialog_3, provider.displayName))
                    .setMessage(getString(R.string.storage_provider_local_description))
                    .setPositiveButton(getString(R.string.action_close), null)
                    .show()
                return
            }
            "usb", "sdcard" -> {
                val dialog = AlertDialog.Builder(this, R.style.CabinetDialogTheme)
                    .setTitle(getString(R.string.main_show_storage_provider_dialog_3, provider.displayName))
                    .setMessage(getString(R.string.storage_provider_document_provider_description, provider.displayName))
                    .setPositiveButton(getString(R.string.storage_provider_choose_folder)) { _, _ ->
                        pendingStorageProviderId = provider.id
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            },
                            REQUEST_STORAGE_PROVIDER_TREE,
                        )
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                if (provider.connectionStatus == "connected") {
                    dialog.setNeutralButton(getString(R.string.storage_provider_disconnect)) { _, _ ->
                        disconnectStorageProvider(provider)
                    }
                }
                dialog.show()
                return
            }
            "dropbox", "google_drive", "onedrive", "box" -> {
                showOAuthProviderDialog(provider)
                return
            }
            "webdav", "nextcloud", "smb" -> {
                showNetworkProviderDialog(provider)
                return
            }
            else -> error(getString(R.string.storage_provider_unsupported, provider.displayName))
        }
    }

    private fun showOAuthProviderDialog(provider: StorageProviderAccountSummary) {
        val config = requireNotNull(OAuthProviderConfig.forProvider(provider.providerType))
        val builder = AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.main_show_storage_provider_dialog_3, provider.displayName))
            .setMessage(
                if (config.clientId.isBlank() || (provider.providerType == "box" && config.clientSecret.isBlank())) {
                    getString(R.string.storage_provider_oauth_client_missing, provider.displayName)
                } else {
                    getString(R.string.storage_provider_oauth_description, provider.displayName)
                },
            )
            .setNegativeButton(getString(R.string.action_cancel), null)
        if (config.clientId.isNotBlank() && (provider.providerType != "box" || config.clientSecret.isNotBlank())) {
            builder.setPositiveButton(getString(R.string.storage_provider_connect)) { _, _ ->
                runCatching {
                    OAuthController(this).authorizationUri(provider.providerType)
                }.onSuccess { authorizationUri ->
                    startActivity(Intent(Intent.ACTION_VIEW, authorizationUri))
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: getString(R.string.storage_provider_auth_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (provider.connectionStatus == "connected") {
            builder.setNeutralButton(getString(R.string.storage_provider_disconnect)) { _, _ ->
                disconnectStorageProvider(provider)
            }
        }
        builder.show()
    }

    private fun showNetworkProviderDialog(provider: StorageProviderAccountSummary) {
        val accountInput = darkInput(getString(R.string.storage_provider_account_name)).apply { setText(provider.accountName) }
        val endpointInput = darkInput(
            getString(if (provider.providerType == "smb") R.string.storage_provider_server else R.string.storage_provider_endpoint),
        ).apply {
            setText(provider.endpointUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val usernameInput = darkInput(getString(R.string.storage_provider_username)).apply { setText(provider.username) }
        val rootInput = darkInput(
            getString(if (provider.providerType == "smb") R.string.storage_provider_share else R.string.storage_provider_remote_root),
        ).apply { setText(provider.remoteRoot.ifBlank { "/" }) }
        val domainInput = darkInput(getString(R.string.storage_provider_domain)).apply { setText(provider.domain) }
        val passwordInput = darkInput(getString(R.string.storage_provider_password)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val views = buildList {
            add(accountInput)
            add(endpointInput)
            add(usernameInput)
            add(passwordInput)
            add(rootInput)
            if (provider.providerType == "smb") add(domainInput)
        }
        val builder = AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.main_show_storage_provider_dialog_3, provider.displayName))
            .setView(dialogContainer(*views.toTypedArray()))
            .setPositiveButton(getString(R.string.storage_provider_connect)) { _, _ ->
                connectNetworkProvider(
                    provider,
                    StorageProviderConfiguration(
                        accountName = accountInput.text.toString().trim(),
                        endpointUrl = endpointInput.text.toString().trim(),
                        username = usernameInput.text.toString().trim(),
                        remoteRoot = rootInput.text.toString().trim(),
                        domain = domainInput.text.toString().trim(),
                        cachePolicy = provider.cachePolicy,
                    ),
                    passwordInput.text.toString(),
                )
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
        if (provider.connectionStatus == "connected") {
            builder.setNeutralButton(getString(R.string.storage_provider_disconnect)) { _, _ ->
                disconnectStorageProvider(provider)
            }
        }
        builder.show()
    }

    private fun connectNetworkProvider(
        provider: StorageProviderAccountSummary,
        configuration: StorageProviderConfiguration,
        password: String,
    ) {
        Toast.makeText(this, getString(R.string.storage_provider_connecting, provider.displayName), Toast.LENGTH_SHORT).show()
        thread(name = "cabinet-provider-connect") {
            runCatching {
                SecureCredentialStore.save(applicationContext, provider.id, "password", password)
                val candidate = provider.copy(
                    accountName = configuration.accountName,
                    endpointUrl = configuration.endpointUrl,
                    username = configuration.username,
                    remoteRoot = configuration.remoteRoot,
                    domain = configuration.domain,
                )
                StorageProviderFactory(applicationContext).create(candidate).testConnection()
                repository.updateStorageProvider(provider.id, configuration)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.storage_provider_connected, provider.displayName), Toast.LENGTH_SHORT).show()
                    openSettings(SettingsTab.INTEGRATION)
                }
            }.onFailure { error ->
                SecureCredentialStore.clear(applicationContext, provider.id)
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.storage_provider_connection_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleStorageOAuthCallback(uri: Uri) {
        Toast.makeText(this, getString(R.string.storage_provider_auth_processing), Toast.LENGTH_SHORT).show()
        thread(name = "cabinet-provider-oauth") {
            runCatching {
                val providerId = OAuthController(applicationContext).finish(uri)
                val provider = repository.settings().providers.first { it.providerType == providerId }
                StorageProviderFactory(applicationContext).create(provider).testConnection()
                repository.updateStorageProvider(
                    provider.id,
                    provider.toConfiguration().copy(
                        accountName = provider.displayName,
                        remoteRoot = "direct://$providerId",
                    ),
                )
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.storage_provider_auth_complete), Toast.LENGTH_SHORT).show()
                    openSettings(SettingsTab.INTEGRATION)
                }
            }.onFailure { error ->
                runOnUiThread {
                    showStorageProviderError(error, R.string.storage_provider_auth_failed)
                    openSettings(SettingsTab.INTEGRATION)
                }
            }
        }
    }

    private fun openStorageProvider(
        provider: StorageProviderAccountSummary,
        onRootBack: () -> Unit = ::openSettings,
    ) {
        if (provider.providerType == "local") {
            openMode("explorer")
            return
        }
        val treeUri = provider.remoteRoot
            .takeIf { it.startsWith("content://") }
            ?.let(Uri::parse)
        if (treeUri != null) {
            runCatching {
                openDocumentTreeRoot(
                    treeUri = treeUri,
                    title = provider.displayName,
                    onRootBack = onRootBack,
                    onChooseRoot = { showStorageProviderDialog(provider) },
                )
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.storage_provider_access_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (provider.connectionStatus != "connected") {
            Toast.makeText(this, getString(R.string.storage_provider_not_connected, provider.displayName), Toast.LENGTH_SHORT).show()
            showStorageProviderDialog(provider)
            return
        }
        syncAndOpenStorageProvider(provider, onRootBack)
    }

    private fun syncAndOpenStorageProvider(provider: StorageProviderAccountSummary, onRootBack: () -> Unit) {
        openRemoteStorageDirectory(provider, "", "/", emptyList(), onRootBack)
    }

    private fun openRemoteStorageDirectory(
        provider: StorageProviderAccountSummary,
        path: String,
        location: String,
        parents: List<RemoteFolderState>,
        onRootBack: () -> Unit,
    ) {
        Toast.makeText(this, getString(R.string.storage_provider_loading, provider.displayName), Toast.LENGTH_SHORT).show()
        thread(name = "cabinet-provider-list") {
            runCatching {
                StorageProviderFactory(applicationContext).create(provider).listFolder(path)
            }.onSuccess { rawEntries ->
                runOnUiThread {
                    val entries = applyRemoteStorageListOptions(rawEntries)
                    isDashboardVisible = false
                    navigateUpTarget = parents.lastOrNull()?.let { parent ->
                        { openRemoteStorageDirectory(provider, parent.key, parent.location, parents.dropLast(1), onRootBack) }
                    } ?: onRootBack
                    dashboardView.renderRemoteStorage(
                        title = provider.displayName,
                        location = location,
                        entries = entries,
                        displayMode = displayModePreference(),
                        fontPreference = fileListDisplayPreference(),
                        listOptions = fileListOptions,
                        onListOptionsChanged = { options ->
                            fileListOptions = options
                            openRemoteStorageDirectory(provider, path, location, parents, onRootBack)
                        },
                        onBack = onRootBack,
                        onParent = parents.lastOrNull()?.let { parent ->
                            { openRemoteStorageDirectory(provider, parent.key, parent.location, parents.dropLast(1), onRootBack) }
                        },
                        onOpenDirectory = { entry ->
                            val childLocation = if (location == "/") "/${entry.name}" else "$location/${entry.name}"
                            openRemoteStorageDirectory(
                                provider,
                                entry.navigationKey,
                                childLocation,
                                parents + RemoteFolderState(path, location),
                                onRootBack,
                            )
                        },
                        onOpenFile = { entry -> registerAndOpenRemoteFile(provider, entry) },
                        onRegisterFile = { entry -> registerRemoteStorageEntry(provider, entry, openAfterRegistration = false) },
                        onAddQuickAccess = { entry -> addQuickAccessEntry(remoteQuickAccessEntry(provider, entry)) },
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    showStorageProviderError(error, R.string.storage_provider_access_failed)
                }
            }
        }
    }

    private fun remoteQuickAccessEntry(
        provider: StorageProviderAccountSummary,
        entry: RemoteStorageEntry,
    ): QuickAccessEntry = QuickAccessEntry(
        type = if (entry.isDirectory) QuickAccessType.REMOTE_FOLDER else QuickAccessType.REMOTE_FILE,
        target = if (entry.isDirectory) entry.navigationKey else entry.id,
        label = entry.name,
        kind = if (entry.isDirectory) "DIR" else extensionOf(entry.name).uppercase().ifBlank { "FILE" },
        providerId = provider.id,
        remoteId = entry.id,
        remotePath = entry.path,
        mimeType = entry.mimeType,
        size = entry.size,
        webUrl = entry.webUrl,
    )

    private fun applyRemoteStorageListOptions(entries: List<RemoteStorageEntry>): List<RemoteStorageEntry> {
        val cutoff = fileListOptions.periodDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        val filtered = entries.filter { entry ->
            val matchesName = fileListOptions.nameQuery.isBlank() ||
                entry.name.contains(fileListOptions.nameQuery, ignoreCase = true)
            if (!matchesName) return@filter false
            if (entry.isDirectory) return@filter true
            val modifiedAt = remoteStorageModifiedAtMillis(entry.modifiedAt)
            val matchesPeriod = cutoff == null || modifiedAt == null || modifiedAt >= cutoff
            val matchesExtension = fileListOptions.extensionQuery.isBlank() ||
                extensionOf(entry.name).equals(fileListOptions.extensionQuery.trimStart('.'), ignoreCase = true)
            matchesPeriod && matchesExtension
        }
        val sorted = when (fileListOptions.sort) {
            FileListSort.DATE_DESC -> filtered.sortedByDescending { remoteStorageModifiedAtMillis(it.modifiedAt) ?: Long.MIN_VALUE }
            FileListSort.DATE_ASC -> filtered.sortedBy { remoteStorageModifiedAtMillis(it.modifiedAt) ?: Long.MAX_VALUE }
            FileListSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            FileListSort.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            FileListSort.EXT_ASC -> filtered.sortedWith(compareBy<RemoteStorageEntry> { extensionOf(it.name).lowercase() }.thenBy { it.name.lowercase() })
            FileListSort.EXT_DESC -> filtered.sortedWith(compareByDescending<RemoteStorageEntry> { extensionOf(it.name).lowercase() }.thenBy { it.name.lowercase() })
        }
        return sorted.sortedWith(compareBy<RemoteStorageEntry> { !it.isDirectory })
    }

    private fun remoteStorageModifiedAtMillis(value: String): Long? = value
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun registerAndOpenRemoteFile(provider: StorageProviderAccountSummary, entry: RemoteStorageEntry) {
        registerRemoteStorageEntry(provider, entry, openAfterRegistration = true)
    }

    private fun registerRemoteStorageEntry(
        provider: StorageProviderAccountSummary,
        entry: RemoteStorageEntry,
        openAfterRegistration: Boolean,
    ) {
        thread(name = "cabinet-provider-register") {
            runCatching {
                repository.registerRemoteFile(
                    providerId = provider.id,
                    remoteFileId = entry.id,
                    remotePath = entry.path,
                    displayName = entry.name,
                    mimeType = entry.mimeType,
                    size = entry.size,
                    webUrl = entry.webUrl,
                    note = provider.displayName,
                )
            }.onSuccess { item ->
                runOnUiThread {
                    if (openAfterRegistration) {
                        openItemFromList(item.id)
                    } else {
                        Toast.makeText(this, getString(R.string.main_register_remote_file, entry.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.main_register_remote_file_2), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showStorageProviderError(error: Throwable, fallbackMessage: Int) {
        val detail = error.message.orEmpty()
        val message = if (
            detail.contains("files.metadata.read", ignoreCase = true) ||
            detail.contains("files/list_folder", ignoreCase = true)
        ) {
            getString(R.string.storage_provider_dropbox_permissions_missing)
        } else {
            detail.ifBlank { getString(fallbackMessage) }
        }
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(R.string.storage_provider_error_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun downloadAndOpenRemoteItem(
        itemId: String,
        detail: jp.viastrasse.cabinet.data.CabinetItemDetail,
    ) {
        val remote = requireNotNull(detail.remote)
        val provider = repository.settings().providers.firstOrNull { it.id == remote.providerAccountId }
        if (provider == null || provider.connectionStatus != "connected") {
            Toast.makeText(this, getString(R.string.storage_provider_not_connected, remote.providerAccountId), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.storage_provider_downloading, remote.displayName), Toast.LENGTH_SHORT).show()
        thread(name = "cabinet-provider-download") {
            runCatching {
                val destination = uniqueDestination(
                    File(filesDir, "remote-cache/${provider.id}").apply { mkdirs() },
                    sanitizeFileName(remote.displayName),
                )
                StorageProviderFactory(applicationContext).create(provider).download(
                    RemoteStorageEntry(
                        id = remote.remoteFileId,
                        path = remote.remotePath,
                        name = remote.displayName,
                        mimeType = remote.mimeType,
                        size = remote.size,
                        webUrl = remote.webUrl,
                    ),
                    destination,
                )
                repository.markRemoteFileCached(itemId, destination.absolutePath, destination.length())
                destination
            }.onSuccess { destination ->
                runOnUiThread {
                    openRegisteredItem(
                        itemId,
                        destination.absolutePath,
                        detail.item.mimeType,
                        detail.item.title,
                        detail.item.displayName,
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: getString(R.string.main_cache_remote_file_4), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectStorageProvider(provider: StorageProviderAccountSummary) {
        runCatching {
            provider.remoteRoot.takeIf { it.startsWith("content://") }?.let { savedUri ->
                val uri = Uri.parse(savedUri)
                val permission = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                val flags = (if (permission?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (flags != 0) {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        flags,
                    )
                }
            }
            SecureCredentialStore.clear(applicationContext, provider.id)
            repository.updateStorageProvider(
                provider.id,
                provider.toConfiguration().copy(accountName = "", remoteRoot = ""),
            )
        }.onSuccess {
            Toast.makeText(this, getString(R.string.storage_provider_disconnected, provider.displayName), Toast.LENGTH_SHORT).show()
            openSettings(SettingsTab.INTEGRATION)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_show_storage_provider_dialog_5), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveStorageProviderConfiguration(
        provider: StorageProviderAccountSummary,
        configuration: StorageProviderConfiguration,
    ) {
        runCatching {
            repository.updateStorageProvider(provider.id, configuration)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_show_storage_provider_dialog_4), Toast.LENGTH_SHORT).show()
            openSettings(SettingsTab.INTEGRATION)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_show_storage_provider_dialog_5), Toast.LENGTH_SHORT).show()
        }
    }

    private fun StorageProviderAccountSummary.toConfiguration() = StorageProviderConfiguration(
        accountName = accountName,
        endpointUrl = endpointUrl,
        username = username,
        remoteRoot = remoteRoot,
        domain = domain,
        cachePolicy = cachePolicy,
    )

    private fun showCreateSmartFolderDialog() {
        val presets = arrayOf("pdf", "image", "url", "remote", "ocr", "protected", "favorites", "unsorted", "trash")
        val titleInput = darkInput(getString(R.string.main_show_create_smart_folder_dialog))
        val presetInput = darkInput("preset: ${presets.joinToString(" / ")}").apply {
            setText("pdf")
        }
        val container = dialogContainer(titleInput, presetInput)
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.main_show_create_smart_folder_dialog_2))
            .setView(container)
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                createSmartFolder(
                    title = titleInput.text.toString().trim().ifBlank { "Smart Folder" },
                    preset = presetInput.text.toString().trim().ifBlank { "pdf" },
                )
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun createSmartFolder(title: String, preset: String) {
        runCatching {
            repository.createSmartFolder(title, preset)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_create_smart_folder), Toast.LENGTH_SHORT).show()
            openSettings(SettingsTab.ORGANIZE)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_create_smart_folder_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRemoteFileDialog(provider: StorageProviderAccountSummary) {
        val remotePathInput = darkInput(getString(R.string.main_show_remote_file_dialog))
        val displayNameInput = darkInput(getString(R.string.hint_display_name))
        val mimeTypeInput = darkInput("MIME type").apply {
            setText("application/octet-stream")
        }
        val sizeInput = darkInput(getString(R.string.main_show_remote_file_dialog_2)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        val remoteIdInput = darkInput(getString(R.string.main_show_remote_file_dialog_3))
        val webUrlInput = darkInput("Web URL")
        val noteInput = darkInput(getString(R.string.label_memo)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val container = dialogContainer(
            remotePathInput,
            displayNameInput,
            mimeTypeInput,
            sizeInput,
            remoteIdInput,
            webUrlInput,
            noteInput,
        )
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(getString(R.string.title_add_reference, provider.displayName))
            .setView(container)
            .setPositiveButton(getString(R.string.action_add)) { _, _ ->
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
            .setNegativeButton(getString(R.string.action_cancel), null)
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
            Toast.makeText(this, getString(R.string.main_register_remote_file, item.displayName), Toast.LENGTH_SHORT).show()
            openDetail(item.id)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_register_remote_file_2), Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportBackup() {
        runCatching {
            repository.exportBackup()
        }.onSuccess { file ->
            Toast.makeText(this, "Backup: ${file.name}", Toast.LENGTH_SHORT).show()
            openSettings(SettingsTab.BACKUP)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_export_backup), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runBackupNow() {
        BackupWorker.enqueueNow(applicationContext)
        Toast.makeText(this, getString(R.string.main_run_backup_now), Toast.LENGTH_SHORT).show()
        openSettings(SettingsTab.BACKUP)
    }

    private fun restoreLatestLocalBackup() {
        runCatching {
            repository.restoreLatestLocalBackup()
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_restore_latest_local_backup), Toast.LENGTH_SHORT).show()
            openSettings(SettingsTab.BACKUP)
        }.onFailure { error ->
            // バックアップ不在は想定内なので専用の文言を出す。
            // それ以外（Rust core からのエラーなど）は原因を見せたいので error.message を優先する。
            val message = if (error is NoSuchElementException) {
                getString(R.string.main_restore_latest_local_backup_missing)
            } else {
                error.message ?: getString(R.string.main_restore_latest_local_backup_2)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSetPinDialog() {
        showPinDialog(
            title = getString(R.string.action_set_pin),
            positiveLabel = getString(R.string.label_settings),
        ) { pin ->
            runCatching {
                repository.setSecurityPin(pin)
            }.onSuccess {
                Toast.makeText(this, getString(R.string.main_show_set_pin_dialog), Toast.LENGTH_SHORT).show()
                openSettings(SettingsTab.SECURITY)
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.main_show_set_pin_dialog_2), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVerifyPinDialog() {
        showPinDialog(
            title = getString(R.string.action_verify_pin),
            positiveLabel = getString(R.string.action_verify),
        ) { pin ->
            runCatching {
                repository.verifySecurityPin(pin)
            }.onSuccess { verified ->
                Toast.makeText(this, if (verified) getString(R.string.main_show_verify_pin_dialog) else getString(R.string.msg_pin_mismatch), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.msg_pin_verify_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPinDialog(title: String, positiveLabel: String, onPin: (String) -> Unit) {
        val input = darkInput("PIN").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this, R.style.CabinetDialogTheme)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel) { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton(getString(R.string.action_cancel), null)
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
                requireNotNull(reader) { getString(R.string.main_import_backup) }.readText()
            }
            repository.importBackup(json)
        }.onSuccess {
            Toast.makeText(this, getString(R.string.main_import_backup_2), Toast.LENGTH_SHORT).show()
            // ファイル選択から戻る経路なので、由来のタブを明示して復帰する。
            openSettings(SettingsTab.BACKUP)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: getString(R.string.main_import_backup_3), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_OPEN_TREE = 2401
        private const val REQUEST_OPEN_BACKUP = 2402
        private const val REQUEST_ADD_VERSION = 2403
        private const val REQUEST_OPEN_SD_TREE = 2404
        private const val REQUEST_STORAGE_PROVIDER_TREE = 2405
        private const val APP_PREFS_NAME = "cabinet-app-settings"
        private const val KEY_SD_TREE_URI = "sd_tree_uri"
        private const val KEY_SD_TREE_URIS = "sd_tree_uris"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_FILE_LIST_KIND_FONT_SIZE = "file_list_kind_font_size"
        private const val KEY_FILE_LIST_NAME_FONT_SIZE = "file_list_name_font_size"
        private const val KEY_FILE_LIST_META_FONT_SIZE = "file_list_meta_font_size"
        private const val KEY_USE_VIASTRASSE_VIEW = "use_viastrasse_view"
        private const val KEY_SELECTED_STORAGE_PROVIDER = "selected_storage_provider"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val FILE_PROVIDER_AUTHORITY = "jp.viastrasse.cabinet.fileprovider"

        // Mail由来の命名だった旧キー。既存インストールの設定を引き継ぐために読むだけ残す。
        private const val LEGACY_KEY_FILE_LIST_FROM_FONT_SIZE = "file_list_from_font_size"
        private const val LEGACY_KEY_FILE_LIST_SUBJECT_FONT_SIZE = "file_list_subject_font_size"
        private const val LEGACY_KEY_FILE_LIST_BODY_FONT_SIZE = "file_list_body_font_size"
    }

    private data class RemoteFolderState(
        val key: String,
        val location: String,
    )

    private data class ViewerNavigationItem(
        val path: String,
        val mimeType: String,
        val title: String,
    )
}
