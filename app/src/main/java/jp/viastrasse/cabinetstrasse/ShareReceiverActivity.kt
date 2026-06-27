package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import jp.viastrasse.cabinetstrasse.data.CabinetRepository

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        finish()
    }

    private fun handleShare(intent: Intent) {
        val repository = CabinetRepository(applicationContext)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.startsWith("http://") || sharedText.startsWith("https://")) {
            runCatching {
                repository.registerUrl(
                    url = sharedText,
                    title = intent.getStringExtra(Intent.EXTRA_TITLE)?.ifBlank { null } ?: sharedText,
                    note = "Android共有から保存",
                )
            }.onSuccess {
                Toast.makeText(this, "Cabinet Inbox に保存しました", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Cabinet への保存に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "共有ファイルの保存処理を受け付けました", Toast.LENGTH_SHORT).show()
        }
    }
}

