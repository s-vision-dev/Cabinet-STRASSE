package jp.viastrasse.cabinetstrasse.data

import org.json.JSONArray
import org.json.JSONObject

object CabinetJsonParser {
    fun dashboard(json: String): CabinetDashboard {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return CabinetDashboard(
            version = root.optLong("version"),
            explorerCount = root.optLong("explorer_count"),
            libraryCount = root.optLong("library_count"),
            collectionCount = root.optLong("collection_count"),
            inboxCount = root.optLong("inbox_count"),
            favoriteCount = root.optLong("favorite_count"),
            trashCount = root.optLong("trash_count"),
            recentItems = root.optJSONArray("recent_items").toItems(),
            collections = root.optJSONArray("collections").toCollections(),
            smartFolders = root.optJSONArray("smart_folders").toSmartFolders(),
        )
    }

    fun search(json: String): SearchResponse {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return SearchResponse(
            query = root.optString("query"),
            results = root.optJSONArray("results").toItems(),
        )
    }

    fun mode(json: String): ModeResponse {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return ModeResponse(
            mode = root.optString("mode"),
            title = root.optString("title"),
            items = root.optJSONArray("items").toItems(),
            collections = root.optJSONArray("collections").toCollections(),
            smartFolders = root.optJSONArray("smart_folders").toSmartFolders(),
        )
    }

    fun detail(json: String): CabinetItemDetail {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return CabinetItemDetail(
            item = root.getJSONObject("item").toItem(),
            path = root.optString("path"),
            hash = root.optString("hash"),
            note = root.optString("note"),
            tags = root.optJSONArray("tags").toTags(),
            collections = root.optJSONArray("collections").toCollections(),
            previews = root.optJSONArray("previews").toPreviews(),
            references = root.optJSONArray("references").toReferences(),
            versions = root.optJSONArray("versions").toVersions(),
            memos = root.optJSONArray("memos").toMemos(),
        )
    }

    fun previewReport(json: String): PreviewProcessReport {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return PreviewProcessReport(
            processed = root.optLong("processed"),
            remaining = root.optLong("remaining"),
        )
    }

    fun settings(json: String): SettingsSnapshot {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return SettingsSnapshot(
            providers = root.optJSONArray("providers").toProviders(),
            backup = root.getJSONObject("backup").toBackup(),
            security = root.getJSONObject("security").toSecurity(),
        )
    }

    fun duplicateReport(json: String): DuplicateReport {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        return DuplicateReport(groups = root.optJSONArray("groups").toDuplicateGroups())
    }

    private fun JSONArray?.toItems(): List<CabinetItemSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                add(getJSONObject(index).toItem())
            }
        }
    }

    private fun JSONObject.toItem(): CabinetItemSummary {
        return CabinetItemSummary(
            id = optString("id"),
            title = optString("title"),
            displayName = optString("display_name"),
            mimeType = optString("mime_type"),
            sourceKind = optString("source_kind"),
            size = optLong("size"),
            isFavorite = optBoolean("is_favorite"),
            isUnsorted = optBoolean("is_unsorted"),
            summaryText = optString("summary_text"),
            updatedAt = optString("updated_at"),
        )
    }

    private fun JSONArray?.toCollections(): List<CabinetCollectionSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetCollectionSummary(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        itemCount = item.optLong("item_count"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toSmartFolders(): List<SmartFolderSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    SmartFolderSummary(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        condition = item.optString("condition"),
                        itemCount = item.optLong("item_count"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toPreviews(): List<CabinetPreviewSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetPreviewSummary(
                        id = item.optString("id"),
                        previewType = item.optString("preview_type"),
                        title = item.optString("title"),
                        summaryText = item.optString("summary_text"),
                        status = item.optString("status"),
                        generatedAt = item.optString("generated_at"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toReferences(): List<CabinetReferenceSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetReferenceSummary(
                        id = item.optString("id"),
                        referenceType = item.optString("reference_type"),
                        sourceApp = item.optString("source_app"),
                        title = item.optString("title"),
                        uri = item.optString("uri"),
                        note = item.optString("note"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toVersions(): List<CabinetVersionSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetVersionSummary(
                        id = item.optString("id"),
                        versionNumber = item.optLong("version_number"),
                        displayName = item.optString("display_name"),
                        note = item.optString("note"),
                        isCurrent = item.optBoolean("is_current"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toMemos(): List<CabinetMemoSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetMemoSummary(
                        id = item.optString("id"),
                        body = item.optString("body"),
                        isProtected = item.optBoolean("is_protected"),
                        updatedAt = item.optString("updated_at"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toProviders(): List<StorageProviderAccountSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    StorageProviderAccountSummary(
                        id = item.optString("id"),
                        providerType = item.optString("provider_type"),
                        displayName = item.optString("display_name"),
                        accountName = item.optString("account_name"),
                        authType = item.optString("auth_type"),
                        connectionStatus = item.optString("connection_status"),
                        lastConnectedAt = item.optString("last_connected_at"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toTags(): List<TagSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    TagSummary(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        color = item.optString("color"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.toBackup(): BackupSummary {
        return BackupSummary(
            itemCount = optLong("item_count"),
            collectionCount = optLong("collection_count"),
            tagCount = optLong("tag_count"),
            previewCount = optLong("preview_count"),
            exportedAt = optString("exported_at"),
        )
    }

    private fun JSONObject.toSecurity(): SecuritySummary {
        return SecuritySummary(
            pinEnabled = optBoolean("pin_enabled"),
            protectedItemCount = optLong("protected_item_count"),
            protectedMemoCount = optLong("protected_memo_count"),
        )
    }

    private fun JSONArray?.toDuplicateGroups(): List<DuplicateGroup> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    DuplicateGroup(
                        hash = item.optString("hash"),
                        size = item.optLong("size"),
                        items = item.optJSONArray("items").toItems(),
                    ),
                )
            }
        }
    }
}
