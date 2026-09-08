package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.smb.SmbBlockingFile
import eu.darken.smb.SmbFile
import eu.darken.smb.SmbShare
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import kotlin.uuid.Uuid

class SmbOutputStreamTest : BaseTest() {
    private val path = SmbPath(Uuid.random(), listOf("file.bin"))
    private val file = mockk<SmbBlockingFile>(relaxed = true)
    private val handle = mockk<SmbFile> { every { blocking() } returns file }
    private val share = mockk<SmbShare> { coEvery { openFile(any(), any()) } returns handle }
    private val location = mockk<SmbLocation> { every { basePath } returns emptyList() }
    private var releases = 0
    private val lease = SmbConnectionPool.Lease(location, share) { releases++ }
    private val pool = mockk<SmbConnectionPool> { coEvery { acquire(path.locationId) } returns lease }
    private val ops = SmbFileSystemOps(pool, TestDispatcherProvider())

    @Test
    fun `flush then close sends buffered bytes and flushes the server once`() = runTest {
        ops.openOutputStream(path, false).use {
            it.write(byteArrayOf(1, 2, 3))
            it.flush()
            it.flush()
        }
        verify(exactly = 1) { file.write(0L, any(), 0, 3) }
        verify(exactly = 1) { file.flush() }
        verify(exactly = 1) { file.close() }
        releases shouldBe 1
    }

    @Test
    fun `writes after a flush are flushed again on close`() = runTest {
        ops.openOutputStream(path, false).use {
            it.write(1)
            it.flush()
            it.write(2)
        }
        verify(exactly = 1) { file.write(0L, any(), 0, 1) }
        verify(exactly = 1) { file.write(1L, any(), 0, 1) }
        verify(exactly = 2) { file.flush() }
        releases shouldBe 1
    }

    @Test
    fun `empty output still flushes its create or truncate once`() = runTest {
        ops.openOutputStream(path, false).use { it.flush() }
        verify(exactly = 1) { file.flush() }
        verify(exactly = 0) { file.write(any(), any(), any(), any()) }
    }

    @Test
    fun `failed server flush can be retried without resending writes`() = runTest {
        var attempts = 0
        every { file.flush() } answers { if (++attempts == 1) throw IOException("flush failed") }
        ops.openOutputStream(path, false).use {
            it.write(1)
            shouldThrow<IOException> { it.flush() }
            it.flush()
        }
        attempts shouldBe 2
        verify(exactly = 1) { file.write(0L, any(), 0, 1) }
        releases shouldBe 1
    }

    @Test
    fun `close releases handle and lease when server flush fails`() = runTest {
        every { file.flush() } throws IOException("flush failed")
        val stream = ops.openOutputStream(path, false)
        stream.write(1)
        shouldThrow<IOException> { stream.close() }
        verify(exactly = 1) { file.close() }
        releases shouldBe 1
    }

    @Test
    fun `small caller writes are batched into one MiB server writes`() = runTest {
        ops.openOutputStream(path, false).use { output ->
            repeat(256) { output.write(ByteArray(8192)) }
        }
        verify(exactly = 1) { file.write(0L, any(), 0, 1024 * 1024) }
        verify(exactly = 1) { file.write(1024 * 1024L, any(), 0, 1024 * 1024) }
        verify(exactly = 2) { file.write(any(), any(), any(), any()) }
        verify(exactly = 1) { file.flush() }
        releases shouldBe 1
    }
}
