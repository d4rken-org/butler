package eu.darken.butler.viewer.ui.viewer

import androidx.core.net.toUri
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * "Save a copy" and the tab rebind that follows it: one Saver at a time, and only the one this
 * viewer launched may replace its tab.
 *
 * Robolectric because the streamed source is built around a `content://` URI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerSaveCopyTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val savedPath = LocalPath.build("/sdcard/Download/backup.zip")
    private val streamed = ViewerSource.Streamed(
        uri = "content://com.example.files/document/42".toUri(),
        displayName = "backup.zip",
        mime = MimeInfo("application/zip"),
        sizeBytes = 4096L,
        arrivalId = "arrival-1",
    )

    private val events = MutableSharedFlow<WorkspaceEvent>(extraBufferCapacity = 8)
    private val creates = mutableListOf<WorkspaceAction.Create>()
    private var nextCreatedId = Workspace.Id()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): ViewerWorkspaceViewModel {
        val workspace = mockk<ViewerWorkspace>().apply {
            every { state } returns MutableStateFlow(
                ViewerWorkspace.State(
                    content = ViewerContent.Archive(
                        mime = MimeInfo("application/zip"),
                        format = ArchiveFormat.ZIP,
                        access = ViewerContent.Archive.Access.NEEDS_COPY,
                    ),
                ),
            )
            every { source } returns streamed
            every { storedPath } returns null
            every { sharedCaption } returns "look at this"
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "backup.zip".toCaString()),
            )
            every { reload() } just Runs
        }
        val remote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { this@apply.events } returns this@ViewerSaveCopyTest.events
            coEvery { execute(any()) } answers {
                val action = firstArg<WorkspaceAction>()
                if (action is WorkspaceAction.Create) creates.add(action)
                WorkspaceAction.Create.Result.Success(nextCreatedId)
            }
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns flowOf(workspace)
            },
            workspaceRemote = remote,
            imageSourceFactory = mockk(relaxed = true),
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
            errorIncidentFactory = mockk(relaxed = true),
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>().apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingErrorShare } returns MutableStateFlow(null)
                    every { pendingConflicts } returns flowOf(emptyMap())
                }
            },
        )
    }

    private fun saveResult(saverId: Workspace.Id, vararg paths: LocalPath) = WorkspaceEvent.SaveResult(
        workspaceId = saverId,
        callerWorkspaceId = workspaceId,
        savedPaths = paths.toList(),
    )

    @Test
    fun `saving a copy launches one saver that reports back`() = runTest2 {
        makeViewModel().saveCopy()

        val arguments = creates.single().arguments.shouldBeInstanceOf<SaverArguments.Default>()
        arguments.sourceUris shouldBe listOf(streamed.uri.toString())
        arguments.callerWorkspaceId shouldBe workspaceId
        arguments.reportSavedPaths shouldBe true
    }

    @Test
    fun `a second tap while a save is outstanding launches nothing`() = runTest2 {
        val vm = makeViewModel()

        vm.saveCopy()
        vm.saveCopy()

        creates.size shouldBe 1
    }

    @Test
    fun `a completed save replaces this tab with one bound to the file`() = runTest2 {
        val saverId = Workspace.Id()
        nextCreatedId = saverId
        val vm = makeViewModel()
        vm.saveCopy()

        events.emit(saveResult(saverId, savedPath))

        val replacement = creates.last()
        replacement.type shouldBe Workspace.Type.VIEWER
        replacement.replace shouldBe workspaceId
        // Same id, so the Saver that reported the save is not swept as an orphan of a gone tab.
        replacement.id shouldBe workspaceId
        // The destination may already be open in another tab; that must not refuse the rebind.
        replacement.skipContentDedup shouldBe true
        val arguments = replacement.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe savedPath
        // Still the same shared file, so the message that came with it carries over.
        arguments.caption shouldBe "look at this"
    }

    @Test
    fun `a result from a saver this viewer did not launch is ignored`() = runTest2 {
        val vm = makeViewModel()
        vm.saveCopy()

        // Same caller id, different Saver: another tab's save must not rebind this one.
        events.emit(saveResult(Workspace.Id(), savedPath))

        creates.size shouldBe 1
    }

    @Test
    fun `a save that wrote nothing leaves the tab on the stream and the retry alive`() = runTest2 {
        val saverId = Workspace.Id()
        nextCreatedId = saverId
        val vm = makeViewModel()
        vm.saveCopy()

        // An overwrite the user backed out of, or a write that failed: the Saver is still open and
        // will report again, so the reservation stays with it.
        events.emit(saveResult(saverId))
        creates.size shouldBe 1

        // A second tap must not stack another Saver over the one that is still on screen.
        vm.saveCopy()
        creates.size shouldBe 1

        events.emit(saveResult(saverId, savedPath))

        val replacement = creates.last()
        replacement.replace shouldBe workspaceId
        replacement.arguments.shouldBeInstanceOf<ViewerArguments.Default>().filePath shouldBe savedPath
    }

    @Test
    fun `closing the saver without saving frees the reservation`() = runTest2 {
        val saverId = Workspace.Id()
        nextCreatedId = saverId
        val vm = makeViewModel()
        vm.saveCopy()

        events.emit(WorkspaceEvent.Closed(workspaceId = saverId, callerWorkspaceId = workspaceId))

        vm.saveCopy()
        creates.size shouldBe 2
    }
}
