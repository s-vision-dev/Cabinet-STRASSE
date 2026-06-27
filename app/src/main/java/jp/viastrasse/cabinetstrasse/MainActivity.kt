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
            dashboardView.render(dashboard) { mode ->
                Toast.makeText(this, "${mode.name} を開きます", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { error ->
            dashboardView.renderError(error.message ?: "Cabinet core の初期化に失敗しました。")
        }
    }
}

