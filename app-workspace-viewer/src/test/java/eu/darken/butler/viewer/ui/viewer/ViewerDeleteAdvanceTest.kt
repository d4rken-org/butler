package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSettings
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import testhelpers.mockDataStoreValue
import java.io.IOException
import kotlin.time.Instant

/**
 * Deleting the file on display moves the viewer to its neighbour in the listing it was opened from,
 * by replacing this tab under its own id, or closes it when there is nowhere to go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerDeleteAdvanceTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val originId = Workspace.Id()
    private val callerId = Workspace.Id()

    private val a = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val b = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val c = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")

    private val creates = mutableListOf<WorkspaceAction.Create>()
    private val closes = mutableListOf<WorkspaceAction.Close>()

    /** Every delete submitted, with the operation id it was handed. */
    private val deletes = mutableListOf<Pair<APath<*>, Operation.Id>>()

    /** Set to hold a create inside the ViewModel, so a tap arrives while one is in flight. */
    private var createGate: CompletableDeferred<Unit>? = null

    /** What a replace answers; anything but Success leaves the current workspace in place. */
    private var createResult: WorkspaceAction.Create.Result = WorkspaceAction.Create.Result.Success(workspaceId)

    /** Runs inside `delete()`, before it returns the operation id. */
    private var onDelete: ((APath<*>, Operation.Id) -> Unit)? = null

    private var showNextAfterDelete = true

    /** No replay, like the real one: a completion nobody listens to is gone. */
    private lateinit var completions: MutableSharedFlow<CompletedOperationSnapshot>

    private lateinit var listing: MutableStateFlow<List<APath<*>>>
    private lateinit var origin: MutableStateFlow<Workspace<*>?>
    private lateinit var remoteState: MutableStateFlow<WorkspaceRemote.State>
    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        creates.clear()
        closes.clear()
        deletes.clear()
        createGate = null
        createResult = WorkspaceAction.Create.Result.Success(workspaceId)
        onDelete = null
        showNextAfterDelete = true
        completions = MutableSharedFlow(extraBufferCapacity = 16)
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

    private fun arguments(path: APath<*>, listingSourceId: Workspace.Id?) = ViewerArguments.Default(
        filePath = path,
        callerWorkspaceId = callerId,
        listingSourceId = listingSourceId,
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
        every { sharedCaption } returns null
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = path.name.toCaString()),
        )
        every { reload() } just Runs
        every { siblingArguments(any()) } answers {
            arguments(path, listingSourceId).copy(filePath = firstArg())
        }
        coEvery { delete(any()) } answers {
            val operationId = Operation.Id()
            deletes.add(path to operationId)
            onDelete?.invoke(path, operationId)
            operationId
        }
    }

    private val incidentStore = recordingIncidentStore()

    private fun makeViewModel(
        path: APath<*> = b,
        listingSourceId: Workspace.Id? = originId,
    ): ViewerWorkspaceViewModel {
        workspaces = MutableStateFlow(makeWorkspace(path, listingSourceId))
        val remote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { events } returns emptyFlow()
            every { this@apply.state } returns remoteState
            coEvery { execute(any()) } coAnswers {
                when (val action = firstArg<WorkspaceAction>()) {
                    is WorkspaceAction.Create -> {
                        creates.add(action)
                        createGate?.await()
                        val result = createResult
                        // The repo publishes the replacement under the same id, which is what the
                        // ViewModel waits for before it lets go of its guard.
                        if (result is WorkspaceAction.Create.Result.Success) {
                            val arguments = action.arguments as ViewerArguments.Default
                            workspaces.value = makeWorkspace(arguments.filePath, arguments.listingSourceId)
                        }
                        result
                    }

                    is WorkspaceAction.Close -> {
                        closes.add(action)
                        WorkspaceAction.Close.Result
                    }

                    else -> error("Unexpected $action")
                }
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
            operationsManager = mockk<OperationsManager>(relaxed = true).apply {
                every { completedOperations } returns completions
            },
            appInstallLauncher = mockk(relaxed = true),
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            errorIncidentStore = incidentStore,
            viewerSettings = mockk<ViewerSettings>().apply {
                every { showNextAfterDelete } returns mockDataStoreValue(this@ViewerDeleteAdvanceTest.showNextAfterDelete)
            },
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>().apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingErrorShare } returns MutableStateFlow(null)
                    every { pendingConflicts } returns flowOf(emptyMap())
                }
            },
        )
    }

    /** The state only renders while it is collected, and the delete reads the neighbours from it. */
    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private fun TestScope.collectErrors(vm: ViewerWorkspaceViewModel): List<Throwable> {
        val errors = mutableListOf<Throwable>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.errorEvents.collect { errors.add(it) } }
        return errors
    }

    private class Done(
        override val error: Throwable?,
        override val report: Operation.Report?,
    ) : Operation.State.Completed {
        override val startedAt: Instant = Instant.fromEpochMilliseconds(0)
        override val completedAt: Instant = Instant.fromEpochMilliseconds(1)
        override val summary: CaString = "done".toCaString()
    }

    private class DeleteReport(
        override val affectedPaths: Collection<Operation.Report.Paths.PathChange>,
    ) : Operation.Report.Paths {
        override val summary: CaString = "deleted".toCaString()
        override val subjectPath: APath<*>? = affectedPaths.firstOrNull()?.path
    }

    private fun snapshot(
        operationId: Operation.Id,
        path: APath<*>,
        error: Throwable? = null,
        removed: Boolean = true,
    ) = CompletedOperationSnapshot(
        id = operationId,
        metadata = mockk(relaxed = true),
        state = Done(
            error = error,
            report = DeleteReport(
                affectedPaths = if (removed) {
                    listOf(
                        Operation.Report.Paths.PathChange(
                            path = path,
                            change = Operation.Report.Paths.PathChange.Change.REMOVED,
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        ),
    )

    /** Asks for the delete of the file on display and confirms it, the way the dialog does. */
    private fun ViewerWorkspaceViewModel.deleteDisplayed() {
        val displayed = workspaces.value.storedPath!!
        requestDelete()
        deleteRequest.value?.targets shouldBe setOf(displayed)
        confirmDelete(forcePermDelete = false)
    }

    /** Delivers the outcome of the most recent delete. */
    private fun completeLastDelete(error: Throwable? = null, removed: Boolean = true) {
        val (path, operationId) = deletes.last()
        completions.tryEmit(snapshot(operationId, path, error = error, removed = removed)) shouldBe true
    }

    private val WorkspaceAction.Create.target: APath<*>
        get() = arguments.shouldBeInstanceOf<ViewerArguments.Default>().filePath

    @Test
    fun `deleting a file in the middle of the listing shows the next one`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        closes shouldBe emptyList()
        val replace = creates.single()
        replace.type shouldBe Workspace.Type.VIEWER
        replace.replace shouldBe workspaceId
        replace.id shouldBe workspaceId
        replace.skipContentDedup shouldBe true
        val arguments = replace.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe c
        arguments.callerWorkspaceId shouldBe callerId
        arguments.listingSourceId shouldBe originId
    }

    @Test
    fun `deleting the last file of the listing shows the previous one`() = runTest2 {
        val vm = makeViewModel(path = c)
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        closes shouldBe emptyList()
        creates.single().target shouldBe b
    }

    @Test
    fun `deleting the only file of the listing closes the viewer`() = runTest2 {
        listing.value = listOf(b)
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        creates shouldBe emptyList()
        closes.single().id shouldBe workspaceId
    }

    @Test
    fun `a viewer without a listing closes after the delete`() = runTest2 {
        val vm = makeViewModel(listingSourceId = null)
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        creates shouldBe emptyList()
        closes.single().id shouldBe workspaceId
    }

    @Test
    fun `with the setting off the viewer closes even with a neighbour`() = runTest2 {
        showNextAfterDelete = false
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        creates shouldBe emptyList()
        closes.single().id shouldBe workspaceId
    }

    @Test
    fun `a failed delete leaves the viewer where it is and reports the error`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)
        val errors = collectErrors(vm)
        val failure = IOException("boom")

        vm.deleteDisplayed()
        completeLastDelete(error = failure, removed = false)

        creates shouldBe emptyList()
        closes shouldBe emptyList()
        errors shouldBe listOf(failure)
    }

    @Test
    fun `a cancelled delete leaves the viewer where it is without an error`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)
        val errors = collectErrors(vm)

        vm.deleteDisplayed()
        completeLastDelete(error = CancellationException("cancelled"), removed = false)

        creates shouldBe emptyList()
        closes shouldBe emptyList()
        errors shouldBe emptyList()
    }

    @Test
    fun `a delete that removed nothing leaves the viewer where it is`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete(removed = false)

        creates shouldBe emptyList()
        closes shouldBe emptyList()
    }

    @Test
    fun `the neighbour is taken before the listing drops the deleted file`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        // The Explorer is told about the removal while the delete still runs.
        listing.value = listOf(a, c)
        completeLastDelete()

        closes shouldBe emptyList()
        creates.single().target shouldBe c
    }

    @Test
    fun `a delete that completes before its id is returned still advances`() = runTest2 {
        onDelete = { path, operationId -> completions.tryEmit(snapshot(operationId, path)) shouldBe true }
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()

        closes shouldBe emptyList()
        creates.single().target shouldBe c
    }

    @Test
    fun `a step tapped while the delete runs does nothing`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        vm.showPreviousFile()
        vm.showNextFile()

        creates shouldBe emptyList()

        completeLastDelete()

        creates.single().target shouldBe c
    }

    @Test
    fun `after advancing, both deleting and stepping work again`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()
        creates.single().target shouldBe c

        // The listing still carries b; the ViewModel knows it is gone.
        vm.showPreviousFile()
        creates.map { it.target } shouldBe listOf(c, a)

        vm.deleteDisplayed()
        completeLastDelete()

        creates.map { it.target } shouldBe listOf(c, a, c)
        deletes.map { it.first } shouldBe listOf(b, a)
        closes shouldBe emptyList()
    }

    @Test
    fun `a stale listing never leads back to a file already deleted`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()
        creates.single().target shouldBe c

        // The origin has not republished, so its listing still has b between a and c.
        vm.deleteDisplayed()
        completeLastDelete()

        creates.map { it.target } shouldBe listOf(c, a)
        closes shouldBe emptyList()
    }

    @Test
    fun `a delete cannot be requested while a step is in flight`() = runTest2 {
        val gate = CompletableDeferred<Unit>()
        createGate = gate
        val vm = makeViewModel()
        startCollecting(vm)

        vm.showNextFile()
        vm.requestDelete()

        vm.deleteRequest.value shouldBe null

        gate.complete(Unit)
        vm.confirmDelete(forcePermDelete = false)

        deletes shouldBe emptyList()
        creates.single().target shouldBe c
    }

    @Test
    fun `a delete cannot be requested while a delete is in flight`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.requestDelete()
        vm.deleteRequest.value?.targets shouldBe setOf(b)
        vm.confirmDelete(forcePermDelete = false)

        vm.requestDelete()
        vm.deleteRequest.value shouldBe null

        completeLastDelete()
        creates.single().target shouldBe c

        vm.requestDelete()
        vm.deleteRequest.value?.targets shouldBe setOf(c)
    }

    @Test
    fun `a confirm for a file that is no longer on display deletes nothing`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.requestDelete()
        vm.deleteRequest.value?.targets shouldBe setOf(b)
        workspaces.value = makeWorkspace(c)

        vm.confirmDelete(forcePermDelete = false)

        deletes shouldBe emptyList()
        creates shouldBe emptyList()
        closes shouldBe emptyList()
    }

    @Test
    fun `a refused replace reloads the deleted file instead of closing`() = runTest2 {
        createResult = WorkspaceAction.Create.Result.Refused
        val vm = makeViewModel()
        startCollecting(vm)
        val deleted = workspaces.value

        vm.deleteDisplayed()
        completeLastDelete()

        creates.single().target shouldBe c
        closes shouldBe emptyList()
        workspaces.value shouldBe deleted
        verify(exactly = 1) { deleted.reload() }
    }

    @Test
    fun `a refused replace keeps the arrows on the deleted file`() = runTest2 {
        createResult = WorkspaceAction.Create.Result.Refused
        val vm = makeViewModel()
        startCollecting(vm)

        vm.deleteDisplayed()
        completeLastDelete()

        creates.single().target shouldBe c
        val ready = vm.state.value.shouldBeInstanceOf<ViewerWorkspaceViewModel.State.Ready>()
        ready.neighbours shouldBe ViewerNeighbours(current = b, previous = a, next = c)
        ready.actions.filterIsInstance<ViewerActionBarItem.PreviousFile>() shouldBe listOf(
            ViewerActionBarItem.PreviousFile(isEnabled = true),
        )
        ready.actions.filterIsInstance<ViewerActionBarItem.NextFile>() shouldBe listOf(
            ViewerActionBarItem.NextFile(isEnabled = true),
        )
    }
}
