package com.pdtool.voltage

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

class UsbHidSession private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val hidInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint,
) : Closeable {

    fun queryStatus(timeoutMs: Long = 1_500): PdUsbProtocol.DeviceStatus {
        send(PdUsbProtocol.queryStatusCommand(), timeoutMs)
        return waitFor(timeoutMs) { PdUsbProtocol.parseStatus(it) }
            ?: throw TimeoutException("PD status response timeout")
    }

    fun applyPreset(voltageMv: Int, currentMa: Int, timeoutMs: Long = 2_000) {
        sendAndWaitForAck(PdUsbProtocol.CMD_APPLY_PRESET, byteArrayOf(), timeoutMs)
        sendAndWaitForAck(
            PdUsbProtocol.CMD_SET_VOLTAGE,
            PdUsbProtocol.unsignedIntPayload(voltageMv),
            timeoutMs,
        )
        sendAndWaitForAck(
            PdUsbProtocol.CMD_SET_CURRENT,
            PdUsbProtocol.unsignedIntPayload(currentMa),
            timeoutMs,
        )
    }

    fun sendSystemCommand(
        command: Int,
        value: Int? = null,
        waitForAck: Boolean = true,
        timeoutMs: Long = 2_000,
    ) {
        val payload = value?.let(PdUsbProtocol::unsignedIntPayload) ?: byteArrayOf()
        if (waitForAck) {
            sendAndWaitForAck(command, payload, timeoutMs)
        } else {
            send(PdUsbProtocol.command(command, payload), timeoutMs)
        }
    }

    fun enterBootloader(timeoutMs: Long = 2_000) {
        send(PdUsbProtocol.command(PdUsbProtocol.CMD_ENTER_BOOTLOADER), timeoutMs)
    }

    fun queryBootloaderInfo(timeoutMs: Long = 2_000): FirmwareProtocol.BootloaderInfo {
        val response = bootloaderTransaction(FirmwareProtocol.CMD_BOOTLOADER_INFO, timeoutMs = timeoutMs)
        return FirmwareProtocol.parseBootloaderInfo(response)
    }

    fun exitBootloader(timeoutMs: Long = 2_000) {
        send(FirmwareProtocol.command(FirmwareProtocol.CMD_EXIT_BOOTLOADER), timeoutMs)
    }

    fun flashFirmware(
        image: FirmwareProtocol.Image,
        expectedHardwareType: String,
        onProgress: (Int) -> Unit,
    ) {
        val info = queryBootloaderInfo()
        require(info.isSupported) { "Bootloader ${info.version} is older than required 1.3.0" }
        require(info.hardwareType == null || info.hardwareType == expectedHardwareType) {
            "Bootloader hardware ${info.hardwareType} does not match $expectedHardwareType"
        }
        require(image.hardwareType == null || image.hardwareType == expectedHardwareType) {
            "Firmware hardware ${image.hardwareType} does not match $expectedHardwareType"
        }

        FirmwareProtocol.requireSuccess(
            bootloaderTransaction(
                FirmwareProtocol.CMD_IAP_START,
                FirmwareProtocol.startPayload(image),
                timeoutMs = 10_000,
            ),
            "IAP start",
        )
        onProgress(0)

        var offset = 0
        while (offset < image.payload.size) {
            val end = minOf(offset + FirmwareProtocol.DATA_CHUNK_SIZE, image.payload.size)
            val chunk = image.payload.copyOfRange(offset, end)
            FirmwareProtocol.requireSuccess(
                bootloaderTransaction(
                    FirmwareProtocol.CMD_IAP_DATA,
                    FirmwareProtocol.dataPayload(offset, chunk),
                ),
                "IAP data at 0x${offset.toString(16).uppercase()}",
            )
            offset = end
            onProgress(offset * 100 / image.payload.size)
        }

        val finish = bootloaderTransaction(FirmwareProtocol.CMD_IAP_FINISH, timeoutMs = 10_000)
        if (finish.status == FirmwareProtocol.STATUS_CRC_FAILED) {
            error("Device CRC32 verification failed")
        }
        FirmwareProtocol.requireSuccess(finish, "IAP finish")
        onProgress(100)
    }

    private fun bootloaderTransaction(
        command: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Long = 5_000,
    ): FirmwareProtocol.Response {
        send(FirmwareProtocol.command(command, payload), timeoutMs)
        return FirmwareProtocol.parseResponse(receive(timeoutMs), command)
    }

    private fun sendAndWaitForAck(command: Int, payload: ByteArray, timeoutMs: Long) {
        send(PdUsbProtocol.command(command, payload), timeoutMs)
        val ack = waitFor(timeoutMs) { report ->
            PdUsbProtocol.parseAck(report)?.takeIf { it.command == command }
        }
        if (ack == null) throw TimeoutException("ACK timeout for command 0x${command.toString(16)}")
    }

    private fun send(report: ByteArray, timeoutMs: Long) {
        require(report.size == PdUsbProtocol.REPORT_SIZE)
        transfer(outputEndpoint, report, timeoutMs)
    }

    private fun receive(timeoutMs: Long): ByteArray {
        val buffer = ByteBuffer.allocateDirect(inputEndpoint.maxPacketSize)
        val request = UsbRequest()
        check(request.initialize(connection, inputEndpoint)) { "Unable to initialize HID IN request" }
        try {
            check(request.queue(buffer)) { "Unable to queue HID IN request" }
            val completed = connection.requestWait(timeoutMs)
            if (completed !== request) throw TimeoutException("HID IN transfer timeout")
            val length = buffer.position()
            buffer.flip()
            return ByteArray(length).also { buffer.get(it) }
        } finally {
            request.close()
        }
    }

    private fun transfer(endpoint: UsbEndpoint, data: ByteArray, timeoutMs: Long) {
        val buffer = ByteBuffer.allocateDirect(data.size)
        buffer.put(data)
        buffer.flip()
        val request = UsbRequest()
        check(request.initialize(connection, endpoint)) { "Unable to initialize HID OUT request" }
        try {
            check(request.queue(buffer)) { "Unable to queue HID OUT request" }
            val completed = connection.requestWait(timeoutMs)
            if (completed !== request) throw TimeoutException("HID OUT transfer timeout")
        } finally {
            request.close()
        }
    }

    private fun <T> waitFor(timeoutMs: Long, parser: (ByteArray) -> T?): T? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val remainingMs = (remainingNanos / 1_000_000).coerceAtLeast(1)
            val result = parser(receive(remainingMs))
            if (result != null) return result
        }
    }

    override fun close() {
        connection.releaseInterface(hidInterface)
        connection.close()
    }

    companion object {
        fun open(usbManager: UsbManager, device: UsbDevice): UsbHidSession {
            check(usbManager.hasPermission(device)) { "USB permission has not been granted" }

            val hidInterface = (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                ?: error("No HID interface found")

            var inputEndpoint: UsbEndpoint? = null
            var outputEndpoint: UsbEndpoint? = null
            for (index in 0 until hidInterface.endpointCount) {
                val endpoint = hidInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> inputEndpoint = endpoint
                    UsbConstants.USB_DIR_OUT -> outputEndpoint = endpoint
                }
            }

            val input = inputEndpoint ?: error("No interrupt IN endpoint found")
            val output = outputEndpoint ?: error("No interrupt OUT endpoint found")
            check(input.maxPacketSize >= PdUsbProtocol.REPORT_SIZE) { "HID IN report is smaller than 64 bytes" }
            check(output.maxPacketSize >= PdUsbProtocol.REPORT_SIZE) { "HID OUT report is smaller than 64 bytes" }

            val connection = usbManager.openDevice(device) ?: error("Unable to open USB device")
            if (!connection.claimInterface(hidInterface, true)) {
                connection.close()
                error("Unable to claim HID interface")
            }

            return UsbHidSession(device, connection, hidInterface, input, output)
        }
    }
}

