package jp.viastrasse.cabinetstrasse.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import org.json.JSONObject

class CabinetEventProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "event_type",
                "item_id",
                "payload_json",
                "created_at",
            ),
        )
        val context = context ?: return cursor
        val limit = uri.getQueryParameter("limit")?.toLongOrNull() ?: 100L
        runCatching {
            JSONObject(CabinetRepository(context).eventsJson(limit))
        }.onSuccess { root ->
            root.optJSONArray("events")?.let { events ->
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    cursor.addRow(
                        arrayOf(
                            event.optString("id"),
                            event.optString("event_type"),
                            event.optString("item_id"),
                            event.optJSONObject("payload")?.toString().orEmpty(),
                            event.optString("created_at"),
                        ),
                    )
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.viastrasse.cabinet.event"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
