package eu.darken.butler.workspace.ui.manager.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class WorkspacePreviewCaptureServiceTest : BaseTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    private val bitmap = mockk<Bitmap>()
    private val renderer = mockk<ComposableBitmapRenderer>().apply {
        coEvery { renderToBitmap(any(), any(), any(), any(), any()) } returns bitmap
    }
    private val generalSettings = mockk<GeneralSettings>().apply {
        every { themeMode } returns mockk { every { flow } returns flowOf(ThemeMode.SYSTEM) }
        every { themeStyle } returns mockk { every { flow } returns flowOf(ThemeStyle.DEFAULT) }
        every { themeColor } returns mockk { every { flow } returns flowOf(ThemeColor.GREEN) }
    }
    private val pauseGate = WorkspacePauseGate()

    private fun info(id: Workspace.Id, lifecycleState: Workspace.LifecycleState) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace".toCaString(),
        lifecycleState = lifecycleState,
    )

    private val repoState = MutableStateFlow(
        WorkspaceRemote.State(
            infos = listOf(
                info(idA, Workspace.LifecycleState.Ready),
                info(idB, Workspace.LifecycleState.Ready),
            ),
        )
    )
    private val workspaceRepo = mockk<WorkspaceRepo>().apply {
        every { state } returns repoState
    }

    private val service = WorkspacePreviewCaptureService(
        composableBitmapRenderer = renderer,
        dispatcherProvider = TestDispatcherProvider(),
        generalSettings = generalSettings,
        workspacePauseGate = pauseGate,
        workspaceRepo = workspaceRepo,
        pageHosts = emptyMap(),
    )

    private suspend fun capture(id: Workspace.Id) = service.captureWorkspace(
        workspaceId = id,
        workspaceType = Workspace.Type.EXPLORER,
        size = DpSize(width = 360.dp, height = 640.dp),
        captureContext = mockk<Context>(),
    )

    @Test
    fun `a capture waits while the same workspace is being paused`() = runTest(UnconfinedTestDispatcher()) {
        val pauseDone = CompletableDeferred<Unit>()
        // Stands in for a pause, which holds the same lease across createArguments() and the swap
        val pause = launch { pauseGate.withLease(idA) { pauseDone.await() } }

        val capture = async { capture(idA) }
        coVerify(exactly = 0) { renderer.renderToBitmap(any(), any(), any(), any(), any()) }

        pauseDone.complete(Unit)
        pause.join()

        // The lease holder left the workspace live, so the re-validation must not block the capture
        capture.await() shouldBe bitmap
        coVerify(exactly = 1) { renderer.renderToBitmap(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a capture bails out if the workspace got paused while it waited`() = runTest(UnconfinedTestDispatcher()) {
        val pauseDone = CompletableDeferred<Unit>()
        // A manual pause sticks: it flips the workspace to paused and does not resume it afterwards
        val pause = launch {
            pauseGate.withLease(idA) {
                pauseDone.await()
                repoState.value = WorkspaceRemote.State(
                    infos = listOf(
                        info(idA, Workspace.LifecycleState.Paused()),
                        info(idB, Workspace.LifecycleState.Ready),
                    ),
                )
            }
        }

        val capture = async { capture(idA) }
        pauseDone.complete(Unit)
        pause.join()

        capture.await() shouldBe null
        coVerify(exactly = 0) { renderer.renderToBitmap(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a capture bails out if the workspace vanished while it waited`() = runTest(UnconfinedTestDispatcher()) {
        val closeDone = CompletableDeferred<Unit>()
        val close = launch {
            pauseGate.withLease(idA) {
                closeDone.await()
                repoState.value = WorkspaceRemote.State(
                    infos = listOf(info(idB, Workspace.LifecycleState.Ready)),
                )
            }
        }

        val capture = async { capture(idA) }
        closeDone.complete(Unit)
        close.join()

        capture.await() shouldBe null
        coVerify(exactly = 0) { renderer.renderToBitmap(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a capture is not blocked by a pause of another workspace`() = runTest(UnconfinedTestDispatcher()) {
        val pauseDone = CompletableDeferred<Unit>()
        val pause = launch { pauseGate.withLease(idB) { pauseDone.await() } }

        capture(idA) shouldBe bitmap

        pauseDone.complete(Unit)
        pause.join()
    }
}
