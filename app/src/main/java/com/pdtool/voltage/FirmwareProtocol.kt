package com.pdtool.voltage

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FirmwareProtocol {
    const val MAX_FIRMWARE_SIZE = 50 * 1024
    const val DATA_CHUNK_SIZE = 54

    const val CMD_IAP_START = 0x80
    const val CMD_IAP_DATA = 0x81
    const val CMD_IAP_FINISH = 0x82
    const val CMD_EXIT_BOOTLOADER = 0x84
    const val CMD_BOOTLOADER_INFO = 0x85

    private const val BOOTLOADER_GROUP = 0x10
    private const val RESPONSE_HEADER_0 = 0xA2
    private const val RESPONSE_HEADER_1 = 0xFF
    private const val STATUS_OK = 0
    const val STATUS_CRC_FAILED = 3
    private const val FIRMWARE_HEADER_OFFSET = 512
    private const val FIRMWARE_HEADER_SIZE = 28
    private val FIRMWARE_MAGIC = byteArrayOf(0x50, 0x44, 0x46, 0x57)

    data class Image(
        val device: String?,
        val hardwareType: String?,
        val version: String?,
        val payload: ByteArray,
        val crc32: Long,
        val nonce: ByteArray,
    )

    data class Response(val command: Int, val status: Int, val data: ByteArray)

    data class BootloaderInfo(
        val version: String,
        val versionTuple: List<Int>,
        val hardwareType: String?,
    ) {
        val isSupported: Boolean
            get() = compareVersion(versionTuple, listOf(1, 3, 0)) >= 0
    }

    fun parseImage(bytes: ByteArray): Image {
        require(bytes.size >= FIRMWARE_HEADER_OFFSET + FIRMWARE_HEADER_SIZE) {
            "Invalid firmware file"
        }
        require(
            FIRMWARE_MAGIC.indices.all { bytes[FIRMWARE_HEADER_OFFSET + it] == FIRMWARE_MAGIC[it] },
        ) { "Invalid firmware header" }
        require(bytes.u8(FIRMWARE_HEADER_OFFSET + 4) == 1) { "Unsupported firmware format" }

        val nonce = bytes.copyOfRange(FIRMWARE_HEADER_OFFSET + 8, FIRMWARE_HEADER_OFFSET + 20)
        val payloadSize = bytes.u32Le(FIRMWARE_HEADER_OFFSET + 20).toInt()
        val expectedCrc = bytes.u32Le(FIRMWARE_HEADER_OFFSET + 24)
        val payloadOffset = FIRMWARE_HEADER_OFFSET + FIRMWARE_HEADER_SIZE
        require(payloadSize in 1..MAX_FIRMWARE_SIZE) { "Firmware size is invalid or exceeds 50 KB" }
        require(bytes.size - payloadOffset == payloadSize) { "Firmware payload length mismatch" }
        val payload = bytes.copyOfRange(payloadOffset, bytes.size)
        return Image(
            device = bytes.readCString(0, 16),
            hardwareType = bytes.readCString(16, 8)?.uppercase(),
            version = bytes.readCString(24, 8),
            payload = payload,
            crc32 = expectedCrc,
            nonce = nonce,
        )
    }

    fun command(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= PdUsbProtocol.REPORT_SIZE - 5) { "Bootloader payload is too large" }
        return ByteArray(PdUsbProtocol.REPORT_SIZE).apply {
            this[0] = 0x52
            this[1] = 0xFF.toByte()
            this[2] = (2 + payload.size).toByte()
            this[3] = BOOTLOADER_GROUP.toByte()
            this[4] = command.toByte()
            payload.copyInto(this, destinationOffset = 5)
        }
    }

    fun parseResponse(report: ByteArray, expectedCommand: Int? = null): Response {
        require(report.size >= 6) { "Bootloader response is too short" }
        require(report.u8(0) == RESPONSE_HEADER_0 && report.u8(1) == RESPONSE_HEADER_1) {
            "Invalid bootloader response header"
        }
        require(report.u8(3) == BOOTLOADER_GROUP) { "Invalid bootloader response group" }
        val command = report.u8(4)
        require(expectedCommand == null || command == expectedCommand) {
            "Unexpected bootloader response command 0x${command.toString(16)}"
        }
        return Response(command, report.u8(5), report.copyOfRange(6, report.size))
    }

    fun parseBootloaderInfo(response: Response): BootloaderInfo {
        require(response.command == CMD_BOOTLOADER_INFO && response.status == STATUS_OK) {
            "Bootloader information request failed (status 0x${response.status.toString(16)})"
        }
        require(response.data.size >= 3) { "Bootloader version response is incomplete" }
        val tuple = response.data.take(3).map(Byte::toUByte).map(UByte::toInt)
        val hardwareType = when (response.data.getOrNull(3)?.toInt()?.and(0xFF)) {
            4 -> "STD"
            1 -> "NANO"
            else -> null
        }
        return BootloaderInfo(tuple.joinToString("."), tuple, hardwareType)
    }

    fun startPayload(image: Image): ByteArray =
        ByteBuffer.allocate(8 + image.nonce.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(image.payload.size)
            .putInt(image.crc32.toInt())
            .put(image.nonce)
            .array()

    fun dataPayload(offset: Int, chunk: ByteArray): ByteArray {
        require(chunk.size <= DATA_CHUNK_SIZE)
        return ByteBuffer.allocate(5 + chunk.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(offset)
            .put(chunk.size.toByte())
            .put(chunk)
            .array()
    }

    fun requireSuccess(response: Response, operation: String) {
        require(response.status == STATUS_OK) {
            "$operation failed (status 0x${response.status.toString(16).uppercase()})"
        }
    }

    private fun compareVersion(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val result = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun ByteArray.readCString(offset: Int, length: Int): String? {
        val end = (offset until minOf(offset + length, size)).firstOrNull { this[it] == 0.toByte() }
            ?: minOf(offset + length, size)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim().ifEmpty { null }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u32Le(offset: Int): Long =
        ByteBuffer.wrap(this, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFF_FFFFL
}
