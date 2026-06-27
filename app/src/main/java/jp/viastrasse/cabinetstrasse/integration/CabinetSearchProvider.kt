package jp.viastrasse.cabinetstrasse.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinetstrasse.data.CabinetItemDetail
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import org.json.JSONArray
import org.json.JSONObject

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
                "tags_json",
                "collections_json",
                "previews_json",
            ),
        )
        val context = context ?: return cursor
        runCatching {
            val repository = CabinetRepository(context)
            repository.search(query) to repository
        }.onSuccess { (response, repository) ->
            response.results.forEach { item ->
                val detail = runCatching { repository.detail(item.id) }.getOrNull()
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
                        detail.tagsJson(),
                        detail.collectionsJson(),
                        detail.previewsJson(),
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

    private fun CabinetItemDetail?.tagsJson(): String {
        return JSONArray(
            this?.tags.orEmpty().map { tag ->
                JSONObject()
                    .put("id", tag.id)
                    .put("name", tag.name)
                    .put("color", tag.color)
            },
        ).toString()
    }

    private fun CabinetItemDetail?.collectionsJson(): String {
        return JSONArray(
            this?.collections.orEmpty().map { collection ->
                JSONObject()
                    .put("id", collection.id)
                    .put("title", collection.title)
                    .put("item_count", collection.itemCount)
                    .put("deep_link", "strasse://cabinet/collection/${collection.id}")
            },
        ).toString()
    }

    private fun CabinetItemDetail?.previewsJson(): String {
        return JSONArray(
            this?.previews.orEmpty().map { preview ->
                JSONObject()
                    .put("id", preview.id)
                    .put("type", preview.previewType)
                    .put("summary", preview.summaryText)
                    .put("thumbnail_path", preview.thumbnailPath)
                    .put("page_count", preview.pageCount)
                    .put("duration", preview.duration)
                    .put("width", preview.width)
                    .put("height", preview.height)
                    .put("status", preview.status)
            },
        ).toString()
    }
}
