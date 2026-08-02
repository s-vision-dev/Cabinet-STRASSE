package jp.viastrasse.cabinet.storage

import android.content.Context
import jp.viastrasse.cabinet.data.StorageProviderAccountSummary

class StorageProviderFactory(private val context: Context) {
    fun create(account: StorageProviderAccountSummary): DirectStorageProvider = when (account.providerType) {
        "dropbox", "google_drive", "onedrive", "box" -> OAuthApiStorageProvider(context, account.providerType)
        "webdav", "nextcloud" -> WebDavStorageProvider(context, account)
        "smb" -> SmbStorageProvider(context, account)
        else -> error("Direct connection is not supported: ${account.providerType}")
    }
}
