package eu.darken.butler.common.ipc

import android.os.RemoteException
import eu.darken.butler.common.files.errors.ServiceConnectionLostException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class RemoteFileHandleExtensionsTest : BaseTest() {

    private fun createMockRemote(): RemoteFileHandle = mockk(relaxed = true)

    @Test
    fun `size returns valid value from remote`() {
        val remote = createMockRemote()
        every { remote.size() } returns 1024L

        val handle = remote.fileHandle(readWrite = false)

        handle.size() shouldBe 1024L
    }

    @Test
    fun `size throws ServiceConnectionLostException when remote returns -2`() {
        val remote = createMockRemote()
        every { remote.size() } returns -2L

        val handle = remote.fileHandle(readWrite = false)

        shouldThrow<ServiceConnectionLostException> {
            handle.size()
        }
    }

    @Test
    fun `size throws ServiceConnectionLostException on RemoteException`() {
        val remote = createMockRemote()
        every { remote.size() } throws RemoteException("connection lost")

        val handle = remote.fileHandle(readWrite = false)

        shouldThrow<ServiceConnectionLostException> {
            handle.size()
        }
    }

    @Test
    fun `read returns valid byte count from remote`() {
        val remote = createMockRemote()
        val buffer = ByteArray(64)
        every { remote.read(0L, buffer, 0, 64) } returns 42

        val handle = remote.fileHandle(readWrite = false)

        handle.read(0L, buffer, 0, 64) shouldBe 42
    }

    @Test
    fun `read throws ServiceConnectionLostException when remote returns -2`() {
        val remote = createMockRemote()
        val buffer = ByteArray(64)
        every { remote.read(0L, buffer, 0, 64) } returns -2

        val handle = remote.fileHandle(readWrite = false)

        shouldThrow<ServiceConnectionLostException> {
            handle.read(0L, buffer, 0, 64)
        }
    }

    @Test
    fun `read throws ServiceConnectionLostException on RemoteException`() {
        val remote = createMockRemote()
        val buffer = ByteArray(64)
        every { remote.read(0L, buffer, 0, 64) } throws RemoteException("connection lost")

        val handle = remote.fileHandle(readWrite = false)

        shouldThrow<ServiceConnectionLostException> {
            handle.read(0L, buffer, 0, 64)
        }
    }

    @Test
    fun `close delegates to remote`() {
        val remote = createMockRemote()

        val handle = remote.fileHandle(readWrite = false)
        handle.close()

        verify { remote.close() }
    }
}
