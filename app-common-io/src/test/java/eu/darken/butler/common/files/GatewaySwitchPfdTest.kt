package eu.darken.butler.common.files

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Covers which lane [GatewaySwitch.openReadPFD] serves a path from, and that no descriptor is left
 * open when a lane gives up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class GatewaySwitchPfdTest : BaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"
    private val safPath = SAFPath.build(treeUri, "file")
    private val archivePath = ArchivePath(
        container = LocalPath.build("storage", "emulated", "0", "a.zip"),
        segments = listOf("entry.txt"),
    )

    private lateinit var localGateway: LocalGateway
    private lateinit var safGateway: SAFGateway
    private lateinit var archiveGateway: ArchiveGateway
    private lateinit var safLocationManager: SAFLocationManager
    private lateinit var proxyPfdFactory: ProxyPfdFactory
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        localGateway = mockk(relaxed = true)
        safGateway = mockk(relaxed = true)
        archiveGateway = mockk(relaxed = true)
        safLocationManager = mockk(relaxed = true)
        proxyPfdFactory = mockk(relaxed = true)
        gatewaySwitch = GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            safGateway = safGateway,
            localGateway = localGateway,
            archiveGateway = archiveGateway,
            safLocationManager = safLocationManager,
            proxyPfdFactory = proxyPfdFactory,
        )
    }

    private fun seekablePfd(size: Long = 42L): ParcelFileDescriptor = mockk(relaxed = true) {
        every { statSize } returns size
    }

    @Test
    fun `a readable local file is opened directly, without gateway or proxy`() = runTest {
        val file = tempFolder.newFile("readable.txt").apply { writeText("hello") }

        val pfd = gatewaySwitch.openReadPFD(LocalPath.build(file))

        pfd shouldNotBe null
        pfd!!.close()
        coVerify(exactly = 0) { localGateway.file(any(), any()) }
        verify(exactly = 0) { proxyPfdFactory.create(any(), any()) }
    }

    @Test
    fun `a local file the app cannot open falls through to the proxy lane`() = runTest {
        // Root- and ADB-routed paths look exactly like this from here: nothing to open directly.
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        val handle = mockk<FileHandle>(relaxed = true)
        val proxyPfd = seekablePfd()
        coEvery { localGateway.file(path, false) } returns handle
        every { proxyPfdFactory.create(handle, "r") } returns proxyPfd

        gatewaySwitch.openReadPFD(path) shouldBe proxyPfd

        coVerify(exactly = 1) { localGateway.file(path, false) }
        verify(exactly = 1) { proxyPfdFactory.create(handle, "r") }
    }

    @Test
    fun `a gateway that cannot hand out a handle resolves to no preview`() = runTest {
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        coEvery { localGateway.file(path, false) } throws ReadException(path = path)

        gatewaySwitch.openReadPFD(path) shouldBe null
    }

    @Test
    fun `a failing proxy creation resolves to no preview`() = runTest {
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        val handle = mockk<FileHandle>(relaxed = true)
        coEvery { localGateway.file(path, false) } returns handle
        every { proxyPfdFactory.create(handle, "r") } throws IllegalStateException("no proxy fd")

        gatewaySwitch.openReadPFD(path) shouldBe null
    }

    @Test
    fun `archive entries never reach the proxy lane`() = runTest {
        gatewaySwitch.openReadPFD(archivePath) shouldBe null

        verify(exactly = 0) { proxyPfdFactory.create(any(), any()) }
    }

    @Test
    fun `a SAF path is served by the SAF gateway`() = runTest {
        val safPfd = seekablePfd()
        coEvery { safGateway.openReadPFD(safPath) } returns safPfd

        gatewaySwitch.openReadPFD(safPath) shouldBe safPfd

        verify(exactly = 0) { proxyPfdFactory.create(any(), any()) }
    }

    @Test
    fun `a non-seekable proxy descriptor resolves to no preview`() = runTest {
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        val handle = mockk<FileHandle>(relaxed = true)
        val proxyPfd = seekablePfd(size = -1L)
        coEvery { localGateway.file(path, false) } returns handle
        every { proxyPfdFactory.create(handle, "r") } returns proxyPfd

        gatewaySwitch.openReadPFD(path) shouldBe null

        verify { proxyPfd.close() }
    }

    @Test
    fun `a proxy descriptor whose size query fails is closed, not leaked`() = runTest {
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        val handle = mockk<FileHandle>(relaxed = true)
        val proxyPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { statSize } throws IllegalStateException("statSize blew up")
        }
        coEvery { localGateway.file(path, false) } returns handle
        every { proxyPfdFactory.create(handle, "r") } returns proxyPfd

        gatewaySwitch.openReadPFD(path) shouldBe null

        verify { proxyPfd.close() }
    }

    @Test
    fun `a proxy descriptor created for a cancelled caller is closed, not leaked`() = runTest {
        val path = LocalPath.build("storage", "emulated", "0", "not-there")
        val handle = mockk<FileHandle>(relaxed = true)
        val proxyPfd = seekablePfd()
        val createEntered = CompletableDeferred<Unit>()
        val createMayFinish = CompletableDeferred<Unit>()
        coEvery { localGateway.file(path, false) } returns handle
        every { proxyPfdFactory.create(handle, "r") } answers {
            createEntered.complete(Unit)
            runBlocking { createMayFinish.await() }
            proxyPfd
        }
        // Real dispatching: the caller has to be cancellable while create() is blocked mid-handoff.
        val switch = GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
            safGateway = safGateway,
            localGateway = localGateway,
            archiveGateway = archiveGateway,
            safLocationManager = safLocationManager,
            proxyPfdFactory = proxyPfdFactory,
        )

        // Caller on another dispatcher: only then is the hand-back a real resume, which is where a
        // cancelled caller drops the descriptor.
        val job = launch(Dispatchers.Default) { switch.openReadPFD(path) }
        createEntered.await()
        job.cancel()
        createMayFinish.complete(Unit)
        job.join()

        verify { proxyPfd.close() }
    }

    @Test
    fun `a SAF descriptor whose size query fails is closed, not leaked`() = runTest {
        val safPfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { statSize } throws IllegalStateException("statSize blew up")
        }
        coEvery { safGateway.openReadPFD(safPath) } returns safPfd

        gatewaySwitch.openReadPFD(safPath) shouldBe null

        verify { safPfd.close() }
    }
}
