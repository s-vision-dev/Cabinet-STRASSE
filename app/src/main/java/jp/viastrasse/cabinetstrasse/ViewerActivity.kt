package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import jp.viastrasse.cabinetstrasse.theme.CabinetColors
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt
import java.util.zip.ZipInputStream

class ViewerActivity : Activity() {
    private var audioPlayer: MediaPlayer? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfPageIndex: Int = 0
    private var pdfImageView: ImageView? = null
    private var pdfCounterView: TextView? = null
    private var navigationPaths: List<String> = emptyList()
    private var navigationMimeTypes: List<String> = emptyList()
    private var navigationTitles: List<String> = emptyList()
    private var navigationIndex: Int = -1

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
        navigationPaths = intent.getStringArrayListExtra(EXTRA_NAVIGATION_PATHS).orEmpty()
        navigationMimeTypes = intent.getStringArrayListExtra(EXTRA_NAVIGATION_MIME_TYPES).orEmpty()
        navigationTitles = intent.getStringArrayListExtra(EXTRA_NAVIGATION_TITLES).orEmpty()
        navigationIndex = intent.getIntExtra(EXTRA_NAVIGATION_INDEX, -1)
        if (navigationPaths.isNotEmpty() && navigationIndex in navigationPaths.indices) {
            openPreviewAt(navigationIndex)
            return
        }
        openPreview(path, mimeType, title)
    }

    private fun openPreviewAt(index: Int) {
        if (index !in navigationPaths.indices) return
        navigationIndex = index
        openPreview(
            path = navigationPaths[index],
            mimeType = navigationMimeTypes.getOrElse(index) { "" },
            title = navigationTitles.getOrElse(index) { "" },
        )
    }

    private fun openPreview(path: String, mimeType: String, title: String) {
        val parsedPath = Uri.parse(path)
        if (parsedPath.scheme?.isNotBlank() == true && parsedPath.scheme !in listOf("content", "file")) {
            openExternal(parsedPath, mimeType)
            finish()
            return
        }
        val uri = viewerUri(path)
        val displayTitle = title.ifBlank { File(path).name.ifBlank { path.substringAfterLast('/') } }
        val typeHint = "$path\n$displayTitle"
        runCatching {
            if (isCsvFile(typeHint, mimeType)) {
                renderCsvPreview(displayTitle, readText(uri), typeHint, mimeType)
            } else if (isMarkdownFile(typeHint, mimeType)) {
                renderMarkdown(displayTitle, readText(uri))
            } else if (isTextFile(typeHint, mimeType)) {
                renderText(displayTitle, readText(uri))
            } else if (isImageFile(typeHint, mimeType)) {
                renderImage(displayTitle, uri)
            } else if (isPdfFile(typeHint, mimeType)) {
                renderPdf(displayTitle, uri)
            } else if (isVideoFile(typeHint, mimeType)) {
                renderVideo(displayTitle, uri)
            } else if (isAudioFile(typeHint, mimeType)) {
                renderAudio(displayTitle, uri)
            } else if (isOfficeOpenXml(typeHint, mimeType)) {
                renderOfficePreview(displayTitle, uri, typeHint, mimeType)
            } else if (isArchiveFile(typeHint, mimeType)) {
                renderArchivePreview(displayTitle, uri, mimeType)
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
        val textView = TextView(this).apply {
            text = body
            setTextColor(CabinetColors.TextPrimary)
            textSize = 15f
            setHorizontallyScrolling(true)
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        setContentView(zoomablePreview(title, textView, useNaturalWidth = true))
    }

    private fun renderMarkdown(title: String, body: String) {
        val textView = TextView(this).apply {
            setTextColor(CabinetColors.TextPrimary)
            textSize = 15f
            setHorizontallyScrolling(true)
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .usePlugin(TaskListPlugin.create(this))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
            .setMarkdown(textView, body)
        setContentView(zoomablePreview(title, textView, useNaturalWidth = true))
    }

    private fun renderCsvPreview(title: String, body: String, path: String, mimeType: String) {
        val delimiter = detectDelimitedTextSeparator(body, path, mimeType)
        val rows = parseDelimitedText(body, delimiter)
            .filter { row -> row.any { it.isNotBlank() } }
            .take(80)
        val preview = if (rows.isEmpty()) {
            "表示できる行がありません。"
        } else {
            formatDelimitedRows(rows)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "CSVプレビュー"
                setTextColor(CabinetColors.TextPrimary)
                textSize = 16f
                setPadding(0, 0, 0, dp(8))
            },
        )
        container.addView(
            TextView(this).apply {
                text = "区切り文字: ${delimiter.label}"
                setTextColor(CabinetColors.TextSecondary)
                textSize = 13f
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
        setContentView(zoomablePreview(title, container, useNaturalWidth = true))
    }

    private fun zoomablePreview(title: String, content: View, useNaturalWidth: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            addView(fixedTitle(title))
            addView(
                zoomableScrollView(content, useNaturalWidth),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    private fun fixedTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(CabinetColors.TextPrimary)
            textSize = 16f
            setPadding(dp(18), dp(12), dp(18), dp(10))
            setBackgroundColor(CabinetColors.AppBackground)
            maxLines = 3
        }
    }

    private fun zoomableScrollView(content: View, useNaturalWidth: Boolean): View {
        return ZoomablePreviewScrollView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
            setContent(content, useNaturalWidth)
            onPrevious = ::showPreviousPreview
            onNext = ::showNextPreview
        }
    }

    private class ZoomablePreviewScrollView(context: Context) : HorizontalScrollView(context) {
        private var scaleFactor = 1f
        private var baseContentWidth = 0
        private var baseContentHeight = 0
        private var contentView: View? = null
        private var useNaturalWidth = false
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var startedAtLeftEdge = false
        private var startedAtRightEdge = false
        private val navigationSwipeDistance = context.resources.displayMetrics.density * 80f
        var onPrevious: (() -> Unit)? = null
        var onNext: (() -> Unit)? = null
        private val verticalScroll = ScrollView(context).apply {
            setBackgroundColor(CabinetColors.AppBackground)
            isFillViewport = true
        }
        private val zoomBounds = FrameLayout(context).apply {
            setBackgroundColor(CabinetColors.AppBackground)
        }
        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldScale = scaleFactor
                    val nextScale = (scaleFactor * detector.scaleFactor).coerceIn(0.75f, 3.5f)
                    if (nextScale == oldScale) return true
                    val focusContentX = (scrollX + detector.focusX) / oldScale
                    val focusContentY = (verticalScroll.scrollY + detector.focusY) / oldScale
                    scaleFactor = nextScale
                    applyZoom()
                    post {
                        scrollTo(((focusContentX * scaleFactor) - detector.focusX).roundToInt(), scrollY)
                        verticalScroll.scrollTo(
                            verticalScroll.scrollX,
                            ((focusContentY * scaleFactor) - detector.focusY).roundToInt(),
                        )
                    }
                    return true
                }
            },
        )

        init {
            isFillViewport = true
            addView(
                verticalScroll,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            verticalScroll.addView(
                zoomBounds,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun setContent(content: View, useNaturalWidth: Boolean) {
            contentView = content
            this.useNaturalWidth = useNaturalWidth
            zoomBounds.removeAllViews()
            zoomBounds.addView(
                content,
                FrameLayout.LayoutParams(
                    if (useNaturalWidth) FrameLayout.LayoutParams.WRAP_CONTENT else FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            post {
                captureBaseContentSize()
                applyZoom()
            }
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            post {
                captureBaseContentSize()
                applyZoom()
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    startedAtLeftEdge = !canScrollHorizontally(-1)
                    startedAtRightEdge = !canScrollHorizontally(1)
                }
                MotionEvent.ACTION_UP -> {
                    handleNavigationSwipe(event)
                }
            }
            scaleDetector.onTouchEvent(event)
            return super.dispatchTouchEvent(event) || scaleDetector.isInProgress
        }

        private fun captureBaseContentSize() {
            val content = contentView ?: return
            val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            if (useNaturalWidth) {
                content.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                baseContentWidth = content.measuredWidth.coerceAtLeast(availableWidth)
            } else {
                val measureWidth = baseContentWidth.takeIf { it > 0 } ?: availableWidth
                content.measure(
                    MeasureSpec.makeMeasureSpec(measureWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                baseContentWidth = availableWidth
            }
            baseContentHeight = content.measuredHeight.coerceAtLeast(1)
        }

        private fun applyZoom() {
            val content = contentView ?: return
            if (baseContentWidth <= 0 || baseContentHeight <= 0) return
            val scaledWidth = (baseContentWidth * scaleFactor).roundToInt().coerceAtLeast(1)
            val scaledHeight = (baseContentHeight * scaleFactor).roundToInt().coerceAtLeast(1)
            zoomBounds.layoutParams = zoomBounds.layoutParams.apply {
                width = scaledWidth
                height = scaledHeight
            }
            content.layoutParams = content.layoutParams.apply {
                width = baseContentWidth
                height = baseContentHeight
            }
            content.pivotX = 0f
            content.pivotY = 0f
            content.scaleX = scaleFactor
            content.scaleY = scaleFactor
            zoomBounds.requestLayout()
        }

        private fun handleNavigationSwipe(event: MotionEvent) {
            if (scaleDetector.isInProgress) return
            val deltaX = event.x - touchStartX
            val deltaY = event.y - touchStartY
            if (kotlin.math.abs(deltaX) < navigationSwipeDistance) return
            if (kotlin.math.abs(deltaX) < kotlin.math.abs(deltaY) * 1.2f) return
            if (deltaX < 0 && startedAtRightEdge && !canScrollHorizontally(1)) {
                onNext?.invoke()
            } else if (deltaX > 0 && startedAtLeftEdge && !canScrollHorizontally(-1)) {
                onPrevious?.invoke()
            }
        }
    }

    private fun showPreviousPreview() {
        if (navigationIndex > 0) openPreviewAt(navigationIndex - 1)
    }

    private fun showNextPreview() {
        if (navigationIndex >= 0 && navigationIndex < navigationPaths.lastIndex) {
            openPreviewAt(navigationIndex + 1)
        }
    }

    private fun renderImage(title: String, uri: Uri) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
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
        setContentView(zoomablePreview(title, layout))
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
        setContentView(zoomablePreview(title, layout))
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

    private fun renderVideo(title: String, uri: Uri) {
        lateinit var control: TextView
        val videoView = VideoView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
            setVideoURI(uri)
            setMediaController(MediaController(this@ViewerActivity).also { it.setAnchorView(this) })
            setOnPreparedListener {
                start()
                control.text = "一時停止"
            }
            setOnCompletionListener {
                control.text = "再生"
            }
        }
        control = commandButton("再生") {
            if (videoView.isPlaying) {
                videoView.pause()
                control.text = "再生"
            } else {
                videoView.start()
                control.text = "一時停止"
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CabinetColors.AppBackground)
            addView(fixedTitle(title))
            addView(
                videoView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(control)
        }
        setContentView(layout)
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
        setContentView(zoomablePreview(title, layout))
    }

    private fun renderSpreadsheetPreview(title: String, uri: Uri, mimeType: String) {
        val preview = buildSpreadsheetPreview(uri)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "先頭シートのプレビュー"
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
        setContentView(zoomablePreview(title, container))
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "プレビュー"
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
        setContentView(zoomablePreview(title, container))
    }

    private fun renderArchivePreview(title: String, uri: Uri, mimeType: String) {
        val preview = buildArchivePreview(uri)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        container.addView(
            TextView(this).apply {
                text = "アーカイブ内容のプレビュー"
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
        setContentView(zoomablePreview(title, container, useNaturalWidth = true))
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

    private fun buildArchivePreview(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした。")
        val rows = mutableListOf<String>()
        var totalCount = 0
        var directoryCount = 0
        var fileCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                totalCount += 1
                if (entry.isDirectory) {
                    directoryCount += 1
                } else {
                    fileCount += 1
                }
                if (rows.size < 120) {
                    val marker = if (entry.isDirectory) "DIR " else "FILE"
                    val size = if (entry.isDirectory) "-" else archiveEntrySize(entry.size)
                    rows += "$marker  ${size.padStart(10)}  ${entry.name}"
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (totalCount == 0) return "表示できるエントリがありません。"
        val header = "entries: $totalCount / files: $fileCount / dirs: $directoryCount"
        val body = rows.joinToString("\n")
        val suffix = if (totalCount > rows.size) "\n...ほか ${totalCount - rows.size} 件" else ""
        return "$header\n\n$body$suffix"
    }

    private fun archiveEntrySize(size: Long): String {
        if (size < 0L) return "unknown"
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
        val bytes = contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: error("ファイルを開けませんでした。")
        return decodeText(bytes)
    }

    private fun detectDelimitedTextSeparator(body: String, path: String, mimeType: String): DelimitedTextSeparator {
        if (mimeType == "text/tab-separated-values" || path.hasAnyExtension("tsv")) {
            return DelimitedTextSeparator.TAB
        }
        val sampleLines = body.lineSequence()
            .filter { it.isNotBlank() }
            .take(20)
            .toList()
        if (sampleLines.isEmpty()) return DelimitedTextSeparator.COMMA
        return listOf(
            DelimitedTextSeparator.COMMA,
            DelimitedTextSeparator.TAB,
            DelimitedTextSeparator.SEMICOLON,
        ).maxBy { separator ->
            sampleLines.sumOf { countDelimiterOutsideQuotes(it, separator.value) }
        }
    }

    private fun parseDelimitedText(body: String, separator: DelimitedTextSeparator): List<List<String>> {
        return body.lineSequence()
            .map { parseDelimitedLine(it, separator.value) }
            .toList()
    }

    private fun parseDelimitedLine(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> inQuotes = !inQuotes
                char == separator && !inQuotes -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        cells += current.toString()
        return cells
    }

    private fun formatDelimitedRows(rows: List<List<String>>): String {
        val columnCount = rows.maxOfOrNull { it.size }?.coerceAtMost(12) ?: 0
        if (columnCount == 0) return "表示できる列がありません。"
        val widths = (0 until columnCount).map { column ->
            rows.maxOf { row -> row.getOrNull(column).orEmpty().singleLineCell().length.coerceAtMost(24) }
                .coerceAtLeast(1)
        }
        return rows.joinToString("\n") { row ->
            (0 until columnCount).joinToString(" | ") { column ->
                row.getOrNull(column)
                    .orEmpty()
                    .singleLineCell()
                    .take(24)
                    .padEnd(widths[column])
            }
        }
    }

    private fun countDelimiterOutsideQuotes(line: String, separator: Char): Int {
        var count = 0
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> index += 1
                char == '"' -> inQuotes = !inQuotes
                char == separator && !inQuotes -> count += 1
            }
            index += 1
        }
        return count
    }

    private fun String.singleLineCell(): String {
        return replace('\r', ' ').replace('\n', ' ').trim()
    }

    private fun decodeText(bytes: ByteArray): String {
        detectBomCharset(bytes)?.let { (charset, offset) ->
            return bytes.copyOfRange(offset, bytes.size).toString(charset)
        }
        if (bytes.hasIso2022JpEscape()) {
            decodeStrict(bytes, Charset.forName("ISO-2022-JP"))?.let { return it }
        }
        decodeStrict(bytes, StandardCharsets.UTF_8)?.let { return it }
        val candidates = listOf(
            "MS932",
            "Shift_JIS",
            "EUC-JP",
            "UTF-16LE",
            "UTF-16BE",
            "ISO-8859-1",
        ).mapNotNull { name ->
            runCatching {
                val charset = Charset.forName(name)
                decodeStrict(bytes, charset)?.let { DecodedText(it, scoreDecodedText(it), charset) }
            }.getOrNull()
        }
        return candidates.maxWithOrNull(compareBy<DecodedText> { it.score }.thenByDescending { it.charset.name() == "MS932" })
            ?.text
            ?: bytes.toString(StandardCharsets.UTF_8)
    }

    private fun detectBomCharset(bytes: ByteArray): Pair<Charset, Int>? {
        return when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8 to 3
            bytes.startsWith(0xFE, 0xFF) -> StandardCharsets.UTF_16BE to 2
            bytes.startsWith(0xFF, 0xFE) -> StandardCharsets.UTF_16LE to 2
            else -> null
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun scoreDecodedText(text: String): Int {
        if (text.isEmpty()) return 0
        val replacementPenalty = text.count { it == '\uFFFD' } * 100
        val controlPenalty = text.count { it.code < 0x20 && it !in listOf('\n', '\r', '\t') } * 12
        val japaneseScore = text.count {
            it in '\u3040'..'\u30FF' || it in '\u3400'..'\u9FFF' || it in '\uFF00'..'\uFFEF'
        } * 3
        val printableScore = text.count { !it.isISOControl() || it in listOf('\n', '\r', '\t') }
        return printableScore + japaneseScore - replacementPenalty - controlPenalty
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }
    }

    private fun ByteArray.hasIso2022JpEscape(): Boolean {
        return indices.any { index ->
            this[index] == 0x1B.toByte() &&
                getOrNull(index + 1) in listOf(0x24.toByte(), 0x28.toByte())
        }
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
            mimeType == "application/vnd.ms-excel.sheet.macroEnabled.12" ||
            path.hasAnyExtension("xlsx", "xlsm")
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

    private fun isArchiveFile(path: String, mimeType: String): Boolean {
        return mimeType in setOf(
            "application/zip",
            "application/vnd.android.package-archive",
            "application/x-zip-compressed",
        ) || path.hasAnyExtension("zip", "apk")
    }

    private fun isCsvFile(path: String, mimeType: String): Boolean {
        return mimeType in setOf("text/csv", "text/tab-separated-values") ||
            path.hasAnyExtension("csv", "tsv")
    }

    private fun isTextFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType in setOf(
                "application/json",
                "application/xml",
                "application/xhtml+xml",
                "application/javascript",
                "application/x-javascript",
                "application/x-sh",
                "application/x-yaml",
                "application/yaml",
            ) ||
            path.hasAnyExtension(
                "txt",
                "text",
                "csv",
                "tsv",
                "json",
                "jsonl",
                "xml",
                "html",
                "htm",
                "xhtml",
                "css",
                "js",
                "mjs",
                "cjs",
                "ts",
                "tsx",
                "jsx",
                "yml",
                "yaml",
                "toml",
                "ini",
                "conf",
                "cfg",
                "properties",
                "env",
                "log",
                "sql",
                "java",
                "kt",
                "kts",
                "gradle",
                "py",
                "rb",
                "go",
                "rs",
                "c",
                "cc",
                "cpp",
                "h",
                "hpp",
                "sh",
                "bash",
                "zsh",
                "bat",
                "cmd",
                "ps1",
                "rtf",
            )
    }

    private fun isMarkdownFile(path: String, mimeType: String): Boolean {
        return mimeType == "text/markdown" || path.hasAnyExtension("md", "markdown")
    }

    private fun isImageFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("image/") ||
            path.hasAnyExtension("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    }

    private fun isPdfFile(path: String, mimeType: String): Boolean {
        return mimeType == "application/pdf" || path.hasAnyExtension("pdf")
    }

    private fun isVideoFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("video/") ||
            path.hasAnyExtension("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3gpp", "avi")
    }

    private fun isAudioFile(path: String, mimeType: String): Boolean {
        return mimeType.startsWith("audio/") ||
            path.hasAnyExtension("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mid", "midi")
    }

    private fun String.hasAnyExtension(vararg extensions: String): Boolean {
        return lineSequence()
            .flatMap { it.splitToSequence('/', '\\') }
            .map { it.substringBefore('?').substringBefore('#') }
            .any { name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NAVIGATION_PATHS = "navigation_paths"
        const val EXTRA_NAVIGATION_MIME_TYPES = "navigation_mime_types"
        const val EXTRA_NAVIGATION_TITLES = "navigation_titles"
        const val EXTRA_NAVIGATION_INDEX = "navigation_index"
    }

    private data class DecodedText(
        val text: String,
        val score: Int,
        val charset: Charset,
    )

    private enum class DelimitedTextSeparator(val value: Char, val label: String) {
        COMMA(',', "カンマ"),
        TAB('\t', "タブ"),
        SEMICOLON(';', "セミコロン"),
    }
}
