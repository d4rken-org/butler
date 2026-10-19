package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.archive.ArchivePasswordStore
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * A step to the next file builds a fresh viewer that has nothing to show until its lookup returns.
 * The pane only draws a Ready workspace, so anything else blanks the whole page for that wait.
 */
class ViewerWorkspaceLifecycleTest : BaseTest() {

    private val imagePath = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")
    private val gatewaySwitch = mockk<GatewaySwitch>()

    /** A lookup that never answers, so the content stays [ViewerContent.Loading]. */
    private fun setupHangingLookup() {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.lookup(any(), any()) } coAnswers { awaitCancellation() }
    }

    private fun workspace(dispatcher: CoroutineDispatcher? = null) = ViewerWorkspace(
        id = Workspace.Id(),
        creationArguments = ViewerArguments.Default(filePath = imagePath),
        dispatcherProvider = TestDispatcherProvider(dispatcher),
        gatewaySwitch = gatewaySwitch,
        imageProbe = mockk(),
        contentReader = readerFor(gatewaySwitch),
        apkArchiveParser = mockk(),
        appInstallInspector = mockk(relaxed = true),
        pkgRepo = mockk(),
        userManager2 = mockk(),
        pdfPreviewLoader = mockk(),
        textPreviewLoader = mockk(),
        operationsManager = mockk(relaxed = true),
        issueHandler = mockk(relaxed = true),
        deleteOperationFactory = mockk(relaxed = true),
        archivePasswordStore = ArchivePasswordStore(),
    )

    @Test
    fun `a viewer whose file is still loading reports itself ready`() = runTest2 {
        setupHangingLookup()
        val workspace = workspace()
        try {
            coVerify { gatewaySwitch.lookup(imagePath, any()) }
            workspace.state.value.content shouldBe ViewerContent.Loading

            workspace.info.first().lifecycleState shouldBe Workspace.LifecycleState.Ready
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a viewer is ready before its info flow first runs`() = runTest2 {
        setupHangingLookup()
        // Nothing launched on the workspace scope runs until the scheduler is advanced, so what
        // info holds here is the seed a freshly stepped-to viewer is first read with.
        val workspace = workspace(StandardTestDispatcher(testScheduler))
        try {
            workspace.info.value.lifecycleState shouldBe Workspace.LifecycleState.Ready
        } finally {
            workspace.release()
        }
    }
}
