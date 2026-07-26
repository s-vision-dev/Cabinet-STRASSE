package jp.viastrasse.cabinet.data

import java.io.File

/**
 * ファイル名・保存先・サイズ表記の共通処理。
 *
 * 以前は MainActivity / ShareReceiverActivity / CabinetEventProvider / CabinetSaveProvider /
 * FolderWatchWorker / CabinetRepository / ThumbnailGenerator に同じ関数が個別に書かれていた。
 * とくに [sanitizeFileName] は CabinetEventProvider だけがパス区切りと制御文字を除去する
 * 強い実装で、他の呼び出し口は弱い実装のままという食い違いがあったため、
 * ここでは常に強いほうへ揃える。
 */
object CabinetFiles {
    /** Android のファイル名上限（255バイト）に対し、日本語込みでも安全側に倒した長さ。 */
    const val MAX_FILE_NAME_LENGTH = 180

    private const val DEFAULT_FALLBACK_NAME = "document"

    /** 制御文字・パス区切り・Windows/Android で使えない記号。 */
    private val UNSAFE_FILE_NAME_CHARS = Regex("[\\u0000-\\u001f\\u007f\\\\/:*?\"<>|]")

    /** ファイル名に使えない文字・パス区切り・制御文字を除去する。 */
    fun sanitizeFileName(value: String, fallback: String = DEFAULT_FALLBACK_NAME): String {
        val normalized = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(UNSAFE_FILE_NAME_CHARS, "_")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        // "." と ".." は自/親ディレクトリ参照になるため、ファイル名としては使わせない。
        if (normalized.isBlank() || normalized.all { it == '.' }) return fallback
        return normalized
    }

    /** [directory] 内で衝突しないファイルを返す。拡張子は保つ。 */
    fun uniqueDestination(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = if (extension.isBlank()) {
                File(directory, "$base-$index")
            } else {
                File(directory, "$base-$index.$extension")
            }
            index += 1
        }
        return candidate
    }

    /** [directory] 内で衝突しないフォルダを返す。 */
    fun uniqueDirectory(directory: File, displayName: String): File {
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$displayName-$index")
            index += 1
        }
        return candidate
    }

    /** 複製時の表示名。拡張子の手前に `-copy` を挟む。 */
    fun duplicateDisplayName(displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        return if (extension.isBlank()) "$base-copy" else "$base-copy.$extension"
    }

    /**
     * 人が読めるサイズ表記。
     * サイズ不明を表す負値は [unknownLabel] を返す（ZIPエントリなどで発生する）。
     */
    fun readableSize(size: Long, unknownLabel: String = "unknown"): String {
        if (size < 0L) return unknownLabel
        if (size < 1024L) return "${size}B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return "%.2f%s".format(value, units[unitIndex])
    }
}
