package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MemoryBudgetTest {
    /** One reader's budget: records every reservation and whether one was taken while holding another. */
    private class RecordingBudget : RarMemoryBudget {
        var balance = 0L
        var highWater = 0L
        val acquired = mutableListOf<Long>()
        var acquiredWhileHolding = false

        override fun acquire(bytes: Long) {
            if (balance != 0L) acquiredWhileHolding = true
            acquired += bytes
            balance += bytes
            highWater = maxOf(highWater, balance)
        }

        override fun release(bytes: Long) {
            balance -= bytes
        }

        fun assertClean() {
            assertEquals(0L, balance)
            assertFalse(acquiredWhileHolding)
            assertTrue(highWater <= (acquired.maxOrNull() ?: 0L))
        }
    }

    /** Blocks [acquire] until enough of [capacity] is free. */
    private class BlockingBudget(private val capacity: Long) : RarMemoryBudget {
        private val lock = ReentrantLock()
        private val freed = lock.newCondition()
        private var used = 0L
        var peak = 0L
            private set

        override fun acquire(bytes: Long) {
            lock.withLock {
                require(bytes <= capacity)
                while (used + bytes > capacity) freed.await()
                used += bytes
                peak = maxOf(peak, used)
            }
        }

        override fun release(bytes: Long) {
            lock.withLock {
                used -= bytes
                freed.signalAll()
            }
        }
    }

    private class Cancelled : RuntimeException()

    /** Reads every unencrypted entry, checking after each read that the window stays inside the reservation. */
    private fun readAll(reader: RarReader, budget: RecordingBudget) {
        while (true) {
            val entry = reader.nextEntry() ?: break
            if (entry.isDirectory || entry.redirection != null || entry.isEncrypted) continue
            val stream = reader.entryStream()
            val buffer = ByteArray(4096)
            while (stream.read(buffer) >= 0) {
                assertTrue(reader.windowStorageBytes <= reader.reservedBytes)
                assertEquals(reader.reservedBytes, budget.balance)
            }
        }
    }

    @Test
    fun `reservations stay balanced over whole archives`() {
        for (path in listOf("rar723/m3.rar", "rar723/solid.rar", "rar723/dict-1m.rar", "rar723/blake2.rar", "libarchive/test_read_format_rar5_multiple_files.rar")) {
            val budget = RecordingBudget()
            RarReader(Fixtures.stream(path), RarOptions(memoryBudget = budget)).use { readAll(it, budget) }
            budget.assertClean()
            assertTrue(budget.acquired.isNotEmpty(), path)
        }
    }

    @Test
    fun `the reservation is the window need rounded to chunks`() {
        // Non-solid archive, known sizes: min(128 KiB, size) in 1 KiB chunks for lorem (12000) and numbers (8893).
        val m3 = RecordingBudget()
        RarReader(Fixtures.stream("rar723/m3.rar"), RarOptions(memoryBudget = m3), 1024).use { readAll(it, m3) }
        assertEquals(listOf(12288L, 9216L), m3.acquired)
        m3.assertClean()

        // Default chunks cap at the 128 KiB window.
        val m3Default = RecordingBudget()
        RarReader(Fixtures.stream("rar723/m3.rar"), RarOptions(memoryBudget = m3Default)).use { readAll(it, m3Default) }
        assertEquals(listOf(131072L, 131072L), m3Default.acquired)

        // A solid archive reserves the whole 1 MiB window once, because later entries continue the stream.
        val solid = RecordingBudget()
        RarReader(Fixtures.stream("rar723/solid.rar"), RarOptions(memoryBudget = solid)).use { readAll(it, solid) }
        assertEquals(listOf(1L shl 20), solid.acquired)
        solid.assertClean()

        // Skipping in a solid archive decodes, so it reserves as well; listing does not.
        val skipped = RecordingBudget()
        RarReader(Fixtures.stream("rar723/solid.rar"), RarOptions(memoryBudget = skipped)).use { reader ->
            while (reader.nextEntry() != null) Unit
            assertEquals(0L, skipped.balance - reader.reservedBytes)
        }
        assertEquals(listOf(1L shl 20), skipped.acquired)
        skipped.assertClean()

        val listed = RecordingBudget()
        Fixtures.list("rar723/solid.rar", RarOptions(memoryBudget = listed, listOnly = true))
        assertTrue(listed.acquired.isEmpty())
    }

    @Test
    fun `a decode failure releases the reservation`() {
        val bytes = Fixtures.bytes("rar723/m3.rar")
        val block = RarBlocks.file(bytes, "docs/lorem.txt")
        val middle = (block.headerEnd + block.dataEnd) / 2
        bytes[middle] = (bytes[middle].toInt() xor 0x55).toByte()
        val budget = RecordingBudget()
        RarReader(ByteArrayInputStream(bytes), RarOptions(memoryBudget = budget)).use { reader ->
            reader.nextEntry()!!
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
            assertEquals(0L, budget.balance)
        }
        assertEquals(1, budget.acquired.size)
        budget.assertClean()
    }

    @Test
    fun `cancellation releases the reservation and propagates unchanged`() {
        val budget = RecordingBudget()
        val cancelled = Cancelled()
        var armed = false
        var calls = 0
        val options = RarOptions(memoryBudget = budget, cancellation = { if (armed && ++calls > 5) throw cancelled })
        RarReader(Fixtures.stream("rar723/dict-128k.rar"), options).use { reader ->
            reader.nextEntry()!!
            val stream = reader.entryStream()
            armed = true
            val buffer = ByteArray(8192)
            var delivered = 0L
            val thrown = assertThrows<Cancelled> {
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    delivered += n
                }
            }
            assertSame(cancelled, thrown)
            assertTrue(delivered < 2_200_000)
            assertEquals(0L, budget.balance)
            assertThrows<IOException> { reader.nextEntry() }
        }
        budget.assertClean()

        // While skipping a solid entry, too.
        val skipping = RecordingBudget()
        var skipCalls = 0
        val skipOptions = RarOptions(memoryBudget = skipping, cancellation = { if (skipping.balance > 0 && ++skipCalls > 3) throw cancelled })
        RarReader(Fixtures.stream("rar723/solid.rar"), skipOptions).use { reader ->
            assertSame(cancelled, assertThrows<Cancelled> { while (reader.nextEntry() != null) Unit })
            assertEquals(0L, skipping.balance)
        }
        skipping.assertClean()
    }

    @Test
    fun `decoding calls the cancellation hook at least every 64 KiB`() {
        var calls = 0
        RarReader(Fixtures.stream("rar723/dict-128k.rar"), RarOptions(cancellation = { calls++ })).use { reader ->
            reader.nextEntry()!!
            val stream = reader.entryStream()
            val before = calls
            stream.readAllBytes()
            assertTrue(calls - before >= 2_200_000 / (64 * 1024), "${calls - before} calls")
        }
    }

    @Test
    fun `password failures keep the reservation balanced`() {
        // Missing, wrong and right passwords; entries read, or only skipped (which decodes in a solid archive).
        for (answer in listOf(null, "passwore", "password")) {
            for (read in listOf(true, false)) {
                val label = "answer $answer, read $read"
                val budget = RecordingBudget()
                val options = RarOptions(memoryBudget = budget, passwordProvider = { answer?.toCharArray() })
                RarReader(Fixtures.stream("rar723/solid-encrypted.rar"), options).use { reader ->
                    while (true) {
                        val entry = reader.nextEntry() ?: break
                        assertEquals(reader.reservedBytes, budget.balance, label)
                        if (!read || entry.isDirectory) continue
                        try {
                            reader.entryStream().use { it.readAllBytes() }
                        } catch (e: RarPasswordRequiredException) {
                            assertTrue(answer == null, label)
                        } catch (e: RarWrongPasswordException) {
                            assertTrue(answer == "passwore", label)
                        }
                        assertEquals(reader.reservedBytes, budget.balance, label)
                    }
                }
                budget.assertClean()
                if (answer == "password") assertEquals(listOf(1L shl 20), budget.acquired, label) else assertTrue(budget.acquired.isEmpty(), label)
            }
        }
    }

    @Test
    fun `a failing constructor holds nothing`() {
        val budget = RecordingBudget()
        val truncated = Fixtures.bytes("rar723/m3.rar").copyOf(20)
        assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(truncated), RarOptions(memoryBudget = budget)) }
        val encrypted = Fixtures.stream("rar723/encrypted-headers.rar")
        assertThrows<RarPasswordRequiredException> { RarReader(encrypted, RarOptions(memoryBudget = budget, passwordProvider = null)) }
        assertTrue(budget.acquired.isEmpty())
        budget.assertClean()
    }

    @Test
    fun `a budget that refuses poisons the reader without holding anything`() {
        class Refused : RuntimeException()
        val refused = Refused()
        var released = 0L
        val budget = object : RarMemoryBudget {
            override fun acquire(bytes: Long) {
                throw refused
            }

            override fun release(bytes: Long) {
                released += bytes
            }
        }
        RarReader(Fixtures.stream("rar723/m3.rar"), RarOptions(memoryBudget = budget)).use { reader ->
            reader.nextEntry()!!
            reader.nextEntry()!!
            assertSame(refused, assertThrows<Refused> { reader.entryStream() })
            assertThrows<IOException> { reader.nextEntry() }
        }
        assertEquals(0L, released)
    }

    @Test
    fun `readers sharing a small blocking budget take turns`() {
        // Each reader needs its 1 MiB window; together they would need 2 MiB.
        val budget = BlockingBudget(capacity = 3L shl 19)
        val expected = Fixtures.rar723.fixtures.getValue("dict-1m.rar").entries.single().sha256
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val results = ConcurrentLinkedQueue<String>()
        val threads = List(2) {
            Thread {
                try {
                    start.await()
                    RarReader(Fixtures.stream("rar723/dict-1m.rar"), RarOptions(memoryBudget = budget)).use { reader ->
                        reader.nextEntry()!!
                        results += Fixtures.sha256(reader.entryStream().readAllBytes())
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        start.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        for (thread in threads) thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
        assertTrue(threads.none { it.isAlive }, "Readers deadlocked on the shared budget")
        assertTrue(failures.isEmpty(), failures.toString())
        assertEquals(listOf(expected, expected), results.toList())
        assertEquals(1L shl 20, budget.peak)
    }
}
