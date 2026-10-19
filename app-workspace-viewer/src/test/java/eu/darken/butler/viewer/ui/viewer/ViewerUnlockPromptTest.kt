package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSettings
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerUnlockPromptTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val container = LocalPath.build("/storage/emulated/0/Download/secret.7z")
    private val entry = ArchivePath(container, listOf("notes.txt"))

    private lateinit var workspaceState: MutableStateFlow<ViewerWorkspace.State>
    private lateinit var workspace: ViewerWorkspace

    private fun lock() = ViewerContent.Locked(container, attemptFailed = true, mayBeDamaged = true)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        workspaceState = MutableStateFlow(ViewerWorkspace.State(content = lock()))
        workspace = mockk<ViewerWorkspace>().apply {
            every { state } returns workspaceState
            every { source } returns ViewerSource.Stored(entry)
            every { storedPath } returns entry
            every { listingSourceId } returns null
            every { stepPaths } returns null
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "notes.txt".toCaString()),
            )
            every { reload() } just Runs
            every { unlock(any(), any()) } just Runs
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
            every { pendingErrorShare } returns MutableStateFlow(null)
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
            imageSourceFactory = mockk(relaxed = true),
            pdfPreviewLoader = mockk(relaxed = true),
            textPreviewLoader = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns flowOf(false)
                every { confirmTrashMove.flow } returns flowOf(true)
            },
            operationsManager = mockk(relaxed = true),
            appInstallLauncher = mockk(relaxed = true),
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            errorIncidentStore = recordingIncidentStore(),
            viewerSettings = mockk<ViewerSettings>().apply {
                every { showNextAfterDelete } returns mockDataStoreValue(true)
                every { swipeBetweenFiles } returns mockDataStoreValue(true)
            },
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns chrome
            },
        )
    }

    @Test
    fun `a dismissed prompt comes back for the next load, even when it looks the same`() = runTest2 {
        val vm = makeViewModel()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.unlockPrompt.collect { } }
        val first = workspaceState.value.content

        vm.unlockPrompt.value shouldBe first
        vm.dismissUnlock()
        vm.unlockPrompt.value shouldBe null

        val second = lock()
        workspaceState.value = ViewerWorkspace.State(content = second)
        vm.unlockPrompt.value shouldBe second

        vm.dismissUnlock()
        vm.unlockPrompt.value shouldBe null

        vm.requestUnlock()
        vm.unlockPrompt.value shouldBe second
    }

    @Test
    fun `an answer to a superseded prompt does not unlock`() = runTest2 {
        val vm = makeViewModel()
        val stale = workspaceState.value.content as ViewerContent.Locked
        val current = lock()
        workspaceState.value = ViewerWorkspace.State(content = current)

        vm.unlock(stale, "secret")
        verify(exactly = 0) { workspace.unlock(any(), any()) }

        vm.unlock(current, "secret")
        verify(exactly = 1) { workspace.unlock(current, any()) }
    }
}
