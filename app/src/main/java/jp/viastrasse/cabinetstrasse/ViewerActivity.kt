package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import jp.viastrasse.cabinetstrasse.theme.CabinetColors
import java.io.File

class ViewerActivity : Activity() {
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
        } else {
            openExternal(path, mimeType)
            finish()
        }
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
