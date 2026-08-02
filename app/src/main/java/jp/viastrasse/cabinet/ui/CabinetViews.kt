package jp.viastrasse.cabinet.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import jp.viastrasse.cabinet.R
import jp.viastrasse.cabinet.data.CabinetCollectionSummary
import jp.viastrasse.cabinet.data.CabinetDashboard
import jp.viastrasse.cabinet.data.CabinetFiles
import jp.viastrasse.cabinet.data.CabinetItemDetail
import jp.viastrasse.cabinet.data.CabinetItemSummary
import jp.viastrasse.cabinet.data.DuplicateReport
import jp.viastrasse.cabinet.data.ModeResponse
import jp.viastrasse.cabinet.data.SearchResponse
import jp.viastrasse.cabinet.data.SettingsSnapshot
import jp.viastrasse.cabinet.data.SmartFolderSummary
import jp.viastrasse.cabinet.data.StorageProviderAccountSummary
import jp.viastrasse.cabinet.theme.CabinetColors
import java.io.File

class CabinetDashboardView(context: Context) : ScrollView(context) {
    var onOpenSettings: (() -> Unit)? = null
    var onShowItemDetail: ((String) -> Unit)? = null

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(CabinetMetrics.SCREEN_PADDING),
            dp(CabinetMetrics.SPACE_LG),
            dp(CabinetMetrics.SCREEN_PADDING),
            dp(CabinetMetrics.SPACE_XL + CabinetMetrics.SPACE_MD),
        )
    }

    init {
        setBackgroundColor(CabinetColors.AppBackground)
        isFillViewport = true
        clipToPadding = false
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
        onOpenSearch: () -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ) {
        resetContent()
        content.addView(homeHeader())
        content.addView(searchLauncher(onOpenSearch))
        content.addView(fileManagerHome(dashboard, onModeSelected, onItemSelected, onImportFolder, onToggleFavorite, onMoveTrash))
        content.addView(organizePanel(dashboard, onModeSelected))
        content.addView(section(context.getString(R.string.view_render)))
        content.addView(
            managementGrid(
                listOf(
                    CabinetMode("Collection", dashboard.collectionCount, "Collection"),
                    CabinetMode("Smart Folder", dashboard.smartFolders.size.toLong(), "Smart Folder"),
                    CabinetMode("Trash", dashboard.trashCount, context.getString(R.string.label_trash)),
                ),
                onModeSelected,
            ),
        )
        content.addView(footerNote("Cabinet core schema v${dashboard.version}"))
    }

    fun renderError(message: String) {
        resetContent()
        content.addView(screenHeader("Cabinet by VIASTRASSE", null, null))
        content.addView(
            noticeCard(
                title = context.getString(R.string.view_render_error),
                body = message,
                accentColor = CabinetColors.Danger,
            ),
        )
    }

    fun renderMode(
        mode: ModeResponse,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        listOptions: FileListOptions,
        showItemList: Boolean,
        onListOptionsChanged: (FileListOptions) -> Unit,
        onBack: () -> Unit,
        onItemSelected: (String) -> Unit,
        onCollectionSelected: (CabinetCollectionSummary) -> Unit,
        onSmartFolderSelected: (SmartFolderSummary) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader(mode.title, mode.mode, onBack))
        if (showItemList) {
            content.addView(sectionWithCount(context.getString(R.string.view_render_mode), mode.items.size.toLong()))
            content.addView(fileListControls(listOptions, onListOptionsChanged))
            if (mode.items.isEmpty()) {
                content.addView(emptyState(emptyListMessage(listOptions)))
            }
            val fileList = fileListContainer(displayMode, listOptions.layout)
            if (listOptions.layout == FileListLayout.PREVIEW_GRID) {
                addCabinetPreviewGrid(fileList, mode.items, fontPreference, onItemSelected, onToggleFavorite, onMoveTrash)
            } else {
                mode.items.forEach {
                    fileList.addView(cabinetFileRow(it, displayMode, fontPreference, onItemSelected, onToggleFavorite, onMoveTrash))
                }
            }
            content.addView(fileList)
        }
        if (mode.collections.isNotEmpty()) {
            content.addView(section("Collection"))
            mode.collections.forEach { content.addView(collectionRow(it, onCollectionSelected)) }
        }
        if (mode.smartFolders.isNotEmpty()) {
            content.addView(section("Smart Folder"))
            mode.smartFolders.forEach { content.addView(smartFolderRow(it, onSmartFolderSelected)) }
        }
    }

    fun renderLocalExplorer(
        currentDirectory: File,
        entries: List<LocalFileEntry>,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        listOptions: FileListOptions,
        onListOptionsChanged: (FileListOptions) -> Unit,
        onBack: () -> Unit,
        onParent: (() -> Unit)?,
        onOpenDirectory: (File) -> Unit,
        onOpenFile: (File) -> Unit,
        onRegisterFile: (File) -> Unit,
        onRenameEntry: (File) -> Unit,
        onCopyEntry: (File) -> Unit,
        onMoveEntry: (File) -> Unit,
        onDuplicateEntry: (File) -> Unit,
        onCreateFolder: () -> Unit,
        onDeleteEntry: (File) -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader("Explorer", currentDirectory.absolutePath, onBack))
        content.addView(
            actionBar(
                buildList {
                    if (onParent != null) add(ActionItem(context.getString(R.string.view_render_local_explorer), onParent))
                    add(ActionItem(context.getString(R.string.view_render_local_explorer_2), onCreateFolder))
                },
            ),
        )
        content.addView(sectionWithCount("Files", entries.size.toLong()))
        content.addView(fileListControls(listOptions, onListOptionsChanged))
        if (entries.isEmpty()) {
            content.addView(emptyState(emptyListMessage(listOptions)))
            return
        }
        val fileList = fileListContainer(displayMode, listOptions.layout)
        if (listOptions.layout == FileListLayout.PREVIEW_GRID) {
            addLocalPreviewGrid(fileList, entries, fontPreference, onOpenDirectory, onOpenFile, onRegisterFile, onRenameEntry, onCopyEntry, onMoveEntry, onDuplicateEntry, onDeleteEntry)
        } else {
            entries.forEach { entry ->
                fileList.addView(
                    fileRow(
                        entry,
                        displayMode,
                        fontPreference,
                        onOpenDirectory,
                        onOpenFile,
                        onRegisterFile,
                        onRenameEntry,
                        onCopyEntry,
                        onMoveEntry,
                        onDuplicateEntry,
                        onDeleteEntry,
                    ),
                )
            }
        }
        content.addView(fileList)
    }

    fun renderDeviceFiles(
        title: String,
        location: String,
        entries: List<DeviceFileEntry>,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        listOptions: FileListOptions,
        onListOptionsChanged: (FileListOptions) -> Unit,
        onBack: () -> Unit,
        onOpenFile: (DeviceFileEntry) -> Unit,
        onRegisterFile: (DeviceFileEntry) -> Unit,
        onOpenLocation: (DeviceFileEntry) -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader(title, location, onBack))
        content.addView(sectionWithCount("Files", entries.size.toLong()))
        content.addView(fileListControls(listOptions, onListOptionsChanged))
        if (entries.isEmpty()) {
            val message = if (listOptions.hasActiveFilter) {
                emptyListMessage(listOptions)
            } else {
                context.getString(R.string.view_render_device_files)
            }
            content.addView(emptyState(message))
            return
        }
        val fileList = fileListContainer(displayMode, listOptions.layout)
        if (listOptions.layout == FileListLayout.PREVIEW_GRID) {
            addDevicePreviewGrid(fileList, entries, fontPreference, onOpenFile, onRegisterFile, onOpenLocation)
        } else {
            entries.forEach { entry ->
                fileList.addView(deviceFileRow(entry, displayMode, fontPreference, onOpenFile, onRegisterFile, onOpenLocation))
            }
        }
        content.addView(fileList)
    }

    fun renderDocumentTree(
        title: String,
        location: String,
        entries: List<DocumentFileEntry>,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        listOptions: FileListOptions,
        sectionTitle: String = "Files",
        emptyMessage: String? = null,
        showListControls: Boolean = true,
        onListOptionsChanged: (FileListOptions) -> Unit,
        onBack: () -> Unit,
        onParent: (() -> Unit)?,
        parentLabel: String = context.getString(R.string.action_move_to_parent_folder),
        onOpenDirectory: (DocumentFileEntry) -> Unit,
        onOpenFile: (DocumentFileEntry) -> Unit,
        onRegisterFile: (DocumentFileEntry) -> Unit,
        onChooseRoot: () -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader(title, location, onBack))
        content.addView(
            actionBar(
                buildList {
                    if (onParent != null) add(ActionItem("↑ $parentLabel", onParent))
                    add(ActionItem(context.getString(R.string.view_render_document_tree), onChooseRoot))
                },
            ),
        )
        content.addView(
            noticeCard(
                title = context.getString(R.string.view_render_document_tree_2),
                body = context.getString(R.string.view_render_document_tree_3),
                accentColor = CabinetColors.Warning,
            ),
        )
        content.addView(sectionWithCount(sectionTitle, entries.size.toLong()))
        if (showListControls) {
            content.addView(fileListControls(listOptions, onListOptionsChanged))
        }
        if (entries.isEmpty()) {
            val message = if (listOptions.hasActiveFilter) {
                emptyListMessage(listOptions)
            } else {
                emptyMessage ?: context.getString(R.string.empty_no_files)
            }
            content.addView(emptyState(message))
            return
        }
        val fileList = fileListContainer(displayMode, listOptions.layout)
        if (listOptions.layout == FileListLayout.PREVIEW_GRID) {
            addDocumentPreviewGrid(fileList, entries, fontPreference, onOpenDirectory, onOpenFile, onRegisterFile)
        } else {
            entries.forEach { entry ->
                fileList.addView(documentFileRow(entry, displayMode, fontPreference, onOpenDirectory, onOpenFile, onRegisterFile))
            }
        }
        content.addView(fileList)
    }

    fun renderSearch(
        response: SearchResponse,
        onBack: () -> Unit,
        onSearch: (String) -> Unit,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader("Search", context.getString(R.string.view_render_search), onBack))
        val input = context.cabinetInput(context.getString(R.string.hint_search_cabinet)).apply {
            setText(response.query)
            setSingleLine(true)
        }
        content.addView(
            input.also {
                it.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_SM)) }
            },
        )
        content.addView(primaryButton(context.getString(R.string.view_render_search_2)) { onSearch(input.text.toString()) })
        content.addView(sectionWithCount(context.getString(R.string.view_render_search_3), response.results.size.toLong()))
        if (response.results.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_search_4)))
        }
        response.results.forEach { content.addView(itemRow(it, onItemSelected, onToggleFavorite, onMoveTrash)) }
    }

    fun renderDetail(
        detail: CabinetItemDetail,
        onBack: () -> Unit,
        onProcessPreview: () -> Unit,
        onToggleFavorite: () -> Unit,
        onMarkSorted: () -> Unit,
        onAddTag: () -> Unit,
        onRemoveTag: (String) -> Unit,
        onAddCollection: () -> Unit,
        onRemoveCollection: (String) -> Unit,
        onMoveTrash: () -> Unit,
        onOpen: () -> Unit,
        onShare: () -> Unit,
        onDuplicate: () -> Unit,
        onRename: () -> Unit,
        onAddVersion: () -> Unit,
        onSetCurrentVersion: (String) -> Unit,
        onCreateZip: () -> Unit,
        onExtractZip: () -> Unit,
        onAddMailReference: () -> Unit,
        onAddTaskReference: () -> Unit,
        onAddAtelierReference: () -> Unit,
        onOpenReference: (String) -> Unit,
        onAddMemo: () -> Unit,
        onAddProtectedMemo: () -> Unit,
        onUnlockProtectedMemos: () -> Unit,
        onAddOcrText: () -> Unit,
        onRunImageOcr: () -> Unit,
        onCacheRemoteFile: () -> Unit,
        onGenerateThumbnail: () -> Unit,
        onProtectItem: () -> Unit,
        onRestoreFromTrash: () -> Unit,
        onDeletePermanently: () -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader(context.getString(R.string.view_render_detail), null, onBack, backLabel = context.getString(R.string.view_render_detail_2)))
        content.addView(detailHero(detail, onToggleFavorite))
        content.addView(
            buttonRow(
                primaryButton(context.getString(R.string.action_open), onOpen),
                ghostButton(context.getString(R.string.view_render_detail_3), onShare),
            ),
        )

        content.addView(section(context.getString(R.string.view_render_detail_4)))
        content.addView(
            card {
                addView(definitionRow(context.getString(R.string.view_render_detail_5), detail.path))
                addView(definitionRow("Hash", detail.hash.ifBlank { context.getString(R.string.view_render_detail_6) }))
                addView(definitionRow(context.getString(R.string.view_render_detail_7), detailStateLabel(detail)))
                if (detail.note.isNotBlank()) {
                    addView(definitionRow(context.getString(R.string.label_memo), detail.note))
                }
            },
        )

        detail.remote?.let { remote ->
            content.addView(section(context.getString(R.string.view_render_detail_8)))
            content.addView(
                card {
                    addView(definitionRow("Provider", remote.providerAccountId))
                    addView(definitionRow(context.getString(R.string.view_render_detail_9), remote.remotePath))
                    addView(definitionRow(context.getString(R.string.view_render_detail_10), if (remote.isCached) remote.cachedFilePath else context.getString(R.string.label_not_fetched)))
                    if (remote.webUrl.isNotBlank()) addView(definitionRow("Web URL", remote.webUrl))
                    addView(spacer(CabinetMetrics.SPACE_SM))
                    addView(ghostButton(context.getString(R.string.view_render_detail_11), onCacheRemoteFile))
                },
            )
        }

        content.addView(sectionWithCount("Tags", detail.tags.size.toLong()))
        content.addView(
            chipFlow(
                chips = detail.tags.map { tag ->
                    ChipItem(
                        text = tag.name,
                        accentColor = parseTagColor(tag.color),
                        onClick = null,
                        onRemove = { onRemoveTag(tag.id) },
                    )
                },
                addLabel = context.getString(R.string.view_render_detail_12),
                onAdd = onAddTag,
                emptyText = context.getString(R.string.view_render_detail_13),
            ),
        )

        content.addView(sectionWithCount("Collections", detail.collections.size.toLong()))
        content.addView(
            chipFlow(
                chips = detail.collections.map { collection ->
                    ChipItem(
                        text = context.getString(R.string.view_render_detail_14, collection.title, collection.itemCount),
                        accentColor = CabinetColors.Accent,
                        onClick = null,
                        onRemove = { onRemoveCollection(collection.id) },
                    )
                },
                addLabel = "＋ Collection",
                onAdd = onAddCollection,
                emptyText = context.getString(R.string.view_render_detail_15),
            ),
        )

        content.addView(section(context.getString(R.string.view_render_detail_16)))
        content.addView(
            actionGrid(
                buildList {
                    add(ActionItem(if (detail.item.isFavorite) context.getString(R.string.view_render_detail_17) else context.getString(R.string.view_render_detail_18), onToggleFavorite))
                    if (detail.item.isUnsorted) add(ActionItem(context.getString(R.string.view_render_detail_19), onMarkSorted))
                    add(ActionItem(context.getString(R.string.action_duplicate), onDuplicate))
                    add(ActionItem(context.getString(R.string.action_rename), onRename))
                    add(ActionItem(context.getString(R.string.view_render_detail_20), onAddVersion))
                    add(ActionItem(context.getString(R.string.view_render_detail_21), onCreateZip))
                    if (detail.item.mimeType == "application/zip" || detail.item.displayName.endsWith(".zip")) {
                        add(ActionItem(context.getString(R.string.view_render_detail_22), onExtractZip))
                    }
                    add(ActionItem(context.getString(R.string.view_render_detail_23), onAddMailReference))
                    add(ActionItem(context.getString(R.string.view_render_detail_24), onAddTaskReference))
                    add(ActionItem(context.getString(R.string.view_render_detail_25), onAddAtelierReference))
                    add(ActionItem(context.getString(R.string.action_add_memo), onAddMemo))
                    add(ActionItem(context.getString(R.string.action_add_protected_memo), onAddProtectedMemo))
                    add(ActionItem(context.getString(R.string.action_show_protected_memo), onUnlockProtectedMemos))
                    add(ActionItem(context.getString(R.string.view_render_detail_26), onProtectItem))
                    add(ActionItem(context.getString(R.string.action_add_ocr_text), onAddOcrText))
                    add(ActionItem(context.getString(R.string.view_render_detail_27), onRunImageOcr))
                    add(ActionItem(context.getString(R.string.view_render_detail_28), onGenerateThumbnail))
                    add(ActionItem(context.getString(R.string.view_render_detail_29), onProcessPreview))
                },
            ),
        )

        content.addView(sectionWithCount("Preview", detail.previews.size.toLong()))
        if (detail.previews.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_detail_30)))
        }
        detail.previews.forEach { preview ->
            content.addView(
                card {
                    addView(cardTitleRow(preview.previewType, statusBadge(preview.status)))
                    preview.metadataText().takeIf { it.isNotBlank() }?.let {
                        addView(label(it, CabinetType.CAPTION, false, CabinetColors.Accent))
                    }
                    addView(spacer(CabinetMetrics.SPACE_XS))
                    addView(label(preview.summaryText.ifBlank { context.getString(R.string.view_render_detail_31) }, CabinetType.BODY, false, CabinetColors.TextSecondary))
                    if (preview.thumbnailPath.isNotBlank()) {
                        thumbnailImage(preview.thumbnailPath)?.let(::addView)
                    }
                },
            )
        }

        content.addView(sectionWithCount("Version", detail.versions.size.toLong()))
        if (detail.versions.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_detail_32)))
        }
        detail.versions.forEach { version ->
            content.addView(
                card {
                    addView(
                        cardTitleRow(
                            "v${version.versionNumber}  ${version.displayName}",
                            if (version.isCurrent) statusBadge("current", CabinetColors.Success) else null,
                        ),
                    )
                    if (version.note.isNotBlank()) {
                        addView(label(version.note, CabinetType.CAPTION, false, CabinetColors.TextSecondary))
                    }
                    if (!version.isCurrent) {
                        addView(spacer(CabinetMetrics.SPACE_SM))
                        addView(ghostButton(context.getString(R.string.view_render_detail_33)) { onSetCurrentVersion(version.id) })
                    }
                },
            )
        }

        content.addView(sectionWithCount("Reference", detail.references.size.toLong()))
        if (detail.references.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_detail_34)))
        }
        detail.references.forEach { reference ->
            content.addView(
                card {
                    addView(cardTitleRow(reference.title.ifBlank { reference.sourceApp }, statusBadge(reference.referenceType)))
                    addView(label(reference.sourceApp, CabinetType.CAPTION, false, CabinetColors.TextMuted))
                    listOf(
                        context.getString(R.string.view_render_detail_35) to reference.documentType,
                        context.getString(R.string.view_render_detail_36) to reference.relatedParty,
                        context.getString(R.string.view_render_detail_37) to reference.documentDate,
                        context.getString(R.string.view_render_detail_38) to reference.dueDate,
                        context.getString(R.string.view_render_detail_39) to reference.emailSubject,
                        context.getString(R.string.view_render_detail_40) to reference.emailSender,
                        context.getString(R.string.view_render_detail_41) to reference.emailReceivedAt,
                        context.getString(R.string.label_account) to reference.emailAccount,
                        "Message-ID" to reference.messageId,
                    ).filter { (_, value) -> value.isNotBlank() }
                        .forEach { (name, value) -> addView(definitionRow(name, value)) }
                    if (reference.uri.isNotBlank()) {
                        addView(definitionRow("URI", reference.uri))
                        addView(spacer(CabinetMetrics.SPACE_SM))
                        addView(
                            ghostButton(if (reference.referenceType == "mail") context.getString(R.string.view_render_detail_42) else context.getString(R.string.view_render_detail_43)) {
                                onOpenReference(reference.uri)
                            },
                        )
                    }
                },
            )
        }

        content.addView(sectionWithCount("Memo", detail.memos.size.toLong()))
        if (detail.memos.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_detail_44)))
        }
        detail.memos.forEach { memo ->
            content.addView(
                card {
                    addView(
                        cardTitleRow(
                            if (memo.isProtected) context.getString(R.string.label_protected_memo) else context.getString(R.string.label_memo),
                            if (memo.isProtected) statusBadge("protected", CabinetColors.Warning) else null,
                        ),
                    )
                    addView(label(memo.body, CabinetType.BODY, false, CabinetColors.TextSecondary))
                    addView(spacer(CabinetMetrics.SPACE_XS))
                    addView(label(memo.updatedAt, CabinetType.MICRO, false, CabinetColors.TextMuted))
                },
            )
        }

        // ゴミ箱の内外で意味のある操作だけを出す。
        // 以前は3つとも常時並んでいたため、通常の資料に「復元」「完全削除」が見えていた。
        content.addView(section(context.getString(R.string.label_trash)))
        content.addView(
            if (detail.item.isArchived) {
                card(strokeColor = CabinetColors.Danger) {
                    addView(label(context.getString(R.string.view_render_detail_45), CabinetType.CAPTION, false, CabinetColors.TextSecondary))
                    addView(spacer(CabinetMetrics.SPACE_MD))
                    addView(ghostButton(context.getString(R.string.view_render_detail_46), onRestoreFromTrash))
                    addView(spacer(CabinetMetrics.SPACE_SM))
                    addView(dangerButton(context.getString(R.string.action_delete_permanently), onDeletePermanently))
                }
            } else {
                card {
                    addView(label(context.getString(R.string.view_render_detail_47), CabinetType.CAPTION, false, CabinetColors.TextSecondary))
                    addView(spacer(CabinetMetrics.SPACE_MD))
                    addView(ghostButton(context.getString(R.string.action_move_to_trash), onMoveTrash))
                }
            },
        )
    }

    fun renderSettings(
        settings: SettingsSnapshot,
        duplicateReport: DuplicateReport,
        onBack: () -> Unit,
        onExportBackup: () -> Unit,
        onRunBackupNow: () -> Unit,
        onImportBackup: () -> Unit,
        onRestoreLatestBackup: () -> Unit,
        onSetPin: () -> Unit,
        onVerifyPin: () -> Unit,
        onConfigureProvider: (StorageProviderAccountSummary) -> Unit,
        onAddRemoteFile: (StorageProviderAccountSummary) -> Unit,
        onOpenProvider: (StorageProviderAccountSummary) -> Unit,
        onCreateSmartFolder: () -> Unit,
        onDuplicateItemSelected: (String) -> Unit,
        selectedProviderId: String,
        onProviderSelected: (StorageProviderAccountSummary) -> Unit,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        onDisplayModeSelected: (String) -> Unit,
        onFontPreferenceChanged: (FileListDisplayPreference) -> Unit,
        viewInstalled: Boolean,
        useViastrasseView: Boolean,
        onUseViastrasseViewChanged: (Boolean) -> Unit,
        onAbout: () -> Unit,
    ) {
        resetContent()
        content.addView(screenHeader("Settings", context.getString(R.string.view_render_settings), onBack))

        content.addView(section(context.getString(R.string.action_show)))
        content.addView(
            card {
                addView(displayModeSelector(displayMode, onDisplayModeSelected))
                addView(spacer(CabinetMetrics.SPACE_LG))
                addView(fontPreferenceSelector(fontPreference, onFontPreferenceChanged))
                addView(spacer(CabinetMetrics.SPACE_LG))
                addView(Switch(context).apply {
                    text = context.getString(R.string.setting_use_viastrasse_view)
                    isChecked = useViastrasseView && viewInstalled
                    isEnabled = viewInstalled
                    setTextColor(CabinetColors.TextPrimary)
                    setOnCheckedChangeListener { _, checked -> onUseViastrasseViewChanged(checked) }
                })
                addView(label(
                    context.getString(
                        if (viewInstalled) R.string.setting_viastrasse_view_available
                        else R.string.setting_viastrasse_view_not_installed,
                    ),
                    CabinetType.CAPTION,
                    false,
                    CabinetColors.TextSecondary,
                ))
            },
        )

        content.addView(section(context.getString(R.string.action_about_app)))
        content.addView(
            card {
                addView(ghostButton(context.getString(R.string.action_about_app), onAbout))
            },
        )

        content.addView(section("Backup"))
        content.addView(
            card {
                addView(statTileRow(
                    listOf(
                        "Items" to settings.backup.itemCount.toString(),
                        "Collections" to settings.backup.collectionCount.toString(),
                        "Tags" to settings.backup.tagCount.toString(),
                    ),
                ))
                addView(spacer(CabinetMetrics.SPACE_SM))
                addView(definitionRow("Previews", settings.backup.previewCount.toString()))
                addView(definitionRow(context.getString(R.string.view_render_settings_2), settings.backup.exportedAt.ifBlank { context.getString(R.string.label_not_fetched) }))
                addView(definitionRow(context.getString(R.string.view_render_settings_3), context.getString(R.string.view_render_settings_4)))
                addView(spacer(CabinetMetrics.SPACE_MD))
                addView(
                    actionGrid(
                        listOf(
                            ActionItem(context.getString(R.string.view_render_settings_5), onExportBackup),
                            ActionItem(context.getString(R.string.view_render_settings_6), onRunBackupNow),
                            ActionItem(context.getString(R.string.view_render_settings_7), onImportBackup),
                            ActionItem(context.getString(R.string.view_render_settings_8), onRestoreLatestBackup),
                        ),
                    ),
                )
            },
        )

        content.addView(section("Security"))
        content.addView(
            card {
                addView(
                    cardTitleRow(
                        "PIN",
                        statusBadge(
                            if (settings.security.pinEnabled) context.getString(R.string.view_render_settings_9) else context.getString(R.string.label_not_configured),
                            if (settings.security.pinEnabled) CabinetColors.Success else CabinetColors.TextMuted,
                        ),
                    ),
                )
                addView(definitionRow(context.getString(R.string.view_render_settings_10), context.getString(R.string.format_item_count, settings.security.protectedItemCount)))
                addView(definitionRow(context.getString(R.string.label_protected_memo), context.getString(R.string.format_item_count, settings.security.protectedMemoCount)))
                addView(spacer(CabinetMetrics.SPACE_MD))
                addView(
                    actionGrid(
                        listOf(
                            ActionItem(context.getString(R.string.action_set_pin), onSetPin),
                            ActionItem(context.getString(R.string.action_verify_pin), onVerifyPin),
                        ),
                    ),
                )
            },
        )

        content.addView(section("Smart Folder"))
        content.addView(ghostButton(context.getString(R.string.view_render_settings_13), onCreateSmartFolder))

        content.addView(sectionWithCount(context.getString(R.string.view_render_settings_14), duplicateReport.groups.size.toLong()))
        if (duplicateReport.groups.isEmpty()) {
            content.addView(emptyState(context.getString(R.string.view_render_settings_15)))
        }
        duplicateReport.groups.forEach { group ->
            content.addView(
                card {
                    addView(cardTitleRow(context.getString(R.string.view_render_settings_16, group.items.size), statusBadge(readableSize(group.size))))
                    addView(label(group.hash, CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    addView(spacer(CabinetMetrics.SPACE_SM))
                    group.items.forEach { item ->
                        addView(ghostButton(item.displayName) { onDuplicateItemSelected(item.id) })
                        addView(spacer(CabinetMetrics.SPACE_XS))
                    }
                },
            )
        }

        content.addView(sectionWithCount(context.getString(R.string.storage_provider_section), settings.providers.size.toLong()))
        val selectedProvider = settings.providers.firstOrNull { it.id == selectedProviderId }
            ?: settings.providers.firstOrNull()
        if (selectedProvider != null) {
            content.addView(providerSelector(settings.providers, selectedProvider.id, onProviderSelected))
            content.addView(spacer(CabinetMetrics.SPACE_MD))
            content.addView(
                card {
                    addView(cardTitleRow(selectedProvider.displayName, statusBadge(providerStatusLabel(selectedProvider.connectionStatus), providerStatusColor(selectedProvider.connectionStatus))))
                    addView(definitionRow(context.getString(R.string.view_render_settings_17), "${selectedProvider.providerType} / ${selectedProvider.authType}"))
                    if (selectedProvider.accountName.isNotBlank()) {
                        addView(definitionRow(context.getString(R.string.label_account), selectedProvider.accountName))
                    }
                    if (selectedProvider.endpointUrl.isNotBlank()) {
                        addView(definitionRow(context.getString(R.string.storage_provider_endpoint), selectedProvider.endpointUrl))
                    }
                    if (selectedProvider.username.isNotBlank()) {
                        addView(definitionRow(context.getString(R.string.storage_provider_username), selectedProvider.username))
                    }
                    if (selectedProvider.remoteRoot.isNotBlank() && !selectedProvider.remoteRoot.startsWith("direct://")) {
                        addView(definitionRow(context.getString(R.string.storage_provider_remote_root), selectedProvider.remoteRoot))
                    }
                    if (selectedProvider.domain.isNotBlank()) {
                        addView(definitionRow(context.getString(R.string.storage_provider_domain), selectedProvider.domain))
                    }
                    addView(definitionRow(context.getString(R.string.storage_provider_cache_policy), cachePolicyLabel(selectedProvider.cachePolicy)))
                    addView(spacer(CabinetMetrics.SPACE_MD))
                    addView(
                        actionGrid(
                            buildList {
                                add(ActionItem(context.getString(R.string.view_render_settings_18)) { onOpenProvider(selectedProvider) })
                                add(ActionItem(context.getString(R.string.label_settings)) { onConfigureProvider(selectedProvider) })
                            },
                        ),
                    )
                },
            )
        }
    }

    fun renderAbout(
        versionName: String,
        versionCode: Int,
        onBack: () -> Unit,
        onOpenViastrasseFamily: () -> Unit,
    ) {
        resetContent()
        content.addView(
            screenHeader(
                context.getString(R.string.action_about_app),
                null,
                onBack,
                context.getString(R.string.label_settings),
            ),
        )
        content.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.cabinet_strasse_startup)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = context.getString(R.string.app_name)
            },
        )
        content.addView(
            label(
                context.getString(R.string.about_version_format, versionName, versionCode),
                CabinetType.BODY,
                true,
                CabinetColors.TextSecondary,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(CabinetMetrics.SPACE_LG), 0, dp(CabinetMetrics.SPACE_XL))
            },
        )
        content.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.viastrasse_branding_dark)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = context.getString(R.string.label_viastrasse_family)
                isClickable = true
                isFocusable = true
                setOnClickListener { onOpenViastrasseFamily() }
            },
        )
    }

    private fun resetContent() {
        content.removeAllViews()
        scrollTo(0, 0)
    }

    // ---------------------------------------------------------------------
    // 画面ヘッダー・セクション
    // ---------------------------------------------------------------------

    private fun screenHeader(
        titleText: String,
        subtitleText: String?,
        onBack: (() -> Unit)?,
        backLabel: String = "Cabinet",
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(CabinetMetrics.SPACE_MD))
            if (onBack != null) {
                addView(
                    label("‹  $backLabel", CabinetType.BODY, true, CabinetColors.Accent).asTappable(
                        context.pressableShape(
                            CabinetColors.Surface,
                            CabinetMetrics.RADIUS_PILL,
                            strokeColor = CabinetColors.Outline,
                        ),
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_SM))
                        setOnClickListener { onBack() }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_MD)) }
                    },
                )
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(titleText, CabinetType.DISPLAY, true).apply {
                            letterSpacing = 0.005f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    addView(appMenuButton())
                },
            )
            if (!subtitleText.isNullOrBlank()) {
                addView(
                    label(subtitleText, CabinetType.CAPTION, false, CabinetColors.TextMuted).apply {
                        setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, 0)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                )
            }
        }
    }

    private fun section(text: String): View = sectionWithCount(text, null)

    private fun sectionWithCount(text: String, count: Long?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(CabinetMetrics.SPACE_XL), 0, dp(CabinetMetrics.SPACE_SM))
            addView(
                View(context).apply {
                    background = context.surfaceShape(CabinetColors.Accent, CabinetMetrics.RADIUS_PILL, strokeColor = null)
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)).apply {
                        rightMargin = dp(CabinetMetrics.SPACE_SM)
                    }
                },
            )
            addView(
                label(text, CabinetType.SECTION, true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (count != null) {
                addView(label(count.toString(), CabinetType.CAPTION, true, CabinetColors.TextMuted))
            }
        }
    }

    private fun footerNote(text: String): View {
        return label(text, CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(CabinetMetrics.SPACE_XL), 0, 0)
        }
    }

    // ---------------------------------------------------------------------
    // ボタン・チップ
    // ---------------------------------------------------------------------

    private fun primaryButton(text: String, onClick: () -> Unit): TextView {
        return buttonBase(text, CabinetColors.TextPrimary).asTappable(
            context.pressableShape(CabinetColors.Brand, CabinetMetrics.RADIUS_CONTROL, strokeColor = CabinetColors.BrandBright),
        ).apply { setOnClickListener { onClick() } }
    }

    private fun ghostButton(text: String, onClick: () -> Unit): TextView {
        return buttonBase(text, CabinetColors.Accent).asTappable(
            context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_CONTROL),
        ).apply { setOnClickListener { onClick() } }
    }

    private fun dangerButton(text: String, onClick: () -> Unit): TextView {
        return buttonBase(text, CabinetColors.Danger).asTappable(
            context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_CONTROL, strokeColor = CabinetColors.Danger),
        ).apply { setOnClickListener { onClick() } }
    }

    private fun buttonBase(text: String, textColor: Int): TextView {
        return label(text, CabinetType.BODY, true, textColor).apply {
            gravity = Gravity.CENTER
            minHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** 横並びのボタン列。各ボタンが均等幅になる。 */
    private fun buttonRow(vararg buttons: TextView): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, button ->
                button.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                }
                addView(button)
            }
        }
    }

    /** 2列で折り返す操作ボタン群。詳細画面の多数アクションを整理する。 */
    private fun actionGrid(actions: List<ActionItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            actions.chunked(2).forEach { pair ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEachIndexed { index, action ->
                            addView(
                                ghostButton(action.label, action.onClick).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                                        bottomMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                        if (pair.size == 1) {
                            addView(
                                View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                        leftMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** 画面上部の補助操作（親フォルダへ、新規フォルダ など）。 */
    private fun actionBar(actions: List<ActionItem>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(CabinetMetrics.SPACE_MD), 0, 0)
            actions.forEachIndexed { index, action ->
                addView(
                    ghostButton(action.label, action.onClick).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                        }
                    },
                )
            }
        }
    }

    private fun chipFlow(
        chips: List<ChipItem>,
        addLabel: String,
        onAdd: () -> Unit,
        emptyText: String,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (chips.isEmpty()) {
                addView(emptyState(emptyText))
            }
            chips.chunked(2).forEach { pair ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEachIndexed { index, item ->
                            addView(
                                chipView(item).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                                        bottomMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                        if (pair.size == 1) {
                            addView(
                                View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                        leftMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                    },
                )
            }
            addView(
                label(addLabel, CabinetType.CAPTION, true, CabinetColors.Accent).asTappable(
                    context.pressableShape(CabinetColors.AppBackground, CabinetMetrics.RADIUS_PILL),
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_SM))
                    setOnClickListener { onAdd() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }
    }

    private fun chipView(item: ChipItem): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_PILL)
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM))
            addView(
                View(context).apply {
                    background = context.surfaceShape(item.accentColor, CabinetMetrics.RADIUS_PILL, strokeColor = null)
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        rightMargin = dp(CabinetMetrics.SPACE_SM)
                    }
                },
            )
            addView(
                label(item.text, CabinetType.CAPTION, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (item.onRemove != null) {
                addView(
                    label("✕", CabinetType.CAPTION, true, CabinetColors.TextMuted).asTappable(
                        context.borderlessRipple(CabinetMetrics.RADIUS_PILL),
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_XS))
                        setOnClickListener { item.onRemove.invoke() }
                    },
                )
            }
            if (item.onClick != null) {
                isClickable = true
                setOnClickListener { item.onClick.invoke() }
            }
        }
    }

    private fun statusBadge(text: String, color: Int = CabinetColors.Accent): TextView {
        return label(text, CabinetType.MICRO, true, color).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_PILL, strokeColor = null)
            setPadding(dp(CabinetMetrics.SPACE_SM), dp(3), dp(CabinetMetrics.SPACE_SM), dp(3))
        }
    }

    // ---------------------------------------------------------------------
    // カード・情報表示
    // ---------------------------------------------------------------------

    private fun card(
        fillColor: Int = CabinetColors.Surface,
        strokeColor: Int = CabinetColors.Outline,
        block: LinearLayout.() -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.surfaceShape(fillColor, CabinetMetrics.RADIUS_CARD, strokeColor)
            setPadding(
                dp(CabinetMetrics.SPACE_LG),
                dp(CabinetMetrics.SPACE_LG),
                dp(CabinetMetrics.SPACE_LG),
                dp(CabinetMetrics.SPACE_LG),
            )
            block()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            }
        }
    }

    private fun cardTitleRow(titleText: String, badge: View?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            addView(
                label(titleText, CabinetType.SECTION, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (badge != null) {
                badge.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(CabinetMetrics.SPACE_SM) }
                addView(badge)
            }
        }
    }

    /** ラベルと値を縦に積む情報行。値が長いパス・ハッシュでも読める。 */
    private fun definitionRow(name: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, dp(CabinetMetrics.SPACE_XS))
            addView(label(name, CabinetType.MICRO, true, CabinetColors.TextMuted))
            addView(
                label(value, CabinetType.CAPTION, false, CabinetColors.TextSecondary).apply {
                    setLineSpacing(0f, 1.25f)
                },
            )
        }
    }

    private fun noticeCard(title: String, body: String, accentColor: Int): View {
        return card(strokeColor = accentColor) {
            addView(label(title, CabinetType.CAPTION, true, accentColor))
            addView(spacer(CabinetMetrics.SPACE_XS))
            addView(
                label(body, CabinetType.CAPTION, false, CabinetColors.TextSecondary).apply {
                    setLineSpacing(0f, 1.3f)
                },
            )
        }
    }

    private fun statTileRow(stats: List<Pair<String, String>>): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            stats.forEachIndexed { index, (name, value) ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                        setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
                        addView(label(name, CabinetType.MICRO, false, CabinetColors.TextMuted))
                        addView(label(value, CabinetType.TITLE, true))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                        }
                    },
                )
            }
        }
    }

    private fun detailHero(detail: CabinetItemDetail, onToggleFavorite: () -> Unit): View {
        val kind = fileKindLabel(detail.item.mimeType)
        return card {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(kind, CabinetType.CAPTION, true, kindAccent(kind)).apply {
                            gravity = Gravity.CENTER
                            background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                                rightMargin = dp(CabinetMetrics.SPACE_MD)
                            }
                        },
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                label(detail.item.title, CabinetType.TITLE, true).apply {
                                    maxLines = 3
                                    setLineSpacing(0f, 1.16f)
                                    ellipsize = TextUtils.TruncateAt.END
                                },
                            )
                            addView(
                                label(
                                    context.getString(R.string.format_dot_separated, detail.item.mimeType, readableSize(detail.item.size)),
                                    CabinetType.CAPTION,
                                    false,
                                    CabinetColors.TextMuted,
                                ).apply { setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, 0) },
                            )
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    addView(
                        label(if (detail.item.isFavorite) "★" else "☆", CabinetType.TITLE, true, CabinetColors.Warning).asTappable(
                            context.borderlessRipple(CabinetMetrics.RADIUS_PILL),
                        ).apply {
                            gravity = Gravity.CENTER
                            contentDescription = if (detail.item.isFavorite) context.getString(R.string.action_unfavorite) else context.getString(R.string.action_favorite)
                            setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM))
                            setOnClickListener { onToggleFavorite() }
                        },
                    )
                },
            )
            addView(spacer(CabinetMetrics.SPACE_MD))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(statusBadge(detail.item.sourceKind))
                    if (detail.item.isUnsorted) {
                        addView(statusBadge(context.getString(R.string.label_unsorted), CabinetColors.Warning).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { leftMargin = dp(CabinetMetrics.SPACE_SM) }
                        })
                    }
                    if (detail.isProtected) {
                        addView(statusBadge(context.getString(R.string.view_detail_hero_2), CabinetColors.Danger).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { leftMargin = dp(CabinetMetrics.SPACE_SM) }
                        })
                    }
                    if (detail.item.isArchived) {
                        addView(statusBadge(context.getString(R.string.label_trash), CabinetColors.Danger).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { leftMargin = dp(CabinetMetrics.SPACE_SM) }
                        })
                    }
                },
            )
        }
    }

    private fun detailStateLabel(detail: CabinetItemDetail): String {
        return buildList {
            add(if (detail.isProtected) context.getString(R.string.view_detail_state_label) else context.getString(R.string.view_detail_state_label_2))
            if (detail.item.isFavorite) add(context.getString(R.string.label_favorite))
            if (detail.item.isUnsorted) add(context.getString(R.string.label_unsorted))
            if (detail.item.isArchived) add(context.getString(R.string.label_trash))
        }.joinToString(" / ")
    }

    private fun providerStatusColor(status: String): Int = when (status.lowercase()) {
        "connected" -> CabinetColors.Success
        "configured" -> CabinetColors.Accent
        "error" -> CabinetColors.Danger
        "offline" -> CabinetColors.Warning
        else -> CabinetColors.TextMuted
    }

    private fun providerStatusLabel(status: String): String = context.getString(
        when (status.lowercase()) {
            "connected" -> R.string.storage_provider_status_connected
            "configured" -> R.string.storage_provider_status_configured
            "offline" -> R.string.storage_provider_status_offline
            "error" -> R.string.storage_provider_status_error
            else -> R.string.label_not_configured
        },
    )

    private fun cachePolicyLabel(policy: String): String = context.getString(
        when (policy) {
            "metadata_only" -> R.string.storage_provider_cache_metadata
            "offline_selected" -> R.string.storage_provider_cache_offline
            else -> R.string.storage_provider_cache_on_demand
        },
    )

    private fun providerSelector(
        providers: List<StorageProviderAccountSummary>,
        selectedProviderId: String,
        onProviderSelected: (StorageProviderAccountSummary) -> Unit,
    ): Spinner {
        return Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(
                context,
                android.R.layout.simple_spinner_item,
                providers.map { it.displayName },
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    styleProviderOption(super.getView(position, convertView, parent))

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    styleProviderOption(super.getDropDownView(position, convertView, parent))

                private fun styleProviderOption(view: View): View = view.apply {
                    (this as? TextView)?.apply {
                        setTextColor(CabinetColors.TextPrimary)
                        setBackgroundColor(CabinetColors.SurfaceAlt)
                        setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
                        minHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
                    }
                }
            }
            setSelection(providers.indexOfFirst { it.id == selectedProviderId }.coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    providers.getOrNull(position)
                        ?.takeIf { it.id != selectedProviderId }
                        ?.let(onProviderSelected)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun parseTagColor(color: String): Int =
        runCatching { android.graphics.Color.parseColor(color) }.getOrDefault(CabinetColors.Accent)

    // ---------------------------------------------------------------------
    // 設定画面の選択UI
    // ---------------------------------------------------------------------

    private fun displayModeSelector(
        selectedMode: String,
        onDisplayModeSelected: (String) -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context.getString(R.string.view_display_mode_selector), CabinetType.SECTION, true))
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(displayModeButton(context.getString(R.string.view_display_mode_selector_2), "compact", selectedMode, onDisplayModeSelected, leftSide = true))
                    addView(displayModeButton(context.getString(R.string.view_display_mode_selector_3), "elegant", selectedMode, onDisplayModeSelected, leftSide = false))
                },
            )
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(
                label(
                    context.getString(R.string.view_display_mode_selector_4),
                    CabinetType.CAPTION,
                    false,
                    CabinetColors.TextMuted,
                ).apply { setLineSpacing(0f, 1.3f) },
            )
        }
    }

    private fun displayModeButton(
        text: String,
        value: String,
        selectedMode: String,
        onDisplayModeSelected: (String) -> Unit,
        leftSide: Boolean,
    ): TextView {
        val selected = value == selectedMode
        val icon = if (value == "compact") "☰" else "▤"
        return label(
            if (selected) "✓  $text" else "$icon  $text",
            CabinetType.BODY,
            true,
            if (selected) CabinetColors.TextPrimary else CabinetColors.TextSecondary,
        ).asTappable(segmentedBackground(selected, leftSide)).apply {
            gravity = Gravity.CENTER
            minHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_MD))
            setOnClickListener { onDisplayModeSelected(value) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!leftSide) leftMargin = -dp(1)
            }
        }
    }

    private fun segmentedBackground(selected: Boolean, leftSide: Boolean): GradientDrawable {
        val radius = dp(CabinetMetrics.RADIUS_CONTROL).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (selected) CabinetColors.AccentSoft else CabinetColors.SurfaceAlt)
            setStroke(dp(1), if (selected) CabinetColors.Accent else CabinetColors.Outline)
            cornerRadii = if (leftSide) {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
    }

    private fun fontPreferenceSelector(
        initialPreference: FileListDisplayPreference,
        onFontPreferenceChanged: (FileListDisplayPreference) -> Unit,
    ): View {
        var current = initialPreference
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context.getString(R.string.view_font_preference_selector), CabinetType.SECTION, true))
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(
                fontSizeTile(context.getString(R.string.view_font_preference_selector_2), initialPreference.kindFontSize) { value ->
                    current = current.copy(kindFontSize = value)
                },
            )
            addView(
                fontSizeTile(context.getString(R.string.label_file_name), initialPreference.nameFontSize) { value ->
                    current = current.copy(nameFontSize = value)
                },
            )
            addView(
                fontSizeTile(context.getString(R.string.view_font_preference_selector_3), initialPreference.metaFontSize) { value ->
                    current = current.copy(metaFontSize = value)
                },
            )
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(ghostButton(context.getString(R.string.view_font_preference_selector_4)) { onFontPreferenceChanged(current) })
        }
    }

    private fun fontSizeTile(
        labelText: String,
        initialValue: Int,
        onChanged: (Int) -> Unit,
    ): View {
        val normalized = initialValue.coerceIn(
            FileListDisplayPreference.MIN_FONT_SIZE,
            FileListDisplayPreference.MAX_FONT_SIZE,
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(CabinetMetrics.SPACE_SM), 0, dp(CabinetMetrics.SPACE_SM))
            val valueText = label("${normalized}sp", CabinetType.CAPTION, true, CabinetColors.Accent).apply {
                gravity = Gravity.END
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(labelText, CabinetType.CAPTION, false, CabinetColors.TextSecondary).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    addView(valueText)
                },
            )
            addView(
                SeekBar(context).apply {
                    max = FileListDisplayPreference.MAX_FONT_SIZE - FileListDisplayPreference.MIN_FONT_SIZE
                    progress = normalized - FileListDisplayPreference.MIN_FONT_SIZE
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val value = FileListDisplayPreference.MIN_FONT_SIZE + progress
                            valueText.text = "${value}sp"
                            onChanged(value)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // ダッシュボード
    // ---------------------------------------------------------------------

    private fun homeHeader(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 0, 0, dp(CabinetMetrics.SPACE_MD))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("THE CABINET", CabinetType.MICRO, true, CabinetColors.Accent).apply {
                        letterSpacing = 0.18f
                    })
                    addView(
                        label("Cabinet by VIASTRASSE", CabinetType.DISPLAY, true).apply {
                            setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, 0)
                        },
                    )
                    addView(
                        label(context.getString(R.string.view_home_header), CabinetType.CAPTION, false, CabinetColors.TextMuted).apply {
                            setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, 0)
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(appMenuButton())
        }
    }

    private fun appMenuButton(): View {
        return label("⋮", CabinetType.TITLE, true, CabinetColors.TextSecondary).asTappable(
            context.borderlessRipple(CabinetMetrics.RADIUS_PILL),
        ).apply {
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.action_app_menu)
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM))
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    menu.add(context.getString(R.string.label_settings))
                    setOnMenuItemClickListener {
                        onOpenSettings?.invoke()
                        true
                    }
                    show()
                }
            }
        }
    }

    private fun searchLauncher(onOpenSearch: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            asTappable(context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_PILL))
            minimumHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            setPadding(dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_MD))
            setOnClickListener { onOpenSearch() }
            addView(
                label("⌕", CabinetType.TITLE, true, CabinetColors.Accent).apply {
                    setPadding(0, 0, dp(CabinetMetrics.SPACE_MD), 0)
                },
            )
            addView(label(context.getString(R.string.hint_search_cabinet), CabinetType.BODY, false, CabinetColors.TextMuted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp(CabinetMetrics.SPACE_SM), 0, dp(CabinetMetrics.SPACE_MD))
            }
        }
    }

    private fun fileManagerHome(
        dashboard: CabinetDashboard,
        onModeSelected: (CabinetMode) -> Unit,
        onItemSelected: (String) -> Unit,
        onImportFolder: () -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ): View {
        return card {
            addView(label("Explorer", CabinetType.TITLE, true))
            addView(
                label(context.getString(R.string.view_file_manager_home), CabinetType.CAPTION, false, CabinetColors.TextMuted).apply {
                    setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, dp(CabinetMetrics.SPACE_MD))
                },
            )
            addView(finderLocations(dashboard, onModeSelected))
            addView(spacer(CabinetMetrics.SPACE_MD))
            addView(ghostButton(context.getString(R.string.view_file_manager_home_2), onImportFolder))
            addView(spacer(CabinetMetrics.SPACE_LG))
            addView(pathBar(context.getString(R.string.view_file_manager_home_3), "${dashboard.libraryCount} files"))
            if (dashboard.recentItems.isEmpty()) {
                addView(emptyState(context.getString(R.string.view_file_manager_home_4)))
            } else {
                dashboard.recentItems.take(10).forEach {
                    addView(fileManagerItemRow(it, onItemSelected, onToggleFavorite, onMoveTrash))
                }
            }
        }
    }

    private fun finderLocations(dashboard: CabinetDashboard, onModeSelected: (CabinetMode) -> Unit): View {
        val modes = listOf(
            CabinetMode("Explorer", dashboard.explorerCount, context.getString(R.string.view_finder_locations)),
            CabinetMode("Downloads", 0, context.getString(R.string.view_finder_locations_2)),
            CabinetMode("Documents", 0, context.getString(R.string.view_finder_locations_3)),
            CabinetMode("Pictures", 0, context.getString(R.string.view_finder_locations_4)),
            CabinetMode("Movies", 0, context.getString(R.string.view_finder_locations_5)),
            CabinetMode("Music", 0, context.getString(R.string.view_finder_locations_6)),
            CabinetMode("SDCard", 0, context.getString(R.string.label_sd_card)),
            CabinetMode("Inbox", dashboard.inboxCount, "Inbox"),
            CabinetMode("Library", dashboard.libraryCount, context.getString(R.string.view_finder_locations_7)),
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            modes.chunked(2).forEach { rowModes ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        rowModes.forEachIndexed { index, mode ->
                            addView(
                                locationShortcut(mode) { onModeSelected(mode) }.apply {
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                                        bottomMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                        if (rowModes.size == 1) {
                            addView(
                                View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                        leftMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun locationShortcut(mode: CabinetMode, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            asTappable(context.pressableShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null))
            minimumHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
            setOnClickListener { onClick() }
            addView(
                label(locationIcon(mode.name), CabinetType.MICRO, true, CabinetColors.Accent).apply {
                    gravity = Gravity.CENTER
                    background = context.surfaceShape(CabinetColors.AccentSoft, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(26)).apply {
                        rightMargin = dp(CabinetMetrics.SPACE_SM)
                    }
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        label(mode.description, CabinetType.CAPTION, true).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    if (mode.count > 0) {
                        addView(label("${mode.count}", CabinetType.MICRO, false, CabinetColors.TextMuted))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }
    }

    private fun pathBar(location: String, detail: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            addView(
                label(location, CabinetType.SECTION, true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(label(detail, CabinetType.CAPTION, false, CabinetColors.TextMuted))
        }
    }

    private fun fileManagerItemRow(
        item: CabinetItemSummary,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ): View {
        val kind = fileKindLabel(item.mimeType)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            asTappable(context.pressableShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null))
            minimumHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_MD))
            setOnClickListener { onItemSelected(item.id) }
            setOnLongClickListener {
                showCabinetItemMenu(item, onItemSelected, onToggleFavorite, onMoveTrash)
                true
            }
            addView(
                label(kind, CabinetType.MICRO, true, kindAccent(kind)).apply {
                    gravity = Gravity.CENTER
                    background = context.surfaceShape(CabinetColors.Surface, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(28)).apply {
                        rightMargin = dp(CabinetMetrics.SPACE_MD)
                    }
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        label(item.title, CabinetType.BODY, true).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                        },
                    )
                    addView(
                        label(item.displayName, CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                label(if (item.isFavorite) "★" else "☆", CabinetType.BODY, true, CabinetColors.Warning).asTappable(
                    context.borderlessRipple(CabinetMetrics.RADIUS_PILL),
                ).apply {
                    gravity = Gravity.CENTER
                    contentDescription = if (item.isFavorite) context.getString(R.string.action_unfavorite) else context.getString(R.string.action_favorite)
                    setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM))
                    setOnClickListener { onToggleFavorite(item) }
                },
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            }
        }
    }

    private fun organizePanel(dashboard: CabinetDashboard, onModeSelected: (CabinetMode) -> Unit): View {
        return card {
            addView(label(context.getString(R.string.view_organize_panel), CabinetType.TITLE, true))
            addView(spacer(CabinetMetrics.SPACE_MD))
            addView(statusStrip(dashboard, onModeSelected))
            addView(spacer(CabinetMetrics.SPACE_MD))
            if (dashboard.collections.isEmpty() && dashboard.smartFolders.isEmpty()) {
                addView(emptyState(context.getString(R.string.view_organize_panel_2)))
            }
            dashboard.collections.take(4).forEach { collection ->
                addView(collectionRow(collection) { selected ->
                    onModeSelected(CabinetMode("collection:${selected.id}", selected.itemCount, selected.title))
                })
            }
            dashboard.smartFolders.take(4).forEach {
                addView(smartFolderRow(it) { folder -> onModeSelected(CabinetMode("smart:${folder.id}", folder.itemCount, folder.condition)) })
            }
        }
    }

    private fun managementGrid(modes: List<CabinetMode>, onModeSelected: (CabinetMode) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            modes.chunked(2).forEach { rowModes ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        rowModes.forEachIndexed { index, mode ->
                            addView(
                                LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    asTappable(context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_TILE))
                                    minimumHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
                                    setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
                                    setOnClickListener { onModeSelected(mode) }
                                    addView(label(mode.description, CabinetType.CAPTION, true))
                                    if (mode.count > 0) {
                                        addView(label("${mode.count}", CabinetType.MICRO, false, CabinetColors.TextMuted))
                                    }
                                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                                        bottomMargin = dp(CabinetMetrics.SPACE_SM)
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun statusStrip(dashboard: CabinetDashboard, onModeSelected: (CabinetMode) -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statusPill(context.getString(R.string.label_unsorted), dashboard.inboxCount, CabinetColors.Warning, 0) {
                onModeSelected(CabinetMode("Inbox", dashboard.inboxCount, context.getString(R.string.label_unsorted)))
            })
            addView(statusPill(context.getString(R.string.label_favorite), dashboard.favoriteCount, CabinetColors.Accent, 1) {
                onModeSelected(CabinetMode("Favorites", dashboard.favoriteCount, context.getString(R.string.label_favorite)))
            })
            addView(statusPill(context.getString(R.string.label_trash), dashboard.trashCount, CabinetColors.TextMuted, 2) {
                onModeSelected(CabinetMode("Trash", dashboard.trashCount, context.getString(R.string.label_trash)))
            })
        }
    }

    private fun statusPill(labelText: String, count: Long, accentColor: Int, index: Int, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            asTappable(context.pressableShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null))
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
            setOnClickListener { onClick() }
            addView(label(labelText, CabinetType.MICRO, false, CabinetColors.TextMuted))
            addView(label(count.toString(), CabinetType.TITLE, true, accentColor))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
            }
        }
    }

    private fun emptyState(text: String): View {
        return label(text, CabinetType.CAPTION, false, CabinetColors.TextMuted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            background = context.surfaceShape(CabinetColors.SurfaceSunken, CabinetMetrics.RADIUS_TILE, strokeColor = null)
            setPadding(dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_XL), dp(CabinetMetrics.SPACE_LG), dp(CabinetMetrics.SPACE_XL))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            }
        }
    }

    private fun emptyListMessage(options: FileListOptions): String =
        if (options.hasActiveFilter) context.getString(R.string.view_empty_list_message) else context.getString(R.string.empty_no_files)

    private fun itemRow(
        item: CabinetItemSummary,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ): View {
        val kind = fileKindLabel(item.mimeType)
        return card {
            asTappable(context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_CARD))
            setOnClickListener { onItemSelected(item.id) }
            setOnLongClickListener {
                showCabinetItemMenu(item, onItemSelected, onToggleFavorite, onMoveTrash)
                true
            }
            addView(cardTitleRow(item.title, statusBadge(kind, kindAccent(kind))))
            addView(label(context.getString(R.string.format_dot_separated, item.displayName, item.sourceKind), CabinetType.MICRO, false, CabinetColors.TextMuted))
            if (item.summaryText.isNotBlank()) {
                addView(spacer(CabinetMetrics.SPACE_SM))
                addView(
                    label(item.summaryText, CabinetType.CAPTION, false, CabinetColors.TextSecondary).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setLineSpacing(0f, 1.25f)
                    },
                )
            }
        }
    }

    private fun collectionRow(
        collection: CabinetCollectionSummary,
        onCollectionSelected: ((CabinetCollectionSummary) -> Unit)? = null,
    ): View {
        return summaryRow(
            titleText = collection.title,
            metaText = "${collection.itemCount} items",
            accentColor = CabinetColors.Accent,
            onClick = onCollectionSelected?.let { { it(collection) } },
        )
    }

    private fun smartFolderRow(
        folder: SmartFolderSummary,
        onSmartFolderSelected: ((SmartFolderSummary) -> Unit)? = null,
    ): View {
        return summaryRow(
            titleText = folder.title,
            metaText = context.getString(R.string.view_smart_folder_row, folder.itemCount, folder.condition),
            accentColor = CabinetColors.Success,
            onClick = onSmartFolderSelected?.let { { it(folder) } },
        )
    }

    private fun summaryRow(
        titleText: String,
        metaText: String,
        accentColor: Int,
        onClick: (() -> Unit)?,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
            setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_MD))
            if (onClick != null) {
                asTappable(context.pressableShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null))
                setOnClickListener { onClick() }
            } else {
                background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
            }
            addView(
                View(context).apply {
                    background = context.surfaceShape(accentColor, CabinetMetrics.RADIUS_PILL, strokeColor = null)
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply {
                        rightMargin = dp(CabinetMetrics.SPACE_MD)
                    }
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        label(titleText, CabinetType.CAPTION, true).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    addView(
                        label(metaText, CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(CabinetMetrics.SPACE_SM))
            }
        }
    }

    // ---------------------------------------------------------------------
    // ファイル一覧の行
    // ---------------------------------------------------------------------

    private fun fileRow(
        entry: LocalFileEntry,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        onOpenDirectory: (File) -> Unit,
        onOpenFile: (File) -> Unit,
        onRegisterFile: (File) -> Unit,
        onRenameEntry: (File) -> Unit,
        onCopyEntry: (File) -> Unit,
        onMoveEntry: (File) -> Unit,
        onDuplicateEntry: (File) -> Unit,
        onDeleteEntry: (File) -> Unit,
    ): View {
        val isElegant = isElegantDisplayMode(displayMode)
        return listRow(isElegant, fontPreference) {
            val openAction = {
                if (entry.isDirectory) {
                    onOpenDirectory(entry.file)
                } else {
                    onOpenFile(entry.file)
                }
            }
            setOnClickListener { openAction() }
            setOnLongClickListener {
                showLocalFileMenu(
                    entry = entry,
                    onOpen = openAction,
                    onRegisterFile = onRegisterFile,
                    onRenameEntry = onRenameEntry,
                    onCopyEntry = onCopyEntry,
                    onMoveEntry = onMoveEntry,
                    onDuplicateEntry = onDuplicateEntry,
                    onDeleteEntry = onDeleteEntry,
                    onOpenPath = onOpenDirectory,
                )
                true
            }
            addView(
                fileRowBody(
                    kind = if (entry.isDirectory) "DIR" else fileKindLabel(entry.kind),
                    name = entry.name,
                    updatedLabel = entry.updatedLabel,
                    sizeLabel = entry.sizeLabel,
                    isElegant = isElegant,
                    fontPreference = fontPreference,
                ),
            )
        }
    }

    private fun deviceFileRow(
        entry: DeviceFileEntry,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        onOpenFile: (DeviceFileEntry) -> Unit,
        onRegisterFile: (DeviceFileEntry) -> Unit,
        onOpenLocation: (DeviceFileEntry) -> Unit,
    ): View {
        val isElegant = isElegantDisplayMode(displayMode)
        return listRow(isElegant, fontPreference) {
            setOnClickListener { onOpenFile(entry) }
            setOnLongClickListener {
                showDeviceFileMenu(entry, onOpenFile, onRegisterFile, onOpenLocation)
                true
            }
            addView(
                fileRowBody(
                    kind = fileKindLabel(entry.mimeType),
                    name = entry.name,
                    updatedLabel = entry.updatedLabel,
                    sizeLabel = entry.sizeLabel,
                    isElegant = isElegant,
                    fontPreference = fontPreference,
                ),
            )
        }
    }

    private fun documentFileRow(
        entry: DocumentFileEntry,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        onOpenDirectory: (DocumentFileEntry) -> Unit,
        onOpenFile: (DocumentFileEntry) -> Unit,
        onRegisterFile: (DocumentFileEntry) -> Unit,
    ): View {
        val isElegant = isElegantDisplayMode(displayMode)
        return listRow(isElegant, fontPreference) {
            val openAction = {
                if (entry.isDirectory) onOpenDirectory(entry) else onOpenFile(entry)
            }
            setOnClickListener { openAction() }
            setOnLongClickListener {
                showDocumentFileMenu(entry, openAction, onRegisterFile)
                true
            }
            addView(
                fileRowBody(
                    kind = if (entry.isDirectory) "DIR" else fileKindLabel(entry.mimeType),
                    name = entry.name,
                    updatedLabel = entry.updatedLabel,
                    sizeLabel = entry.sizeLabel,
                    isElegant = isElegant,
                    fontPreference = fontPreference,
                ),
            )
        }
    }

    private fun cabinetFileRow(
        item: CabinetItemSummary,
        displayMode: String,
        fontPreference: FileListDisplayPreference,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ): View {
        val isElegant = isElegantDisplayMode(displayMode)
        return listRow(isElegant, fontPreference) {
            setOnClickListener { onItemSelected(item.id) }
            setOnLongClickListener {
                showCabinetItemMenu(item, onItemSelected, onToggleFavorite, onMoveTrash)
                true
            }
            addView(
                fileRowBody(
                    kind = fileKindLabel(item.mimeType),
                    name = item.displayName.ifBlank { item.title },
                    updatedLabel = item.updatedAt.ifBlank { context.getString(R.string.label_no_updated_at) },
                    sizeLabel = readableSize(item.size),
                    isElegant = isElegant,
                    fontPreference = fontPreference,
                ),
            )
        }
    }

    private fun listRow(
        isElegant: Boolean,
        fontPreference: FileListDisplayPreference,
        block: LinearLayout.() -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (isElegant) CabinetMetrics.SPACE_LG else CabinetMetrics.SPACE_MD
            val vertical = if (isElegant) CabinetMetrics.SPACE_MD else CabinetMetrics.SPACE_SM
            setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
            minimumHeight = dp(fileTileMinHeight(isElegant, fontPreference))
            asTappable(
                context.pressableShape(
                    CabinetColors.Surface,
                    if (isElegant) CabinetMetrics.RADIUS_CARD else CabinetMetrics.RADIUS_TILE,
                ),
            )
            block()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(if (isElegant) CabinetMetrics.SPACE_SM else CabinetMetrics.SPACE_XS))
            }
        }
    }

    private fun fileRowBody(
        kind: String,
        name: String,
        updatedLabel: String,
        sizeLabel: String,
        isElegant: Boolean,
        fontPreference: FileListDisplayPreference,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fileKindPill(kind, isElegant, fontPreference))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(if (isElegant) CabinetMetrics.SPACE_MD else CabinetMetrics.SPACE_SM), 0, 0, 0)
                    addView(fileNameLabel(name, isElegant, fontPreference))
                    addView(spacer(if (isElegant) CabinetMetrics.SPACE_XS else 2))
                    addView(metaRow(updatedLabel, sizeLabel, fontPreference))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }
    }

    private fun fileKindPill(kind: String, isElegant: Boolean, fontPreference: FileListDisplayPreference): TextView {
        val accent = kindAccent(kind)
        return label(kind, fontPreference.kindFontSize, true, accent).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_XS))
            background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
            layoutParams = LinearLayout.LayoutParams(
                dp(if (isElegant) 50 else 44),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun fileNameLabel(text: String, isElegant: Boolean, fontPreference: FileListDisplayPreference): TextView {
        return label(text, fontPreference.nameFontSize, true).apply {
            maxLines = 2
            setLineSpacing(0f, if (isElegant) 1.2f else 1.14f)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
    }

    private fun metaRow(
        updatedLabel: String,
        sizeLabel: String,
        fontPreference: FileListDisplayPreference,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                label(updatedLabel, fontPreference.metaFontSize, false, CabinetColors.TextMuted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                label(sizeLabel, fontPreference.metaFontSize, false, CabinetColors.TextMuted).apply {
                    gravity = Gravity.END
                    maxLines = 1
                },
            )
        }
    }

    private fun fileTileMinHeight(isElegant: Boolean, fontPreference: FileListDisplayPreference): Int {
        val textHeight = (fontPreference.kindFontSize * 1.16f) +
            (fontPreference.nameFontSize * 1.16f) +
            (fontPreference.metaFontSize * 1.22f)
        val compactHeight = (14f + 8f + textHeight + 4f).coerceIn(64f, 104f).toInt()
        return if (isElegant) compactHeight + 40 else compactHeight
    }

    private fun isElegantDisplayMode(displayMode: String): Boolean = displayMode == "elegant"

    private fun fileListContainer(displayMode: String, layout: FileListLayout): LinearLayout {
        val isElegant = isElegantDisplayMode(displayMode)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (isElegant || layout == FileListLayout.PREVIEW_GRID) {
                setPadding(
                    dp(CabinetMetrics.SPACE_MD),
                    dp(CabinetMetrics.SPACE_MD),
                    dp(CabinetMetrics.SPACE_MD),
                    dp(CabinetMetrics.SPACE_SM),
                )
                background = context.surfaceShape(CabinetColors.SurfaceSunken, CabinetMetrics.RADIUS_CARD, strokeColor = null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 長押しメニュー
    // ---------------------------------------------------------------------

    private fun showLocalFileMenu(
        entry: LocalFileEntry,
        onOpen: () -> Unit,
        onRegisterFile: (File) -> Unit,
        onRenameEntry: (File) -> Unit,
        onCopyEntry: (File) -> Unit,
        onMoveEntry: (File) -> Unit,
        onDuplicateEntry: (File) -> Unit,
        onDeleteEntry: (File) -> Unit,
        onOpenPath: (File) -> Unit,
    ) {
        val pathTarget = if (entry.isDirectory) entry.file else entry.file.parentFile
        showActionSheet(
            title = entry.name,
            path = entry.file.absolutePath,
            onPathLongClick = pathTarget?.let { { onOpenPath(it) } },
            actions = buildList {
                add(ActionItem(context.getString(R.string.action_open), onOpen))
                add(ActionItem(context.getString(R.string.action_rename)) { onRenameEntry(entry.file) })
                if (!entry.isDirectory) {
                    add(ActionItem(context.getString(R.string.action_register_to_cabinet)) { onRegisterFile(entry.file) })
                }
                add(ActionItem(context.getString(R.string.view_show_local_file_menu)) { onCopyEntry(entry.file) })
                add(ActionItem(context.getString(R.string.view_show_local_file_menu_2)) { onMoveEntry(entry.file) })
                add(ActionItem(context.getString(R.string.action_duplicate)) { onDuplicateEntry(entry.file) })
            },
            destructiveActions = listOf(ActionItem(context.getString(R.string.view_show_local_file_menu_3)) { onDeleteEntry(entry.file) }),
        )
    }

    private fun showDeviceFileMenu(
        entry: DeviceFileEntry,
        onOpenFile: (DeviceFileEntry) -> Unit,
        onRegisterFile: (DeviceFileEntry) -> Unit,
        onOpenLocation: (DeviceFileEntry) -> Unit,
    ) {
        showActionSheet(
            title = entry.name,
            path = "${entry.location}${entry.name}",
            onPathLongClick = { onOpenLocation(entry) },
            actions = listOf(
                ActionItem(context.getString(R.string.action_open)) { onOpenFile(entry) },
                ActionItem(context.getString(R.string.action_register_to_cabinet)) { onRegisterFile(entry) },
            ),
        )
    }

    private fun showDocumentFileMenu(
        entry: DocumentFileEntry,
        onOpen: () -> Unit,
        onRegisterFile: (DocumentFileEntry) -> Unit,
    ) {
        showActionSheet(
            title = entry.name,
            path = entry.uri,
            onPathLongClick = null,
            actions = buildList {
                add(ActionItem(context.getString(R.string.action_open), onOpen))
                if (!entry.isDirectory) {
                    add(ActionItem(context.getString(R.string.action_register_to_cabinet)) { onRegisterFile(entry) })
                }
            },
        )
    }

    private fun showCabinetItemMenu(
        item: CabinetItemSummary,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ) {
        showActionSheet(
            title = item.displayName.ifBlank { item.title },
            path = context.getString(R.string.format_dot_separated, item.mimeType, item.sourceKind),
            onPathLongClick = null,
            actions = listOf(
                ActionItem(context.getString(R.string.action_open)) { onItemSelected(item.id) },
                ActionItem(context.getString(R.string.action_show_details)) { onShowItemDetail?.invoke(item.id) },
                ActionItem(if (item.isFavorite) context.getString(R.string.view_show_cabinet_item_menu_2) else context.getString(R.string.action_favorite)) { onToggleFavorite(item) },
            ),
            // ゴミ箱の中の資料に「ゴミ箱へ移動」を出しても意味がない。
            destructiveActions = if (item.isArchived) {
                emptyList()
            } else {
                listOf(ActionItem(context.getString(R.string.action_move_to_trash)) { onMoveTrash(item) })
            },
        )
    }

    private fun showActionSheet(
        title: String,
        path: String,
        onPathLongClick: (() -> Unit)?,
        actions: List<ActionItem>,
        destructiveActions: List<ActionItem> = emptyList(),
    ) {
        var dialog: AlertDialog? = null
        val container = ScrollView(context).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(CabinetColors.Surface)
                    setPadding(
                        dp(CabinetMetrics.SPACE_LG),
                        dp(CabinetMetrics.SPACE_LG),
                        dp(CabinetMetrics.SPACE_LG),
                        dp(CabinetMetrics.SPACE_LG),
                    )
                    addView(
                        label(title, CabinetType.TITLE, true).apply {
                            maxLines = 3
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                        },
                    )
                    addView(
                        label(path, CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
                            setPadding(0, dp(CabinetMetrics.SPACE_XS), 0, dp(CabinetMetrics.SPACE_MD))
                            setLineSpacing(0f, 1.25f)
                            if (onPathLongClick != null) {
                                isClickable = true
                                setOnLongClickListener {
                                    dialog?.dismiss()
                                    onPathLongClick()
                                    true
                                }
                            }
                        },
                    )
                    actions.forEach { action ->
                        addView(
                            ghostButton(action.label) {
                                dialog?.dismiss()
                                action.onClick()
                            },
                        )
                        addView(spacer(CabinetMetrics.SPACE_SM))
                    }
                    destructiveActions.forEach { action ->
                        addView(
                            dangerButton(action.label) {
                                dialog?.dismiss()
                                action.onClick()
                            },
                        )
                        addView(spacer(CabinetMetrics.SPACE_SM))
                    }
                },
            )
        }
        dialog = AlertDialog.Builder(context, R.style.CabinetDialogTheme)
            .setView(container)
            .create()
        dialog.show()
    }

    // ---------------------------------------------------------------------
    // 一覧のソート・絞り込み
    // ---------------------------------------------------------------------

    private fun fileListControls(
        options: FileListOptions,
        onListOptionsChanged: (FileListOptions) -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(CabinetMetrics.SPACE_MD))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        toolButton("⇅  ${context.getString(options.sort.labelRes)}", context.getString(R.string.view_file_list_controls, context.getString(options.sort.labelRes))) {
                            showSortPopup(it, options, onListOptionsChanged)
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
                        },
                    )
                    addView(
                        toolButton(
                            if (options.hasActiveFilter) context.getString(R.string.view_file_list_controls_2) else context.getString(R.string.view_file_list_controls_3),
                            filterSummary(options),
                            highlighted = options.hasActiveFilter,
                        ) {
                            showFilterDialog(options, onListOptionsChanged)
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                leftMargin = dp(CabinetMetrics.SPACE_SM)
                            }
                        },
                    )
                    addView(
                        toolButton(options.layout.icon, context.getString(R.string.view_file_list_controls_4, context.getString(options.layout.labelRes))) {
                            val next = if (options.layout == FileListLayout.LIST) FileListLayout.PREVIEW_GRID else FileListLayout.LIST
                            onListOptionsChanged(options.copy(layout = next))
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = dp(CabinetMetrics.SPACE_SM)
                            }
                        },
                    )
                },
            )
            if (options.hasActiveFilter) {
                addView(spacer(CabinetMetrics.SPACE_SM))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            label(filterSummary(options), CabinetType.MICRO, false, CabinetColors.TextMuted).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            },
                        )
                        addView(
                            label(context.getString(R.string.action_clear), CabinetType.MICRO, true, CabinetColors.Accent).asTappable(
                                context.pressableShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_PILL, strokeColor = null),
                            ).apply {
                                setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_XS))
                                setOnClickListener {
                                    onListOptionsChanged(options.copy(periodDays = null, nameQuery = "", extensionQuery = ""))
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    private fun toolButton(
        text: String,
        description: String,
        highlighted: Boolean = false,
        onClick: (View) -> Unit,
    ): TextView {
        return label(text, CabinetType.CAPTION, true, if (highlighted) CabinetColors.TextPrimary else CabinetColors.Accent).asTappable(
            context.pressableShape(
                if (highlighted) CabinetColors.AccentSoft else CabinetColors.Surface,
                CabinetMetrics.RADIUS_CONTROL,
                strokeColor = if (highlighted) CabinetColors.Accent else CabinetColors.Outline,
            ),
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = description
            minHeight = dp(40)
            setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM))
            setOnClickListener { onClick(this) }
        }
    }

    private fun showSortPopup(
        anchor: View,
        options: FileListOptions,
        onListOptionsChanged: (FileListOptions) -> Unit,
    ) {
        var popup: PopupWindow? = null
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.surfaceShape(CabinetColors.SurfaceElevated, CabinetMetrics.RADIUS_CARD)
            setPadding(dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_SM))
            FileListSort.entries.forEach { sort ->
                val selected = sort == options.sort
                addView(
                    label(
                        if (selected) "✓  ${context.getString(sort.labelRes)}" else "　  ${context.getString(sort.labelRes)}",
                        CabinetType.BODY,
                        selected,
                        if (selected) CabinetColors.Accent else CabinetColors.TextPrimary,
                    ).asTappable(context.borderlessRipple(CabinetMetrics.RADIUS_TILE)).apply {
                        minHeight = dp(CabinetMetrics.MIN_TOUCH_HEIGHT)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_MD), dp(CabinetMetrics.SPACE_SM))
                        setOnClickListener {
                            popup?.dismiss()
                            onListOptionsChanged(options.copy(sort = sort))
                        }
                    },
                )
            }
        }
        popup = PopupWindow(container, dp(240), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(CabinetMetrics.SPACE_SM).toFloat()
        }
        popup.showAsDropDown(anchor)
    }

    private fun showFilterDialog(
        options: FileListOptions,
        onListOptionsChanged: (FileListOptions) -> Unit,
    ) {
        var current = options
        fun apply(update: FileListOptions) {
            current = update
            onListOptionsChanged(current)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(CabinetMetrics.SPACE_LG),
                dp(CabinetMetrics.SPACE_SM),
                dp(CabinetMetrics.SPACE_LG),
                0,
            )
            addView(label(context.getString(R.string.view_apply), CabinetType.MICRO, true, CabinetColors.TextMuted))
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(filterChoiceRow(current) { apply(current.copy(periodDays = it)) })
            addView(spacer(CabinetMetrics.SPACE_LG))
            addView(label(context.getString(R.string.view_apply_2), CabinetType.MICRO, true, CabinetColors.TextMuted))
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(filterInput(current.nameQuery, context.getString(R.string.label_file_name)) { apply(current.copy(nameQuery = it)) })
            addView(spacer(CabinetMetrics.SPACE_LG))
            addView(label(context.getString(R.string.view_apply_3), CabinetType.MICRO, true, CabinetColors.TextMuted))
            addView(spacer(CabinetMetrics.SPACE_SM))
            addView(filterInput(current.extensionQuery, context.getString(R.string.view_apply_4)) { apply(current.copy(extensionQuery = it.trimStart('.'))) })
        }
        AlertDialog.Builder(context, R.style.CabinetDialogTheme)
            .setTitle(context.getString(R.string.view_apply_5))
            .setView(container)
            .setPositiveButton(context.getString(R.string.view_apply_6), null)
            .setNegativeButton(context.getString(R.string.action_clear)) { _, _ ->
                onListOptionsChanged(options.copy(periodDays = null, nameQuery = "", extensionQuery = ""))
            }
            .show()
    }

    private fun filterChoiceRow(options: FileListOptions, onPeriodChanged: (Int?) -> Unit): View {
        val choices = listOf(null to context.getString(R.string.label_period_all), 1 to context.getString(R.string.label_period_today), 7 to context.getString(R.string.view_filter_choice_row), 30 to context.getString(R.string.view_filter_choice_row_2), 365 to context.getString(R.string.view_filter_choice_row_3))
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            choices.forEachIndexed { index, (days, text) ->
                val selected = options.periodDays == days
                addView(
                    label(
                        text,
                        CabinetType.MICRO,
                        true,
                        if (selected) CabinetColors.TextPrimary else CabinetColors.TextSecondary,
                    ).asTappable(
                        context.pressableShape(
                            if (selected) CabinetColors.AccentSoft else CabinetColors.SurfaceAlt,
                            CabinetMetrics.RADIUS_PILL,
                            strokeColor = if (selected) CabinetColors.Accent else CabinetColors.Outline,
                        ),
                    ).apply {
                        gravity = Gravity.CENTER
                        minHeight = dp(36)
                        setPadding(dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_SM))
                        setOnClickListener { onPeriodChanged(days) }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_XS)
                        }
                    },
                )
            }
        }
    }

    private fun filterInput(initialValue: String, hint: String, onChanged: (String) -> Unit): View {
        return context.cabinetInput(hint).apply {
            setText(initialValue)
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onChanged(s?.toString()?.trim().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun filterSummary(options: FileListOptions): String {
        if (!options.hasActiveFilter) return context.getString(R.string.view_filter_summary)
        return buildList {
            if (options.periodDays != null) add(options.periodLabel(context))
            if (options.nameQuery.isNotBlank()) add(context.getString(R.string.view_filter_summary_2, options.nameQuery))
            if (options.extensionQuery.isNotBlank()) add(context.getString(R.string.view_filter_summary_3, options.extensionQuery))
        }.joinToString(" / ")
    }

    // ---------------------------------------------------------------------
    // プレビューグリッド
    // ---------------------------------------------------------------------

    private fun addLocalPreviewGrid(
        container: LinearLayout,
        entries: List<LocalFileEntry>,
        fontPreference: FileListDisplayPreference,
        onOpenDirectory: (File) -> Unit,
        onOpenFile: (File) -> Unit,
        onRegisterFile: (File) -> Unit,
        onRenameEntry: (File) -> Unit,
        onCopyEntry: (File) -> Unit,
        onMoveEntry: (File) -> Unit,
        onDuplicateEntry: (File) -> Unit,
        onDeleteEntry: (File) -> Unit,
    ) {
        addPreviewGrid(container, entries) { entry ->
            val openAction = {
                if (entry.isDirectory) onOpenDirectory(entry.file) else onOpenFile(entry.file)
            }
            previewCard(
                title = entry.name,
                kind = if (entry.isDirectory) "DIR" else fileKindLabel(entry.kind),
                meta = "${entry.updatedLabel}  ${entry.sizeLabel}",
                imageUri = if (!entry.isDirectory && entry.kind.startsWith("image/")) Uri.fromFile(entry.file) else null,
                fontPreference = fontPreference,
                onOpen = openAction,
                onMenu = {
                    showLocalFileMenu(entry, openAction, onRegisterFile, onRenameEntry, onCopyEntry, onMoveEntry, onDuplicateEntry, onDeleteEntry, onOpenDirectory)
                },
            )
        }
    }

    private fun addDevicePreviewGrid(
        container: LinearLayout,
        entries: List<DeviceFileEntry>,
        fontPreference: FileListDisplayPreference,
        onOpenFile: (DeviceFileEntry) -> Unit,
        onRegisterFile: (DeviceFileEntry) -> Unit,
        onOpenLocation: (DeviceFileEntry) -> Unit,
    ) {
        addPreviewGrid(container, entries) { entry ->
            previewCard(
                title = entry.name,
                kind = fileKindLabel(entry.mimeType),
                meta = "${entry.updatedLabel}  ${entry.sizeLabel}",
                imageUri = if (entry.mimeType.startsWith("image/")) Uri.parse(entry.uri) else null,
                fontPreference = fontPreference,
                onOpen = { onOpenFile(entry) },
                onMenu = { showDeviceFileMenu(entry, onOpenFile, onRegisterFile, onOpenLocation) },
            )
        }
    }

    private fun addDocumentPreviewGrid(
        container: LinearLayout,
        entries: List<DocumentFileEntry>,
        fontPreference: FileListDisplayPreference,
        onOpenDirectory: (DocumentFileEntry) -> Unit,
        onOpenFile: (DocumentFileEntry) -> Unit,
        onRegisterFile: (DocumentFileEntry) -> Unit,
    ) {
        addPreviewGrid(container, entries) { entry ->
            val openAction = {
                if (entry.isDirectory) onOpenDirectory(entry) else onOpenFile(entry)
            }
            previewCard(
                title = entry.name,
                kind = if (entry.isDirectory) "DIR" else fileKindLabel(entry.mimeType),
                meta = "${entry.updatedLabel}  ${entry.sizeLabel}",
                imageUri = if (!entry.isDirectory && entry.mimeType.startsWith("image/")) Uri.parse(entry.uri) else null,
                fontPreference = fontPreference,
                onOpen = openAction,
                onMenu = { showDocumentFileMenu(entry, openAction, onRegisterFile) },
            )
        }
    }

    private fun addCabinetPreviewGrid(
        container: LinearLayout,
        entries: List<CabinetItemSummary>,
        fontPreference: FileListDisplayPreference,
        onItemSelected: (String) -> Unit,
        onToggleFavorite: (CabinetItemSummary) -> Unit,
        onMoveTrash: (CabinetItemSummary) -> Unit,
    ) {
        addPreviewGrid(container, entries) { item ->
            // CabinetItemSummary はサムネイルのパスを持たないため、種別バッジで表示する。
            previewCard(
                title = item.displayName.ifBlank { item.title },
                kind = fileKindLabel(item.mimeType),
                meta = "${item.updatedAt.ifBlank { "更新日時なし" }}  ${readableSize(item.size)}",
                imageUri = null,
                fontPreference = fontPreference,
                onOpen = { onItemSelected(item.id) },
                onMenu = { showCabinetItemMenu(item, onItemSelected, onToggleFavorite, onMoveTrash) },
            )
        }
    }

    private fun <T> addPreviewGrid(
        container: LinearLayout,
        entries: List<T>,
        cardFactory: (T) -> View,
    ) {
        entries.chunked(2).forEach { pair ->
            container.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { index, entry ->
                        addView(
                            cardFactory(entry).apply {
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                    if (index > 0) leftMargin = dp(CabinetMetrics.SPACE_SM)
                                    bottomMargin = dp(CabinetMetrics.SPACE_SM)
                                }
                            },
                        )
                    }
                    if (pair.size == 1) {
                        addView(
                            View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                    leftMargin = dp(CabinetMetrics.SPACE_SM)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun previewCard(
        title: String,
        kind: String,
        meta: String,
        imageUri: Uri?,
        fontPreference: FileListDisplayPreference,
        onOpen: () -> Unit,
        onMenu: () -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(CabinetMetrics.SPACE_SM),
                dp(CabinetMetrics.SPACE_SM),
                dp(CabinetMetrics.SPACE_SM),
                dp(CabinetMetrics.SPACE_MD),
            )
            asTappable(context.pressableShape(CabinetColors.Surface, CabinetMetrics.RADIUS_CARD))
            setOnClickListener { onOpen() }
            setOnLongClickListener {
                onMenu()
                true
            }
            addView(previewBox(kind, imageUri))
            addView(
                fileNameLabel(title, true, fontPreference).apply {
                    setPadding(dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_SM), dp(CabinetMetrics.SPACE_XS), dp(CabinetMetrics.SPACE_XS))
                },
            )
            addView(
                label(meta, fontPreference.metaFontSize, false, CabinetColors.TextMuted).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(CabinetMetrics.SPACE_XS), 0, dp(CabinetMetrics.SPACE_XS), 0)
                },
            )
        }
    }

    private fun previewBox(kind: String, imageUri: Uri?): View {
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112))
        return if (imageUri != null) {
            ImageView(context).apply {
                setImageURI(imageUri)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                this.layoutParams = layoutParams
            }
        } else {
            label(kind, CabinetType.TITLE, true, kindAccent(kind)).apply {
                gravity = Gravity.CENTER
                background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
                this.layoutParams = layoutParams
            }
        }
    }

    // ---------------------------------------------------------------------
    // 基本部品
    // ---------------------------------------------------------------------

    private fun fileKindLabel(kind: String): String {
        return when {
            kind == "folder" -> "DIR"
            kind.startsWith("image/") -> "IMG"
            kind.startsWith("video/") -> "VID"
            kind.startsWith("audio/") -> "AUD"
            kind == "application/pdf" -> "PDF"
            kind == "text/markdown" -> "MD"
            kind == "text/csv" || kind == "text/tab-separated-values" -> "CSV"
            kind.startsWith("text/") -> "TXT"
            kind.contains("android.package-archive") -> "APK"
            kind.contains("macroEnabled") -> "XLSM"
            kind.contains("spreadsheet") || kind.contains("excel") -> "XLS"
            kind.contains("wordprocessing") || kind.contains("msword") -> "DOC"
            kind.contains("presentation") || kind.contains("powerpoint") -> "PPT"
            kind.contains("zip") -> "ZIP"
            else -> "FILE"
        }
    }

    private fun locationIcon(modeName: String): String {
        return when (modeName) {
            "Library" -> "CAB"
            "Downloads" -> "DL"
            "Inbox" -> "IN"
            "Explorer" -> "LOC"
            "SDCard" -> "SD"
            "Documents" -> "DOC"
            "Pictures" -> "IMG"
            "Movies" -> "MOV"
            "Music" -> "AUD"
            else -> "DIR"
        }
    }

    private fun readableSize(size: Long): String = CabinetFiles.readableSize(size)

    private fun thumbnailImage(path: String): ImageView? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = context.surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_TILE, strokeColor = null)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180),
            ).apply {
                setMargins(0, dp(CabinetMetrics.SPACE_MD), 0, 0)
            }
        }
    }

    private fun jp.viastrasse.cabinet.data.CabinetPreviewSummary.metadataText(): String {
        return listOfNotNull(
            pageCount?.let { "$it pages" },
            duration?.let { "${it}ms" },
            if (width != null && height != null) "${width} x ${height}px" else null,
        ).joinToString(" / ")
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

    private fun spacer(height: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

/** 汎用の操作項目。ボタン列・アクションシート・グリッドで共有する。 */
data class ActionItem(
    val label: String,
    val onClick: () -> Unit,
)

/** タグ・Collection を表すチップ。 */
data class ChipItem(
    val text: String,
    val accentColor: Int,
    val onClick: (() -> Unit)?,
    val onRemove: (() -> Unit)?,
)

data class CabinetMode(
    val name: String,
    val count: Long,
    val description: String,
)

data class LocalFileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val kind: String,
    val sizeLabel: String,
    val updatedLabel: String,
    val updatedAtMillis: Long,
    val extension: String,
)

data class DeviceFileEntry(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sizeLabel: String,
    val updatedLabel: String,
    val location: String,
    val updatedAtMillis: Long,
    val extension: String,
)

data class DocumentFileEntry(
    val document: DocumentFile,
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val sizeLabel: String,
    val updatedLabel: String,
    val updatedAtMillis: Long,
    val extension: String,
)

/**
 * enum は Context を持てないため、文言そのものではなく文字列リソースIDを保持する。
 * 表示側で `context.getString(sort.labelRes)` として解決する。
 */
enum class FileListSort(@param:StringRes val labelRes: Int) {
    DATE_DESC(R.string.sort_date_desc),
    DATE_ASC(R.string.sort_date_asc),
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
    EXT_ASC(R.string.sort_ext_asc),
    EXT_DESC(R.string.sort_ext_desc),
}

enum class FileListLayout(@param:StringRes val labelRes: Int, val icon: String) {
    LIST(R.string.layout_list, "☷"),
    PREVIEW_GRID(R.string.layout_preview_grid, "▦"),
}

data class FileListOptions(
    val sort: FileListSort = FileListSort.DATE_DESC,
    val periodDays: Int? = null,
    val nameQuery: String = "",
    val extensionQuery: String = "",
    val layout: FileListLayout = FileListLayout.LIST,
) {
    val hasActiveFilter: Boolean
        get() = periodDays != null || nameQuery.isNotBlank() || extensionQuery.isNotBlank()

}

/**
 * 期間フィルタの表示名。
 * data class は Context を持てないため、拡張関数として Context 側に置く。
 */
fun FileListOptions.periodLabel(context: Context): String = when (periodDays) {
    null -> context.getString(R.string.label_period_all)
    1 -> context.getString(R.string.label_period_today)
    7 -> context.getString(R.string.period_within_7_days)
    30 -> context.getString(R.string.period_within_30_days)
    365 -> context.getString(R.string.period_within_1_year)
    else -> context.getString(R.string.period_within_days, periodDays)
}

/**
 * ファイル一覧の文字サイズ設定。
 *
 * 種別バッジ / ファイル名 / 日時・サイズ の3要素に対応する。
 */
data class FileListDisplayPreference(
    val kindFontSize: Int = DEFAULT_FONT_SIZE,
    val nameFontSize: Int = DEFAULT_FONT_SIZE,
    val metaFontSize: Int = DEFAULT_FONT_SIZE,
) {
    companion object {
        const val MIN_FONT_SIZE = 10
        const val MAX_FONT_SIZE = 22
        const val DEFAULT_FONT_SIZE = 14
    }
}
