package jp.viastrasse.cabinet.storage

import android.content.Context
import android.net.Uri
import jp.viastrasse.cabinet.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

data class OAuthProviderConfig(
    val providerId: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val clientSecret: String = "",
) {
    val redirectUri: String get() = "viastrasse-cabinet://oauth/$providerId"

    companion object {
        fun forProvider(providerId: String): OAuthProviderConfig? = when (providerId) {
            "dropbox" -> OAuthProviderConfig(
                providerId,
                BuildConfig.DROPBOX_CLIENT_ID,
                "https://www.dropbox.com/oauth2/authorize",
                "https://api.dropboxapi.com/oauth2/token",
                listOf("account_info.read", "files.metadata.read", "files.content.read", "files.content.write"),
            )
            "google_drive" -> OAuthProviderConfig(
                providerId,
                BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                listOf("https://www.googleapis.com/auth/drive"),
            )
            "onedrive" -> OAuthProviderConfig(
                providerId,
                BuildConfig.ONEDRIVE_CLIENT_ID,
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                listOf("offline_access", "User.Read", "Files.ReadWrite"),
            )
            "box" -> OAuthProviderConfig(
                providerId,
                BuildConfig.BOX_CLIENT_ID,
                "https://account.box.com/api/oauth2/authorize",
                "https://api.box.com/oauth2/token",
                emptyList(),
                BuildConfig.BOX_CLIENT_SECRET,
            )
            else -> null
        }
    }
}

class OAuthController(private val context: Context) {
    private val prefs = context.getSharedPreferences("cabinet_oauth_pending", Context.MODE_PRIVATE)

    fun authorizationUri(providerId: String): Uri {
        val config = requireNotNull(OAuthProviderConfig.forProvider(providerId))
        require(config.clientId.isNotBlank()) { "OAuth Client ID is not configured: $providerId" }
        require(providerId != "box" || config.clientSecret.isNotBlank()) { "OAuth Client Secret is not configured: box" }
        val verifier = randomUrlToken(64)
        val state = randomUrlToken(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        prefs.edit()
            .putString("$providerId:verifier", verifier)
            .putString("$providerId:state", state)
            .apply()
        return Uri.parse(config.authorizationEndpoint).buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply {
                if (config.scopes.isNotEmpty()) appendQueryParameter("scope", config.scopes.joinToString(" "))
                if (providerId == "dropbox") appendQueryParameter("token_access_type", "offline")
                if (providerId == "google_drive") {
                    appendQueryParameter("access_type", "offline")
                    appendQueryParameter("prompt", "consent")
                }
            }
            .build()
    }

    fun finish(uri: Uri): String {
        val providerId = uri.pathSegments.firstOrNull().orEmpty()
        val config = requireNotNull(OAuthProviderConfig.forProvider(providerId))
        uri.getQueryParameter("error")?.let { error(it) }
        val expectedState = prefs.getString("$providerId:state", null)
        require(expectedState != null && expectedState == uri.getQueryParameter("state")) { "OAuth state mismatch." }
        val verifier = requireNotNull(prefs.getString("$providerId:verifier", null))
        val code = requireNotNull(uri.getQueryParameter("code"))
        val tokenFields = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "code_verifier" to verifier,
        )
        if (config.clientSecret.isNotBlank()) tokenFields["client_secret"] = config.clientSecret
        val token = HttpSupport.form(
            config.tokenEndpoint,
            tokenFields,
        )
        saveToken(providerId, token)
        prefs.edit().remove("$providerId:state").remove("$providerId:verifier").apply()
        return providerId
    }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun saveToken(providerId: String, token: JSONObject) {
        SecureCredentialStore.save(context, providerId, "access_token", token.getString("access_token"))
        token.optString("refresh_token").takeIf(String::isNotBlank)?.let {
            SecureCredentialStore.save(context, providerId, "refresh_token", it)
        }
        val expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L
        SecureCredentialStore.save(context, providerId, "expires_at", expiresAt.toString())
    }
}

class OAuthTokenProvider(private val context: Context, private val providerId: String) {
    fun accessToken(): String {
        val current = SecureCredentialStore.load(context, providerId, "access_token")
        val expiresAt = SecureCredentialStore.load(context, providerId, "expires_at")?.toLongOrNull() ?: 0L
        if (!current.isNullOrBlank() && expiresAt > System.currentTimeMillis() + 60_000L) return current
        val config = requireNotNull(OAuthProviderConfig.forProvider(providerId))
        val refresh = SecureCredentialStore.load(context, providerId, "refresh_token")
        if (refresh.isNullOrBlank()) return requireNotNull(current) { "OAuth authentication is required: $providerId" }
        val fields = mutableMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to config.clientId,
        )
        if (config.clientSecret.isNotBlank()) fields["client_secret"] = config.clientSecret
        val token = HttpSupport.form(
            config.tokenEndpoint,
            fields,
        )
        val access = token.getString("access_token")
        SecureCredentialStore.save(context, providerId, "access_token", access)
        token.optString("refresh_token").takeIf(String::isNotBlank)?.let {
            SecureCredentialStore.save(context, providerId, "refresh_token", it)
        }
        SecureCredentialStore.save(
            context,
            providerId,
            "expires_at",
            (System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L).toString(),
        )
        return access
    }
}

class OAuthApiStorageProvider(
    private val context: Context,
    override val providerId: String,
) : DirectStorageProvider {
    private val tokens = OAuthTokenProvider(context, providerId)
    private fun bearer() = mapOf("Authorization" to "Bearer ${tokens.accessToken()}")

    override fun listFiles(): List<RemoteStorageEntry> = listFolder("")

    override fun listFolder(path: String): List<RemoteStorageEntry> = when (providerId) {
        "dropbox" -> listDropbox(path)
        "google_drive" -> listGoogleDrive(path)
        "onedrive" -> listOneDrive(path)
        "box" -> listBox(path)
        else -> error("Unsupported OAuth provider: $providerId")
    }

    override fun testConnection() {
        when (providerId) {
            "dropbox" -> {
                HttpSupport.json(
                    "https://api.dropboxapi.com/2/users/get_current_account",
                    "POST",
                    bearer(),
                    JSONObject(),
                )
                HttpSupport.json(
                    "https://api.dropboxapi.com/2/files/list_folder",
                    "POST",
                    bearer(),
                    JSONObject().put("path", "").put("recursive", false).put("limit", 1),
                )
            }
            "google_drive" -> HttpSupport.json(
                "https://www.googleapis.com/drive/v3/about?fields=user",
                headers = bearer(),
            )
            "onedrive" -> HttpSupport.json(
                "https://graph.microsoft.com/v1.0/me/drive?\$select=id",
                headers = bearer(),
            )
            "box" -> HttpSupport.json("https://api.box.com/2.0/users/me", headers = bearer())
            else -> error("Unsupported OAuth provider: $providerId")
        }
    }

    override fun download(entry: RemoteStorageEntry, destination: File) {
        destination.parentFile?.mkdirs()
        val connection = when (providerId) {
            "dropbox" -> HttpSupport.request(
                "https://content.dropboxapi.com/2/files/download",
                "POST",
                bearer() + ("Dropbox-API-Arg" to JSONObject().put("path", entry.id).toString()),
            )
            "google_drive" -> HttpSupport.request(
                if (entry.path.startsWith("google-export:")) {
                    "https://www.googleapis.com/drive/v3/files/${HttpSupport.encode(entry.id)}/export?mimeType=${HttpSupport.encode(entry.path.removePrefix("google-export:"))}"
                } else {
                    "https://www.googleapis.com/drive/v3/files/${HttpSupport.encode(entry.id)}?alt=media"
                },
                headers = bearer(),
            )
            "onedrive" -> HttpSupport.request(
                "https://graph.microsoft.com/v1.0/me/drive/items/${HttpSupport.encode(entry.id)}/content",
                headers = bearer(),
            )
            "box" -> HttpSupport.request(
                "https://api.box.com/2.0/files/${HttpSupport.encode(entry.id)}/content",
                headers = bearer(),
            )
            else -> error("Unsupported OAuth provider: $providerId")
        }
        HttpSupport.copy(connection, destination)
    }

    private fun listDropbox(path: String): List<RemoteStorageEntry> {
        val result = mutableListOf<RemoteStorageEntry>()
        var response = HttpSupport.json(
            "https://api.dropboxapi.com/2/files/list_folder",
            "POST",
            bearer(),
            JSONObject().put("path", path).put("recursive", false).put("limit", 1000),
        )
        while (true) {
            result += response.getJSONArray("entries").objects().map { item ->
                val isDirectory = item.optString(".tag") == "folder"
                RemoteStorageEntry(
                    id = item.optString("id").ifBlank { item.getString("path_lower") },
                    path = item.getString("path_display"),
                    name = item.getString("name"),
                    mimeType = if (isDirectory) "inode/directory" else mimeFromName(item.getString("name")),
                    size = item.optLong("size"),
                    modifiedAt = item.optString("server_modified"),
                    isDirectory = isDirectory,
                )
            }
            if (!response.optBoolean("has_more")) break
            response = HttpSupport.json(
                "https://api.dropboxapi.com/2/files/list_folder/continue",
                "POST",
                bearer(),
                JSONObject().put("cursor", response.getString("cursor")),
            )
        }
        return result
    }

    private fun listGoogleDrive(folderId: String): List<RemoteStorageEntry> {
        val result = mutableListOf<RemoteStorageEntry>()
        var pageToken = ""
        do {
            val parentId = folderId.ifBlank { "root" }.replace("'", "\\'")
            val url = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("q", "'$parentId' in parents and trashed = false")
                .appendQueryParameter("pageSize", "1000")
                .appendQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,webViewLink)")
                .apply { if (pageToken.isNotBlank()) appendQueryParameter("pageToken", pageToken) }
                .build().toString()
            val response = HttpSupport.json(url, headers = bearer())
            result += response.optJSONArray("files").objects().map { item ->
                val sourceMime = item.optString("mimeType")
                val isDirectory = sourceMime == "application/vnd.google-apps.folder"
                val export = googleExport(sourceMime)
                val displayName = item.getString("name") + export?.second.orEmpty()
                RemoteStorageEntry(
                    id = item.getString("id"),
                    path = if (isDirectory) item.getString("name") else export?.let { "google-export:${it.first}" }.orEmpty().ifBlank { item.getString("name") },
                    name = if (isDirectory) item.getString("name") else displayName,
                    mimeType = if (isDirectory) "inode/directory" else export?.first ?: sourceMime.ifBlank { mimeFromName(displayName) },
                    size = item.optLong("size"), modifiedAt = item.optString("modifiedTime"),
                    webUrl = item.optString("webViewLink"),
                    isDirectory = isDirectory,
                    navigationKey = item.getString("id"),
                )
            }
            pageToken = response.optString("nextPageToken")
        } while (pageToken.isNotBlank())
        return result
    }

    private fun listOneDrive(folderId: String): List<RemoteStorageEntry> {
        val entries = mutableListOf<RemoteStorageEntry>()
        var url = (if (folderId.isBlank()) {
            "https://graph.microsoft.com/v1.0/me/drive/root/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/items/${HttpSupport.encode(folderId)}/children"
        }) + "?\$top=999&\$select=id,name,size,lastModifiedDateTime,webUrl,file,folder"
        while (url.isNotBlank() && entries.size < MAX_REMOTE_FILES) {
            val response = HttpSupport.json(url, headers = bearer())
            entries += response.optJSONArray("value").objects().map { item ->
                val isDirectory = item.has("folder")
                RemoteStorageEntry(
                    id = item.getString("id"), path = item.getString("name"), name = item.getString("name"),
                    mimeType = if (isDirectory) "inode/directory" else item.optJSONObject("file")?.optString("mimeType").orEmpty().ifBlank { mimeFromName(item.getString("name")) },
                    size = item.optLong("size"), modifiedAt = item.optString("lastModifiedDateTime"), webUrl = item.optString("webUrl"),
                    isDirectory = isDirectory,
                    navigationKey = item.getString("id"),
                )
            }
            url = response.optString("@odata.nextLink")
        }
        return entries.take(MAX_REMOTE_FILES)
    }

    private fun listBox(folderId: String): List<RemoteStorageEntry> {
        val result = mutableListOf<RemoteStorageEntry>()
        var offset = 0
        do {
            val response = HttpSupport.json(
                "https://api.box.com/2.0/folders/${HttpSupport.encode(folderId.ifBlank { "0" })}/items?limit=1000&offset=$offset&fields=id,type,name,size,modified_at",
                headers = bearer(),
            )
            val entries = response.optJSONArray("entries").objects()
            result += entries.map { item ->
                val isDirectory = item.optString("type") == "folder"
                RemoteStorageEntry(
                    id = item.getString("id"), path = item.getString("name"), name = item.getString("name"),
                    mimeType = if (isDirectory) "inode/directory" else mimeFromName(item.getString("name")),
                    size = item.optLong("size"), modifiedAt = item.optString("modified_at"),
                    isDirectory = isDirectory,
                    navigationKey = item.getString("id"),
                )
            }
            offset += entries.size
        } while (entries.isNotEmpty() && offset < response.optInt("total_count"))
        return result.take(MAX_REMOTE_FILES)
    }

    private fun googleExport(mimeType: String): Pair<String, String>? = when (mimeType) {
        "application/vnd.google-apps.document" -> "application/pdf" to ".pdf"
        "application/vnd.google-apps.spreadsheet" ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx"
        "application/vnd.google-apps.presentation" ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx"
        "application/vnd.google-apps.drawing" -> "image/png" to ".png"
        else -> null
    }

    companion object {
        private const val MAX_REMOTE_FILES = 2000
    }
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).map(::getJSONObject)
}

internal fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "csv" -> "text/csv"
    "xlsx", "xlsm" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
