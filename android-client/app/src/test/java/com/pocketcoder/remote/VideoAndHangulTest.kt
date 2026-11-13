package com.pocketcoder.remote

import com.pocketcoder.protocol.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class VideoAndHangulTest {
    @Test fun h264WirePreservesPacketBoundariesAndRejectsTruncation() {
        val header = VideoHeader(1280, 720, 60)
        val bytes = ByteArrayOutputStream().also { raw ->
            val out = DataOutputStream(raw)
            writeVideoHeader(out, header)
            writeVideoPacket(out, byteArrayOf(0, 0, 0, 1, 9, 16), 123L)
            writeVideoPacket(out, byteArrayOf(0, 0, 1, 65, 5), 456L)
        }.toByteArray()
        val reader = VideoReader(bytes.inputStream())
        assertEquals(header, reader.header)
        assertEquals(123L, reader.next()?.timeUs)
        assertEquals(456L, reader.next()?.timeUs)
        assertNull(reader.next())
        val truncated = VideoReader(bytes.dropLast(1).toByteArray().inputStream())
        truncated.next()
        assertThrows(EOFException::class.java) { truncated.next() }
    }
    @Test fun accessUnitsKeepSpsPpsAndIdrTogether() {
        val nals = listOf(byteArrayOf(9, 16), byteArrayOf(103, 1, 2), byteArrayOf(104, 3), byteArrayOf(101, 4, 5),
            byteArrayOf(9, 16), byteArrayOf(65, 6), byteArrayOf(9, 16), byteArrayOf(65, 7))
        val wire = nals.fold(byteArrayOf()) { bytes, nal -> bytes + annexB(nal) }
        val reader = AccessUnitReader(wire.inputStream())
        assertEquals(listOf(9, 7, 8, 5), nalUnits(reader.next()!!).map { it[0].toInt() and 31 })
        assertEquals(listOf(9, 1), nalUnits(reader.next()!!).map { it[0].toInt() and 31 })
        assertNotNull(reader.next())
        assertNull(reader.next())
    }
    @Test fun inputStreamDelimitsUnicodeWithoutSplittingEvents() {
        val encoded = "type=text&value=" + java.net.URLEncoder.encode("한글\n영어", "UTF-8")
        val input = (encoded + "\n" + "type=key&key=DELETE\n").byteInputStream()
        assertEquals(encoded, readInputLine(input))
        assertEquals("type=key&key=DELETE", readInputLine(input))
        assertNull(readInputLine(input))
        assertThrows(IOException::class.java) { readInputLine(("x".repeat(32769) + "\n").byteInputStream()) }
    }
    @Test fun twoSetHangulComposesFinalsAndResyllabifies() {
        fun type(keys: String) = keys.fold("") { text, key -> Hangul.append(text, Hangul.key(key.uppercaseChar(), key.isUpperCase())!!) }
        assertEquals("한글", type("gksrmf"))
        assertEquals("가나", type("rksk"))
        assertEquals("값", type("rkqt"))
        assertEquals("갑사", type("rkqtk"))
        assertEquals("곽", type("rhkr"))
        assertEquals("까", type("Rk"))
    }
    @Test fun hangulBackspaceRemovesOneCompositionStep() {
        var text = "곽"
        for (expected in listOf("과", "고", "ㄱ", "")) { text = Hangul.backspace(text); assertEquals(expected, text) }
        assertEquals("가", Hangul.backspace("가😀"))
        assertEquals("갑", Hangul.backspace("값"))
    }
    @Test fun coalescedMovementRetainsMonitorIdentity() {
        val queue = InputBuffer()
        repeat(2) { queue.offer(mapOf("type" to "move", "dx" to "2", "dy" to "0", "screen" to "1")) }
        val event = queue.next()!!
        assertEquals("1", event["screen"])
        assertEquals("4", event["dx"])
    }
}
