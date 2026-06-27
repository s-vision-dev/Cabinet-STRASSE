package jp.viastrasse.cabinetstrasse.data

data class CabinetDashboard(
    val version: Long,
    val explorerCount: Long,
    val libraryCount: Long,
    val collectionCount: Long,
    val inboxCount: Long,
    val favoriteCount: Long,
    val recentItems: List<CabinetItemSummary>,
    val collections: List<CabinetCollectionSummary>,
    val smartFolders: List<SmartFolderSummary>,
)

data class CabinetItemSummary(
    val id: String,
    val title: String,
    val displayName: String,
    val mimeType: String,
    val sourceKind: String,
    val size: Long,
    val isFavorite: Boolean,
    val isUnsorted: Boolean,
    val summaryText: String,
    val updatedAt: String,
)

data class CabinetCollectionSummary(
    val id: String,
    val title: String,
    val itemCount: Long,
)

data class SmartFolderSummary(
    val id: String,
    val title: String,
    val condition: String,
    val itemCount: Long,
)

data class SearchResponse(
    val query: String,
    val results: List<CabinetItemSummary>,
)

