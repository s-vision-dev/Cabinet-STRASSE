package jp.viastrasse.cabinetstrasse.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import jp.viastrasse.cabinetstrasse.data.CabinetCollectionSummary
import jp.viastrasse.cabinetstrasse.data.CabinetDashboard
import jp.viastrasse.cabinetstrasse.data.CabinetItemSummary
import jp.viastrasse.cabinetstrasse.data.ModeResponse
import jp.viastrasse.cabinetstrasse.data.SearchResponse
import jp.viastrasse.cabinetstrasse.data.SmartFolderSummary
import jp.viastrasse.cabinetstrasse.theme.CabinetColors

class CabinetDashboardView(context: Context) : ScrollView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(28))
    }

    init {
        setBackgroundColor(CabinetColors.AppBackground)
        addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun render(dashboard: CabinetDashboard, onModeSelected: (CabinetMode) -> Unit) {
        content.removeAllViews()
        content.addView(title("Cabinet-STRASSE"))
        content.addView(subtitle("The Cabinet / Powered by VIASTRASSE"))
        content.addView(space(14))
        content.addView(
            modeGrid(
                listOf(
                    CabinetMode("Explorer", dashboard.explorerCount, "通常ファイルと取り込み資料"),
                    CabinetMode("Library", dashboard.libraryCount, "種別から資料を参照"),
                    CabinetMode("Collection", dashboard.collectionCount, "テーマ単位の資料群"),
                    CabinetMode("Inbox", dashboard.inboxCount, "未整理の一時保管"),
                    CabinetMode("Search", dashboard.favoriteCount, "検索とお気に入り"),
                    CabinetMode("Settings", dashboard.version, "Provider / Backup / Security"),
                ),
                onModeSelected,
            ),
        )
        content.addView(section("最近の資料"))
        dashboard.recentItems.forEach { content.addView(itemRow(it)) }
        content.addView(section("Collection"))
        dashboard.collections.forEach { content.addView(collectionRow(it)) }
        content.addView(section("Smart Folder"))
        dashboard.smartFolders.forEach { content.addView(smartFolderRow(it)) }
    }

    fun renderError(message: String) {
        content.removeAllViews()
        content.addView(title("Cabinet-STRASSE"))
        content.addView(errorText(message))
    }

    fun renderMode(mode: ModeResponse, onBack: () -> Unit) {
        content.removeAllViews()
        content.addView(command("← Cabinet", onBack))
        content.addView(title(mode.title))
        content.addView(subtitle(mode.mode))
        if (mode.items.isNotEmpty()) {
            content.addView(section("資料"))
            mode.items.forEach { content.addView(itemRow(it)) }
        }
        if (mode.collections.isNotEmpty()) {
            content.addView(section("Collection"))
            mode.collections.forEach { content.addView(collectionRow(it)) }
        }
        if (mode.smartFolders.isNotEmpty()) {
            content.addView(section("Smart Folder"))
            mode.smartFolders.forEach { content.addView(smartFolderRow(it)) }
        }
    }

    fun renderSearch(response: SearchResponse, onBack: () -> Unit, onSearch: (String) -> Unit) {
        content.removeAllViews()
        content.addView(command("← Cabinet", onBack))
        content.addView(title("Search"))
        val input = android.widget.EditText(context).apply {
            setText(response.query)
            hint = "Cabinet内を検索"
            setTextColor(CabinetColors.TextPrimary)
            setHintTextColor(CabinetColors.TextSecondary)
            setSingleLine(true)
            setBackgroundColor(CabinetColors.SurfaceAlt)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(input)
        content.addView(command("検索", onClick = { onSearch(input.text.toString()) }))
        content.addView(section("検索結果"))
        response.results.forEach { content.addView(itemRow(it)) }
    }

    private fun modeGrid(modes: List<CabinetMode>, onModeSelected: (CabinetMode) -> Unit): LinearLayout {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        modes.chunked(2).forEach { rowModes ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            rowModes.forEach { mode ->
                row.addView(modeTile(mode, onModeSelected))
            }
            if (rowModes.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    private fun modeTile(mode: CabinetMode, onModeSelected: (CabinetMode) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CabinetColors.Brand)
            isClickable = true
            setOnClickListener { onModeSelected(mode) }
            addView(label(mode.name, 18, true))
            addView(label(mode.count.toString(), 24, true))
            addView(label(mode.description, 12, false, CabinetColors.TextSecondary))
            layoutParams = LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }

    private fun itemRow(item: CabinetItemSummary): View {
        return panel {
            addView(label(item.title, 16, true))
            addView(label("${item.displayName} / ${item.mimeType} / ${item.sourceKind}", 12, false, CabinetColors.TextSecondary))
            addView(label(item.summaryText, 13, false, CabinetColors.TextSecondary))
        }
    }

    private fun collectionRow(collection: CabinetCollectionSummary): View {
        return panel {
            addView(label(collection.title, 15, true))
            addView(label("${collection.itemCount} items", 12, false, CabinetColors.TextSecondary))
        }
    }

    private fun smartFolderRow(folder: SmartFolderSummary): View {
        return panel {
            addView(label(folder.title, 15, true))
            addView(label("${folder.itemCount} items / ${folder.condition}", 12, false, CabinetColors.TextSecondary))
        }
    }

    private fun panel(block: LinearLayout.() -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CabinetColors.Surface)
            block()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp(4), 0, dp(6))
            }
        }
    }

    private fun title(text: String) = label(text, 28, true).apply {
        gravity = Gravity.START
    }

    private fun subtitle(text: String) = label(text, 13, false, CabinetColors.Accent)

    private fun section(text: String) = label(text, 18, true).apply {
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun errorText(text: String) = label(text, 14, false, CabinetColors.Warning).apply {
        setPadding(0, dp(16), 0, 0)
    }

    private fun command(text: String, onClick: () -> Unit): TextView {
        return label(text, 15, true, CabinetColors.Accent).apply {
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun label(
        text: String,
        sp: Int,
        bold: Boolean,
        color: Int = CabinetColors.TextPrimary,
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun space(height: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

data class CabinetMode(
    val name: String,
    val count: Long,
    val description: String,
)
