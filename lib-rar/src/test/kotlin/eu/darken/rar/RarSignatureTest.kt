package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

class RarSignatureTest {
    private val rar5 = RarBuilder.SIGNATURE
    private val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

    @Test
    fun `detects RAR5, RAR4 and everything else`() {
        assertEquals(RarSignature.Kind.RAR5, RarSignature.detect(rar5))
        assertEquals(RarSignature.Kind.RAR4, RarSignature.detect(rar4))
        assertEquals(RarSignature.Kind.RAR4, RarSignature.detect(rar4 + 0x00))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect(rar5.copyOf(7)))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect(rar5, length = 7))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect(ByteArray(0)))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect("PK\u0003\u0004 not a rar".toByteArray()))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect(rar5.copyOf().also { it[7] = 1 }))
        assertEquals(RarSignature.Kind.UNKNOWN, RarSignature.detect(rar5.copyOf().also { it[6] = 2 }))
    }

    @Test
    fun `RAR4 archive is refused`() {
        val stream = TrackingInputStream(Fixtures.stream("libarchive/test_read_format_rar.rar"))
        val e = assertThrows<RarUnsupportedException> { RarReader(stream) }
        assertEquals(RarUnsupportedReason.Rar4, e.reason)
        assertTrue(stream.closed)
    }

    @Test
    fun `non-RAR input is corrupt`() {
        for (input in listOf(ByteArray(0), rar5.copyOf(5), "just some text that is long enough".toByteArray())) {
            val stream = TrackingInputStream(ByteArrayInputStream(input))
            assertThrows<RarCorruptException> { RarReader(stream) }
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `SFX executables are not scanned for an archive`() {
        assertThrows<RarCorruptException> { RarReader(Fixtures.stream("libarchive/test_read_format_rar5_sfx.exe")) }
    }

    @Test
    fun `signature without headers is corrupt`() {
        assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(rar5)) }
    }
}
