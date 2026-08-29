package eu.darken.butler.explorer.core

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.BrowsingAbortedException
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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The engine republishes its failure on every state change around it, and a retry clears the error
 * card before the same failure comes back. Neither may produce a second incident: the report would
 * carry a time and a state from long after the navigation failed, plus a second log trail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceErrorIncidentTest {

    private val home = ExplorerNavigation.Target.Home
    private val engineLocation = MutableStateFlow(BrowsingEngine.State())
    private val engine = mockk<BrowsingEngine>(relaxed = true).apply {
        every { location } returns engineLocation
    }
    private val spoolDir = Files.createTempDirectory("incident-spool").toFile()
    private val incidentFactory: ErrorIncidentFactory = recordingIncidentFactory(spoolDir)
    private val incidentStore = ErrorIncidentStore(incidentFactory)

    @After
    fun teardown() {
        spoolDir.deleteRecursively()
    }

    private fun TestScope.startedOnHome() = testExplorerWorkspace(
        ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
        UnconfinedTestDispatcher(testScheduler),
        browsingEngine = engine,
        errorIncidentStore = incidentStore,
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

    private fun loaded() = BrowsingEngine.State(
        location = ExplorerLocation.Home(items = emptyList(), progress = null),
        breadcrumbs = emptyList(),
        target = home,
    )

    private fun breadcrumb(path: String) = ExplorerBreadcrumb(
        target = ExplorerNavigation.Target.Directory(LocalPath.build(path)),
        label = path.toCaString(),
        icon = mockk(),
    )

    private fun spooledLogs(): List<File> = spoolDir.listFiles()?.toList() ?: emptyList()

    @Test
    fun `the same failure republished keeps the incident it was frozen with`() = runTest {
        val boom = IOException("nope")
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(boom, breadcrumbs = listOf(breadcrumb("/sdcard")), refreshId = 1)
            advanceUntilIdle()

            val first = incidentStore.get(boom).shouldNotBeNull()

            engineLocation.value = failed(boom, breadcrumbs = listOf(breadcrumb("/sdcard/Pictures")), refreshId = 7)
            advanceUntilIdle()

            val second = incidentStore.get(boom).shouldNotBeNull()
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
            val firstError = IOException("first")
            engineLocation.value = failed(firstError, breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()
            val first = incidentStore.get(firstError).shouldNotBeNull()

            val secondError = IOException("second")
            engineLocation.value = failed(secondError, breadcrumbs = null, refreshId = 2)
            advanceUntilIdle()
            val second = incidentStore.get(secondError).shouldNotBeNull()

            second.incidentId shouldNotBe first.incidentId
            second.error.message shouldBe "second"
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `clearing the error card keeps the incident the share still needs`() = runTest {
        val boom = IOException("nope")
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(boom, breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()
            val incident = incidentStore.get(boom).shouldNotBeNull()

            engineLocation.value = loaded()
            advanceUntilIdle()

            workspace.ready().error shouldBe null
            incidentStore.get(boom)?.incidentId shouldBe incident.incidentId
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a retry that fails the same way stays one incident`() = runTest {
        val boom = IOException("nope")
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(boom, breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()
            val first = incidentStore.get(boom).shouldNotBeNull()

            // What Retry does: the card is cleared, then the engine reports the same failure again.
            workspace.navigate(ExplorerNavigation.Refresh)
            advanceUntilIdle()
            workspace.ready().error shouldBe null
            engineLocation.value = failed(boom, breadcrumbs = null, refreshId = 2)
            advanceUntilIdle()

            val second = incidentStore.get(boom).shouldNotBeNull()
            second.incidentId shouldBe first.incidentId
            second.occurredAt shouldBe first.occurredAt
            spooledLogs().size shouldBe 1
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a cancelled load is never frozen`() = runTest {
        val aborted = BrowsingAbortedException(home)
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(aborted, breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()

            workspace.ready().error shouldBe aborted
            incidentStore.get(aborted) shouldBe null
            spooledLogs() shouldBe emptyList()
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `the share action hands over the incident the failure was remembered with`() = runTest {
        val sentinel = IOException("sentinel")
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = failed(sentinel, breadcrumbs = null, refreshId = 1)
            advanceUntilIdle()

            // What the page's share action does with the state it renders the card from.
            val shared = incidentStore.getOrFreeze(workspace.ready().error!!)

            shared.error shouldBe sentinel
            (shared.error === sentinel) shouldBe true
            shared.context.containsKey("incident.frozenAtShare") shouldBe false
        } finally {
            workspace.release()
        }
    }
}
