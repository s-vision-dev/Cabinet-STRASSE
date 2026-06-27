package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import jp.viastrasse.cabinetstrasse.theme.CabinetColors
import java.io.File

class ViewerActivity : Activity() {
    private var audioPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path)))
            finish()
            return
        }
        if (mimeType.startsWith("text/") || path.endsWith(".md") || path.endsWith(".txt")) {
            renderText(title.ifBlank { File(path).name }, File(path).readText(Charsets.UTF_8))
        } else if (mimeType.startsWith("image/")) {
            renderImage(title.ifBlank { File(path).name }, File(path))
        } else if (mimeType.startsWith("video/")) {
            renderVideo(File(path))
        } else if (mimeType.startsWith("audio/")) {
            renderAudio(title.ifBlank { File(path).name }, File(path))
        } else {
            openExternal(path, mimeType)
            finish()
        }
    }

    override fun onDestroy() {
        audioPlayer?.release()
        audioPlayer = null
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

    private fun renderImage(title: String, file: File) {
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
                setImageURI(Uri.fromFile(file))
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

    private fun renderVideo(file: File) {
        val videoView = VideoView(this).apply {
            setBackgroundColor(CabinetColors.AppBackground)
            setVideoURI(Uri.fromFile(file))
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

    private fun renderAudio(title: String, file: File) {
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
            setDataSource(file.absolutePath)
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

    private fun openExternal(path: String, mimeType: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            this,
            "jp.viastrasse.cabinetstrasse.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
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
