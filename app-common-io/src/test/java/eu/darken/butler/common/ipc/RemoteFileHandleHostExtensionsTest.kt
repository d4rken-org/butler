package eu.darken.butler.common.ipc

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okio.FileHandle
import okio.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemoteFileHandleHostExtensionsTest : BaseTest() {

    private fun createMockFileHandle(readWrite: Boolean = false): FileHandle = mockk(relaxed = true) {
        every { this@mockk.readWrite } returns readWrite
    }

    @Test
    fun `readWrite returns value from FileHandle`() {
        val handle = createMockFileHandle(readWrite = true)

        val remote = handle.remoteFileHandle()

        remote.readWrite() shouldBe true
    }

    @Test
    fun `read returns data from FileHandle`() {
        val handle = createMockFileHandle()
        val buffer = ByteArray(64)
        every { handle.read(0L, buffer, 0, 64) } returns 42

        val remote = handle.remoteFileHandle()

        remote.read(0L, buffer, 0, 64) shouldBe 42
    }

    @Test
    fun `read returns -2 when FileHandle throws IOException`() {
        val handle = createMockFileHandle()
        val buffer = ByteArray(64)
        every { handle.read(0L, buffer, 0, 64) } throws IOException("disk error")

        val remote = handle.remoteFileHandle()

        remote.read(0L, buffer, 0, 64) shouldBe -2
    }

    @Test
    fun `size returns value from FileHandle`() {
        val handle = createMockFileHandle()
        every { handle.size() } returns 1024L

        val remote = handle.remoteFileHandle()

        remote.size() shouldBe 1024L
    }

    @Test
    fun `size returns -2 when FileHandle throws IOException`() {
        val handle = createMockFileHandle()
        every { handle.size() } throws IOException("disk error")

        val remote = handle.remoteFileHandle()

        remote.size() shouldBe -2L
    }

    @Test
    fun `write delegates to FileHandle`() {
        val handle = createMockFileHandle(readWrite = true)
        val data = ByteArray(32)

        val remote = handle.remoteFileHandle()
        remote.write(0L, data, 0, 32)

        verify { handle.write(0L, data, 0, 32) }
    }

    @Test
    fun `write swallows IOException`() {
        val handle = createMockFileHandle(readWrite = true)
        val data = ByteArray(32)
        every { handle.write(0L, data, 0, 32) } throws IOException("disk error")

        val remote = handle.remoteFileHandle()

        shouldNotThrowAny {
            remote.write(0L, data, 0, 32)
        }
    }

    @Test
    fun `close delegates to FileHandle`() {
        val handle = createMockFileHandle()

        val remote = handle.remoteFileHandle()
        remote.close()

        verify { handle.close() }
    }
}
