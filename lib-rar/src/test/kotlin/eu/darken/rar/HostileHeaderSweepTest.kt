package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * How a hostile-input run walks an archive: read every entry, list with `listOnly`, or only call
 * `nextEntry()`. Public because test methods take it as a parameter.
 */
enum class ReadMode { READ, LIST_ONLY, SKIP }

internal fun RarOptions.forMode(mode: ReadMode): RarOptions = if (mode == ReadMode.LIST_ONLY) copy(listOnly = true) else this

/** Walks an archive in a [ReadMode]; anything but success or a RarException is a failure. */
internal object HostileRun {
    fun exercise(
        bytes: ByteArray,
        options: RarOptions = RarOptions(),
        derivation: Rar5KeyDerivation = Rar5Crypto.DERIVATION,
        mode: ReadMode = ReadMode.READ,
        wrap: (InputStream) -> InputStream = { it },
    ): Throwable? = try {
        RarReader(wrap(ByteArrayInputStream(bytes)), options.forMode(mode), Rar5Window.DEFAULT_CHUNK_SIZE, derivation).use { reader ->
            drive(reader, mode, ByteArray(8192))
        }
        null
    } catch (e: RarException) {
        null
    } catch (t: Throwable) {
        t
    }

    /**
     * Calls `nextEntry()` until the end and, in [ReadMode.READ], reads every entry's data into [buffer].
     * An entry's RarException does not end the walk unless it left the reader failed; then it is
     * what the walk throws.
     */
    fun drive(reader: RarReader, mode: ReadMode, buffer: ByteArray) {
        var entryFailure: RarException? = null
        while (true) {
            val entry = try {
                reader.nextEntry()
            } catch (e: IOException) {
                val earlier = entryFailure
                if (earlier != null && e !is RarException && e.cause === earlier) throw earlier
                throw e
            }
            if (entry == null) return
            entryFailure = null
            if (mode != ReadMode.READ) continue
            try {
                reader.entryStream().use { stream -> while (stream.read(buffer) >= 0) Unit }
            } catch (e: RarException) {
                entryFailure = e
            }
        }
    }

    /** Entry names and contents (null where data is unavailable), for comparing stream behaviours. */
    fun contents(stream: InputStream, readData: Boolean = true): List<Pair<String, ByteArray?>> =
        RarReader(stream).use { reader ->
            generateSequence { reader.nextEntry() }.map { entry ->
                val data = if (!readData) {
                    null
                } else {
                    try {
                        reader.entryStream().use { it.readAllBytes() }
                    } catch (e: RarPasswordRequiredException) {
                        null
                    } catch (e: RarUnsupportedException) {
                        null
                    }
                }
                entry.name to data
            }.toList()
        }
}

@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HostileHeaderSweepTest {
    private fun assertNoForeignFailures(label: String, inputs: Sequence<Pair<String, ByteArray>>) {
        val failures = inputs.mapNotNull { (what, bytes) -> HostileRun.exercise(bytes)?.let { "$what: $it" } }.take(10).toList()
        assertTrue(failures.isEmpty(), "$label:\n${failures.joinToString("\n")}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["symlinks.rar", "per-file-passwords.rar", "comment.rar", "hardlinks.rar"])
    fun `truncation at every offset`(name: String) {
        val bytes = Fixtures.bytes("rar723/$name")
        assertNoForeignFailures(name, (0 until bytes.size).asSequence().map { "length $it" to bytes.copyOf(it) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["symlinks.rar", "per-file-passwords.rar", "comment.rar", "hardlinks.rar"])
    fun `single bit flips`(name: String) {
        val bytes = Fixtures.bytes("rar723/$name")
        val flips = (0 until bytes.size * 8).asSequence().map { bit ->
            "bit $bit" to bytes.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
        }
        assertNoForeignFailures(name, flips)
    }

    @Test
    fun `bit flips with header CRCs recomputed reach the field parsers`() {
        // Flipping a header byte normally just breaks its CRC. Re-sealing the CRC after the flip lets
        // the damaged fields themselves reach the parsers.
        val bytes = Fixtures.bytes("rar723/symlinks.rar")
        val blocks = blockRanges(bytes)
        val inputs = blocks.asSequence().flatMap { (start, end) ->
            (start + 4 until end).asSequence().flatMap { offset ->
                (0 until 8).asSequence().map { bit ->
                    val copy = bytes.copyOf()
                    copy[offset] = (copy[offset].toInt() xor (1 shl bit)).toByte()
                    val crc = RarBuilder.crc(copy.copyOfRange(start + 4, end))
                    RarBuilder.le32(crc).copyInto(copy, start)
                    "offset $offset bit $bit" to copy
                }
            }
        }
        assertNoForeignFailures("symlinks.rar resealed", inputs)
    }

    /** [start, end) of each base block's CRC, size field and header, found by walking the original archive. */
    private fun blockRanges(bytes: ByteArray): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var pos = 8
        while (pos + 4 < bytes.size) {
            var size = 0L
            var len = 0
            while (true) {
                val b = bytes[pos + 4 + len].toInt() and 0xFF
                size = size or ((b and 0x7F).toLong() shl (7 * len))
                len++
                if (b and 0x80 == 0) break
            }
            val end = pos + 4 + len + size.toInt()
            val cursor = HeaderCursor(bytes, pos + 4 + len, end)
            cursor.vint()
            val flags = cursor.vint()
            if (flags and HeaderFlags.EXTRA_DATA != 0L) cursor.vint()
            val data = if (flags and HeaderFlags.DATA != 0L) cursor.vint() else 0L
            ranges += pos to end
            pos = end + data.toInt()
        }
        return ranges
    }

    @Test
    fun `one byte per read gives the same result`() {
        for (name in listOf("stored.rar", "per-file-passwords.rar", "symlinks.rar")) {
            val bytes = Fixtures.bytes("rar723/$name")
            val normal = HostileRun.contents(ByteArrayInputStream(bytes))
            val slow = HostileRun.contents(OneByteInputStream(ByteArrayInputStream(bytes)))
            assertEquals(normal.map { it.first }, slow.map { it.first }, name)
            normal.zip(slow).forEach { (a, b) -> assertArrayEquals(a.second, b.second, "$name ${a.first}") }
        }
    }

    @Test
    fun `a stream whose skip never progresses still lists`() {
        for (name in listOf("stored.rar", "m3.rar", "dict-128k.rar", "comment.rar")) {
            val bytes = Fixtures.bytes("rar723/$name")
            val normal = HostileRun.contents(ByteArrayInputStream(bytes), readData = false).map { it.first }
            val noSkip = HostileRun.contents(NoSkipInputStream(ByteArrayInputStream(bytes)), readData = false).map { it.first }
            assertEquals(normal, noSkip, name)
            val listOnly = RarReader(NoSkipInputStream(ByteArrayInputStream(bytes)), RarOptions(listOnly = true)).use {
                Fixtures.listAll(it).map { e -> e.name }
            }
            assertEquals(normal, listOnly, name)
            assertEquals(null, HostileRun.exercise(bytes) { NoSkipInputStream(it) }, name)
        }
    }
}
