package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class EntryStreamStateTest {
    private val first = "first entry content".toByteArray()
    private val second = "second".toByteArray()

    private fun archive(firstCrc: Int = RarBuilder.crc(first), emptyCrc: Int = 0) = RarBuilder().main()
        .file("first.txt", first, crc = firstCrc)
        .file("empty.txt", ByteArray(0), crc = emptyCrc)
        .file("second.txt", second)
        .end().build()

    private fun reader(bytes: ByteArray = archive(), options: RarOptions = RarOptions()) =
        RarReader(ByteArrayInputStream(bytes), options)

    /** Counts the bytes read from and skipped in the wrapped stream. */
    private class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
        var read = 0L
            private set
        var skipped = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) skipped += it }
    }

    @Test
    fun `entryStream is available once per entry`() {
        reader().use { r ->
            r.nextEntry()
            r.entryStream().close()
            assertThrows<IllegalStateException> { r.entryStream() }
            r.nextEntry()
            r.entryStream()
            assertThrows<IllegalStateException> { r.entryStream() }
        }
    }

    @Test
    fun `entryStream needs a current entry`() {
        reader().use { r ->
            assertThrows<IllegalStateException> { r.entryStream() }
            while (r.nextEntry() != null) Unit
            assertThrows<IllegalStateException> { r.entryStream() }
            assertNull(r.nextEntry())
        }
    }

    @Test
    fun `nextEntry and close invalidate the stream`() {
        reader().use { r ->
            r.nextEntry()
            val stream = r.entryStream()
            assertEquals(first[0].toInt(), stream.read())
            r.nextEntry()
            assertThrows<IOException> { stream.read() }
            assertThrows<IOException> { stream.read(ByteArray(4)) }
            assertThrows<IOException> { stream.skip(1) }
        }
        val r = reader()
        r.nextEntry()
        val stream = r.entryStream()
        r.close()
        assertThrows<IOException> { stream.read() }
    }

    @Test
    fun `reads after closing the entry stream fail`() {
        reader().use { r ->
            r.nextEntry()
            val stream = r.entryStream()
            stream.close()
            assertThrows<IOException> { stream.read() }
        }
    }

    @Test
    fun `closing the entry stream neither closes nor drains the reader`() {
        val counting = CountingInputStream(ByteArrayInputStream(archive()))
        val tracking = TrackingInputStream(counting)
        RarReader(tracking).use { r ->
            assertEquals(first.size.toLong(), r.nextEntry()!!.size)
            val stream = r.entryStream()
            assertEquals(3, stream.read(ByteArray(3)))
            val read = counting.read
            val skipped = counting.skipped
            stream.close()
            assertEquals(read, counting.read, "bytes read from the archive")
            assertEquals(skipped, counting.skipped, "bytes skipped in the archive")
            assertFalse(tracking.closed)
            assertEquals("empty.txt", r.nextEntry()!!.name)
            assertEquals("second.txt", r.nextEntry()!!.name)
            assertArrayEquals(second, r.entryStream().readAllBytes())
        }
        assertTrue(tracking.closed)
    }

    @Test
    fun `checksum is verified on the read that delivers the last byte`() {
        reader(archive(firstCrc = 0x12345678)).use { r ->
            r.nextEntry()
            val stream = r.entryStream()
            val buffer = ByteArray(first.size)
            assertEquals(first.size - 1, stream.read(buffer, 0, first.size - 1))
            assertThrows<RarCorruptException> { stream.read(buffer, first.size - 1, 1) }
        }
        // A caller that stops after exactly `size` bytes is still verified.
        reader(archive(firstCrc = 0x12345678)).use { r ->
            r.nextEntry()
            assertThrows<RarCorruptException> { r.entryStream().readNBytes(first.size) }
        }
        reader().use { r ->
            r.nextEntry()
            assertArrayEquals(first, r.entryStream().readNBytes(first.size))
        }
    }

    @Test
    fun `an empty entry verifies on its first read`() {
        reader(archive(emptyCrc = 1)).use { r ->
            r.nextEntry()
            r.nextEntry()
            assertThrows<RarCorruptException> { r.entryStream().read() }
        }
        reader().use { r ->
            r.nextEntry()
            r.nextEntry()
            assertEquals(-1, r.entryStream().read())
        }
    }

    @Test
    fun `skipped data is not verified`() {
        reader(archive(firstCrc = 0x12345678)).use { r ->
            r.nextEntry()
            r.entryStream().read(ByteArray(2))
            assertEquals("empty.txt", r.nextEntry()!!.name)
            assertEquals("second.txt", r.nextEntry()!!.name)
            assertArrayEquals(second, r.entryStream().readAllBytes())
        }
    }

    @Test
    fun `skip on the entry stream reads and discards`() {
        reader().use { r ->
            r.nextEntry()
            val stream = r.entryStream()
            assertEquals(6, stream.skip(6))
            assertEquals(first.copyOfRange(6, first.size).toList(), stream.readAllBytes().toList())
            assertEquals(0, stream.skip(5))
        }
        reader(archive(firstCrc = 0x12345678)).use { r ->
            r.nextEntry()
            assertThrows<RarCorruptException> { r.entryStream().skip(first.size.toLong()) }
        }
    }

    @Test
    fun `corruption poisons the reader`() {
        val r = reader(archive(firstCrc = 0x12345678))
        r.nextEntry()
        assertThrows<RarCorruptException> { r.entryStream().readAllBytes() }
        assertThrows<IOException> { r.nextEntry() }
        assertThrows<IOException> { r.entryStream() }
        r.close()
        r.close()
    }

    @Test
    fun `corrupt header poisons the reader`() {
        val bytes = RarBuilder().main().file("a.txt", "a".toByteArray())
            .block(type = HeaderType.FILE, fields = ByteArray(8), crcDelta = 1).end().build()
        reader(bytes).use { r ->
            r.nextEntry()
            assertThrows<RarCorruptException> { r.nextEntry() }
            assertThrows<IOException> { r.nextEntry() }
        }
    }

    @Test
    fun `exceptions from the caller's stream propagate unchanged and poison`() {
        val boom = IOException("disk gone")
        val bytes = archive()
        val failing = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            var position = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= bytes.size - 20) throw boom
                val n = super.read(b, off, minOf(len, bytes.size - 20 - position))
                if (n > 0) position += n
                return n
            }

            // Every byte goes through read(), so the failure point is reached.
            override fun skip(n: Long): Long = 0
        }
        RarReader(failing).use { r ->
            val thrown = assertThrows<IOException> { while (r.nextEntry() != null) Unit }
            assertSame(boom, thrown)
            assertThrows<IOException> { r.nextEntry() }
        }
    }

    @Test
    fun `cancellation propagates unchanged`() {
        class Cancelled : RuntimeException()

        var calls = 0
        var cancelAfter = Int.MAX_VALUE
        val options = RarOptions(cancellation = { if (++calls > cancelAfter) throw Cancelled() })
        reader(options = options).use { r ->
            r.nextEntry()
            val stream = r.entryStream()
            cancelAfter = calls
            assertThrows<Cancelled> { stream.read() }
            assertThrows<IOException> { r.nextEntry() }
        }
        calls = 0
        cancelAfter = 0
        assertThrows<Cancelled> { reader(options = options) }
    }

    @Test
    fun `password and unsupported errors do not poison`() {
        RarReader(Fixtures.stream("rar723/encrypted-stored.rar"), RarOptions(passwordProvider = null)).use { r ->
            val entry = r.nextEntry()!!
            val e = assertThrows<RarPasswordRequiredException> { r.entryStream() }
            assertEquals(RarPasswordScope.ENTRY, e.scope)
            assertEquals(entry.ordinal, e.ordinal)
            assertEquals(entry.name, e.entryName)
            assertEquals("numbers.txt", r.nextEntry()!!.name)
            assertNull(r.nextEntry())
        }
        val unsupported = RarBuilder().main()
            .file("new.bin", "data".toByteArray(), compression = RarBuilder.compression(version = 2, method = 3, compat = true))
            .file("after.txt", "after".toByteArray())
            .end().build()
        RarReader(ByteArrayInputStream(unsupported)).use { r ->
            assertEquals("new.bin", r.nextEntry()!!.name)
            assertThrows<RarUnsupportedException> { r.entryStream() }
            assertEquals("after.txt", r.nextEntry()!!.name)
            assertEquals("after", String(r.entryStream().readAllBytes()))
        }
    }

    @Test
    fun `the reader closes its stream`() {
        val ok = TrackingInputStream(ByteArrayInputStream(archive()))
        RarReader(ok).close()
        assertTrue(ok.closed)

        val encrypted = TrackingInputStream(Fixtures.stream("rar723/encrypted-headers.rar"))
        assertThrows<RarPasswordRequiredException> { RarReader(encrypted, RarOptions(passwordProvider = null)) }
        assertTrue(encrypted.closed)

        val truncated = TrackingInputStream(ByteArrayInputStream(archive().copyOf(10)))
        assertThrows<RarCorruptException> { RarReader(truncated) }
        assertTrue(truncated.closed)
    }

    @Test
    fun `calls after close fail`() {
        val r = reader()
        r.close()
        assertThrows<IOException> { r.nextEntry() }
        assertThrows<IOException> { r.entryStream() }
    }

    @Test
    fun `premature end of entry data is corrupt`() {
        val bytes = archive()
        val cut = RarBuilder().main().build().size + 40
        reader(bytes.copyOf(cut)).use { r ->
            r.nextEntry()
            assertThrows<RarCorruptException> { r.entryStream().readAllBytes() }
        }
    }

    @Test
    fun `missing end-of-archive header is corrupt`() {
        val bytes = RarBuilder().main().file("a.txt", "a".toByteArray()).build()
        reader(bytes).use { r ->
            r.nextEntry()
            assertThrows<RarCorruptException> { r.nextEntry() }
        }
    }

    @Test
    fun `listOnly has no entry streams`() {
        for (name in listOf("solid.rar", "encrypted-data.rar", "stored.rar")) {
            RarReader(Fixtures.stream("rar723/$name"), RarOptions(listOnly = true)).use { r ->
                var count = 0
                while (r.nextEntry() != null) {
                    count++
                    assertThrows<IllegalStateException> { r.entryStream() }
                }
                assertEquals(13, count, name)
            }
        }
    }

    @Test
    fun `the end of the archive is sticky`() {
        reader().use { r ->
            repeat(3) { assertTrue(r.nextEntry() != null) }
            assertNull(r.nextEntry())
            assertNull(r.nextEntry())
        }
    }
}
