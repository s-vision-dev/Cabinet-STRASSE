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
    /** [QuickAccessType.FOLDER]/[QuickAccessType.FILE] は絶対パス、[QuickAccessType.ITEM] は資料 ID。 */
    val target: String,
    val label: String,
    /** 種別バッジに出す文字。DIR / PDF など。 */
    val kind: String,
) {
    /** 同じ対象を指すかどうか。重複登録の判定に使う。 */
    fun isSameTarget(other: QuickAccessEntry): Boolean =
        type == other.type && target == other.target
}

enum class QuickAccessType {
    FOLDER,
    FILE,
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
    }
}
