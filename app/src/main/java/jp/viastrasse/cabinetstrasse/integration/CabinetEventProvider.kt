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
                "deep_link",
            ),
        )
        val context = context ?: return cursor
        val limit = uri.getQueryParameter("limit")?.toLongOrNull() ?: 100L
        val eventTypeFilter = uri.getQueryParameter("event_type")
            ?: selectionArgs?.getOrNull(0)
        val itemIdFilter = uri.getQueryParameter("item_id")
            ?: selectionArgs?.getOrNull(1)
        runCatching {
            JSONObject(CabinetRepository(context).eventsJson(limit))
        }.onSuccess { root ->
            root.optJSONArray("events")?.let { events ->
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val eventType = event.optString("event_type")
                    val itemId = event.optString("item_id")
                    if (!eventTypeFilter.isNullOrBlank() && eventType != eventTypeFilter) {
                        continue
                    }
                    if (!itemIdFilter.isNullOrBlank() && itemId != itemIdFilter) {
                        continue
                    }
                    cursor.addRow(
                        arrayOf<Any>(
                            event.optString("id"),
                            eventType,
                            itemId,
                            event.optJSONObject("payload")?.toString().orEmpty(),
                            event.optString("created_at"),
                            if (itemId.isBlank()) "" else "strasse://cabinet/open/$itemId",
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
