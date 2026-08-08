package com.pdtool.voltage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MippsProtocolTest {
    @Test
    fun buildsStartDataFinishAndApplyReports() {
        val start = MippsProtocol.startCommand()
        assertArrayEquals(
            byteArrayOf(0x52, 0xFF.toByte(), 5, 0x03, 0x00, 0x00, 69, 0),
            start.copyOf(8),
        )

        val data = MippsProtocol.dataCommand(48, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertArrayEquals(
            byteArrayOf(0x52, 0xFF.toByte(), 7, 0x03, 0x01, 0x00, 48, 0, 0xAA.toByte(), 0xBB.toByte()),
            data.copyOf(10),
        )

        assertArrayEquals(
            byteArrayOf(0x52, 0xFF.toByte(), 5, 0x03, 0x02, 0x00, 0xA9.toByte(), 0x12),
            MippsProtocol.finishCommand(0x12A9).copyOf(8),
        )
        assertArrayEquals(
            byteArrayOf(0x52, 0xFF.toByte(), 4, 0x03, 0x08, 0x00, 0x01),
            MippsProtocol.applyCommand().copyOf(7),
        )
    }

    @Test
    fun parsesLegacyAndNewResponses() {
        val legacy = ByteArray(64).apply {
            this[0] = 0xA2.toByte(); this[1] = 0xFF.toByte(); this[2] = 5; this[3] = 0x02
            this[4] = 0x02; this[5] = 0x01; this[6] = 0x00; this[7] = 0x00
        }
        assertEquals(0, MippsProtocol.parseResponse(legacy, MippsProtocol.OP_DATA)?.status)

        val modern = ByteArray(64).apply {
            this[0] = 0xA1.toByte(); this[1] = 0xFF.toByte(); this[10] = 5; this[11] = 0x02
            this[12] = 0x02; this[13] = 0x08; this[14] = 0x00; this[15] = 0x03
        }
        assertEquals(3, MippsProtocol.parseResponse(modern, MippsProtocol.OP_APPLY)?.status)
        assertNull(MippsProtocol.parseResponse(modern, MippsProtocol.OP_FINISH))
    }

    @Test
    fun crcMatchesPublicMdy14edBaseline() {
        assertEquals(0x12A9, MippsProtocol.crc16CcittFalse(PUBLIC_SAMPLE_SLOT0.hexBytes()))
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val PUBLIC_SAMPLE_SLOT0 =
            "271702012717010456B7470C9D6F22CA003E4032" +
                "271701054E061E2DA992D28AFBEB41F2" +
                "2717020559B742D4F56F69FA25CB2178" +
                "271701084D0F97FA68F0DFF3485D431600"
    }
}
