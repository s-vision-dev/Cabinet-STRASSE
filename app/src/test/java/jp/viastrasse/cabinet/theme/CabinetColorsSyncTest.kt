package jp.viastrasse.cabinet.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `CabinetColors`（Kotlin）と `res/values/colors.xml`（XMLテーマ用）の同期を検証する。
 *
 * XMLテーマは Kotlin オブジェクトを参照できないため、同じ色を2箇所で持たざるを得ない。
 * 片方だけ変更されると起動直後のウィンドウ色とアプリ内の描画色がずれるので、
 * ここで機械的に突き合わせて気付けるようにしている。
 */
class CabinetColorsSyncTest {
    /** colors.xml の色名 -> CabinetColors のプロパティ値。 */
    private val expected: Map<String, Int> = mapOf(
        "cabinet_startup_background" to CabinetColors.StartupBackground,
        "cabinet_app_background" to CabinetColors.AppBackground,
        "cabinet_surface" to CabinetColors.Surface,
        "cabinet_surface_alt" to CabinetColors.SurfaceAlt,
        "cabinet_brand" to CabinetColors.Brand,
        "cabinet_accent" to CabinetColors.Accent,
        "cabinet_text_primary" to CabinetColors.TextPrimary,
        "cabinet_text_secondary" to CabinetColors.TextSecondary,
    )

    @Test
    fun `colors_xml matches CabinetColors`() {
        val declared = parseColorsXml()
        expected.forEach { (name, kotlinValue) ->
            val xmlValue = declared[name]
                ?: error("colors.xml に <color name=\"$name\"> がありません。")
            assertEquals(
                "colors.xml の $name が CabinetColors と一致しません。両方を更新してください。",
                String.format("#%08X", kotlinValue),
                String.format("#%08X", xmlValue),
            )
        }
    }

    @Test
    fun `colors_xml has no orphan entries`() {
        val declared = parseColorsXml()
        val orphans = declared.keys - expected.keys
        assertTrue(
            "colors.xml にこのテストが知らない色があります: $orphans。" +
                " CabinetColors に対応する定数を用意し、このテストの expected へ追加してください。",
            orphans.isEmpty(),
        )
    }

    private fun parseColorsXml(): Map<String, Int> {
        val file = resolveColorsXml()
        val pattern = Regex("""<color\s+name="([^"]+)"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")
        return pattern.findAll(file.readText(Charsets.UTF_8))
            .associate { match ->
                match.groupValues[1] to parseArgb(match.groupValues[2])
            }
    }

    /** テストの作業ディレクトリは `app/` になるが、リポジトリ直下から実行される場合にも備える。 */
    private fun resolveColorsXml(): File {
        val candidates = listOf(
            File("src/main/res/values/colors.xml"),
            File("app/src/main/res/values/colors.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("colors.xml が見つかりません。探索先: ${candidates.map { it.absolutePath }}")
    }

    /** `#RRGGBB` は不透明として扱う。 */
    private fun parseArgb(value: String): Int {
        val hex = value.removePrefix("#")
        val normalized = if (hex.length == 6) "FF$hex" else hex
        return normalized.toLong(16).toInt()
    }
}
