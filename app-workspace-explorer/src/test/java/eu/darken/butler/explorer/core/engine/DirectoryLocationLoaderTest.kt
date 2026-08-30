package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.uuid.Uuid

class DirectoryLocationLoaderTest : BaseTest() {

    private val gatewaySwitch = mockk<GatewaySwitch>(relaxed = true)

    private val pathPermissionCheck = mockk<PathPermissionCheck>().apply {
        every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements())
    }

    private fun loader() = DirectoryLocationLoader(
        workspaceId = Workspace.Id(),
        gatewaySwitch = gatewaySwitch,
        pathPermissionCheck = pathPermissionCheck,
        storageEnvironment = mockk(relaxed = true),
        metadataRepo = mockk(relaxed = true),
        rootManager = mockk(relaxed = true),
        adbManager = mockk(relaxed = true),
        safLocationManager = mockk(relaxed = true),
        writabilityEvaluator = WritabilityEvaluator(),
        folderPreviewResolver = mockk(relaxed = true),
    )

    init {
        coEvery { gatewaySwitch.useRes<Any?>(any()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.getFileSystem(any()) } returns FileSystem(freeSpace = 1L, totalSpace = 2L)
        coEvery { gatewaySwitch.listFiles(any()) } returns emptyList()
        coEvery { gatewaySwitch.lookupFiles(any(), any<LookupOptions>()) } returns emptyList()
    }

    @Test
    fun `an SMB directory is writable even though the extended pass is skipped`() = runTest {
        val path = SmbPath(LOCATION_ID, listOf("media"))

        val emissions = loader().loadDirectory(path).toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Directory>()
        last.info?.isWritable shouldBe true
        last.progress shouldBe null

        // The extended pass stays skipped, no extra round trip over the network.
        coVerify(exactly = 0) { gatewaySwitch.lookup(any(), any<LookupOptions>()) }
    }

    @Test
    fun `a local target that is gone fails as not found`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("no such file")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        shouldThrow<PathNotFoundException> { loader().loadDirectory(LOCAL_PATH).toList() }
    }

    @Test
    fun `a target that is still there keeps its original error`() = runTest {
        val original = IOException("something else")
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.PRESENT

        shouldThrow<IOException> { loader().loadDirectory(LOCAL_PATH).toList() } shouldBe original
    }

    @Test
    fun `a probe that cannot tell keeps the original error`() = runTest {
        val original = IOException("something else")
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN

        shouldThrow<IOException> { loader().loadDirectory(LOCAL_PATH).toList() } shouldBe original
    }

    @Test
    fun `a failing existence probe keeps the original error`() = runTest {
        val original = IOException("something else")
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } throws IOException("probe broke")

        shouldThrow<IOException> { loader().loadDirectory(LOCAL_PATH).toList() } shouldBe original
    }

    @Test
    fun `a target that vanishes after the peek also fails as not found`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } returns emptyList()
        coEvery { gatewaySwitch.lookupFiles(any(), any<LookupOptions>()) } throws IOException("no such file")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        shouldThrow<PathNotFoundException> { loader().loadDirectory(LOCAL_PATH).toList() }
    }

    /**
     * flatMapLatest runs the load in a child coroutine, so a cancellation coming out of it ends the
     * flow quietly instead of surfacing here. What must not happen is a probe or a not-found error.
     */
    @Test
    fun `a cancelled load is never probed`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws CancellationException("cancelled")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        loader().loadDirectory(LOCAL_PATH).toList()

        coVerify(exactly = 0) { gatewaySwitch.existsStrict(any()) }
    }

    /** A cancelled probe must not fall back to the original error either, see above. */
    @Test
    fun `a cancelled probe ends the load`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("no such file")
        coEvery { gatewaySwitch.existsStrict(any()) } throws CancellationException("cancelled")

        loader().loadDirectory(LOCAL_PATH).toList()
    }

    /**
     * The probe can outlive the coroutine that started it: a load cancelled while it ran must not
     * end as "folder gone" for a target the user has already navigated away from.
     */
    @Test
    fun `a probe that returns after cancellation is not an answer`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("no such file")
        coEvery { gatewaySwitch.existsStrict(any()) } coAnswers {
            currentCoroutineContext().cancel()
            Existence.ABSENT
        }

        val thrown = runCatching { loader().loadDirectory(LOCAL_PATH).toList() }.exceptionOrNull()

        val reported = listOfNotNull(thrown) + thrown?.suppressedExceptions.orEmpty()
        reported.any { it is PathNotFoundException } shouldBe false
        coVerify(exactly = 1) { gatewaySwitch.existsStrict(any()) }
    }

    @Test
    fun `an unreachable share is never reported as a missing directory`() = runTest {
        val original = IOException("host unreachable")
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN

        val path = SmbPath(LOCATION_ID, listOf("media"))

        shouldThrow<IOException> { loader().loadDirectory(path).toList() } shouldBe original
    }

    @Test
    fun `a share folder that is gone fails as not found`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("no such file")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        val path = SmbPath(LOCATION_ID, listOf("media"))

        shouldThrow<PathNotFoundException> { loader().loadDirectory(path).toList() }
    }

    /** A provider that is unreachable, crashing or mid-update answers UNKNOWN, not ABSENT. */
    @Test
    fun `a failing document provider is never reported as a missing directory`() = runTest {
        val original = IOException("provider died")
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN

        val path = SAFPath(SAF_TREE_ROOT, listOf("Music"))

        shouldThrow<IOException> { loader().loadDirectory(path).toList() } shouldBe original
    }

    @Test
    fun `a SAF folder that is gone fails as not found`() = runTest {
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("no such document")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        val path = SAFPath(SAF_TREE_ROOT, listOf("Music"))

        shouldThrow<PathNotFoundException> { loader().loadDirectory(path).toList() }
    }

    @Test
    fun `a stream-only archive keeps its own error`() = runTest {
        val container = LocalPath.build("/storage/emulated/0/Download/archive.zip")
        val original = ArchiveNotSeekableException(container)
        coEvery { gatewaySwitch.listFiles(any()) } throws original
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN

        val path = ArchivePath(container, emptyList())

        shouldThrow<ArchiveNotSeekableException> { loader().loadDirectory(path).toList() } shouldBe original
    }

    @Test
    fun `an archive folder that is gone fails as not found`() = runTest {
        val container = LocalPath.build("/storage/emulated/0/Download/archive.zip")
        coEvery { gatewaySwitch.listFiles(any()) } throws IOException("entry not found")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        val path = ArchivePath(container, listOf("docs"))

        shouldThrow<PathNotFoundException> { loader().loadDirectory(path).toList() }
    }

    companion object {
        private val LOCATION_ID = Uuid.parse("11111111-2222-3333-4444-555555555555")
        private val LOCAL_PATH = LocalPath.build("/data/data/eu.darken.butler")
        private const val SAF_TREE_ROOT = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    }
}
