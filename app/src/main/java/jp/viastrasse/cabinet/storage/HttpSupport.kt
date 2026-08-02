package jp.viastrasse.cabinet.storage

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object HttpSupport {
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        if (connection.responseCode !in 200..299 && connection.responseCode != 207) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("HTTP ${connection.responseCode}: $message")
        }
        return connection
    }

    fun json(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        val actualHeaders = headers.toMutableMap()
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null) actualHeaders.putIfAbsent("Content-Type", "application/json; charset=utf-8")
        return request(url, method, actualHeaders, bytes).useConnection { connection ->
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }

    fun form(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(Charsets.UTF_8)
        return request(
            url,
            "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body,
        ).useConnection { connection ->
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }

    fun copy(connection: HttpURLConnection, destination: java.io.File) {
        connection.useConnection { value ->
            value.inputStream.use { input ->
                destination.outputStream().buffered().use(input::copyTo)
            }
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}
