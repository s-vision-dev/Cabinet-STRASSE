package jp.viastrasse.cabinetstrasse.data

import android.content.Context
import jp.viastrasse.cabinetstrasse.core.CabinetNative
import java.io.File

class CabinetRepository(context: Context) {
    private val databasePath = File(context.filesDir, "cabinet-strasse.db").absolutePath

    fun dashboard(): CabinetDashboard {
        return CabinetJsonParser.dashboard(CabinetNative.dashboardJson(databasePath))
    }

    fun search(query: String): SearchResponse {
        return CabinetJsonParser.search(CabinetNative.searchJson(databasePath, query))
    }

    fun mode(mode: String): ModeResponse {
        return CabinetJsonParser.mode(CabinetNative.modeJson(databasePath, mode))
    }

    fun registerUrl(url: String, title: String, note: String): CabinetItemSummary {
        val json = CabinetNative.registerUrlJson(databasePath, url, title, note)
        return CabinetJsonParser.search("""{"query":"","results":[$json]}""").results.first()
    }
}
