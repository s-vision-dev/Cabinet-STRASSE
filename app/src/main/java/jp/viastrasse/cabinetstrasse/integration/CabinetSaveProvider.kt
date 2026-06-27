package jp.viastrasse.cabinetstrasse.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinetstrasse.data.CabinetRepository

class CabinetSaveProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return MatrixCursor(
            arrayOf(
                "capability",
                "required_values",
                "optional_values",
            ),
        ).apply {
            addRow(arrayOf("save_url", "url", "title,note"))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.viastrasse.cabinet.save"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val url = values?.getAsString("url").orEmpty()
        if (url.isBlank()) {
            return null
        }
        return runCatching {
            val item = CabinetRepository(context).registerUrl(
                url = url,
                title = values?.getAsString("title").orEmpty().ifBlank { url },
                note = values?.getAsString("note").orEmpty().ifBlank { "CabinetSaveProviderから保存" },
            )
            Uri.parse("strasse://cabinet/open/${item.id}")
        }.getOrNull()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
