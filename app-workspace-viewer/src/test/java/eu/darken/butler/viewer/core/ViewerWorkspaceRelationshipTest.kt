package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * A viewer opened from a file list is a drill-down of that list. The relationship has to reach
 * [Workspace.Info] - the repo reads it synchronously for child cleanup and unit pausing - and it has
 * to survive [Workspace.createArguments], which is what a pause captures and a resume rebuilds from.
 */
class ViewerWorkspaceRelationshipTest : BaseTest() {

    private val imagePath = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val imageProbe = mockk<ImageProbe>()

    @Suppress("UNCHECKED_CAST")
    private fun setupGateway() {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.lookup(any(), any()) } coAnswers {
            LocalPathLookup(
                lookedUp = imagePath,
                fileType = FileType.FILE,
                size = 1024L,
                modifiedAt = null,
            ) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.exists(any()) } returns true
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
    }

    private fun workspace(arguments: ViewerArguments) = ViewerWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        dispatcherProvider = TestDispatcherProvider(),
        gatewaySwitch = gatewaySwitch,
        contentReader = readerFor(gatewaySwitch),
        imageProbe = imageProbe,
        apkArchiveParser = mockk(relaxed = true),
        appInstallInspector = mockk(relaxed = true),
        pkgRepo = mockk(relaxed = true),
        userManager2 = mockk(relaxed = true),
        pdfPreviewLoader = mockk(relaxed = true),
        textPreviewLoader = mockk(relaxed = true),
        operationsManager = mockk(relaxed = true),
        issueHandler = mockk(relaxed = true),
        deleteOperationFactory = mockk(relaxed = true),
    )

    @Test
    fun `a drill-down publishes its caller, presentation and pausability`() = runTest2 {
        setupGateway()
        val caller = Workspace.Id()

        val info = workspace(ViewerArguments.Default(filePath = imagePath, callerWorkspaceId = caller)).info.value

        info.callerWorkspaceId shouldBe caller
        info.isSubWorkspace shouldBe true
        info.modalPresentation shouldBe Workspace.ModalPresentationMode.PANE_LOCAL
        info.pausableAsChild shouldBe true
    }

    @Test
    fun `a tab viewer publishes no caller`() = runTest2 {
        setupGateway()

        val info = workspace(ViewerArguments.Default(filePath = imagePath)).info.value

        info.callerWorkspaceId shouldBe null
        info.isSubWorkspace shouldBe false
    }

    @Test
    fun `a drill-down still publishes its content path`() = runTest2 {
        setupGateway()

        val info = workspace(
            ViewerArguments.Default(filePath = imagePath, callerWorkspaceId = Workspace.Id())
        ).info.value

        info.contentPath shouldBe imagePath
    }

    /** A pause that dropped the caller would resume the viewer as a tab of its own. */
    @Test
    fun `createArguments preserves the caller`() = runTest2 {
        setupGateway()
        val caller = Workspace.Id()
        val arguments = ViewerArguments.Default(filePath = imagePath, callerWorkspaceId = caller)

        val captured = workspace(arguments).createArguments()

        captured shouldBe arguments
        (captured as Workspace.ArgumentsWithCaller).callerWorkspaceId shouldBe caller
    }

    @Test
    fun `createArguments keeps a tab viewer a tab`() = runTest2 {
        setupGateway()
        val arguments = ViewerArguments.Default(filePath = imagePath)

        val captured = workspace(arguments).createArguments()

        (captured as Workspace.ArgumentsWithCaller).callerWorkspaceId shouldBe null
    }
}
