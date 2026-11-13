package com.pocketcoder.remote

import com.pocketcoder.protocol.InputBuffer
import com.pocketcoder.protocol.MjpegReader
import com.pocketcoder.protocol.writeMjpegFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProtocolTest {
    @Test fun fragmentedFramesPreserveBinaryDataAndBoundaries() {
        // Include boundary-looking bytes in the payload; length framing must preserve them.
        val payload = byteArrayOf(-1, -40, 0, 13, 10) + "--pocketcoder-frame".toByteArray() + byteArrayOf(-1, -39)
        val wire = ByteArrayOutputStream().also { repeat(3) { _ -> writeMjpegFrame(it, payload) } }.toByteArray()
        val fragmented = object : java.io.FilterInputStream(wire.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 3))
        }
        val reader = MjpegReader(fragmented)
        repeat(3) { assertArrayEquals(payload, reader.nextFrame()) }
        assertNull(reader.nextFrame())
    }

    @Test fun malformedAndOversizedFramesAreRejected() {
        for (length in listOf("-1", "0", "2097153", "99999999999999", "no-number")) {
            val wire = "--pocketcoder-frame\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\n"
            assertThrows(IOException::class.java) { MjpegReader(wire.byteInputStream()).nextFrame() }
        }
        val wire = "--pocketcoder-frame\r\nContent-Type: image/jpeg\r\nContent-Length: 20\r\n\r\nabc"
        assertThrows(IOException::class.java) { MjpegReader(wire.byteInputStream()).nextFrame() }
    }

    @Test fun movementMergesWithoutCrossingClicksOrKeys() {
        val buffer = InputBuffer()
        repeat(20) { buffer.offer(mapOf("type" to "move", "dx" to "2", "dy" to "-1")) }
        buffer.offer(mapOf("type" to "click", "button" to "left"))
        buffer.offer(mapOf("type" to "move", "dx" to "3", "dy" to "4"))
        buffer.offer(mapOf("type" to "key", "key" to "ENTER"))
        assertEquals(mapOf("type" to "move", "dx" to "40", "dy" to "-20"), buffer.next())
        assertEquals("click", buffer.next()?.get("type"))
        assertEquals("3", buffer.next()?.get("dx"))
        assertEquals("key", buffer.next()?.get("type"))
        assertNull(buffer.next())
    }

    @Test fun slowLinkExpiresMotionButKeepsDiscreteInputAndBoundsQueue() {
        var time = 0L
        val buffer = InputBuffer { time }
        buffer.offer(mapOf("type" to "move", "dx" to "20", "dy" to "0"))
        buffer.offer(mapOf("type" to "text", "value" to "한글"))
        time = 300
        assertEquals("text", buffer.next()?.get("type"))
        assertNull(buffer.next())
        repeat(64) { assertTrue(buffer.offer(mapOf("type" to "key", "key" to "A"))) }
        assertFalse(buffer.offer(mapOf("type" to "key", "key" to "B")))
        assertTrue(buffer.startDrain())
        assertFalse(buffer.startDrain())
        repeat(64) { assertEquals("A", buffer.next()?.get("key")) }
        assertNull(buffer.next())
        assertTrue(buffer.startDrain())
    }
}
