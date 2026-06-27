package jp.viastrasse.cabinetstrasse

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import jp.viastrasse.cabinetstrasse.data.CabinetRepository
import jp.viastrasse.cabinetstrasse.ui.CabinetDashboardView

class MainActivity : Activity() {
    private lateinit var repository: CabinetRepository
    private lateinit var dashboardView: CabinetDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CabinetRepository(applicationContext)
        dashboardView = CabinetDashboardView(this)
        setContentView(dashboardView)
        renderDashboard()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderDashboard()
    }

    private fun renderDashboard() {
        runCatching {
            repository.dashboard()
        }.onSuccess { dashboard ->
            dashboardView.render(dashboard, { mode -> openMode(mode.name) }, ::openDetail)
        }.onFailure { error ->
            dashboardView.renderError(error.message ?: "Cabinet core の初期化に失敗しました。")
        }
    }

    private fun openMode(mode: String) {
        if (mode == "Search") {
            openSearch("")
            return
        }
        if (mode == "Settings") {
            openSettings()
            return
        }
        runCatching {
            repository.mode(mode)
        }.onSuccess {
            dashboardView.renderMode(it, ::renderDashboard, ::openDetail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "画面を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSearch(query: String) {
        runCatching {
            repository.search(query)
        }.onSuccess {
            dashboardView.renderSearch(it, ::renderDashboard, ::openSearch, ::openDetail)
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "検索できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDetail(itemId: String) {
        runCatching {
            repository.detail(itemId)
        }.onSuccess { detail ->
            dashboardView.renderDetail(
                detail = detail,
                onBack = ::renderDashboard,
                onProcessPreview = {
                    processPreviewQueue()
                    openDetail(itemId)
                },
                onToggleFavorite = {
                    updateItemFlags(
                        itemId = itemId,
                        isFavorite = !detail.item.isFavorite,
                        isUnsorted = detail.item.isUnsorted,
                    )
                },
                onMarkSorted = {
                    updateItemFlags(
                        itemId = itemId,
                        isFavorite = detail.item.isFavorite,
                        isUnsorted = false,
                    )
                },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "詳細を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateItemFlags(itemId: String, isFavorite: Boolean, isUnsorted: Boolean) {
        runCatching {
            repository.updateItemFlags(itemId, isFavorite, isUnsorted)
        }.onSuccess {
            dashboardView.renderDetail(
                detail = it,
                onBack = ::renderDashboard,
                onProcessPreview = {
                    processPreviewQueue()
                    openDetail(itemId)
                },
                onToggleFavorite = {
                    updateItemFlags(itemId, !it.item.isFavorite, it.item.isUnsorted)
                },
                onMarkSorted = {
                    updateItemFlags(itemId, it.item.isFavorite, false)
                },
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "資料を更新できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processPreviewQueue() {
        runCatching {
            repository.processPreviewQueue()
        }.onSuccess { report ->
            Toast.makeText(
                this,
                "Preview processed: ${report.processed}, remaining: ${report.remaining}",
                Toast.LENGTH_SHORT,
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "プレビュー処理に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        runCatching {
            repository.settings()
        }.onSuccess { settings ->
            dashboardView.renderSettings(
                settings = settings,
                onBack = ::renderDashboard,
                onExportBackup = ::exportBackup,
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "設定を開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportBackup() {
        runCatching {
            repository.exportBackup()
        }.onSuccess { file ->
            Toast.makeText(this, "Backup: ${file.name}", Toast.LENGTH_SHORT).show()
            openSettings()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "バックアップに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }
}
