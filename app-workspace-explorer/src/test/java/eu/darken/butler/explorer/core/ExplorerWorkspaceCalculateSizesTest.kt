package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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
 * A running calculation holds its root, so a second tap - action bar or info bar chip - cannot
 * submit a duplicate walk of the same folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceCalculateSizesTest {

    private val directory = LocalPath.build("/sdcard/Download")
    private val engineLocation = MutableStateFlow(BrowsingEngine.State())
    private val engine = mockk<BrowsingEngine>(relaxed = true).apply {
        every { location } returns engineLocation
    }

    private fun managedOperation(state: Operation.State) = mockk<ManagedOperation>(relaxed = true).apply {
        every { id } returns Operation.Id()
        every { this@apply.state } returns MutableStateFlow(state)
    }

    private fun operationsManager(managed: ManagedOperation) = mockk<OperationsManager>(relaxed = true).apply {
        every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        coEvery { submitManaged(any()) } returns managed
    }

    private fun TestScope.workspace(operationsManager: OperationsManager) = testExplorerWorkspace(
        ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
        UnconfinedTestDispatcher(testScheduler),
        browsingEngine = engine,
        operationsManager = operationsManager,
    )

    @Test
    fun `a second request while one is running submits nothing`() = runTest {
        val manager = operationsManager(managedOperation(Operation.State.Queued(startedAt = Instant.DISTANT_PAST)))
        val workspace = workspace(manager)

        try {
            advanceUntilIdle()

            workspace.calculateSizes(directory)
            workspace.calculateSizes(directory)
            advanceUntilIdle()

            coVerify(exactly = 1) { manager.submitManaged(any()) }
            workspace.directorySizes.snapshot.value.isRunning(directory) shouldBe true
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `the reservation is released once the operation completes`() = runTest {
        val completed = mockk<Operation.State.Completed>(relaxed = true).apply {
            every { error } returns null
        }
        val manager = operationsManager(managedOperation(completed))
        val workspace = workspace(manager)

        try {
            advanceUntilIdle()

            workspace.calculateSizes(directory)
            advanceUntilIdle()

            workspace.directorySizes.snapshot.value.isRunning(directory) shouldBe false
        } finally {
            workspace.release()
        }
    }
}
