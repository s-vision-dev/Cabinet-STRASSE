package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import java.io.File
import java.nio.charset.Charset

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
            if (mimeType.startsWith("text/") || path.endsWith(".md") || path.endsWith(".txt")) {
                renderText(displayTitle, readText(uri))
            } else if (mimeType.startsWith("image/")) {
                renderImage(displayTitle, uri)
            } else if (mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)) {
                renderPdf(displayTitle, uri)
            } else if (mimeType.startsWith("video/")) {
                renderVideo(uri)
            } else if (mimeType.startsWith("audio/")) {
                renderAudio(displayTitle, uri)
            } else {
                renderUnsupported(displayTitle, mimeType)
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

    private fun renderUnsupported(title: String, mimeType: String) {
        val typeText = mimeType.ifBlank { "unknown" }
        renderText(title, "この形式はCabinet内ビューアでは表示できません。\nMIME: $typeText")
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
    }

    private fun viewerUri(path: String): Uri {
        val parsed = Uri.parse(path)
        if (parsed.scheme == "content") return parsed
        if (parsed.scheme == "file") return parsed
        val file = File(path)
        return Uri.fromFile(file)
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
