package jp.viastrasse.cabinetstrasse.data

data class CabinetDashboard(
    val version: Long,
    val explorerCount: Long,
    val libraryCount: Long,
    val collectionCount: Long,
    val inboxCount: Long,
    val favoriteCount: Long,
    val trashCount: Long,
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

data class ModeResponse(
    val mode: String,
    val title: String,
    val items: List<CabinetItemSummary>,
    val collections: List<CabinetCollectionSummary>,
    val smartFolders: List<SmartFolderSummary>,
)

data class CabinetItemDetail(
    val item: CabinetItemSummary,
    val path: String,
    val hash: String,
    val note: String,
    val tags: List<TagSummary>,
    val collections: List<CabinetCollectionSummary>,
    val previews: List<CabinetPreviewSummary>,
    val references: List<CabinetReferenceSummary>,
    val versions: List<CabinetVersionSummary>,
    val memos: List<CabinetMemoSummary>,
)

data class TagSummary(
    val id: String,
    val name: String,
    val color: String,
)

data class CabinetPreviewSummary(
    val id: String,
    val previewType: String,
    val title: String,
    val summaryText: String,
    val status: String,
    val generatedAt: String,
)

data class CabinetReferenceSummary(
    val id: String,
    val referenceType: String,
    val sourceApp: String,
    val title: String,
    val uri: String,
    val note: String,
)

data class CabinetVersionSummary(
    val id: String,
    val versionNumber: Long,
    val displayName: String,
    val note: String,
    val isCurrent: Boolean,
)

data class CabinetMemoSummary(
    val id: String,
    val body: String,
    val isProtected: Boolean,
    val updatedAt: String,
)

data class PreviewProcessReport(
    val processed: Long,
    val remaining: Long,
)

data class SettingsSnapshot(
    val providers: List<StorageProviderAccountSummary>,
    val backup: BackupSummary,
    val security: SecuritySummary,
)

data class StorageProviderAccountSummary(
    val id: String,
    val providerType: String,
    val displayName: String,
    val accountName: String,
    val authType: String,
    val connectionStatus: String,
    val lastConnectedAt: String,
)

data class BackupSummary(
    val itemCount: Long,
    val collectionCount: Long,
    val tagCount: Long,
    val previewCount: Long,
    val exportedAt: String,
)

data class SecuritySummary(
    val pinEnabled: Boolean,
    val protectedItemCount: Long,
    val protectedMemoCount: Long,
)

data class DuplicateReport(
    val groups: List<DuplicateGroup>,
)

data class DuplicateGroup(
    val hash: String,
    val size: Long,
    val items: List<CabinetItemSummary>,
)
