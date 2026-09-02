package eu.darken.butler.viewer.ui.viewer

import android.graphics.Bitmap
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.PdfPreviewLoader
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
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
 * PDF paging as the ViewModel drives it: which page the loader is asked for, and when it may be
 * asked at all. A native render ignores cancellation, so only one may be in flight at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerWorkspaceViewModelPagingTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val filePath = LocalPath.build("/storage/emulated/0/Download/manual.pdf")
    private val source = ViewerSource.Stored(filePath)
    private val mime = MimeInfo("application/pdf")

    private val requestedPages = mutableListOf<Int>()

    /** The same requests with the document they were made for, for the tab-swap case. */
    private val requestedDocuments = mutableListOf<Pair<ViewerSource, Int>>()
    private lateinit var workspaceState: MutableStateFlow<ViewerWorkspace.State>
    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>
    private lateinit var loader: PdfPreviewLoader

    /** Set to gate a specific page's render, so the test can hold it in flight. */
    private var renderGate: Pair<Int, CompletableDeferred<Unit>>? = null

    /** Pages that resolve to null, i.e. a render the loader could not deliver. */
    private val failingPages = mutableSetOf<Int>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        requestedPages.clear()
        requestedDocuments.clear()
        failingPages.clear()
        renderGate = null
        workspaceState = MutableStateFlow(
            ViewerWorkspace.State(content = ViewerContent.PdfPreview(mime, pageCount = 3)),
        )
        loader = mockk<PdfPreviewLoader>().apply {
            coEvery { page(any(), any()) } coAnswers {
                val index = secondArg<Int>()
                requestedPages.add(index)
                requestedDocuments.add(firstArg<ViewerSource>() to index)
                renderGate?.takeIf { it.first == index }?.second?.await()
                if (index in failingPages) null else mockk<Bitmap>()
            }
        }
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    /**
     * [pdfPageState] feeds the page render branch of the ViewModel, [workspaceState] the content it
     * displays. They are the same flow in production; handing the branch its own lets a test pin the
     * window where the content is already a PDF but no page has been rendered yet.
     */
    private val incidentStore = recordingIncidentStore()

    private fun makeViewModel(
        pdfPageState: MutableStateFlow<ViewerWorkspace.State> = workspaceState,
    ): ViewerWorkspaceViewModel {
        val workspace = mockk<ViewerWorkspace>().apply {
            every { state } returnsMany listOf(workspaceState, pdfPageState)
            every { source } returns this@ViewerWorkspaceViewModelPagingTest.source
            every { storedPath } returns this@ViewerWorkspaceViewModelPagingTest.filePath
            every { listingSourceId } returns null
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "manual.pdf".toCaString()),
            )
            every { reload() } just Runs
        }
        workspaces = MutableStateFlow(workspace)
        val chrome = mockk<WorkspacePageChrome>().apply {
            every { shareIntentEvent } returns SingleEventFlow()
            every { pendingErrorShare } returns MutableStateFlow(null)
            // The ViewModel derives its issue sheet from this at construction time.
            every { pendingConflicts } returns flowOf(emptyMap())
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns workspaces
            },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
            },
            imageSourceFactory = mockk(relaxed = true),
            pdfPreviewLoader = loader,
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            // Not relaxed all the way down: the state combine collects `enabled.flow`, and a relaxed
            // Flow mock completes without emitting, which would stall the state at Initializing.
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns flowOf(false)
            },
            operationsManager = mockk(relaxed = true),
            appInstallInspector = mockk(relaxed = true),
            appInstaller = mockk(relaxed = true),
            appInstallOperationFactory = mockk(relaxed = true),
            // Unused by the paging cases, but the ViewModel now owns the APK icon export too.
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            errorIncidentStore = incidentStore,
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns chrome
            },
        )
    }

    /** The state only renders while it is collected, so every case needs a live subscriber. */
    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private val ViewerWorkspaceViewModel.readyState: ViewerWorkspaceViewModel.State.Ready
        get() = state.value as ViewerWorkspaceViewModel.State.Ready

    @Test
    fun `a pdf renders its first page`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        requestedPages shouldBe listOf(0)
        vm.readyState.pdfPage!!.index shouldBe 0
    }

    @Test
    fun `stepping forward renders the following pages in order`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.nextPdfPage()
        vm.nextPdfPage()

        requestedPages shouldBe listOf(0, 1, 2)
        vm.readyState.pdfPage!!.index shouldBe 2
    }

    @Test
    fun `stepping while a render is in flight is ignored`() = runTest2 {
        val gate = CompletableDeferred<Unit>()
        renderGate = 0 to gate
        val vm = makeViewModel()
        startCollecting(vm)

        vm.nextPdfPage()
        requestedPages shouldBe listOf(0)

        gate.complete(Unit)
        vm.nextPdfPage()
        requestedPages shouldBe listOf(0, 1)
    }

    @Test
    fun `stepping before a page is on display is ignored`() = runTest2 {
        val pdfPageState = MutableStateFlow(ViewerWorkspace.State(content = ViewerContent.Loading))
        val vm = makeViewModel(pdfPageState = pdfPageState)
        startCollecting(vm)

        vm.readyState.content shouldBe ViewerContent.PdfPreview(mime, pageCount = 3)
        vm.readyState.pdfPage shouldBe null

        vm.nextPdfPage()
        requestedPages shouldBe emptyList<Int>()

        // Once the page branch catches up, the document must start at its first page, not skip one.
        pdfPageState.value = workspaceState.value
        requestedPages shouldBe listOf(0)
        vm.readyState.pdfPage!!.index shouldBe 0
    }

    @Test
    fun `retry re-renders the page that is on display`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        vm.nextPdfPage()
        vm.retry()

        requestedPages shouldBe listOf(0, 1, 1)
        vm.readyState.pdfPage!!.index shouldBe 1
    }

    @Test
    fun `a document that shrank clamps the page it renders`() = runTest2 {
        workspaceState.value = ViewerWorkspace.State(content = ViewerContent.PdfPreview(mime, pageCount = 100))
        val vm = makeViewModel()
        startCollecting(vm)

        repeat(9) { vm.nextPdfPage() }
        vm.readyState.pdfPage!!.index shouldBe 9

        workspaceState.value = ViewerWorkspace.State(content = ViewerContent.PdfPreview(mime, pageCount = 3))
        vm.readyState.pdfPage!!.index shouldBe 2

        vm.previousPdfPage()
        vm.readyState.pdfPage!!.index shouldBe 1
        requestedPages.takeLast(2) shouldBe listOf(2, 1)
    }

    @Test
    fun `a tab swapped to another document starts that one at its first page`() = runTest2 {
        // Stepping to the next file replaces this tab under its own id, so the ViewModel - and with
        // it the page the user picked - outlives the document it was picked in.
        val vm = makeViewModel()
        startCollecting(vm)

        vm.nextPdfPage()
        vm.nextPdfPage()
        requestedPages shouldBe listOf(0, 1, 2)

        val otherPath = LocalPath.build("/storage/emulated/0/Download/other.pdf")
        val otherSource = ViewerSource.Stored(otherPath)
        workspaces.value = mockk<ViewerWorkspace>().apply {
            every { state } returns MutableStateFlow(
                ViewerWorkspace.State(content = ViewerContent.PdfPreview(mime, pageCount = 5)),
            )
            every { source } returns otherSource
            every { storedPath } returns otherPath
            every { listingSourceId } returns null
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = "other.pdf".toCaString()),
            )
            every { reload() } just Runs
        }

        vm.readyState.pdfPage!!.index shouldBe 0
        // Page 0 of the new document, and nothing more asked of the old one.
        requestedDocuments shouldBe listOf(
            source to 0,
            source to 1,
            source to 2,
            otherSource to 0,
        )
    }

    @Test
    fun `a page that cannot be rendered fails on its own without failing the document`() = runTest2 {
        failingPages.add(0)
        val vm = makeViewModel()
        startCollecting(vm)

        val failed = vm.readyState
        failed.pdfPage!!.failed shouldBe true
        failed.content shouldBe ViewerContent.PdfPreview(mime, pageCount = 3)

        vm.nextPdfPage()
        vm.readyState.pdfPage!!.let {
            it.index shouldBe 1
            it.failed shouldBe false
        }
    }
}
