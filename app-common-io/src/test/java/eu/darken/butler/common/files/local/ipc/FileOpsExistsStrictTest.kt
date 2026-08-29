package eu.darken.butler.common.files.local.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalFileSystemOps
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException

/**
 * The existence answer crosses the binder as an int, so both ends have to agree on the mapping and
 * neither may turn a transport failure into an answer about the path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FileOpsExistsStrictTest : BaseTest() {

    private val path = LocalPath.build("/data/data/eu.darken.butler")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = mockk<FileOpsConnection>()
    private val client = FileOpsClient(connection)
    private val hostOps = mockk<LocalFileSystemOps>()

    private fun host() = FileOpsHost(
        context = ApplicationProvider.getApplicationContext<Context>(),
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(),
        fileSystemOps = hostOps,
    )

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun `every answer survives the round trip`() = runTest {
        Existence.entries.forEach { existence ->
            coEvery { hostOps.existsStrict(path) } returns existence
            every { connection.existsStrict(path) } returns host().existsStrict(path)

            client.existsStrict(path) shouldBe existence
        }
    }

    /** A host on an older build answering with a code this one does not know is not an answer. */
    @Test
    fun `an unknown code reads as unknown`() = runTest {
        every { connection.existsStrict(path) } returns 42

        client.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `a failed transport is not an answer`() = runTest {
        every { connection.existsStrict(path) } throws IOException("binder died")

        client.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `cancellation propagates instead of becoming an answer`() = runTest {
        every { connection.existsStrict(path) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> { client.existsStrict(path) }
    }

    @Test
    fun `the host reports a backend failure as unknown instead of throwing`() {
        coEvery { hostOps.existsStrict(path) } throws IOException("stat blew up")

        host().existsStrict(path) shouldBe Existence.UNKNOWN.ipcCode
    }

    @Test
    fun `the host passes a definitive backend answer through`() {
        coEvery { hostOps.existsStrict(path) } returns Existence.ABSENT

        host().existsStrict(path) shouldBe Existence.ABSENT.ipcCode
    }
}
