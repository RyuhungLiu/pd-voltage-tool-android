package com.pdtool.voltage

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FirmwareRepository(
    private val baseUrl: String = "https://pd.ruaorz.com",
) {
    data class Release(
        val tag: String,
        val name: String,
        val assetSize: Long,
        val variant: String,
        val prerelease: Boolean,
    )

    fun latest(variant: String): Release {
        val json = getBytes("/api/firmware?variant=${encode(variant)}", MAX_LIST_BYTES)
        val array = JSONArray(json.toString(Charsets.UTF_8))
        require(array.length() > 0) { "No firmware is available for $variant" }
        val releases = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Release(
                tag = item.getString("tag"),
                name = item.optString("name").ifEmpty { item.getString("tag") },
                assetSize = item.optLong("asset_size", -1),
                variant = item.optString("variant").ifEmpty { variant },
                prerelease = item.optBoolean("prerelease", false),
            )
        }
        val candidates = releases.filterNot(Release::prerelease).ifEmpty { releases }
        return candidates.maxWithOrNull { left, right ->
            compareVersion(
                versionTuple(left.tag, left.name),
                versionTuple(right.tag, right.name),
            )
        } ?: candidates.first()
    }

    fun download(release: Release): ByteArray {
        val path = "/api/firmware/${encode(release.tag)}/download?variant=${encode(release.variant)}"
        return getBytes(path, MAX_DOWNLOAD_BYTES)
    }

    private fun getBytes(path: String, maxBytes: Int): ByteArray {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        connection.setRequestProperty("User-Agent", "PDVoltageTool-Android/0.4")
        try {
            val status = connection.responseCode
            require(status in 200..299) { "Firmware server returned HTTP $status" }
            val contentLength = connection.contentLengthLong
            require(contentLength < 0 || contentLength <= maxBytes) { "Firmware server response is too large" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "Firmware server response is too large" }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun versionTuple(vararg values: String): List<Int> {
        val match = VERSION_REGEX.find(values.joinToString(" ")) ?: return listOf(0)
        return match.value.split('.').mapNotNull(String::toIntOrNull)
    }

    private fun compareVersion(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val result = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val MAX_LIST_BYTES = 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 64 * 1024
        private val VERSION_REGEX = Regex("\\d+(?:\\.\\d+)+")
    }
}
