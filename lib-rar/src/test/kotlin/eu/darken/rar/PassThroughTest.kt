package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Failures that do not concern the archive pass through as the identical instance: an IOException
 * from the caller's stream, volume provider or memory budget, and whatever the cancellation hook
 * throws. The reader then holds no reservation, fails later calls with that instance as the cause,
 * and closes every stream on close().
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PassThroughTest {
    enum class Fault { STORAGE, CANCELLATION }

    private class Cancelled : RuntimeException("cancelled by the test")

    private class BalanceBudget : RarMemoryBudget {
        var balance = 0L

        override fun acquire(bytes: Long) {
            balance += bytes
        }

        override fun release(bytes: Long) {
            balance -= bytes
        }
    }

    /**
     * Where a fault fires: the first time the stream is at or past the middle of [entry]'s packed
     * data (anywhere without one, or with [cancelAnywhere]) while the named method is on the call
     * stack: [storageIn] for an injected read or skip failure, [cancelIn] for the cancellation hook.
     * With [partialOutput], it also waits until the entry has delivered output: the decoder reads
     * ahead of its output, so the stream can pass the middle before the first byte is delivered.
     */
    private class Point(
        val label: String,
        val path: String,
        val mode: ReadMode,
        val entry: String?,
        val storageIn: String?,
        val cancelIn: String,
        val cancelAnywhere: Boolean = false,
        val password: String? = null,
        val partialOutput: Boolean = false,
    )

    companion object {
        private val POINTS = listOf(
            Point("non-solid skip", "rar723/stored.rar", ReadMode.SKIP, "docs/lorem.txt", "skipFully", "skipFully"),
            Point("listOnly skip", "rar723/m3.rar", ReadMode.LIST_ONLY, "docs/lorem.txt", "skipFully", "skipFully"),
            Point("solid decode-and-skip", "rar723/solid.rar", ReadMode.SKIP, "docs/lorem.txt", "skipDecode", "skipDecode"),
            Point("long LZ stream", "rar723/dict-128k.rar", ReadMode.READ, "dictdata.bin", "readDecoded", "readDecoded", partialOutput = true),
            Point("filtered stream", "rar723/filter-delta.rar", ReadMode.READ, "delta.bin", "readDecoded", "runFilter", cancelAnywhere = true),
            // Key derivation reads no archive data, so only the hook can fail there.
            Point("entry key derivation", "rar723/encrypted-stored.rar", ReadMode.READ, null, null, "deriveKeys", password = "password"),
            Point("header key derivation", "rar723/encrypted-headers.rar", ReadMode.READ, null, null, "deriveKeys", password = "password"),
        )

        @JvmStatic
        fun faults(): List<Arguments> = POINTS.flatMap { point ->
            Fault.entries.filter { it == Fault.CANCELLATION || point.storageIn != null }.map { Arguments.of(point.label, it) }
        }

        /** Reads and skips advance at most this far, so a fault can land inside a skip or a decode. */
        private const val STEP = 512
    }

    /** [bytes] in reads and skips of at most [STEP] bytes; [beforeAccess] runs before each. */
    private class SteppedStream(private val bytes: ByteArray) : InputStream() {
        var position = 0
            private set
        var closed = false
            private set
        var beforeAccess: () -> Unit = {}

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            beforeAccess()
            if (len == 0) return 0
            if (position >= bytes.size) return -1
            val n = minOf(len, STEP, bytes.size - position)
            bytes.copyInto(b, off, position, position + n)
            position += n
            return n
        }

        override fun skip(n: Long): Long {
            beforeAccess()
            val skipped = minOf(n, STEP.toLong(), (bytes.size - position).toLong()).coerceAtLeast(0)
            position += skipped.toInt()
            return skipped
        }

        override fun close() {
            closed = true
        }
    }

    /** Fires once: when asked with the stream at or past [from], [ready] true and a method named [method] on the call stack. */
    private class Trigger(
        private val method: String,
        private val from: Long,
        private val stream: SteppedStream,
        private val ready: () -> Boolean,
    ) {
        var fired = false
            private set
        var onFire: () -> Unit = {}

        fun due(): Boolean {
            if (fired || stream.position < from || !ready()) return false
            if (Thread.currentThread().stackTrace.none { it.methodName.startsWith(method) }) return false
            fired = true
            onFire()
            return true
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("faults")
    fun `a fault passes through unchanged`(label: String, fault: Fault) {
        val point = POINTS.single { it.label == label }
        val bytes = Fixtures.bytes(point.path)
        val middle = point.entry?.let { name -> RarBlocks.file(bytes, name).let { it.headerEnd + (it.dataEnd - it.headerEnd) / 2L } }
        val from = if (middle == null || (fault == Fault.CANCELLATION && point.cancelAnywhere)) 0L else middle
        val stream = SteppedStream(bytes)
        var delivered = 0L
        var deliveredAtFault = -1L
        val method = if (fault == Fault.STORAGE) point.storageIn!! else point.cancelIn
        val trigger = Trigger(method, from, stream, ready = { !point.partialOutput || delivered > 0 })
        val storage = IOException("injected storage failure")
        val cancelled = Cancelled()
        val expected: Throwable = if (fault == Fault.STORAGE) storage else cancelled
        if (fault == Fault.STORAGE) stream.beforeAccess = { if (trigger.due()) throw storage }
        trigger.onFire = { deliveredAtFault = delivered }
        val budget = BalanceBudget()
        val options = RarOptions(
            passwordProvider = point.password?.let { password -> RarPasswordProvider { password.toCharArray() } },
            memoryBudget = budget,
            listOnly = point.mode == ReadMode.LIST_ONLY,
            cancellation = { if (fault == Fault.CANCELLATION && trigger.due()) throw cancelled },
        )
        var reader: RarReader? = null
        val buffer = ByteArray(8192)
        val thrown = assertThrows<Throwable> {
            val r = RarReader(stream, options).also { reader = it }
            while (true) {
                val entry = r.nextEntry() ?: break
                if (point.mode != ReadMode.READ || entry.isDirectory || entry.redirection != null) continue
                delivered = 0
                r.entryStream().use { data ->
                    while (true) {
                        val n = data.read(buffer)
                        if (n < 0) break
                        delivered += n
                    }
                }
            }
        }
        assertSame(expected, thrown)
        if (point.partialOutput) assertTrue(deliveredAtFault > 0, "Nothing was delivered before the fault")
        assertEquals(0L, budget.balance)
        reader?.let { r ->
            assertSame(expected, assertThrows<IOException> { r.nextEntry() }.cause)
            r.close()
        }
        assertTrue(stream.closed)
    }

    @ParameterizedTest
    @EnumSource(ReadMode::class)
    fun `a volume stream that fails on its first read passes through unchanged`(mode: ReadMode) {
        val parts = Fixtures.volumeSet("rar723/vol-plain.part1.rar").map { Fixtures.bytes(it) }
        val storage = IOException("volume unreadable")
        val failing = object : InputStream() {
            var closed = false

            override fun read(): Int = throw storage

            override fun read(b: ByteArray, off: Int, len: Int): Int = throw storage

            override fun close() {
                closed = true
            }
        }
        val first = TrackingInputStream(ByteArrayInputStream(parts[0]))
        val budget = BalanceBudget()
        val options = RarOptions(volumeProvider = { index -> if (index == 1) failing else null }, memoryBudget = budget).forMode(mode)
        RarReader(first, options).use { reader ->
            assertSame(storage, assertThrows<IOException> { HostileRun.drive(reader, mode, ByteArray(8192)) })
            assertEquals(0L, budget.balance)
            assertSame(storage, assertThrows<IOException> { reader.nextEntry() }.cause)
        }
        assertTrue(first.closed)
        assertTrue(failing.closed)
    }

    @ParameterizedTest
    @EnumSource(ReadMode::class)
    fun `cancellation while the next volume is read passes through unchanged`(mode: ReadMode) {
        val volumes = TestVolumes.fixture("rar723/vol-plain.part1.rar")
        val cancelled = Cancelled()
        val budget = BalanceBudget()
        var fired = false
        val cancellation = {
            if (!fired && Thread.currentThread().stackTrace.any { it.methodName.startsWith("startVolume") }) {
                fired = true
                throw cancelled
            }
        }
        val options = RarOptions(volumeProvider = volumes, memoryBudget = budget, cancellation = cancellation).forMode(mode)
        RarReader(volumes.first(), options).use { reader ->
            assertSame(cancelled, assertThrows<Cancelled> { HostileRun.drive(reader, mode, ByteArray(8192)) })
            assertEquals(0L, budget.balance)
            assertSame(cancelled, assertThrows<IOException> { reader.nextEntry() }.cause)
        }
        assertEquals(listOf(0, 1), volumes.opened)
        assertTrue(volumes.allClosed)
    }

    @ParameterizedTest
    @EnumSource(Fault::class)
    fun `a fault from a blocked acquire passes through unchanged and holds nothing`(fault: Fault) {
        // The budget is caller code: its acquire blocks until the test releases it, then throws.
        val reach = listOf(
            "rar723/m3.rar" to { reader: RarReader ->
                reader.nextEntry()!!
                reader.nextEntry()!!
                reader.entryStream()
                Unit
            },
            // Skipping the first entry of a solid archive decodes it.
            "rar723/solid.rar" to { reader: RarReader -> while (reader.nextEntry() != null) Unit },
        )
        for ((path, action) in reach) {
            val failure: Throwable = if (fault == Fault.STORAGE) IOException("budget backend failed") else Cancelled()
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            var released = 0L
            val budget = object : RarMemoryBudget {
                override fun acquire(bytes: Long) {
                    entered.countDown()
                    proceed.await()
                    throw failure
                }

                override fun release(bytes: Long) {
                    released += bytes
                }
            }
            val stream = TrackingInputStream(Fixtures.stream(path))
            val reader = RarReader(stream, RarOptions(memoryBudget = budget))
            val outcome = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try {
                    action(reader)
                } catch (t: Throwable) {
                    outcome.set(t)
                }
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS), path)
            assertTrue(worker.isAlive, path)
            proceed.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(30))
            assertSame(failure, outcome.get(), path)
            assertEquals(0L, released, path)
            assertEquals(0L, reader.reservedBytes, path)
            assertSame(failure, assertThrows<IOException>(path) { reader.nextEntry() }.cause)
            reader.close()
            assertTrue(stream.closed, path)
        }
    }

    /** Blocks the first read at [blockAt] until another thread closes it, then throws [failure]. */
    private class BlockingStream(private val bytes: ByteArray, private val blockAt: Int) : InputStream() {
        val failure = IOException("Stream closed")
        val blocked = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (closed.count == 0L) throw failure
            if (position >= blockAt) {
                blocked.countDown()
                closed.await()
                throw failure
            }
            val n = minOf(len, blockAt - position)
            bytes.copyInto(b, off, position, position + n)
            position += n
            return n
        }

        override fun close() = closed.countDown()
    }

    @Test
    fun `closing a blocked caller stream from another thread surfaces its IOException unchanged`() {
        // The cancellation hook cannot reach a read blocked inside the caller's stream; closing the
        // stream is the caller's way out.
        val bytes = Fixtures.bytes("rar723/stored.rar")
        val lorem = RarBlocks.file(bytes, "docs/lorem.txt")
        val stream = BlockingStream(bytes, blockAt = lorem.headerEnd + 1000)
        val reader = RarReader(stream)
        val outcome = AtomicReference<Throwable>()
        val worker = thread(isDaemon = true) {
            try {
                HostileRun.drive(reader, ReadMode.READ, ByteArray(8192))
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }
        assertTrue(stream.blocked.await(30, TimeUnit.SECONDS))
        assertTrue(worker.isAlive)
        stream.close()
        worker.join(TimeUnit.SECONDS.toMillis(30))
        assertSame(stream.failure, outcome.get())
        assertSame(stream.failure, assertThrows<IOException> { reader.nextEntry() }.cause)
        reader.close()
    }
}
