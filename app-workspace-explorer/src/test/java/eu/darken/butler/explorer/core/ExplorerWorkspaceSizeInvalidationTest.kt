package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.sizes.DirectoryScan
import eu.darken.butler.explorer.core.sizes.DirectorySize
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * A file operation anywhere under a scanned folder makes its totals wrong, so the tab drops them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceSizeInvalidationTest {

    private val scanned = LocalPath.build("/a")
    private val engineLocation = MutableStateFlow(BrowsingEngine.State())
    private val engine = mockk<BrowsingEngine>(relaxed = true).apply {
        every { location } returns engineLocation
    }
    private val hinter = FileSystemHinter()

    private fun lookup(path: String) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = 1L,
        modifiedAt = null,
    )

    private val scan = DirectoryScan(
        root = scanned,
        scannedAt = Instant.parse("2026-09-07T14:32:00Z"),
        sizes = mapOf("/a" to DirectorySize(10L, true)),
        itemCount = 1,
        errorCount = 0,
    )

    private fun TestScope.workspace() = testExplorerWorkspace(
        ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
        UnconfinedTestDispatcher(testScheduler),
        browsingEngine = engine,
        fileSystemHinter = hinter,
    )

    @Test
    fun `a deletion under a scanned folder drops its sizes`() = runTest {
        val workspace = workspace()

        try {
            advanceUntilIdle()
            workspace.directorySizes.publish(scan)

            hinter.trackPathsRemoved(Operation.Id(), listOf(lookup("/a/file")))
            advanceUntilIdle()

            workspace.directorySizes.snapshot.value.scanFor(scanned) shouldBe null
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a deletion elsewhere leaves them alone`() = runTest {
        val workspace = workspace()

        try {
            advanceUntilIdle()
            workspace.directorySizes.publish(scan)

            hinter.trackPathsRemoved(Operation.Id(), listOf(lookup("/b/file")))
            advanceUntilIdle()

            workspace.directorySizes.snapshot.value.scanFor(scanned).shouldNotBeNull()
        } finally {
            workspace.release()
        }
    }
}
