package jp.viastrasse.cabinetstrasse.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinetstrasse.data.CabinetRepository

class CabinetSearchProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val query = uri.getQueryParameter("q")
            ?: selectionArgs?.firstOrNull()
            ?: selection.orEmpty()
        val cursor = MatrixCursor(
            arrayOf(
                "provider",
                "item_id",
                "title",
                "display_name",
                "mime_type",
                "source_kind",
                "summary",
                "deep_link",
                "updated_at",
                "is_favorite",
                "is_unsorted",
            ),
        )
        val context = context ?: return cursor
        runCatching {
            CabinetRepository(context).search(query)
        }.onSuccess { response ->
            response.results.forEach { item ->
                cursor.addRow(
                    arrayOf<Any>(
                        "Cabinet-STRASSE",
                        item.id,
                        item.title,
                        item.displayName,
                        item.mimeType,
                        item.sourceKind,
                        item.summaryText,
                        "strasse://cabinet/open/${item.id}",
                        item.updatedAt,
                        if (item.isFavorite) 1 else 0,
                        if (item.isUnsorted) 1 else 0,
                    ),
                )
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.viastrasse.cabinet.search"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
