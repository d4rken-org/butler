package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.ViewerBrokenSymlinkException
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerExternalChange
import eu.darken.butler.viewer.core.ViewerFileGoneException
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * How the external-change verdict reaches the page, and what Refresh has to survive: a render error
 * from a superseded attempt must never outlive the source that reported it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerWorkspaceViewModelExternalChangeTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val filePath = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val source = ViewerSource.Stored(filePath)
    private val mime = MimeInfo("image/jpeg")

    private lateinit var workspaceState: MutableStateFlow<ViewerWorkspace.State>
    private lateinit var workspace: ViewerWorkspace

    /** The error callbacks handed to each image source, in creation order. */
    private val sourceErrorSinks = mutableListOf<(Throwable) -> Unit>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        sourceErrorSinks.clear()
        workspaceState = MutableStateFlow(ViewerWorkspace.State(content = ViewerContent.Image(mime)))
        workspace = mockk<ViewerWorkspace>().apply {
            every { state } returns workspaceState
            every { source } returns this@ViewerWorkspaceViewModelExternalChangeTest.source
            every { storedPath } returns filePath
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "photo.jpg".toCaString()),
            )
            every { reload() } just Runs
            coEvery { checkExternalChange() } just Runs
        }
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): ViewerWorkspaceViewModel {
        val chrome = mockk<WorkspacePageChrome>().apply {
            every { shareIntentEvent } returns SingleEventFlow()
            every { pendingConflicts } returns flowOf(emptyMap())
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns flowOf(workspace)
            },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
            },
            imageSourceFactory = mockk<GatewayZoomableImageSource.Factory>().apply {
                every { create(any(), any()) } answers {
                    sourceErrorSinks.add(secondArg())
                    mockk(relaxed = true)
                }
            },
            pdfPreviewLoader = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns flowOf(false)
            },
            operationsManager = mockk(relaxed = true),
            appInstallInspector = mockk(relaxed = true),
            appInstaller = mockk(relaxed = true),
            appInstallOperationFactory = mockk(relaxed = true),
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns chrome
            },
        )
    }

    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private val ViewerWorkspaceViewModel.readyState: ViewerWorkspaceViewModel.State.Ready
        get() = state.value as ViewerWorkspaceViewModel.State.Ready

    @Test
    fun `the workspace verdict reaches the page state`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.readyState.externalChange shouldBe null

        workspaceState.value = workspaceState.value.copy(externalChange = ViewerExternalChange.Modified)
        vm.readyState.externalChange shouldBe ViewerExternalChange.Modified

        workspaceState.value = workspaceState.value.copy(externalChange = ViewerExternalChange.Gone)
        vm.readyState.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `a file that is gone keeps only the actions that do not need it`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        workspaceState.value = workspaceState.value.copy(externalChange = ViewerExternalChange.Gone)

        vm.readyState.actions shouldContainExactly listOf(
            ViewerActionBarItem.OpenLocation(isEnabled = true),
        )
    }

    @Test
    fun `refreshing a gone file does not bring its actions back`() = runTest2 {
        // The reload assigns a fresh state, which clears the probe's verdict and reports the loss as
        // a failure instead. The actions must not reappear next to the "file is gone" card.
        val vm = makeViewModel()
        startCollecting(vm)

        workspaceState.value = workspaceState.value.copy(externalChange = ViewerExternalChange.Gone)

        vm.retry()
        workspaceState.value = ViewerWorkspace.State(
            content = ViewerContent.Failed(ViewerFileGoneException(filePath)),
        )

        vm.readyState.externalChange shouldBe null
        vm.readyState.actions shouldContainExactly listOf(
            ViewerActionBarItem.OpenLocation(isEnabled = true),
        )
    }

    @Test
    fun `refreshing a symlink whose target is gone does not bring its actions back`() = runTest2 {
        // The link itself still resolves, so the reload succeeds far enough to reject the target as
        // broken. That failure means the content is just as unreachable as a deleted file's.
        val vm = makeViewModel()
        startCollecting(vm)

        workspaceState.value = workspaceState.value.copy(externalChange = ViewerExternalChange.Gone)

        vm.retry()
        workspaceState.value = ViewerWorkspace.State(
            content = ViewerContent.Failed(ViewerBrokenSymlinkException(filePath)),
        )

        vm.readyState.externalChange shouldBe null
        vm.readyState.actions shouldContainExactly listOf(
            ViewerActionBarItem.OpenLocation(isEnabled = true),
        )
    }

    @Test
    fun `the poll hands the probe to the workspace`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.checkExternalChange()

        coVerify(exactly = 1) { workspace.checkExternalChange() }
    }

    @Test
    fun `a render error from a superseded attempt does not survive the refresh`() = runTest2 {
        // Refresh disposes the old image source, which reports its failure afterwards. Landing it in
        // the single render-error slot would fail the picture that was just reloaded.
        val vm = makeViewModel()
        startCollecting(vm)
        sourceErrorSinks.size shouldBe 1

        vm.retry()
        sourceErrorSinks.size shouldBe 2

        sourceErrorSinks[0].invoke(IllegalStateException("late decode failure"))
        vm.readyState.content.shouldBeInstanceOf<ViewerContent.Image>()

        sourceErrorSinks[1].invoke(IllegalStateException("current decode failure"))
        vm.readyState.content.shouldBeInstanceOf<ViewerContent.Failed>()
    }

    @Test
    fun `the workspace's own failure outranks a render error`() = runTest2 {
        // On a deleted file the refresh briefly composes an image source against nothing, and its
        // generic decode error would replace the accurate "file is gone" card.
        val vm = makeViewModel()
        startCollecting(vm)

        sourceErrorSinks[0].invoke(IllegalStateException("decode failure"))
        vm.readyState.content.shouldBeInstanceOf<ViewerContent.Failed>()

        val gone = IllegalStateException("file is gone")
        workspaceState.value = ViewerWorkspace.State(content = ViewerContent.Failed(gone))

        vm.readyState.content.shouldBeInstanceOf<ViewerContent.Failed>().error shouldBe gone
    }
}
