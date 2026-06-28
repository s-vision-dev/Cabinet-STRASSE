package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import jp.viastrasse.cabinetstrasse.theme.CabinetColors
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

class ViewerActivity : Activity() {
    private var audioPlayer: MediaPlayer? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfPageIndex: Int = 0
    private var pdfImageView: ImageView? = null
    private var pdfCounterView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            openFromIntent()
        }.onFailure { error ->
            renderText("Viewer", error.message ?: "ファイルを表示できませんでした。")
        }
    }

    private fun openFromIntent() {
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val parsedPath = Uri.parse(path)
        if (parsedPath.scheme?.isNotBlank() == true && parsedPath.scheme !in listOf("content", "file")) {
            openExternal(parsedPath, mimeType)
            finish()
            return
        }
        val uri = viewerUri(path)
        val displayTitle = title.ifBlank { File(path).name.ifBlank { path.substringAfterLast('/') } }
        runCatching {
            if (isTextFile(path, mimeType)) {
                renderText(displayTitle, readText(uri))
            } else if (isImageFile(path, mimeType)) {
                renderImage(displayTitle, uri)
            } else if (isPdfFile(path, mimeType)) {
                renderPdf(displayTitle, uri)
            } else if (isVideoFile(path, mimeType)) {
                renderVideo(uri)
            } else if (isAudioFile(path, mimeType)) {
                renderAudio(displayTitle, uri)
            } else if (isOfficeOpenXml(path, mimeType)) {
                renderOfficePreview(displayTitle, uri, path, mimeType)
            } else {
                renderUnsupported(displayTitle, uri, mimeType)
            }
        }.onFailure { error ->
            renderText(displayTitle, error.message ?: "ファイルを表示できませんでした。")
        }
    }

    override fun onDestroy() {
        audioPlayer?.release()
        audioPlayer = null
        pdfRenderer?.close()
        pdfRenderer = null
        pdfDescriptor?.close()
        pdfDescriptor = null
        super.onDestroy()
    }

    private fun renderText(title: String, body: String) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
        }
        val textView = TextView(this).apply {
            text = "$title\n\n$body"
            setTextColor(CabinetColors.TextPrimary)
            textSize = 15f
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scrollView.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scrollView)
    }

    private fun renderImage(title: String, uri: Uri) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        layout.addView(
            TextView(this).apply {
                text = title
                setTextColor(CabinetColors.TextPrimary)
                textSize = 16f
                setPadding(0, 0, 0, dp(12))
            },
        )
        layout.addView(
            ImageView(this).apply {
                setImageURI(uri)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(CabinetColors.AppBackground)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(layout)
    }

    private fun renderPdf(title: String, uri: Uri) {
        pdfDescriptor = openReadDescriptor(uri)
        pdfRenderer = PdfRenderer(pdfDescriptor ?: return)
        val renderer = pdfRenderer ?: return
        if (renderer.pageCount == 0) {
            renderText(title, "PDFページを表示できませんでした。")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        layout.addView(
            TextView(this).apply {
                text = title
                setTextColor(CabinetColors.TextPrimary)
                textSize = 16f
                setPadding(0, 0, 0, dp(10))
            },
        )
        pdfCounterView = TextView(this).apply {
            setTextColor(CabinetColors.TextSecondary)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(pdfCounterView)
        pdfImageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(CabinetColors.SurfaceAlt)
        }
        layout.addView(
            pdfImageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        controls.addView(commandButton("前へ") {
            showPdfPage((pdfPageIndex - 1).coerceAtLeast(0))
        })
        controls.addView(commandButton("次へ") {
            showPdfPage((pdfPageIndex + 1).coerceAtMost(renderer.pageCount - 1))
        })
        layout.addView(controls)
        setContentView(layout)
        showPdfPage(0)
    }

    private fun showPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        renderer.openPage(index).use { page ->
            val targetWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
            val targetHeight = (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(dp(240))
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pdfImageView?.setImageBitmap(bitmap)
        }
        pdfPageIndex = index
        pdfCounterView?.text = "${index + 1} / ${renderer.pageCount}"
    }

    private fun commandButton(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(CabinetColors.Accent)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(CabinetColors.SurfaceAlt)
            setOnClickListener { action() }
        }
    }

    private fun renderVideo(uri: Uri) {
        val videoView = VideoView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
            setVideoURI(uri)
            setMediaController(MediaController(this@ViewerActivity).also { it.setAnchorView(this) })
            setOnPreparedListener { start() }
        }
        setContentView(
            videoView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun renderAudio(title: String, uri: Uri) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(CabinetColors.AppBackground)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(CabinetColors.TextPrimary)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        val control = TextView(this).apply {
            text = "再生"
            setTextColor(CabinetColors.Accent)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundColor(CabinetColors.SurfaceAlt)
        }
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@ViewerActivity, uri)
            prepare()
            setOnCompletionListener {
                control.text = "再生"
            }
        }
        control.setOnClickListener {
            val player = audioPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
                control.text = "再生"
            } else {
                player.start()
                control.text = "一時停止"
            }
        }
        layout.addView(titleView)
        layout.addView(control)
        setContentView(layout)
    }

    private fun renderUnsupported(title: String, uri: Uri, mimeType: String) {
        val typeText = mimeType.ifBlank { "unknown" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        layout.addView(
            TextView(this).apply {
                text = "$title\n\nこの形式はCabinet内ビューアでは表示できません。\nMIME: $typeText"
                setTextColor(CabinetColors.TextPrimary)
                textSize = 15f
            },
        )
        layout.addView(
            commandButton("外部アプリで開く") {
                openExternal(uri, mimeType)
            }.apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
            },
        )
        setContentView(layout)
    }

    private fun renderSpreadsheetPreview(title: String, uri: Uri, mimeType: String) {
        val preview = buildSpreadsheetPreview(uri)
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "$title\n\n先頭シートのプレビュー"
                setTextColor(CabinetColors.TextPrimary)
                textSize = 16f
                setPadding(0, 0, 0, dp(12))
            },
        )
        container.addView(
            TextView(this).apply {
                text = preview
                setTextColor(CabinetColors.TextPrimary)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.15f)
            },
        )
        container.addView(
            commandButton("外部アプリで開く") {
                openExternal(uri, mimeType)
            }.apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
            },
        )
        scrollView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scrollView)
    }

    private fun renderOfficePreview(title: String, uri: Uri, path: String, mimeType: String) {
        if (isSpreadsheet(path, mimeType)) {
            renderSpreadsheetPreview(title, uri, mimeType)
            return
        }
        val preview = when {
            isWordDocument(path, mimeType) -> buildWordPreview(uri)
            isPresentation(path, mimeType) -> buildPresentationPreview(uri)
            else -> "このOffice形式はプレビューに対応していません。"
        }
        renderOfficeTextPreview(title, preview, uri, mimeType)
    }

    private fun renderOfficeTextPreview(title: String, preview: String, uri: Uri, mimeType: String) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "$title\n\nプレビュー"
                setTextColor(CabinetColors.TextPrimary)
                textSize = 16f
                setPadding(0, 0, 0, dp(12))
            },
        )
        container.addView(
            TextView(this).apply {
                text = preview
                setTextColor(CabinetColors.TextPrimary)
                textSize = 14f
                setLineSpacing(0f, 1.2f)
            },
        )
        container.addView(
            commandButton("外部アプリで開く") {
                openExternal(uri, mimeType)
            }.apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
            },
        )
        scrollView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scrollView)
    }

    private fun buildSpreadsheetPreview(uri: Uri): String {
        val entries = readSpreadsheetEntries(uri)
        val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"].orEmpty())
        val sheetXml = entries["xl/worksheets/sheet1.xml"].orEmpty()
        if (sheetXml.isBlank()) return "プレビュー対象のシートを見つけられませんでした。"
        val rows = parseSheetRows(sheetXml, sharedStrings)
        if (rows.isEmpty()) return "表示できるセルがありません。"
        return rows.take(40).joinToString("\n") { row ->
            row.take(8).joinToString(" | ") { it.ifBlank { "-" }.take(40) }
        }
    }

    private fun readSpreadsheetEntries(uri: Uri): Map<String, String> {
        val targets = setOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")
        val result = mutableMapOf<String, String>()
        val input = contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした。")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in targets) {
                    result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                if (result.keys.containsAll(targets)) break
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun buildWordPreview(uri: Uri): String {
        val xml = readZipTextEntries(uri) { it == "word/document.xml" }["word/document.xml"].orEmpty()
        if (xml.isBlank()) return "本文を見つけられませんでした。"
        val paragraphs = parseWordParagraphs(xml)
        if (paragraphs.isEmpty()) return "表示できる本文がありません。"
        return paragraphs.take(80).joinToString("\n")
    }

    private fun buildPresentationPreview(uri: Uri): String {
        val entries = readZipTextEntries(uri) {
            it.startsWith("ppt/slides/slide") && it.endsWith(".xml")
        }
        if (entries.isEmpty()) return "スライドを見つけられませんでした。"
        return entries.toSortedMap(compareBy(::slideNumberFromPath).thenBy { it })
            .entries
            .take(20)
            .joinToString("\n\n") { (path, xml) ->
                val slideNumber = slideNumberFromPath(path)
                val texts = parseTextRuns(xml).filter { it.isNotBlank() }
                val body = if (texts.isEmpty()) "表示できるテキストがありません。" else texts.joinToString("\n")
                "Slide $slideNumber\n$body"
            }
    }

    private fun readZipTextEntries(uri: Uri, include: (String) -> Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val input = contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした。")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && include(entry.name)) {
                    result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseWordParagraphs(xml: String): List<String> {
        val parser = xmlParser(xml)
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> current.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p") {
                        val text = current.toString().trim()
                        if (text.isNotBlank()) paragraphs += text
                        current.clear()
                    }
                }
            }
        }
        return paragraphs
    }

    private fun parseTextRuns(xml: String): List<String> {
        val parser = xmlParser(xml)
        val texts = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.TEXT) {
                parser.text.trim().takeIf { it.isNotBlank() }?.let { texts += it }
            }
        }
        return texts
    }

    private fun slideNumberFromPath(path: String): Int {
        return path.substringAfterLast("slide")
            .substringBefore(".xml")
            .toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseSharedStrings(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val parser = xmlParser(xml)
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inStringItem = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inStringItem = true
                        current.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inStringItem) current.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        values += current.toString()
                        inStringItem = false
                    }
                }
            }
        }
        return values
    }

    private fun parseSheetRows(xml: String, sharedStrings: List<String>): List<List<String>> {
        val parser = xmlParser(xml)
        val rows = mutableListOf<List<String>>()
        val currentRow = sortedMapOf<Int, String>()
        var cellType = ""
        var cellRef = ""
        var cellText = ""
        var inCell = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow.clear()
                        "c" -> {
                            inCell = true
                            cellType = parser.getAttributeValue(null, "t").orEmpty()
                            cellRef = parser.getAttributeValue(null, "r").orEmpty()
                            cellText = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inCell) cellText += parser.text
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            val column = columnIndex(cellRef)
                            if (column >= 0) {
                                currentRow[column] = cellValue(cellText.trim(), cellType, sharedStrings)
                            }
                            inCell = false
                        }
                        "row" -> {
                            if (currentRow.isNotEmpty()) {
                                val maxColumn = currentRow.keys.maxOrNull() ?: 0
                                rows += (0..maxColumn).map { currentRow[it].orEmpty() }
                            }
                            if (rows.size >= 40) return rows
                        }
                    }
                }
            }
        }
        return rows
    }

    private fun cellValue(raw: String, type: String, sharedStrings: List<String>): String {
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            else -> raw
        }
    }

    private fun columnIndex(cellRef: String): Int {
        var result = 0
        val letters = cellRef.takeWhile { it.isLetter() }.uppercase()
        if (letters.isBlank()) return -1
        letters.forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }

    private fun xmlParser(xml: String): XmlPullParser {
        return XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }
    }

    private fun readText(uri: Uri): String {
        return contentResolver.openInputStream(uri)
            ?.bufferedReader(Charset.forName("UTF-8"))
            ?.use { it.readText() }
            ?: error("ファイルを開けませんでした。")
    }

    private fun openReadDescriptor(uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "content") {
            return contentResolver.openFileDescriptor(uri, "r")
                ?: error("ファイルを開けませんでした。")
        }
        return ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun openExternal(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { error ->
                renderText("Viewer", error.message ?: "外部アプリで開けませんでした。")
            }
    }

    private fun viewerUri(path: String): Uri {
        val parsed = Uri.parse(path)
        if (parsed.scheme == "content") return parsed
        if (parsed.scheme == "file") return parsed
        val file = File(path)
        return Uri.fromFile(file)
    }

    private fun isSpreadsheet(path: String, mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            path.endsWith(".xlsx", ignoreCase = true)
    }

    private fun isWordDocument(path: String, mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            path.endsWith(".docx", ignoreCase = true)
    }

    private fun isPresentation(path: String, mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            path.endsWith(".pptx", ignoreCase = true)
    }

    private fun isOfficeOpenXml(path: String, mimeType: String): Boolean {
        return isSpreadsheet(path, mimeType) || isWordDocument(path, mimeType) || isPresentation(path, mimeType)
    }

    private fun isTextFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            path.hasAnyExtension("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log")
    }

    private fun isImageFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("image/") ||
            path.hasAnyExtension("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun isPdfFile(path: String, mimeType: String): Boolean {
        return mimeType == "application/pdf" || path.hasAnyExtension("pdf")
    }

    private fun isVideoFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("video/") ||
            path.hasAnyExtension("mp4", "m4v", "mov", "webm", "mkv", "3gp")
    }

    private fun isAudioFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("audio/") ||
            path.hasAnyExtension("mp3", "m4a", "aac", "wav", "ogg", "flac")
    }

    private fun String.hasAnyExtension(vararg extensions: String): Boolean {
        val name = substringBefore('?').substringBefore('#')
        return extensions.any { name.endsWith(".$it", ignoreCase = true) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_TITLE = "title"
    }
}
