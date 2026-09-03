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
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import testhelpers.error.recordingIncidentStore

/**
 * Stepping to the neighbouring file of the listing this viewer was opened from: which arrows the bar
 * offers, and what one tap does.
 *
 * A step replaces this tab under its own id, so the ViewModel outlives every workspace it drives -
 * which is why the guard against a second tap has to hold until the replacement has arrived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerFileStepTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val originId = Workspace.Id()
    private val callerId = Workspace.Id()

    private val a = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val b = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val c = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")

    private val creates = mutableListOf<WorkspaceAction.Create>()

    /** Set to hold a create inside the ViewModel, so a second tap arrives while one is in flight. */
    private var createGate: CompletableDeferred<Unit>? = null

    private lateinit var listing: MutableStateFlow<List<APath<*>>>
    private lateinit var origin: MutableStateFlow<Workspace<*>?>
    private lateinit var remoteState: MutableStateFlow<WorkspaceRemote.State>
    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        creates.clear()
        createGate = null
        listing = MutableStateFlow(listOf(a, b, c))
        origin = MutableStateFlow(makeOrigin())
        remoteState = MutableStateFlow(
            WorkspaceRemote.State(
                infos = listOf(
                    Workspace.Info(id = originId, type = Workspace.Type.EXPLORER, title = "DCIM".toCaString()),
                    Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "b.jpg".toCaString()),
                ),
            ),
        )
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeOrigin(): Workspace<*> {
        val explorer = mockk<Workspace<Workspace.Arguments>>(
            moreInterfaces = arrayOf(Workspace.FileListingSource::class),
        )
        every { (explorer as Workspace.FileListingSource).fileListing } returns listing
        return explorer
    }

    /** The arguments a viewer opened from that Explorer carries, and steps with. */
    private fun arguments(path: APath<*>) = ViewerArguments.Default(
        filePath = path,
        caption = "look at this",
        callerWorkspaceId = callerId,
        listingSourceId = originId,
    )

    private fun makeWorkspace(
        path: APath<*>,
        listingSourceId: Workspace.Id? = originId,
    ) = mockk<ViewerWorkspace>().apply {
        every { state } returns MutableStateFlow(
            ViewerWorkspace.State(content = ViewerContent.Image(MimeInfo("image/jpeg"))),
        )
        every { source } returns ViewerSource.Stored(path)
        every { storedPath } returns path
        every { this@apply.listingSourceId } returns listingSourceId
        every { sharedCaption } returns "look at this"
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = path.name.toCaString()),
        )
        every { reload() } just Runs
        every { siblingArguments(any()) } answers {
            arguments(path).copy(filePath = firstArg(), caption = null)
        }
    }

    private val incidentStore = recordingIncidentStore()

    private fun makeViewModel(path: APath<*> = b): ViewerWorkspaceViewModel {
        workspaces = MutableStateFlow(makeWorkspace(path))
        val remote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { events } returns emptyFlow()
            every { this@apply.state } returns remoteState
            coEvery { execute(any()) } coAnswers {
                val action = firstArg<WorkspaceAction>()
                if (action is WorkspaceAction.Create) {
                    creates.add(action)
                    createGate?.await()
                }
                WorkspaceAction.Create.Result.Success(workspaceId)
            }
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns workspaces
                every { retrieve(originId) } returns origin
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
            operationsManager = mockk(relaxed = true),
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

    /** The state only renders while it is collected, and stepping reads it. */
    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private val ViewerWorkspaceViewModel.readyState: ViewerWorkspaceViewModel.State.Ready
        get() = state.value as ViewerWorkspaceViewModel.State.Ready

    private val ViewerWorkspaceViewModel.steps: List<ViewerActionBarItem>
        get() = readyState.actions.filter {
            it is ViewerActionBarItem.PreviousFile || it is ViewerActionBarItem.NextFile
        }

    @Test
    fun `a file in the middle of the listing steps both ways`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.steps shouldBe listOf(
            ViewerActionBarItem.PreviousFile(isEnabled = true),
            ViewerActionBarItem.NextFile(isEnabled = true),
        )
    }

    @Test
    fun `stepping forward replaces this tab with the next file`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.showNextFile()

        val step = creates.single()
        step.type shouldBe Workspace.Type.VIEWER
        step.replace shouldBe workspaceId
        // Same id, so the tab keeps its pane slot and its sub-workspaces.
        step.id shouldBe workspaceId
        // The neighbour may already be open elsewhere; that must not strand this tab.
        step.skipContentDedup shouldBe true
        val arguments = step.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe c
        arguments.listingSourceId shouldBe originId
        // A drill-down stays one: the step does not turn the overlay into a tab of its own.
        arguments.callerWorkspaceId shouldBe callerId
        // The caption belonged to the file that was shared, not to its neighbour.
        arguments.caption shouldBe null
    }

    @Test
    fun `a paused origin keeps the last listing it published`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        // Pausing releases the live instance; the stand-in that takes its place holds no listing.
        origin.value = null

        vm.steps shouldBe listOf(
            ViewerActionBarItem.PreviousFile(isEnabled = true),
            ViewerActionBarItem.NextFile(isEnabled = true),
        )

        vm.showNextFile()
        creates.single().arguments.shouldBeInstanceOf<ViewerArguments.Default>().filePath shouldBe c
    }

    @Test
    fun `a closed origin takes the steps away`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        remoteState.value = WorkspaceRemote.State(infos = remoteState.value.infos.filter { it.id != originId })

        vm.steps shouldBe emptyList()
    }

    @Test
    fun `a listing without the file on display offers no steps`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        // The Explorer navigated away, or a filter dropped this file.
        listing.value = listOf(a, c)

        vm.steps shouldBe emptyList()
    }

    @Test
    fun `the last file of a listing cannot step forward`() = runTest2 {
        val vm = makeViewModel(path = c)
        startCollecting(vm)

        vm.steps shouldBe listOf(
            ViewerActionBarItem.PreviousFile(isEnabled = true),
            ViewerActionBarItem.NextFile(isEnabled = false),
        )

        vm.showNextFile()

        creates shouldBe emptyList()
    }

    @Test
    fun `a second tap while the create is still running does nothing`() = runTest2 {
        val gate = CompletableDeferred<Unit>()
        createGate = gate
        val vm = makeViewModel()
        startCollecting(vm)

        vm.showNextFile()
        vm.showNextFile()

        creates.size shouldBe 1
        gate.complete(Unit)
    }

    @Test
    fun `a tap before the replacement arrives does nothing`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        // The create returned, but the provider still hands out the tab's previous incarnation:
        // a step taken now would read the file the user already left.
        vm.showNextFile()
        creates.size shouldBe 1

        vm.showNextFile()
        creates.size shouldBe 1

        // Once the replacement is published, the next step is allowed again.
        workspaces.value = makeWorkspace(c)
        vm.showPreviousFile()
        creates.size shouldBe 2
        creates.last().arguments.shouldBeInstanceOf<ViewerArguments.Default>().filePath shouldBe b
    }

    @Test
    fun `neighbours resolved for the previous file never reach the new one`() = runTest2 {
        val vm = makeViewModel()
        val seen = mutableListOf<ViewerWorkspaceViewModel.State.Ready>()
        backgroundScope.launch(Dispatchers.Unconfined) {
            vm.state.collect { if (it is ViewerWorkspaceViewModel.State.Ready) seen.add(it) }
        }

        workspaces.value = makeWorkspace(c)

        seen.isNotEmpty() shouldBe true
        seen.forEach { state ->
            val current = state.neighbours?.current ?: return@forEach
            current shouldBe (state.source as ViewerSource.Stored).path
        }
    }
}
