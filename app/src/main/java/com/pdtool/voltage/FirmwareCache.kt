package com.pdtool.voltage

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FirmwareCache(context: Context) {
    data class Entry(
        val release: FirmwareRepository.Release,
        val image: FirmwareProtocol.Image,
    )

    private val directory = File(context.applicationContext.filesDir, CACHE_DIRECTORY)

    fun load(variant: String): Entry? {
        val normalizedVariant = normalizeVariant(variant)
        val firmwareFile = File(directory, "$normalizedVariant.ro")
        val metadataFile = File(directory, "$normalizedVariant.json")
        return try {
            if (!firmwareFile.isFile || !metadataFile.isFile) return null
            val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
            require(metadata.getString("variant") == normalizedVariant)
            val bytes = firmwareFile.readBytes()
            require(bytes.size.toLong() == metadata.getLong("fileSize"))
            require(sha256(bytes).equals(metadata.getString("sha256"), ignoreCase = true))
            val release = FirmwareRepository.Release(
                tag = metadata.getString("tag"),
                name = metadata.getString("name"),
                assetSize = metadata.optLong("assetSize", bytes.size.toLong()),
                variant = normalizedVariant,
                prerelease = metadata.optBoolean("prerelease", false),
            )
            Entry(release, FirmwareProtocol.parseImage(bytes))
        } catch (_: Throwable) {
            remove(normalizedVariant)
            null
        }
    }

    fun save(release: FirmwareRepository.Release, bytes: ByteArray) {
        val variant = normalizeVariant(release.variant)
        FirmwareProtocol.parseImage(bytes)
        directory.mkdirs()
        require(directory.isDirectory) { "Unable to create firmware cache" }

        val firmwareFile = File(directory, "$variant.ro")
        val metadataFile = File(directory, "$variant.json")
        val firmwareTemp = File(directory, "$variant.ro.tmp")
        val metadataTemp = File(directory, "$variant.json.tmp")
        val metadata = JSONObject()
            .put("tag", release.tag)
            .put("name", release.name)
            .put("assetSize", release.assetSize)
            .put("variant", variant)
            .put("prerelease", release.prerelease)
            .put("fileSize", bytes.size)
            .put("sha256", sha256(bytes))

        try {
            firmwareTemp.writeBytes(bytes)
            metadataTemp.writeText(metadata.toString(), Charsets.UTF_8)
            replace(firmwareTemp, firmwareFile)
            replace(metadataTemp, metadataFile)
        } finally {
            firmwareTemp.delete()
            metadataTemp.delete()
        }
    }

    fun remove(variant: String) {
        val normalizedVariant = normalizeVariant(variant)
        File(directory, "$normalizedVariant.ro").delete()
        File(directory, "$normalizedVariant.json").delete()
        File(directory, "$normalizedVariant.ro.tmp").delete()
        File(directory, "$normalizedVariant.json.tmp").delete()
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun normalizeVariant(variant: String): String {
        val normalized = variant.lowercase()
        require(normalized == "std" || normalized == "nano") { "Unsupported firmware variant" }
        return normalized
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val CACHE_DIRECTORY = "firmware-cache"
    }
}
