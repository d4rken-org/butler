package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The hostile-input rule (success or a RarException, nothing else) applied to compressed data with
 * re-sealed header fields or damaged packed bytes. Truncations and plain bit flips: MutationSweepTest.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HostileDecoderSweepTest {
    private fun assertNoForeignFailures(label: String, inputs: Sequence<Pair<String, ByteArray>>) {
        val failures = inputs.mapNotNull { (what, bytes) -> HostileRun.exercise(bytes)?.let { "$what: $it" } }.take(10).toList()
        assertTrue(failures.isEmpty(), "$label:\n${failures.joinToString("\n")}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["test_read_format_rar5_compressed.rar", "test_read_format_rar5_multiple_files_solid.rar"])
    fun `the originals decode cleanly`(name: String) {
        val contents = HostileRun.contents(Fixtures.stream("libarchive/$name"))
        assertTrue(contents.isNotEmpty() && contents.all { it.second != null }, name)
    }

    @Test
    fun `bit flips in file headers with CRCs recomputed`() {
        // Re-sealing the header CRC lets damaged sizes, methods, windows and solid flags reach the decoder.
        for (name in listOf("test_read_format_rar5_compressed.rar", "test_read_format_rar5_multiple_files_solid.rar")) {
            val bytes = Fixtures.bytes("libarchive/$name")
            val headers = RarBlocks.parse(bytes).filter { it.type == HeaderType.FILE }
            val inputs = headers.asSequence().flatMap { block ->
                (block.start + 4 until block.headerEnd).asSequence().flatMap { offset ->
                    (0 until 8).asSequence().map { bit ->
                        val copy = bytes.copyOf()
                        copy[offset] = (copy[offset].toInt() xor (1 shl bit)).toByte()
                        RarBlocks.reseal(copy, block)
                        "offset $offset bit $bit" to copy
                    }
                }
            }
            assertNoForeignFailures("$name resealed", inputs)
        }
    }

    @Test
    fun `random damage to packed data`() {
        // Byte-level damage throughout the data of every compressed fixture with small windows.
        val random = kotlin.random.Random(5)
        for (path in listOf("rar723/m3.rar", "rar723/solid.rar", "rar723/filter-e8e9.rar", "rar723/filter-delta.rar", "libarchive/test_read_format_rar5_arm.rar")) {
            val bytes = Fixtures.bytes(path)
            val data = RarBlocks.parse(bytes).filter { it.type == HeaderType.FILE && it.dataEnd > it.headerEnd }
            val inputs = (0 until 150).asSequence().map { i ->
                val block = data[i % data.size]
                val copy = bytes.copyOf()
                repeat(1 + i % 3) {
                    val at = random.nextInt(block.headerEnd, block.dataEnd)
                    copy[at] = random.nextInt().toByte()
                }
                "damage $i in ${block.name}" to copy
            }
            assertNoForeignFailures(path, inputs)
        }
    }
}
