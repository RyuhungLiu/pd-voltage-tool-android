package com.pdtool.voltage

object MippsProtocol {
    const val SLOT_ID = 0
    const val SLOT_SIZE = 69
    const val DATA_CHUNK_SIZE = 48

    const val OP_START = 0x00
    const val OP_DATA = 0x01
    const val OP_FINISH = 0x02
    const val OP_APPLY = 0x08

    data class Response(
        val operation: Int,
        val slotId: Int,
        val status: Int,
        val payload: ByteArray,
    )

    fun startCommand(size: Int = SLOT_SIZE): ByteArray {
        require(size in 1..0xFFFF) { "Invalid MIPPS slot size" }
        return command(OP_START, byteArrayOf(size.toByte(), (size ushr 8).toByte()))
    }

    fun dataCommand(offset: Int, chunk: ByteArray): ByteArray {
        require(offset in 0..0xFFFF) { "Invalid MIPPS data offset" }
        require(chunk.isNotEmpty() && chunk.size <= DATA_CHUNK_SIZE) { "Invalid MIPPS data chunk" }
        return command(
            OP_DATA,
            byteArrayOf(offset.toByte(), (offset ushr 8).toByte()) + chunk,
        )
    }

    fun finishCommand(crc16: Int): ByteArray {
        require(crc16 in 0..0xFFFF) { "Invalid MIPPS CRC16" }
        return command(OP_FINISH, byteArrayOf(crc16.toByte(), (crc16 ushr 8).toByte()))
    }

    fun applyCommand(): ByteArray = command(OP_APPLY, byteArrayOf(0x01))

    fun command(operation: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(operation in 0..0xFF) { "Invalid MIPPS operation" }
        require(payload.size <= PdUsbProtocol.REPORT_SIZE - 6) { "MIPPS payload is too large" }
        return ByteArray(PdUsbProtocol.REPORT_SIZE).apply {
            this[0] = 0x52
            this[1] = 0xFF.toByte()
            this[2] = (3 + payload.size).toByte()
            this[3] = 0x03
            this[4] = operation.toByte()
            this[5] = SLOT_ID.toByte()
            payload.copyInto(this, destinationOffset = 6)
        }
    }

    fun parseResponse(
        report: ByteArray,
        expectedOperation: Int,
        expectedSlotId: Int = SLOT_ID,
    ): Response? {
        val packet = packet(report) ?: return null
        if (packet.type != RESPONSE_TYPE || packet.length < 4) return null
        val offset = packet.offset
        if (report.u8(offset) != RESPONSE_GROUP) return null
        val operation = report.u8(offset + 1)
        val slotId = report.u8(offset + 2)
        if (operation != expectedOperation || slotId != expectedSlotId) return null
        val status = report.u8(offset + 3)
        return Response(
            operation = operation,
            slotId = slotId,
            status = status,
            payload = report.copyOfRange(offset + 4, offset + packet.length),
        )
    }

    fun requireSuccess(response: Response, step: String) {
        check(response.status == 0) {
            "$step failed with MIPPS status 0x${response.status.toString(16).uppercase().padStart(2, '0')}"
        }
    }

    fun crc16CcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        data.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private data class Packet(val offset: Int, val length: Int, val type: Int)

    private fun packet(report: ByteArray): Packet? {
        if (report.size < 4 || report.u8(1) != 0xFF) return null
        val packet = when (report.u8(0)) {
            0xA1 -> {
                if (report.size < 12) return null
                Packet(offset = 12, length = report.u8(10) - 1, type = report.u8(11))
            }
            0xA2 -> Packet(offset = 4, length = report.u8(2) - 1, type = report.u8(3))
            else -> return null
        }
        return packet.takeIf { it.length >= 0 && it.offset + it.length <= report.size }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private const val RESPONSE_TYPE = 0x02
    private const val RESPONSE_GROUP = 0x02
}
