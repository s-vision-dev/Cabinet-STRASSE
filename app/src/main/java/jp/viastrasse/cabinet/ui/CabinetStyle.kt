package jp.viastrasse.cabinet.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import jp.viastrasse.cabinet.theme.CabinetColors

/**
 * Cabinet UI 共通の寸法トークン。値は dp。
 *
 * 画面ごとに角丸や余白を書き分けると見た目が揃わないため、
 * 新しい部品はここの値から選ぶこと。
 */
object CabinetMetrics {
    /** 画面左右の余白。 */
    const val SCREEN_PADDING = 16

    /** カード / パネル。 */
    const val RADIUS_CARD = 14

    /** ボタン・入力欄。 */
    const val RADIUS_CONTROL = 12

    /** 小さめのタイル・バッジ。 */
    const val RADIUS_TILE = 10

    /** 完全な丸み（チップ）。 */
    const val RADIUS_PILL = 999

    const val STROKE = 1

    /** タップ可能要素の最小高さ。 */
    const val MIN_TOUCH_HEIGHT = 48

    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 24
}

/** Cabinet UI 共通のタイプスケール（sp）。 */
object CabinetType {
    const val DISPLAY = 26
    const val TITLE = 20
    const val SECTION = 15
    const val BODY = 14
    const val CAPTION = 12
    const val MICRO = 11
}

internal fun Context.dpSize(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

internal fun Context.dpSizeF(value: Int): Float =
    value * resources.displayMetrics.density

/** 角丸の面。[strokeColor] に null を渡すと枠線なし。 */
internal fun Context.surfaceShape(
    fillColor: Int,
    radius: Int = CabinetMetrics.RADIUS_CARD,
    strokeColor: Int? = CabinetColors.Outline,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dpSizeF(radius)
    setColor(fillColor)
    if (strokeColor != null) {
        setStroke(dpSize(CabinetMetrics.STROKE), strokeColor)
    }
}

/**
 * 角丸に沿ってクリップされるリップル付きの面。
 * マスクを渡さないとリップルが矩形に広がってしまうため、同じ形状をマスクにする。
 */
internal fun Context.pressableShape(
    fillColor: Int,
    radius: Int = CabinetMetrics.RADIUS_CARD,
    strokeColor: Int? = CabinetColors.Outline,
    rippleColor: Int = CabinetColors.Ripple,
): Drawable = RippleDrawable(
    ColorStateList.valueOf(rippleColor),
    surfaceShape(fillColor, radius, strokeColor),
    surfaceShape(Color.WHITE, radius, strokeColor = null),
)

/** 背景を持たない要素（テキストリンクなど）向けのリップル。 */
internal fun Context.borderlessRipple(
    radius: Int = CabinetMetrics.RADIUS_CONTROL,
    rippleColor: Int = CabinetColors.Ripple,
): Drawable = RippleDrawable(
    ColorStateList.valueOf(rippleColor),
    null,
    surfaceShape(Color.WHITE, radius, strokeColor = null),
)

/** タップ可能にし、フォーカス枠とリップルを付ける。 */
internal fun <T : View> T.asTappable(background: Drawable): T = apply {
    this.background = background
    isClickable = true
    isFocusable = true
}

/** Cabinet の面に合わせた入力欄。ダイアログ・検索欄で共有する。 */
internal fun Context.cabinetInput(hintText: String): EditText = EditText(this).apply {
    hint = hintText
    setTextColor(CabinetColors.TextPrimary)
    setHintTextColor(CabinetColors.TextMuted)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, CabinetType.BODY.toFloat())
    background = surfaceShape(CabinetColors.SurfaceAlt, CabinetMetrics.RADIUS_CONTROL)
    setPadding(
        dpSize(CabinetMetrics.SPACE_MD),
        dpSize(CabinetMetrics.SPACE_MD),
        dpSize(CabinetMetrics.SPACE_MD),
        dpSize(CabinetMetrics.SPACE_MD),
    )
    minHeight = dpSize(CabinetMetrics.MIN_TOUCH_HEIGHT)
}

/** ファイル種別ラベルに対応する色。 */
internal fun kindAccent(kind: String): Int = when (kind) {
    "DIR" -> CabinetColors.KindFolder
    "IMG" -> CabinetColors.KindImage
    "PDF", "DOC", "XLS", "XLSM", "PPT" -> CabinetColors.KindDocument
    "TXT", "MD", "CSV" -> CabinetColors.KindText
    "VID", "AUD" -> CabinetColors.KindMedia
    "ZIP", "APK" -> CabinetColors.KindArchive
    else -> CabinetColors.KindOther
}
