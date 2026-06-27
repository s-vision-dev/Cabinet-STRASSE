package jp.viastrasse.cabinetstrasse.core

object CabinetNative {
    init {
        System.loadLibrary("cabinet_android_ffi")
    }

    external fun dashboardJson(databasePath: String): String

    external fun searchJson(databasePath: String, query: String): String

    external fun modeJson(databasePath: String, mode: String): String

    external fun registerUrlJson(
        databasePath: String,
        url: String,
        title: String,
        note: String,
    ): String

    external fun registerFileJson(
        databasePath: String,
        path: String,
        displayName: String,
        mimeType: String,
        size: Long,
        sourceKind: String,
        note: String,
    ): String
}
