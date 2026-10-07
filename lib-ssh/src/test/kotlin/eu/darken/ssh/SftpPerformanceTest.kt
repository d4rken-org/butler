package eu.darken.ssh

import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Throughput scenarios against the OpenSSH container. No thresholds yet: results are printed and
 * written to `build/reports/ssh-perf/<connector>.json` for comparison between implementations.
 *
 * Each scenario runs twice on the same session: a warm-up pass, reported as `warmupMillis`, and the
 * measured pass. Only the first scenario of a class would otherwise pay for class loading and JIT.
 */
@Tag("ssh-perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal abstract class SftpPerformanceTest {
    protected abstract val connectorName: String

    protected abstract fun connector(config: SftpConfig = SftpConfig()): SftpConnector

    private val results = sortedMapOf<String, Result>()

    private data class Result(val warmup: Duration, val duration: Duration, val amount: Long, val unit: String) {
        val perSecond: Double
            get() = amount / duration.toDouble(DurationUnit.SECONDS)
    }

    private suspend fun session(): SftpSession =
        connector()
            .connect(
                OpenSshServer.shared.endpoint(),
                SshCredentials.Password(OpenSshServer.PASSWORD_USER, OpenSshServer.PASSWORD.toCharArray()),
                HostKeyPolicy.Pinned(TestKeys.hostEd25519),
            )

    private suspend fun SftpSession.scratch(): SftpPath =
        SftpContractTest.UPLOAD.child("perf-" + Uuid.random().toString()).also { mkdir(it) }

    /**
     * Times [block] for the warm-up pass (round 0) and the measured pass (round 1) and records both;
     * [amount] reads the measured pass's result. Returns both results for the caller's assertions.
     */
    private suspend fun <T> scenario(
        name: String,
        unit: String,
        amount: (T) -> Long,
        block: suspend (round: Int) -> T,
    ): List<T> {
        val (warmup, measured) = (0..1).map { round -> measureTimedValue { block(round) } }
        val result = Result(warmup.duration, measured.duration, amount(measured.value), unit)
        results[name] = result
        println(
            "[ssh-perf] $connectorName $name: ${result.duration} (warm-up ${result.warmup}), " +
                "${result.amount} $unit, %.1f $unit/s".format(java.util.Locale.ROOT, result.perSecond)
        )
        return listOf(warmup.value, measured.value)
    }

    private suspend fun SftpSession.upload(path: SftpPath, payload: ByteArray) {
        openFile(path, SftpOpenMode.CREATE_NEW).use { file ->
            var offset = 0
            while (offset < payload.size) {
                val count = minOf(CHUNK, payload.size - offset)
                file.write(offset.toLong(), payload, offset, count)
                offset += count
            }
            file.flush()
        }
    }

    @Test
    fun `sequential upload of 64 MiB`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val paths = scenario("upload_64MiB", "bytes", { payload.size.toLong() }) { round ->
                dir.child("upload-$round.bin").also { session.upload(it, payload) }
            }
            paths.forEach { assertEquals(payload.size.toLong(), session.stat(it).size) }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `sequential download of 64 MiB`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val path = dir.child("download.bin")
            session.upload(path, payload)
            val expected = MessageDigest.getInstance("SHA-256").digest(payload)
            val rounds = scenario("download_64MiB", "bytes", { it.first }) {
                val digest = MessageDigest.getInstance("SHA-256")
                session.openFile(path).use { file ->
                    val buffer = ByteArray(CHUNK)
                    var position = 0L
                    while (true) {
                        val count = file.read(position, buffer)
                        if (count == -1) break
                        digest.update(buffer, 0, count)
                        position += count
                    }
                    position to digest.digest()
                }
            }
            rounds.forEach { (total, digest) ->
                assertEquals(payload.size.toLong(), total)
                assertArrayEquals(expected, digest)
            }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `listing 5000 entries`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            repeat(5000) { session.openFile(dir.child("entry-$it"), SftpOpenMode.CREATE_NEW).close() }
            val rounds = scenario("list_5000", "entries", { it.size.toLong() }) { session.list(dir).toList() }
            rounds.forEach { entries ->
                assertEquals(5000, entries.size)
                assertEquals(5000, entries.map { it.path }.toSet().size)
            }
            session.delete(dir, recursive = true)
        }
    }

    @Test
    fun `stat of 500 small files`(): Unit = runBlocking {
        session().use { session ->
            val dir = session.scratch()
            val paths = (0 until 500).map { dir.child("small-$it") }
            for ((index, path) in paths.withIndex()) {
                session.openFile(path, SftpOpenMode.CREATE_NEW).use { it.write(0, byteArrayOf(index.toByte())) }
            }
            val rounds = scenario("stat_500", "stats", { it.size.toLong() }) { paths.map { session.stat(it) } }
            rounds.forEach { entries -> assertTrue(entries.all { it.type == SftpFileType.FILE && it.size == 1L }) }
            session.delete(dir, recursive = true)
        }
    }

    @AfterAll
    fun writeReport() {
        val dir = File(System.getProperty("ssh.perf.reportDir") ?: "build/reports/ssh-perf")
        dir.mkdirs()
        val json =
            results.entries.joinToString(",\n", prefix = "{\n  \"connector\": \"$connectorName\",\n  \"scenarios\": {\n", postfix = "\n  }\n}\n") { (name, result) ->
                "    \"$name\": {\"millis\": ${result.duration.inWholeMilliseconds}, " +
                    "\"warmupMillis\": ${result.warmup.inWholeMilliseconds}, " +
                    "\"amount\": ${result.amount}, \"unit\": \"${result.unit}\", " +
                    "\"perSecond\": ${"%.1f".format(java.util.Locale.ROOT, result.perSecond)}}"
            }
        File(dir, "$connectorName.json").writeText(json)
    }

    private companion object {
        const val CHUNK = 256 * 1024
        val payload: ByteArray by lazy { ByteArray(64 * 1024 * 1024).also { java.util.Random(64).nextBytes(it) } }
    }
}
