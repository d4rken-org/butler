package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import testhelpers.error.recordingIncidentStore

/**
 * The delete confirmation as the ViewModel drives it. Stepping to a neighbour replaces the workspace
 * under the same id, so a dialog opened for one file can still be up when another one is on display.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerWorkspaceViewModelDeleteTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val originId = Workspace.Id()

    private val a = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val b = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val c = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")

    /** Set to hold a file step's create inside the ViewModel. */
    private var createGate: CompletableDeferred<Unit>? = null

    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        createGate = null
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeWorkspace(path: APath<*>) = mockk<ViewerWorkspace>().apply {
        every { state } returns MutableStateFlow(
            ViewerWorkspace.State(content = ViewerContent.Image(MimeInfo("image/jpeg"))),
        )
        every { source } returns ViewerSource.Stored(path)
        every { storedPath } returns path
        every { listingSourceId } returns originId
        every { sharedCaption } returns null
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = path.name.toCaString()),
        )
        every { reload() } just Runs
        every { siblingArguments(any()) } answers {
            ViewerArguments.Default(filePath = firstArg(), listingSourceId = originId)
        }
        coEvery { delete(any()) } returns Operation.Id()
    }

    private val incidentStore = recordingIncidentStore()

    private fun makeViewModel(path: APath<*> = b): ViewerWorkspaceViewModel {
        workspaces = MutableStateFlow(makeWorkspace(path))
        val listing = MutableStateFlow(listOf(a, b, c))
        val origin = mockk<Workspace<Workspace.Arguments>>(
            moreInterfaces = arrayOf(Workspace.FileListingSource::class),
        ).apply {
            every { (this@apply as Workspace.FileListingSource).fileListing } returns listing
        }
        val remote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { events } returns emptyFlow()
            every { state } returns MutableStateFlow(
                WorkspaceRemote.State(
                    infos = listOf(
                        Workspace.Info(id = originId, type = Workspace.Type.EXPLORER, title = "DCIM".toCaString()),
                        Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = path.name.toCaString()),
                    ),
                ),
            )
            coEvery { execute(any()) } coAnswers {
                if (firstArg<WorkspaceAction>() is WorkspaceAction.Create) createGate?.await()
                WorkspaceAction.Create.Result.Success(workspaceId)
            }
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns workspaces
                every { retrieve(originId) } returns MutableStateFlow(origin)
            },
            workspaceRemote = remote,
            imageSourceFactory = mockk(relaxed = true),
            pdfPreviewLoader = mockk(relaxed = true),
            textPreviewLoader = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns flowOf(false)
            },
            // Never completes: these cases are about what gets submitted, not about the outcome.
            operationsManager = mockk<OperationsManager>(relaxed = true).apply {
                every { completedOperations } returns MutableSharedFlow()
            },
            appInstallLauncher = mockk(relaxed = true),
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            errorIncidentStore = incidentStore,
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>().apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingErrorShare } returns MutableStateFlow(null)
                    every { pendingConflicts } returns flowOf(emptyMap())
                }
            },
        )
    }

    /** Stepping reads the page state, which only renders while it is collected. */
    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    @Test
    fun `a delete request names the file on display and leaves the trash choice alone`() = runTest2 {
        val vm = makeViewModel()

        vm.requestDelete()

        vm.deleteRequest.value shouldBe ViewerWorkspaceViewModel.DeleteRequest(
            targets = setOf(b),
            initialPermanentDelete = false,
        )
    }

    @Test
    fun `a permanent delete request pre-ticks the permanent delete`() = runTest2 {
        val vm = makeViewModel()

        vm.requestDelete(permanent = true)

        vm.deleteRequest.value shouldBe ViewerWorkspaceViewModel.DeleteRequest(
            targets = setOf(b),
            initialPermanentDelete = true,
        )
    }

    @Test
    fun `confirming deletes the file the dialog named`() = runTest2 {
        val vm = makeViewModel()
        val shown = workspaces.value

        vm.requestDelete()
        vm.confirmDelete(forcePermDelete = false)

        coVerify(exactly = 1) { shown.delete(forcePermDelete = false) }
        vm.deleteRequest.value shouldBe null
    }

    @Test
    fun `a step that lands while the dialog is open deletes nothing`() = runTest2 {
        val vm = makeViewModel()
        val requested = workspaces.value

        vm.requestDelete()
        val swapped = makeWorkspace(c)
        workspaces.value = swapped
        vm.confirmDelete(forcePermDelete = false)

        coVerify(exactly = 0) { swapped.delete(any()) }
        coVerify(exactly = 0) { requested.delete(any()) }
        vm.deleteRequest.value shouldBe null

        // The aborted confirm must not keep the next one out.
        vm.requestDelete()
        vm.confirmDelete(forcePermDelete = false)
        coVerify(exactly = 1) { swapped.delete(forcePermDelete = false) }
    }

    @Test
    fun `a delete requested while a file step is in flight is ignored`() = runTest2 {
        val gate = CompletableDeferred<Unit>()
        createGate = gate
        val vm = makeViewModel()
        startCollecting(vm)

        vm.showNextFile()
        vm.requestDelete()
        vm.deleteRequest.value shouldBe null

        // The create returned, but the replacement has not arrived yet.
        gate.complete(Unit)
        vm.requestDelete()
        vm.deleteRequest.value shouldBe null

        workspaces.value = makeWorkspace(c)
        vm.requestDelete()
        vm.deleteRequest.value shouldBe ViewerWorkspaceViewModel.DeleteRequest(
            targets = setOf(c),
            initialPermanentDelete = false,
        )
    }
}
