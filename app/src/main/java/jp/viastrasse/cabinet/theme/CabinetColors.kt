package jp.viastrasse.cabinet.theme

/**
 * Cabinet by VIASTRASSE のカラートークン。
 *
 * XMLテーマ（styles.xml）は Kotlin オブジェクトを参照できないため、
 * 同じ値が `res/values/colors.xml` にも存在する。
 * 片方だけ変更すると起動直後の色とアプリ内の色がずれるので、
 * `CabinetColorsSyncTest` が両者の一致を検証している。
 *
 * 値は ARGB の Int リテラルで持つ。`android.graphics.Color` を使わないのは、
 * JVM のユニットテストからそのまま参照できるようにするため。
 */
object CabinetColors {
    /** 起動時ウィンドウ / システムバー。 */
    const val StartupBackground: Int = 0xFF050B14.toInt()

    /** 画面全体の下地。 */
    const val AppBackground: Int = 0xFF101317.toInt()

    /** リスト背景などの一段沈んだ面。 */
    const val SurfaceSunken: Int = 0xFF0B0E12.toInt()

    /** カード・パネルの基本面。 */
    const val Surface: Int = 0xFF151A20.toInt()

    /** カード内のサブ面（入力欄・バッジなど）。 */
    const val SurfaceAlt: Int = 0xFF1C2229.toInt()

    /** 前面に浮かせる面（ポップアップ・選択状態）。 */
    const val SurfaceElevated: Int = 0xFF232A33.toInt()

    /** ブランドカラー。主要アクションの塗り。 */
    const val Brand: Int = 0xFF004273.toInt()

    /** ブランドの明るい側。枠線・強調など。 */
    const val BrandBright: Int = 0xFF005A96.toInt()

    /** アクセント。リンク・強調テキスト・アイコン。 */
    const val Accent: Int = 0xFF86B6D8.toInt()

    /** アクセントの淡い面。チップ・選択中タブの背景。 */
    const val AccentSoft: Int = 0xFF27333D.toInt()

    const val TextPrimary: Int = 0xFFEFF4F8.toInt()
    const val TextSecondary: Int = 0xFFB0BECB.toInt()

    /** 補足情報用のさらに控えめな文字色。 */
    const val TextMuted: Int = 0xFF7C8A99.toInt()

    /** 区切り線。 */
    const val Divider: Int = 0xFF303741.toInt()

    /** カード枠線。Dividerより控えめ。 */
    const val Outline: Int = 0xFF2A313A.toInt()

    /**
     * タイルなど、面の上に置いて輪郭を見せたい要素の枠線。
     *
     * [Outline] は [Surface] とのコントラストが約1.3:1しかなく、面に重ねると
     * ほぼ見えない。VIASTRASSE ファミリー共通のタイル枠にはこちらを使う。
     */
    const val HairlineStrong: Int = 0xFF3A4650.toInt()

    const val Warning: Int = 0xFFDFB150.toInt()

    /** 破壊的操作。 */
    const val Danger: Int = 0xFFE0685F.toInt()

    /** 完了・成功状態。 */
    const val Success: Int = 0xFF6FBF8B.toInt()

    /** タップ時のリップル。 */
    const val Ripple: Int = 0x3886B6D8

    /** 種別バッジの配色。ファイル一覧の走査性を上げる。 */
    const val KindImage: Int = 0xFF8BC3A0.toInt()
    const val KindDocument: Int = 0xFFE0847A.toInt()
    const val KindText: Int = 0xFF96BADC.toInt()
    const val KindMedia: Int = 0xFFBAA0D6.toInt()
    const val KindArchive: Int = 0xFFDFB150.toInt()
    const val KindFolder: Int = 0xFF86B6D8.toInt()
    const val KindOther: Int = 0xFF96A5B4.toInt()
}
