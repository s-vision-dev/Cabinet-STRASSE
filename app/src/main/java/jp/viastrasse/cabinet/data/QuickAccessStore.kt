package jp.viastrasse.cabinet.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * クイックアクセスに登録した項目。
 *
 * 端末上のフォルダ／ファイルと、Cabinet に登録済みの資料の両方を扱う。
 * 参照の仕方が違うため [target] の意味も種別ごとに変わる。
 */
data class QuickAccessEntry(
    val type: QuickAccessType,
    /** 通常ファイルは絶対パス、SAFはcontent URI、資料は資料ID。 */
    val target: String,
    val label: String,
    /** 種別バッジに出す文字。DIR / PDF など。 */
    val kind: String,
    /** SAF項目への永続アクセス権を保持しているツリーURI。 */
    val treeUri: String = "",
    /** SAFツリーのルートから対象までの表示名による相対階層。 */
    val relativePath: List<String> = emptyList(),
    /** リモート項目を所有するストレージプロバイダのアカウントID。 */
    val providerId: String = "",
    val remoteId: String = "",
    val remotePath: String = "",
    val mimeType: String = "",
    val size: Long = 0L,
    val webUrl: String = "",
) {
    /** 同じ対象を指すかどうか。重複登録の判定に使う。 */
    fun isSameTarget(other: QuickAccessEntry): Boolean =
        type == other.type && target == other.target && providerId == other.providerId
}

enum class QuickAccessType {
    FOLDER,
    FILE,
    SAF_FOLDER,
    SAF_FILE,
    CONTENT_FILE,
    REMOTE_FOLDER,
    REMOTE_FILE,
    ITEM,
    ;

    companion object {
        fun from(value: String): QuickAccessType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FILE
    }
}

/**
 * クイックアクセスの保存先。
 *
 * 表示のための設定なので、Rust コアのスキーマではなく SharedPreferences に置く。
 * 表示密度や選択中プロバイダと同じ扱いで、スキーマ移行を伴わない。
 */
class QuickAccessStore(private val preferences: SharedPreferences) {

    fun load(): List<QuickAccessEntry> {
        val raw = preferences.getString(KEY_QUICK_ACCESS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val target = item.optString(FIELD_TARGET)
                    if (target.isBlank()) continue
                    add(
                        QuickAccessEntry(
                            type = QuickAccessType.from(item.optString(FIELD_TYPE)),
                            target = target,
                            label = item.optString(FIELD_LABEL).ifBlank { target },
                            kind = item.optString(FIELD_KIND).ifBlank { "FILE" },
                            treeUri = item.optString(FIELD_TREE_URI),
                            relativePath = item.optJSONArray(FIELD_RELATIVE_PATH)?.let { path ->
                                buildList {
                                    for (pathIndex in 0 until path.length()) {
                                        path.optString(pathIndex).takeIf { it.isNotBlank() }?.let(::add)
                                    }
                                }
                            }.orEmpty(),
                            providerId = item.optString(FIELD_PROVIDER_ID),
                            remoteId = item.optString(FIELD_REMOTE_ID),
                            remotePath = item.optString(FIELD_REMOTE_PATH),
                            mimeType = item.optString(FIELD_MIME_TYPE),
                            size = item.optLong(FIELD_SIZE),
                            webUrl = item.optString(FIELD_WEB_URL),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 既に同じ対象があれば追加せず false を返す。 */
    fun add(entry: QuickAccessEntry): Boolean {
        val current = load()
        if (current.any { it.isSameTarget(entry) }) return false
        save(current + entry)
        return true
    }

    fun remove(entry: QuickAccessEntry) {
        save(load().filterNot { it.isSameTarget(entry) })
    }

    fun contains(entry: QuickAccessEntry): Boolean = load().any { it.isSameTarget(entry) }

    /**
     * [entry] を [target] があった位置へ移す。ドラッグでの並べ替え用。
     *
     * 掴んだタイルを離した先のタイルと入れ替えるのではなく、その位置へ差し込む。
     * 間のタイルは順に押し出され、見た目どおりの並びになる。
     */
    fun reorder(entry: QuickAccessEntry, target: QuickAccessEntry) {
        val current = load().toMutableList()
        val from = current.indexOfFirst { it.isSameTarget(entry) }
        val to = current.indexOfFirst { it.isSameTarget(target) }
        if (from < 0 || to < 0 || from == to) return
        current.removeAt(from)
        current.add(to, entry)
        save(current)
    }

    /** [entry] を [offset] 分だけ前後に動かす。並び替え用。 */
    fun move(entry: QuickAccessEntry, offset: Int) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.isSameTarget(entry) }
        if (index < 0) return
        val destination = (index + offset).coerceIn(0, current.lastIndex)
        if (destination == index) return
        current.removeAt(index)
        current.add(destination, entry)
        save(current)
    }

    private fun save(entries: List<QuickAccessEntry>) {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(FIELD_TYPE, entry.type.name)
                    put(FIELD_TARGET, entry.target)
                    put(FIELD_LABEL, entry.label)
                    put(FIELD_KIND, entry.kind)
                    put(FIELD_TREE_URI, entry.treeUri)
                    put(FIELD_RELATIVE_PATH, JSONArray(entry.relativePath))
                    put(FIELD_PROVIDER_ID, entry.providerId)
                    put(FIELD_REMOTE_ID, entry.remoteId)
                    put(FIELD_REMOTE_PATH, entry.remotePath)
                    put(FIELD_MIME_TYPE, entry.mimeType)
                    put(FIELD_SIZE, entry.size)
                    put(FIELD_WEB_URL, entry.webUrl)
                },
            )
        }
        preferences.edit().putString(KEY_QUICK_ACCESS, array.toString()).apply()
    }

    companion object {
        /** 並びすぎると探しにくくなるので上限を設ける。 */
        const val MAX_ENTRIES = 12

        private const val KEY_QUICK_ACCESS = "quick_access_entries"
        private const val FIELD_TYPE = "type"
        private const val FIELD_TARGET = "target"
        private const val FIELD_LABEL = "label"
        private const val FIELD_KIND = "kind"
        private const val FIELD_TREE_URI = "tree_uri"
        private const val FIELD_RELATIVE_PATH = "relative_path"
        private const val FIELD_PROVIDER_ID = "provider_id"
        private const val FIELD_REMOTE_ID = "remote_id"
        private const val FIELD_REMOTE_PATH = "remote_path"
        private const val FIELD_MIME_TYPE = "mime_type"
        private const val FIELD_SIZE = "size"
        private const val FIELD_WEB_URL = "web_url"
    }
}
