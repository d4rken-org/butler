package eu.darken.rar

import eu.darken.rar.RarBuilder.Companion.compression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

class CompressionInfoTest {
    @Test
    fun `version 0 uses four main bits and ignores the fraction`() {
        // Bit 14 (fifth main bit) and the fraction bits are set but must not count for version 0.
        val info = CompressionInfo(compression(version = 0, method = 3, main = 0x15, fraction = 7, solid = true))
        assertTrue(info.supportedVersion)
        assertEquals(3, info.method)
        assertTrue(info.solid)
        assertEquals(5, info.dictionaryMain)
        assertEquals(0, info.dictionaryFraction)
        assertEquals(32L shl 17, info.windowBytes)
        assertEquals(128L * 1024, CompressionInfo(compression(version = 0, method = 1)).windowBytes)
        assertEquals(4L shl 30, CompressionInfo(compression(version = 0, method = 1, main = 15)).windowBytes)
    }

    @Test
    fun `version 1 with the compat bit uses five main bits and the fraction`() {
        val info = CompressionInfo(compression(version = 1, method = 5, main = 17, fraction = 3, compat = true))
        assertTrue(info.supportedVersion)
        assertEquals(17, info.dictionaryMain)
        assertEquals(3, info.dictionaryFraction)
        assertEquals(35L shl 29, info.windowBytes)
        assertEquals(63L shl 43, CompressionInfo(compression(version = 1, method = 1, main = 31, fraction = 31, compat = true)).windowBytes)
    }

    @Test
    fun `version 1 without the compat bit and later versions are unsupported`() {
        assertFalse(CompressionInfo(compression(version = 1, method = 1, main = 20, fraction = 3)).supportedVersion)
        assertNull(CompressionInfo(compression(version = 1, method = 1)).windowBytes)
        assertFalse(CompressionInfo(compression(version = 2, method = 1, compat = true)).supportedVersion)
        // The real archive written by RAR 7.23 with -md5g: raw version 1, compat bit clear.
        assertFalse(CompressionInfo(0x1bc81).supportedVersion)
        assertEquals(1, CompressionInfo(0x1bc81).algorithmVersion)
    }

    private fun entryStreamOf(compressionInfo: Long, limits: RarLimits = RarLimits(), size: Long = 4) {
        val archive = RarBuilder().main().file("a.bin", "data".toByteArray(), compression = compressionInfo, size = size).end().build()
        RarReader(ByteArrayInputStream(archive), RarOptions(limits = limits)).use {
            it.nextEntry()
            it.entryStream()
        }
    }

    @Test
    fun `unsupported version fails when data is requested, not when listing`() {
        for (raw in listOf(compression(version = 1, method = 0), compression(version = 1, method = 3), compression(version = 2, method = 3, compat = true))) {
            val archive = RarBuilder().main().file("a.bin", "data".toByteArray(), compression = raw).end().build()
            RarReader(ByteArrayInputStream(archive)).use { reader ->
                assertEquals("a.bin", reader.nextEntry()!!.name)
                val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
                assertEquals(RarUnsupportedReason.AlgorithmVersion((raw and 0x3F).toInt()), e.reason)
                assertNull(reader.nextEntry())
            }
        }
    }

    @Test
    fun `stored data under version 1 with compat is readable`() {
        val archive = RarBuilder().main()
            .file("a.bin", "data".toByteArray(), compression = compression(version = 1, method = 0, main = 3, fraction = 9, compat = true))
            .end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            reader.nextEntry()
            assertEquals("data", String(reader.entryStream().readAllBytes()))
        }
    }

    @Test
    fun `over-large dictionary is refused with the required size`() {
        // A known-size entry needs min(window, size): the entry's size here, the window below.
        val e = assertThrows<RarUnsupportedException> {
            entryStreamOf(compression(version = 1, method = 3, main = 31, fraction = 31, compat = true), size = 300L shl 20)
        }
        assertEquals(RarUnsupportedReason.DictionaryTooLarge(300L shl 20, RarLimits().maxWindowBytes), e.reason)

        val small = RarLimits(maxWindowBytes = 64L * 1024)
        val e2 = assertThrows<RarUnsupportedException> { entryStreamOf(compression(version = 0, method = 3), small, size = 1L shl 20) }
        assertEquals(RarUnsupportedReason.DictionaryTooLarge(128L * 1024, 64L * 1024), e2.reason)
    }

    @Test
    fun `a huge declared window is fine for a small entry`() {
        // 4 bytes of garbage are not a valid compressed stream, but the dictionary is no obstacle.
        assertThrows<RarCorruptException> {
            RarReader(
                ByteArrayInputStream(
                    RarBuilder().main()
                        .file("a.bin", "data".toByteArray(), compression = compression(version = 1, method = 3, main = 31, fraction = 31, compat = true))
                        .end().build(),
                ),
            ).use {
                it.nextEntry()
                it.entryStream().readAllBytes()
            }
        }
    }

    @Test
    fun `methods above 5 are unknown`() {
        for (method in listOf(6, 7)) {
            val e = assertThrows<RarUnsupportedException> { entryStreamOf(compression(version = 0, method = method)) }
            assertEquals(RarUnsupportedReason.UnknownMethod(method), e.reason)
        }
    }
}
