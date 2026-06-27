package jp.viastrasse.cabinetstrasse.core

object CabinetNative {
    init {
        System.loadLibrary("cabinet_android_ffi")
    }

    external fun dashboardJson(databasePath: String): String

    external fun searchJson(databasePath: String, query: String): String

    external fun modeJson(databasePath: String, mode: String): String

    external fun itemDetailJson(databasePath: String, itemId: String): String

    external fun processPreviewQueueJson(databasePath: String, limit: Long): String

    external fun settingsJson(databasePath: String): String

    external fun updateStorageProviderJson(
        databasePath: String,
        providerId: String,
        accountName: String,
        connectionStatus: String,
    ): String

    external fun eventsJson(databasePath: String, limit: Long): String

    external fun backupExportJson(databasePath: String): String

    external fun backupImportJson(databasePath: String, backupJson: String): String

    external fun duplicateReportJson(databasePath: String): String

    external fun updateItemFlagsJson(
        databasePath: String,
        itemId: String,
        isFavorite: Boolean,
        isUnsorted: Boolean,
    ): String

    external fun markItemOpenedJson(databasePath: String, itemId: String): String

    external fun addTagToItemJson(databasePath: String, itemId: String, tagName: String, color: String): String

    external fun removeTagFromItemJson(databasePath: String, itemId: String, tagId: String): String

    external fun addItemToCollectionJson(databasePath: String, itemId: String, collectionTitle: String): String

    external fun removeItemFromCollectionJson(databasePath: String, itemId: String, collectionId: String): String

    external fun moveItemToTrashJson(databasePath: String, itemId: String): String

    external fun restoreItemFromTrashJson(databasePath: String, itemId: String): String

    external fun deleteItemPermanentlyJson(databasePath: String, itemId: String): String

    external fun duplicateItemJson(databasePath: String, itemId: String): String

    external fun renameItemJson(databasePath: String, itemId: String, newDisplayName: String): String

    external fun addVersionJson(
        databasePath: String,
        itemId: String,
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        note: String,
    ): String

    external fun setCurrentVersionJson(databasePath: String, itemId: String, versionId: String): String

    external fun addReferenceJson(
        databasePath: String,
        itemId: String,
        referenceType: String,
        sourceApp: String,
        sourceId: String,
        title: String,
        uri: String,
        note: String,
    ): String

    external fun addMemoJson(databasePath: String, itemId: String, body: String, isProtected: Boolean): String

    external fun setSecurityPinJson(databasePath: String, pin: String): String

    external fun verifySecurityPinJson(databasePath: String, pin: String): String

    external fun setItemProtectedJson(databasePath: String, itemId: String, isProtected: Boolean): String

    external fun registerUrlJson(
        databasePath: String,
        url: String,
        title: String,
        note: String,
    ): String

    external fun registerFileJson(
        databasePath: String,
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        sourceKind: String,
        note: String,
    ): String
}
