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

    private fun JSONArray?.toItems(): List<CabinetItemSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                add(
                    CabinetItemSummary(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        displayName = item.optString("display_name"),
                        mimeType = item.optString("mime_type"),
                        sourceKind = item.optString("source_kind"),
                        size = item.optLong("size"),
                        isFavorite = item.optBoolean("is_favorite"),
                        isUnsorted = item.optBoolean("is_unsorted"),
                        summaryText = item.optString("summary_text"),
                        updatedAt = item.optString("updated_at"),
                    ),
                )
            }
        }
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
}

