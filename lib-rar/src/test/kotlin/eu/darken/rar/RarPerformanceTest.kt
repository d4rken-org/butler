package eu.darken.rar

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

/**
 * Decoding benchmarks over in-memory archives. No thresholds: results are printed and written to
 * `build/reports/rar-perf/rar.json` for comparison between revisions.
 *
 * Each scenario runs twice: a warm-up pass, reported as `warmupMillis`, and the measured pass.
 * Throughput scenarios read an archive as many times as it takes to produce [TARGET_BYTES] of
 * entry data and report MiB of that output per second.
 */
@Tag("rar-perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RarPerformanceTest {
    private val results = sortedMapOf<String, Result>()
    private val buffer = ByteArray(64 * 1024)

    private data class Result(val warmup: Duration, val duration: Duration, val amount: Double, val unit: String) {
        val perSecond: Double
            get() = amount / duration.toDouble(DurationUnit.SECONDS)
    }

    /** Times [block] for the warm-up pass and the measured pass; [amount] reads the measured pass's result. */
    private fun <T> scenario(name: String, unit: String, amount: (T) -> Double, block: () -> T): T {
        val (warmup, measured) = (0..1).map { measureTimedValue(block) }
        val result = Result(warmup.duration, measured.duration, amount(measured.value), unit)
        results[name] = result
        println(
            "[rar-perf] $name: ${result.duration} (warm-up ${result.warmup}), " +
                "%.1f $unit, %.1f $unit/s".format(Locale.ROOT, result.amount, result.perSecond),
        )
        return measured.value
    }

    /** Reads every entry's data and returns how many bytes that was. */
    private fun drain(reader: RarReader): Long = reader.use {
        var total = 0L
        while (true) {
            val entry = it.nextEntry() ?: break
            if (entry.isDirectory || entry.redirection != null) continue
            it.entryStream().use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                }
            }
        }
        total
    }

    private fun throughput(name: String, open: () -> RarReader) {
        val perPass = drain(open())
        assertTrue(perPass > 0, name)
        val passes = ((TARGET_BYTES + perPass - 1) / perPass).toInt()
        val total = scenario(name, "MiB", { it.toDouble() / MIB }) {
            var bytes = 0L
            repeat(passes) { bytes += drain(open()) }
            bytes
        }
        assertEquals(perPass * passes, total, name)
    }

    private fun volumes(first: String): List<ByteArray> = Fixtures.volumeSet(first).map(Fixtures::bytes)

    private fun volumeReader(volumes: List<ByteArray>, options: RarOptions = RarOptions()): RarReader = RarReader(
        ByteArrayInputStream(volumes[0]),
        options.copy(volumeProvider = RarVolumeProvider { index -> volumes.getOrNull(index)?.let(::ByteArrayInputStream) }),
    )

    @Test
    fun `LZ decode of a solid volume set`() {
        val set = volumes("libarchive/test_read_format_rar5_multiarchive_solid.part01.rar")
        assertTrue(set.size > 1)
        throughput("lz-solid") { volumeReader(set) }
    }

    @Test
    fun `LZ decode of a compressed archive`() {
        val bytes = Fixtures.bytes("rar723/dict-1m.rar")
        throughput("lz-compressed") { RarReader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `stored entry`() {
        val data = Random(1).nextBytes(16 shl 20)
        val archive = RarBuilder().main().file("stored.bin", data).end().build()
        throughput("stored") { RarReader(ByteArrayInputStream(archive)) }
    }

    @Test
    fun `E8 filter`() {
        val bytes = Fixtures.bytes("rar723/filter-e8.rar")
        throughput("filter-e8") { RarReader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `delta filter`() {
        val bytes = Fixtures.bytes("rar723/filter-delta.rar")
        throughput("filter-delta") { RarReader(ByteArrayInputStream(bytes)) }
    }

    /** Keys come from [MemoizedDerivation], which [throughput]'s untimed first read fills, so no pass derives. */
    @Test
    fun `AES decrypt and decode`() {
        val bytes = Fixtures.bytes("rar723/encrypted-data.rar")
        throughput("aes-decrypt-decode") { cryptReader(ByteArrayInputStream(bytes), PASSWORD_OPTIONS, MemoizedDerivation) }
    }

    @Test
    fun `key derivation`() {
        var parameters: Pair<ByteArray, Int>? = null
        val recording = Rar5KeyDerivation { password, salt, log2Count, cancellation ->
            parameters = salt.copyOf() to log2Count
            Rar5Crypto.deriveKeys(password, salt, log2Count, cancellation)
        }
        drain(cryptReader(ByteArrayInputStream(Fixtures.bytes("rar723/encrypted-data.rar")), PASSWORD_OPTIONS, recording))
        val (salt, log2Count) = checkNotNull(parameters) { "encrypted-data.rar derived no keys" }
        val password = Rar5Crypto.passwordBytes(PASSWORD.toCharArray())
        scenario("kdf-log2-$log2Count", "derivations", { KDF_ROUNDS.toDouble() }) {
            repeat(KDF_ROUNDS) { Rar5Crypto.deriveKeys(password, salt, log2Count) {}.wipe() }
        }
    }

    @Test
    fun `listOnly over a volume set`() {
        val set = volumes("rar723/vol-plain.part1.rar")
        assertTrue(set.size > 1)
        val options = RarOptions(listOnly = true)
        val perPass = volumeReader(set, options).use { Fixtures.listAll(it).size }
        assertTrue(perPass > 0)
        val passes = (LIST_TARGET + perPass - 1) / perPass
        val total = scenario("list-only-volumes", "entries", { it.toDouble() }) {
            var entries = 0
            repeat(passes) { entries += volumeReader(set, options).use { Fixtures.listAll(it).size } }
            entries
        }
        assertEquals(perPass * passes, total)
    }

    @AfterAll
    fun writeReport() {
        val dir = File(System.getProperty("rar.perf.reportDir") ?: "build/reports/rar-perf")
        dir.mkdirs()
        val json = results.entries.joinToString(",\n", prefix = "{\n  \"scenarios\": {\n", postfix = "\n  }\n}\n") { (name, result) ->
            "    \"$name\": {\"millis\": ${result.duration.inWholeMilliseconds}, " +
                "\"warmupMillis\": ${result.warmup.inWholeMilliseconds}, " +
                "\"amount\": ${"%.1f".format(Locale.ROOT, result.amount)}, \"unit\": \"${result.unit}\", " +
                "\"perSecond\": ${"%.1f".format(Locale.ROOT, result.perSecond)}}"
        }
        File(dir, "rar.json").writeText(json)
    }

    private companion object {
        const val MIB = 1024.0 * 1024
        const val TARGET_BYTES = 128L shl 20
        const val LIST_TARGET = 50_000
        const val KDF_ROUNDS = 16
        const val PASSWORD = "password"
        val PASSWORD_OPTIONS = RarOptions(passwordProvider = RarPasswordProvider { PASSWORD.toCharArray() })
    }
}
