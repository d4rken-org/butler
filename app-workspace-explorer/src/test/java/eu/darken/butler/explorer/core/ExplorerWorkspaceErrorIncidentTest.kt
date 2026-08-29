package eu.darken.butler.explorer.core

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The engine republishes its failure on every state change around it. Re-freezing on each of those
 * would restamp the report with a time and a state from long after the navigation actually failed,
 * and spool a second log trail for the same incident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceErrorIncidentTest {

    private val home = ExplorerNavigation.Target.Home
    private val engineLocation = MutableStateFlow(BrowsingEngine.State())
    private val engine = mockk<BrowsingEngine>(relaxed = true).apply {
        every { location } returns engineLocation
    }
    private val incidentFactory: ErrorIncidentFactory = recordingIncidentFactory()

    private fun TestScope.startedOnHome() = testExplorerWorkspace(
        ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
        UnconfinedTestDispatcher(testScheduler),
        browsingEngine = engine,
        errorIncidentFactory = incidentFactory,
    )

    private suspend fun ExplorerWorkspace.ready() = state.first() as ExplorerWorkspace.State.Ready

    private fun failed(
        error: Throwable,
        breadcrumbs: List<ExplorerBreadcrumb>?,
        refreshId: Int,
    ) = BrowsingEngine.State(
        location = ExplorerLocation.Home(items = emptyList(), progress = null),
        error = error,
        breadcrumbs = breadcrumbs,
        target = home,
        refreshId = refreshId,
    )

    private fun breadcrumb(path: String) = ExplorerBreadcrumb(
        target = ExplorerNavigation.Target.Directory(LocalPath.build(path)),
        label = path.toCaString(),
        icon = mockk(),
    )

    @Test
    fun `the same failure republished keeps the incident it was frozen with`() = runTest {
        val boom = IOException("nope")
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(boom, breadcrumbs = listOf(breadcrumb("/sdcard")), refreshId = 1)
            advanceUntilIdle()

            val first = workspace.ready().errorIncident.shouldNotBeNull()

            engineLocation.value = failed(boom, breadcrumbs = listOf(breadcrumb("/sdcard/Pictures")), refreshId = 7)
            advanceUntilIdle()

            val second = workspace.ready().errorIncident.shouldNotBeNull()
            second.incidentId shouldBe first.incidentId
            second.occurredAt shouldBe first.occurredAt
            second.context shouldBe first.context
            second.context["nav.refreshId"] shouldBe "1"

            // One freeze means one spooled log trail for this failure
            coVerify(exactly = 1) { incidentFactory.freeze(boom, any(), any()) }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a different failure is frozen anew`() = runTest {
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(IOException("first"), breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()
            val first = workspace.ready().errorIncident.shouldNotBeNull()

            engineLocation.value = failed(IOException("second"), breadcrumbs = null, refreshId = 2)
            advanceUntilIdle()
            val second = workspace.ready().errorIncident.shouldNotBeNull()

            second.incidentId shouldNotBe first.incidentId
            second.error.message shouldBe "second"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a cleared failure drops the incident`() = runTest {
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(IOException("nope"), breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()
            workspace.ready().errorIncident.shouldNotBeNull()

            engineLocation.value = BrowsingEngine.State(
                location = ExplorerLocation.Home(items = emptyList(), progress = null),
                breadcrumbs = emptyList(),
                target = home,
            )
            advanceUntilIdle()

            workspace.ready().errorIncident shouldBe null
            workspace.ready().error shouldBe null
        } finally {
            workspace.release()
        }
    }
}
