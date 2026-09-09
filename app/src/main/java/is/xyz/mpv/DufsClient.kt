package `is`.xyz.mpv

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object DufsClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    data class Entry(
        val name: String,
        val url: String,
        val isDirectory: Boolean,
    )

    fun normalizeServerUrl(serverUrl: String): String {
        val root = Uri.parse(serverUrl.trim())
        require(root.scheme == "http" || root.scheme == "https") {
            "Dufs URLs must use HTTP or HTTPS"
        }
        require(!root.host.isNullOrEmpty()) { "The Dufs URL has no host" }
        return root.normalizedRoot().toString()
    }

    fun listDirectory(directoryUrl: String): List<Entry> {
        if (Thread.currentThread().isInterrupted)
            throw InterruptedException()

        val directory = Uri.parse(normalizeServerUrl(directoryUrl))
        val paths = fetchDirectory(directory).getJSONArray("paths")
        val entries = mutableListOf<Entry>()

        for (index in 0 until paths.length()) {
            val item = paths.getJSONObject(index)
            val name = item.getString("name")
            val type = item.getString("path_type")
            val child = directory.appendPath(name)

            when (type) {
                "Dir", "SymlinkDir" -> entries.add(Entry(name, child.toString(), true))
                "File", "SymlinkFile" -> {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in Utils.MEDIA_EXTENSIONS)
                        entries.add(Entry(name, child.toString(), false))
                }
            }
        }

        return entries.sortedWith(
            compareByDescending<Entry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    private fun fetchDirectory(directory: Uri): JSONObject {
        val requestUri = directory.buildUpon()
            .fragment(null)
            .appendQueryParameter("json", "")
            .build()
        val connection = URL(requestUri.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        directory.userInfo?.let {
            val credentials = Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $credentials")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299)
                throw IOException("Dufs server returned HTTP $status")
            return connection.inputStream.bufferedReader().use {
                JSONObject(it.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Uri.normalizedRoot(): Uri {
        val builder = buildUpon().clearQuery().fragment(null)
        queryParameterNames
            .filterNot { it in DUFS_CONTROL_QUERY_PARAMETERS }
            .forEach { name ->
                getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        return builder.build()
    }

    private fun Uri.appendPath(name: String): Uri =
        buildUpon().appendPath(name).fragment(null).build()

    private val DUFS_CONTROL_QUERY_PARAMETERS = setOf(
        "edit", "hash", "json", "noscript", "q", "simple", "view", "zip",
    )
}
