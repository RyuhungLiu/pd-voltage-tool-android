package com.pdtool.voltage

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PdUsbProtocol {
    const val REPORT_SIZE = 64
    const val REPORT_ID = 0

    const val CMD_MCU_REBOOT = 0x00
    const val CMD_USB_PD_REBOOT = 0x01
    const val CMD_QUERY_STATUS = 0x02
    const val CMD_VBUS_OFF = 0x11
    const val CMD_VBUS_ON = 0x12
    const val CMD_PRIORITY_FORWARD = 0x21
    const val CMD_PRIORITY_REPLY = 0x22
    const val CMD_PD_MODE_SPR = 0x31
    const val CMD_PD_MODE_EPR = 0x32
    const val CMD_PD_MODE_PROP = 0x33
    const val CMD_PD_MODE_PD32 = 0x34
    const val CMD_REPORT_STD = 0x41
    const val CMD_REPORT_MINI = 0x42
    const val CMD_VBUS_MODE_OFF = 0x51
    const val CMD_VBUS_MODE_ON = 0x52
    const val CMD_VBUS_MODE_HOLD = 0x53
    const val CMD_TRIGGER_HOLD_OFF = 0x61
    const val CMD_TRIGGER_HOLD_ON = 0x62
    const val CMD_APPLY_PRESET = 0x64
    const val CMD_SET_VOLTAGE = 0x65
    const val CMD_SET_CURRENT = 0x66
    const val CMD_ADC_LOG_OFF = 0x71
    const val CMD_ADC_LOG_ON = 0x72
    const val CMD_ADC_LOG_INTERVAL = 0x73
    const val CMD_FLASH_FALLBACK_OFF = 0x81
    const val CMD_FLASH_FALLBACK_ON = 0x82
    const val CMD_TRIGGER_IMMEDIATE = 0x91
    const val CMD_TRIGGER_DELAYED = 0x92
    const val CMD_ENTER_BOOTLOADER = 0xF0

    private const val REQUEST_HEADER_0 = 0x52
    private const val REQUEST_HEADER_1 = 0xFF
    private const val REQUEST_GROUP = 0x01
    private const val REQUEST_TYPE = 0x01

    private const val RESPONSE_HEADER_NEW = 0xA1
    private const val RESPONSE_HEADER_LEGACY = 0xA2
    private const val RESPONSE_HEADER_1 = 0xFF
    private const val RESPONSE_TYPE_STATUS = 0x02

    data class DeviceStatus(
        val messagePriority: Int,
        val sinkMode: Int,
        val reportType: Int,
        val vbusStatus: Int,
        val triggerHoldStatus: Int,
        val presetVoltageMv: Int,
        val presetCurrentMa: Int,
        val adcLogEnabled: Boolean,
        val adcLogIntervalMs: Long,
        val nvBackend: Int,
        val triggerDelayMode: Int,
    )

    data class CommandAck(
        val command: Int,
        val value: Int?,
    )

    private data class Packet(
        val payloadOffset: Int,
        val payloadLength: Int,
        val type: Int,
    )

    fun command(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= REPORT_SIZE - 6) { "HID payload is too large" }
        return ByteArray(REPORT_SIZE).apply {
            this[0] = REQUEST_HEADER_0.toByte()
            this[1] = REQUEST_HEADER_1.toByte()
            this[2] = (3 + payload.size).toByte()
            this[3] = REQUEST_GROUP.toByte()
            this[4] = REQUEST_TYPE.toByte()
            this[5] = command.toByte()
            payload.copyInto(this, destinationOffset = 6)
        }
    }

    fun queryStatusCommand(): ByteArray = command(CMD_QUERY_STATUS, ByteArray(4))

    fun unsignedIntPayload(value: Int): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    fun parseStatus(report: ByteArray): DeviceStatus? {
        val packet = parsePacket(report) ?: return null
        if (packet.type != RESPONSE_TYPE_STATUS) return null

        var offset = packet.payloadOffset
        val wrapped = packet.payloadLength == 22
        when (packet.payloadLength) {
            20 -> Unit
            22 -> {
                if (!report.hasRange(offset, 3)) return null
                if (report.u8(offset) != 1 || report.u8(offset + 2) != 0) return null
                offset += 3
            }
            else -> return null
        }

        if (!report.hasRange(offset, 15)) return null
        return DeviceStatus(
            messagePriority = report.u8(offset),
            sinkMode = report.u8(offset + 1),
            reportType = report.u8(offset + 2),
            vbusStatus = report.u8(offset + 3),
            triggerHoldStatus = report.u8(offset + 4),
            presetVoltageMv = report.u16Le(offset + 5),
            presetCurrentMa = report.u16Le(offset + 7),
            adcLogEnabled = report.u8(offset + 9) != 0,
            adcLogIntervalMs = report.u32Le(offset + 10),
            nvBackend = report.u8(offset + 14),
            triggerDelayMode = if (wrapped && report.hasRange(offset + 15, 1)) {
                report.u8(offset + 15)
            } else {
                0
            },
        )
    }

    fun parseAck(report: ByteArray): CommandAck? {
        val packet = parsePacket(report) ?: return null
        if (packet.type != RESPONSE_TYPE_STATUS || packet.payloadLength !in setOf(2, 6)) return null
        if (!report.hasRange(packet.payloadOffset, packet.payloadLength)) return null
        if (report.u8(packet.payloadOffset) != 1) return null

        val command = report.u8(packet.payloadOffset + 1)
        val value = if (packet.payloadLength == 6) {
            ByteBuffer.wrap(report, packet.payloadOffset + 2, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
        } else {
            null
        }
        return CommandAck(command, value)
    }

    private fun parsePacket(report: ByteArray): Packet? {
        if (!report.hasRange(0, 4) || report.u8(1) != RESPONSE_HEADER_1) return null
        val packet = when (report.u8(0)) {
            RESPONSE_HEADER_NEW -> {
                if (!report.hasRange(0, 12)) return null
                Packet(
                    payloadLength = report.u8(10) - 1,
                    payloadOffset = 12,
                    type = report.u8(11),
                )
            }
            RESPONSE_HEADER_LEGACY -> Packet(
                payloadLength = report.u8(2) - 1,
                payloadOffset = 4,
                type = report.u8(3),
            )
            else -> return null
        }
        return packet.takeIf {
            it.payloadLength >= 0 && report.hasRange(it.payloadOffset, it.payloadLength)
        }
    }

    private fun ByteArray.hasRange(offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset + length <= size

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u16Le(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u32Le(offset: Int): Long =
        (u8(offset).toLong()) or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)
}

