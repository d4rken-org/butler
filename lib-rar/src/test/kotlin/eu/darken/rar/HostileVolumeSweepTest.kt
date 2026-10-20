package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The hostile-input rule applied to a damaged second volume of vol-plain, read through the volume
 * provider in every [ReadMode]. The `test` task runs a sample, the `rar-sweep` tag every truncation
 * and bit flip. Complete sweep of a small crafted set: MutationSweepTest.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HostileVolumeSweepTest {
    private val parts = Fixtures.volumeSet("rar723/vol-plain.part1.rar").map { Fixtures.bytes(it) }
    private val second = parts[1]

    /** The end of the second volume's end header; RAR pads the volume after it. */
    private val end = RarBlocks.parse(second).last { it.type == HeaderType.ENDARC }.dataEnd

    private fun run(damaged: ByteArray, mode: ReadMode): SweepCheck.Result {
        val volumes = TestVolumes { index -> if (index == 1) damaged else parts.getOrNull(index) }
        return SweepCheck.run(parts[0], mode, RarOptions(limits = SweepCheck.LIMITS, volumeProvider = volumes), volumes)
    }

    private fun assertTruncations(mode: ReadMode, lengths: Sequence<Int>) =
        SweepCheck.assertAll("truncation, $mode", lengths.map { "length $it" to second.copyOf(it) }) { damaged ->
            SweepCheck.judge(run(damaged, mode), truncatedTo = damaged.size, end = end)
        }

    private fun assertFlips(mode: ReadMode, bits: Sequence<Int>) =
        SweepCheck.assertAll("bit flips, $mode", bits.map { "bit $it" to SweepCheck.flip(second, it) }) { damaged ->
            SweepCheck.judge(run(damaged, mode))
        }

    /** Offsets near every block boundary of the second volume, plus a stride through its packed data. */
    private fun offsets(): List<Int> {
        val boundaries = RarBlocks.parse(second).flatMap { listOf(it.start, it.headerEnd, it.dataEnd) } + RarSignature.RAR5_SIZE + second.size
        val near = boundaries.flatMap { (it - 24..it + 24).toList() }
        return (near + (0 until second.size step 61)).filter { it in 0 until second.size }.distinct().sorted()
    }

    /** Every bit of every header, plus 3000 seeded random bits, about one in 40 of the volume. */
    private fun sampledBits(): List<Int> {
        val headers = RarBlocks.parse(second).flatMap { (it.start until it.headerEnd).toList() } + (0 until RarSignature.RAR5_SIZE)
        val random = Random(9)
        val sample = List(3000) { random.nextInt(second.size * 8) }
        return (headers.flatMap { byte -> (0 until 8).map { byte * 8 + it } } + sample).distinct()
    }

    @Test
    fun `the original set reads cleanly`() {
        for (mode in ReadMode.entries) {
            val result = run(second, mode)
            assertEquals(null, result.outcome, "$mode")
            assertEquals(emptyList<String>(), result.violations, "$mode")
        }
        val volumes = TestVolumes(parts)
        val names = mutableListOf<String>()
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes)).use { reader ->
            while (true) {
                val entry = reader.nextEntry() ?: break
                reader.entryStream().use { it.readAllBytes() }
                names += entry.name
            }
        }
        assertEquals(listOf("random.bin", "lorem.txt", "small.txt"), names)
    }

    @ParameterizedTest
    @EnumSource(ReadMode::class)
    fun `truncated second volume`(mode: ReadMode) = assertTruncations(mode, offsets().asSequence())

    @ParameterizedTest
    @EnumSource(ReadMode::class)
    fun `bit flips in the second volume`(mode: ReadMode) = assertFlips(mode, sampledBits().asSequence())

    @Test
    fun `header bit flips with CRCs recomputed`() {
        // Re-sealing the CRC lets damaged volume numbers, split flags, names and sizes reach the reader.
        val inputs = RarBlocks.parse(second).asSequence().flatMap { block ->
            (block.start + 4 until block.headerEnd).asSequence().flatMap { offset ->
                (0 until 8).asSequence().map { bit ->
                    val copy = second.copyOf()
                    copy[offset] = (copy[offset].toInt() xor (1 shl bit)).toByte()
                    RarBlocks.reseal(copy, block)
                    "offset $offset bit $bit" to copy
                }
            }
        }
        for (mode in ReadMode.entries) {
            SweepCheck.assertAll("resealed, $mode", inputs) { damaged -> SweepCheck.judge(run(damaged, mode)) }
        }
    }

    @Tag("rar-sweep")
    @Timeout(value = 30, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ParameterizedTest
    @EnumSource(ReadMode::class)
    fun `every truncation and bit flip of the second volume`(mode: ReadMode) {
        assertTruncations(mode, (0 until second.size).asSequence())
        assertFlips(mode, (0 until second.size * 8).asSequence())
    }
}
