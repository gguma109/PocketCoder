package com.pocketcoder.remote

import com.pocketcoder.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class AllFeaturesTest {
    @Test fun controlHandOffExpiryAndOldSessionReleaseAreSafe() {
        var time = 0L; var releases = 0
        val lease = ControlLease({ time }, { releases++ })
        assertTrue(lease.join("phone", "S25").mine)
        assertFalse(lease.join("tablet", "Tablet").mine)
        assertFalse(lease.allows("tablet"))
        assertTrue(lease.claim("tablet", "Tablet").mine)
        assertEquals(1, releases)
        lease.release("phone")
        assertTrue(lease.allows("tablet"))
        time = 12001
        assertFalse(lease.allows("tablet"))
        assertEquals(2, releases)
        assertTrue(lease.join("phone2", "S25").mine)
    }
    @Test fun qrRejectsPublicAddressesCredentialsAndForeignLinks() {
        assertEquals("100.82.90.67:8765", parseConnectionQr("pocketcoder://connect?host=100.82.90.67&port=8765&v=1"))
        listOf("https://evil.example/", "pocketcoder://connect?host=8.8.8.8", "pocketcoder://connect?host=100.82.90.67&token=secret", "pocketcoder://connect?host=100.999.0.1").forEach {
            assertTrue(runCatching { parseConnectionQr(it) }.isFailure)
        }
    }
    @Test fun audioProtocolValidatesPcmFramesAndPreservesSamples() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeAudioHeader(out, AudioHeader(48000))
        out.writeInt(4); out.write(byteArrayOf(1, 2, 3, 4))
        val reader = AudioReader(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(48000, reader.header.rate)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reader.next())
        val bad = ByteArrayOutputStream(); val output = DataOutputStream(bad)
        writeAudioHeader(output, AudioHeader(48000)); output.writeInt(3)
        assertTrue(runCatching { AudioReader(ByteArrayInputStream(bad.toByteArray())).next() }.isFailure)
    }
    @Test fun profilesAreBoundedAndNativeKeepsResolution() {
        assertEquals(8192, VideoProfile().maxEdge)
        assertEquals(1280, VideoProfile(30, "data", 5).maxEdge)
        assertTrue(runCatching { VideoProfile(999, "native", 40) }.isFailure)
        assertTrue(runCatching { VideoProfile(60, "foreign", 40) }.isFailure)
    }
}
