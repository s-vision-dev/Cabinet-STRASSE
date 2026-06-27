package jp.viastrasse.cabinetstrasse.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinetstrasse.data.CabinetRepository

class CabinetHomeSummaryProvider : ContentProvider() {
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

        return MatrixCursor(
            arrayOf(
                "app",
                "recent_count",
                "inbox_count",
                "favorite_count",
                "collection_count",
            ),
        ).apply {
            addRow(
                arrayOf<Any>(
                    "Cabinet-STRASSE",
                    dashboard?.libraryCount ?: 0,
                    dashboard?.inboxCount ?: 0,
                    dashboard?.favoriteCount ?: 0,
                    dashboard?.collectionCount ?: 0,
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
        return MatrixCursor(
            arrayOf(
                "app",
                "recent_count",
                "inbox_count",
                "favorite_count",
                "collection_count",
            ),
        )
    }
}
