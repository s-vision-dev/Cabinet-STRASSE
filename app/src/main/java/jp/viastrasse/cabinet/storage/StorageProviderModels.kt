package jp.viastrasse.cabinet.storage

import java.io.File

data class RemoteStorageEntry(
    val id: String,
    val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: String = "",
    val isDirectory: Boolean = false,
    val webUrl: String = "",
    val navigationKey: String = path,
)

interface DirectStorageProvider {
    val providerId: String

    fun listFiles(): List<RemoteStorageEntry>

    fun listFolder(path: String): List<RemoteStorageEntry> {
        require(path.isBlank()) { "Folder navigation is not supported: $providerId" }
        return listFiles()
    }

    fun download(entry: RemoteStorageEntry, destination: File)

    fun testConnection() {
        listFiles()
    }
}
