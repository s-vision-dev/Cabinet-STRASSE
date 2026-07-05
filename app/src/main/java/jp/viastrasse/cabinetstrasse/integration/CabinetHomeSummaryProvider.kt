package jp.viastrasse.cabinet.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinet.data.CabinetCollectionSummary
import jp.viastrasse.cabinet.data.CabinetItemSummary
import jp.viastrasse.cabinet.data.CabinetRepository
import jp.viastrasse.cabinet.data.SmartFolderSummary
import org.json.JSONArray
import org.json.JSONObject

class CabinetHomeSummaryProvider : ContentProvider() {
    private val columns = arrayOf(
        "app",
        "recent_count",
        "inbox_count",
        "favorite_count",
        "collection_count",
        "trash_count",
        "recent_items_json",
        "collections_json",
        "smart_folders_json",
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return emptyCursor()
        val dashboard = runCatching {
            CabinetRepository(context).dashboard()
        }.getOrNull()

        return MatrixCursor(columns).apply {
            addRow(
                arrayOf<Any>(
                    "Cabinet by VIASTRASSE",
                    dashboard?.libraryCount ?: 0,
                    dashboard?.inboxCount ?: 0,
                    dashboard?.favoriteCount ?: 0,
                    dashboard?.collectionCount ?: 0,
                    dashboard?.trashCount ?: 0,
                    dashboard?.recentItems.toJson(),
                    dashboard?.collections.toCollectionJson(),
                    dashboard?.smartFolders.toSmartFolderJson(),
                ),
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.viastrasse.cabinet.summary"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun emptyCursor(): Cursor {
        return MatrixCursor(columns)
    }

    private fun List<CabinetItemSummary>?.toJson(): String {
        val items = this ?: emptyList()
        return JSONArray(
            items.map { item ->
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("display_name", item.displayName)
                    .put("mime_type", item.mimeType)
                    .put("source_kind", item.sourceKind)
                    .put("summary", item.summaryText)
                    .put("updated_at", item.updatedAt)
                    .put("deep_link", "viastrasse-cabinet://open/${item.id}")
            },
        ).toString()
    }

    private fun List<CabinetCollectionSummary>?.toCollectionJson(): String {
        val items = this ?: emptyList()
        return JSONArray(
            items.map { collection ->
                JSONObject()
                    .put("id", collection.id)
                    .put("title", collection.title)
                    .put("item_count", collection.itemCount)
                    .put("deep_link", "viastrasse-cabinet://collection/${collection.id}")
            },
        ).toString()
    }

    private fun List<SmartFolderSummary>?.toSmartFolderJson(): String {
        val items = this ?: emptyList()
        return JSONArray(
            items.map { folder ->
                JSONObject()
                    .put("id", folder.id)
                    .put("title", folder.title)
                    .put("condition", folder.condition)
                    .put("item_count", folder.itemCount)
                    .put("deep_link", "viastrasse-cabinet://smart/${folder.id}")
            },
        ).toString()
    }
}
