package com.pdtool.voltage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object XiaomiCsvParser {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val SLOT_SIZE = 69

    enum class ErrorReason {
        EMPTY_CSV,
        RAW_COLUMN_MISSING,
        INVALID_CSV,
        INVALID_RAW_HEX,
        MISSING_STEPS,
    }

    class ParseException(
        val reason: ErrorReason,
        message: String,
    ) : IllegalArgumentException(message)

    data class Segment(
        val label: String,
        val command: Int,
        val vdos: List<Long>,
    )

    data class Result(
        val slot0: ByteArray,
        val segments: List<Segment>,
        val needs010aResend: Boolean,
    )

    private data class RequiredStep(
        val command: Int,
        val label: String,
        val targetOffset: Int,
        val vdoCount: Int,
    )

    private val requiredSteps = listOf(
        RequiredStep(0x0201, "XM_01 response", 0, 1),
        RequiredStep(0x0104, "XM_04 request", 4, 4),
        RequiredStep(0x0105, "XM_05 request", 20, 4),
        RequiredStep(0x0205, "XM_05 response", 36, 4),
        RequiredStep(0x0108, "XM_08 request", 52, 4),
    )
    private val requiredByCommand = requiredSteps.associateBy(RequiredStep::command)

    fun parse(bytes: ByteArray): Result {
        if (bytes.isEmpty()) fail(ErrorReason.EMPTY_CSV, "CSV is empty")
        require(bytes.size <= MAX_FILE_BYTES) { "CSV exceeds the 2 MiB limit" }

        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            fail(ErrorReason.INVALID_CSV, "CSV is not valid UTF-8")
        }
        if (text.isBlank()) fail(ErrorReason.EMPTY_CSV, "CSV is empty")

        val rows = parseRows(text)
        val header = rows.firstOrNull()
            ?: fail(ErrorReason.EMPTY_CSV, "CSV is empty")
        val rawColumn = header.indexOfFirst { it.trim().equals("Raw", ignoreCase = true) }
        if (rawColumn < 0) fail(ErrorReason.RAW_COLUMN_MISSING, "CSV does not contain a Raw column")

        val found = linkedMapOf<Int, List<Long>>()
        var needs010aResend = false
        rows.drop(1).forEachIndexed { index, row ->
            val raw = row.getOrNull(rawColumn)?.trim().orEmpty()
            if (raw.isEmpty()) return@forEachIndexed
            val hex = raw.filter { it.isAsciiHexDigit() }
            if (hex.isEmpty()) return@forEachIndexed
            if (hex.length % 2 != 0) {
                fail(ErrorReason.INVALID_RAW_HEX, "Raw hex is incomplete at CSV row ${index + 2}")
            }
            val report = try {
                ByteArray(hex.length / 2) { byteIndex ->
                    hex.substring(byteIndex * 2, byteIndex * 2 + 2).toInt(16).toByte()
                }
            } catch (_: NumberFormatException) {
                fail(ErrorReason.INVALID_RAW_HEX, "Raw hex is invalid at CSV row ${index + 2}")
            }
            if (report.size < 9 || report[2].toInt() and 0xFF != 0xE0) return@forEachIndexed

            val vdos = buildList {
                var offset = 5
                while (offset + 3 < report.size) {
                    add(
                        ByteBuffer.wrap(report, offset, Int.SIZE_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int.toLong() and 0xFFFF_FFFFL,
                    )
                    offset += Int.SIZE_BYTES
                }
            }
            val first = vdos.firstOrNull() ?: return@forEachIndexed
            if ((first ushr 16).toInt() != XIAOMI_VENDOR_ID) return@forEachIndexed
            val command = (first and 0xFFFF).toInt()
            if (command == CMD_010A) needs010aResend = true
            val step = requiredByCommand[command] ?: return@forEachIndexed
            if (command !in found && vdos.size >= step.vdoCount) {
                found[command] = vdos.take(step.vdoCount)
            }
        }

        val missing = requiredSteps.filterNot { it.command in found }
        if (missing.isNotEmpty()) {
            fail(
                ErrorReason.MISSING_STEPS,
                "Missing required Xiaomi messages: ${missing.joinToString { it.label }}",
            )
        }

        val slot0 = ByteArray(SLOT_SIZE)
        val segments = requiredSteps.map { step ->
            val vdos = checkNotNull(found[step.command])
            vdos.forEachIndexed { index, vdo ->
                val offset = step.targetOffset + index * Int.SIZE_BYTES
                slot0[offset] = (vdo ushr 24).toByte()
                slot0[offset + 1] = (vdo ushr 16).toByte()
                slot0[offset + 2] = (vdo ushr 8).toByte()
                slot0[offset + 3] = vdo.toByte()
            }
            Segment(step.label, step.command, vdos)
        }
        slot0[68] = if (needs010aResend) 1 else 0
        return Result(slot0, segments, needs010aResend)
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.setLength(0)
            afterQuote = false
        }
        fun finishRow() {
            finishField()
            rows += row.toList()
            row.clear()
        }

        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = false
                        afterQuote = true
                    }
                } else {
                    field.append(char)
                }
            } else {
                when {
                    char == '"' && field.isEmpty() && !afterQuote -> quoted = true
                    char == ',' -> finishField()
                    char == '\r' || char == '\n' -> {
                        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                        finishRow()
                    }
                    afterQuote && char.isWhitespace() -> Unit
                    afterQuote -> fail(ErrorReason.INVALID_CSV, "Unexpected character after quoted CSV field")
                    else -> field.append(char)
                }
            }
            index++
        }
        if (quoted) fail(ErrorReason.INVALID_CSV, "CSV contains an unterminated quoted field")
        if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) finishRow()
        while (rows.lastOrNull()?.all(String::isEmpty) == true) rows.removeAt(rows.lastIndex)
        return rows
    }

    private fun Char.isAsciiHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun fail(reason: ErrorReason, message: String): Nothing = throw ParseException(reason, message)

    private const val XIAOMI_VENDOR_ID = 10007
    private const val CMD_010A = 0x010A
}
