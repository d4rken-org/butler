package eu.darken.rar

import com.code_intelligence.jazzer.junit.FuzzTest

/**
 * The whole reader over arbitrary input under [SweepCheck.LIMITS]: the first byte picks the
 * [ReadMode], the rest is the archive. A run may end in success or a RarException; anything else
 * it throws is a finding.
 *
 * `test` replays the seeds under RarReaderFuzzTestInputs/reader, fixtures behind a mode byte
 * (`solid-skip` starts with 2, [ReadMode.SKIP]); `rarFuzz` fuzzes this method.
 */
class RarReaderFuzzTest {
    @FuzzTest
    fun reader(data: ByteArray) {
        if (data.isEmpty()) return
        val mode = ReadMode.entries[(data[0].toInt() and 0xFF) % ReadMode.entries.size]
        val archive = data.copyOfRange(1, data.size)
        HostileRun.exercise(archive, OPTIONS, MemoizedDerivation, mode)?.let { throw it }
    }

    private companion object {
        val OPTIONS = RarOptions(limits = SweepCheck.LIMITS, passwordProvider = RarPasswordProvider { "password".toCharArray() })
    }
}
