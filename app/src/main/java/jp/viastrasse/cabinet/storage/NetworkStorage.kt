package jp.viastrasse.cabinet.storage

import android.content.Context
import android.util.Base64
import jp.viastrasse.cabinet.data.StorageProviderAccountSummary
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

class WebDavStorageProvider(
    private val context: Context,
    private val account: StorageProviderAccountSummary,
) : DirectStorageProvider {
    override val providerId: String = account.id
    private val password get() = SecureCredentialStore.load(context, providerId, "password").orEmpty()
    private val authorization: String
        get() = "Basic " + Base64.encodeToString(
            "${account.username}:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

    override fun listFiles(): List<RemoteStorageEntry> {
        require(account.endpointUrl.startsWith("http://") || account.endpointUrl.startsWith("https://"))
        val root = resolveUrl(account.endpointUrl, account.remoteRoot)
        val pending = ArrayDeque<String>().apply { add(root) }
        val visited = mutableSetOf<String>()
        val files = mutableListOf<RemoteStorageEntry>()
        while (pending.isNotEmpty() && visited.size < MAX_ENTRIES) {
            val folder = pending.removeFirst()
            if (!visited.add(folder)) continue
            listFolder(folder).forEach { entry ->
                if (entry.isDirectory) pending.add(entry.path) else files.add(entry)
            }
        }
        return files.take(MAX_ENTRIES)
    }

    override fun download(entry: RemoteStorageEntry, destination: File) {
        val connection = HttpSupport.request(entry.path, headers = mapOf("Authorization" to authorization))
        HttpSupport.copy(connection, destination)
    }

    override fun testConnection() {
        listFolder(resolveUrl(account.endpointUrl, account.remoteRoot))
    }

    private fun listFolder(url: String): List<RemoteStorageEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
            .toByteArray(Charsets.UTF_8)
        val connection = HttpSupport.request(
            url,
            "PROPFIND",
            mapOf(
                "Authorization" to authorization,
                "Depth" to "1",
                "Content-Type" to "application/xml; charset=utf-8",
            ),
            body,
        )
        return try {
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                setExpandEntityReferences(false)
            }
                .newDocumentBuilder().parse(connection.inputStream)
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            buildList {
                for (index in 0 until responses.length) {
                    val element = responses.item(index) as? Element ?: continue
                    val href = element.firstText("href")
                    val resolved = URI(url).resolve(href).toString()
                    if (normalizedUrl(resolved) == normalizedUrl(url)) continue
                    val isDirectory = element.getElementsByTagNameNS("DAV:", "collection").length > 0
                    val name = element.firstText("displayname").ifBlank {
                        UriCodec.decode(resolved.trimEnd('/').substringAfterLast('/'))
                    }
                    add(
                        RemoteStorageEntry(
                            id = href,
                            path = resolved,
                            name = name,
                            mimeType = if (isDirectory) "inode/directory" else element.firstText("getcontenttype").ifBlank { mimeFromName(name) },
                            size = element.firstText("getcontentlength").toLongOrNull() ?: 0L,
                            modifiedAt = element.firstText("getlastmodified"),
                            isDirectory = isDirectory,
                            webUrl = resolved,
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Element.firstText(localName: String): String =
        getElementsByTagNameNS("DAV:", localName).item(0)?.textContent.orEmpty().trim()

    private fun resolveUrl(endpoint: String, root: String): String {
        val base = endpoint.trimEnd('/') + "/"
        return if (root.startsWith("http://") || root.startsWith("https://")) root else URI(base).resolve(root.trimStart('/')).toString()
    }

    private fun normalizedUrl(value: String) = value.trimEnd('/')

    companion object {
        private const val MAX_ENTRIES = 1000
    }
}

class SmbStorageProvider(
    private val context: Context,
    private val account: StorageProviderAccountSummary,
) : DirectStorageProvider {
    override val providerId: String = account.id

    private fun smbContext(): CIFSContext = SingletonContext.getInstance().withCredentials(
        NtlmPasswordAuthenticator(
            account.domain,
            account.username,
            SecureCredentialStore.load(context, providerId, "password").orEmpty(),
        ),
    )

    private fun rootUrl(): String {
        val server = account.endpointUrl.removePrefix("smb://").trim('/')
        val share = account.remoteRoot.trim('/')
        require(server.isNotBlank() && share.isNotBlank())
        return "smb://$server/$share/"
    }

    override fun listFiles(): List<RemoteStorageEntry> {
        val context = smbContext()
        val pending = ArrayDeque<SmbFile>().apply { add(SmbFile(rootUrl(), context)) }
        val files = mutableListOf<RemoteStorageEntry>()
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_ENTRIES) {
            val folder = pending.removeFirst()
            folder.listFiles().forEach { child ->
                visited += 1
                if (child.isDirectory) {
                    pending.add(child)
                } else {
                    val name = child.name.trimEnd('/')
                    files += RemoteStorageEntry(
                        id = child.canonicalPath,
                        path = child.canonicalPath,
                        name = name,
                        mimeType = mimeFromName(name),
                        size = child.length(),
                        modifiedAt = child.lastModified().toString(),
                    )
                }
            }
        }
        return files.take(MAX_ENTRIES)
    }

    override fun download(entry: RemoteStorageEntry, destination: File) {
        SmbFile(entry.path, smbContext()).inputStream.use { input ->
            destination.outputStream().buffered().use(input::copyTo)
        }
    }

    override fun testConnection() {
        require(SmbFile(rootUrl(), smbContext()).exists())
    }

    companion object {
        private const val MAX_ENTRIES = 1000
    }
}

private object UriCodec {
    fun decode(value: String): String = java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
}
