package com.pdtool.voltage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiCsvParserTest {
    @Test
    fun parsesXiaomiCaptureIntoWebCompatibleSlot0() {
        val csv = """
            序号,详细数据,Raw
            1,"quoted, field","FE 07 E0 00 00 01 02 17 27"
            2,test,"FE17E00000040117270C47B756CA226F9D32403E00B57EA76F"
            3,test,"FE17E00000050117272D1E064E8AD292A9F241EBFB"
            4,test,"FE17E0000005021727D442B759FA696FF57821CB25"
            5,test,"FE17E0000008011727FA970F4DF3DFF06816435D48"
            6,test,"FE07E000000A011727"
        """.trimIndent().toByteArray()

        val result = XiaomiCsvParser.parse(csv)

        assertEquals(5, result.segments.size)
        assertTrue(result.needs010aResend)
        assertArrayEquals(EXPECTED_SLOT0_WITH_010A.hexBytes(), result.slot0)
        assertEquals(0x0288, MippsProtocol.crc16CcittFalse(result.slot0))
    }

    @Test
    fun reportsMissingRequiredMessages() {
        val error = runCatching {
            XiaomiCsvParser.parse("Raw\nFE07E0000001021727\n".toByteArray())
        }.exceptionOrNull() as XiaomiCsvParser.ParseException

        assertEquals(XiaomiCsvParser.ErrorReason.MISSING_STEPS, error.reason)
        assertTrue(error.message.orEmpty().contains("XM_04"))
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val EXPECTED_SLOT0_WITH_010A =
            "271702012717010456B7470C9D6F22CA003E4032" +
                "271701054E061E2DA992D28AFBEB41F2" +
                "2717020559B742D4F56F69FA25CB2178" +
                "271701084D0F97FA68F0DFF3485D431601"
    }
}
