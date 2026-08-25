package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
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

    companion object {
        private val LOCATION_ID = Uuid.parse("11111111-2222-3333-4444-555555555555")
    }
}
