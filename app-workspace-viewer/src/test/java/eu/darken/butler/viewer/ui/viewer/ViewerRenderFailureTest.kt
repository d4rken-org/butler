package eu.darken.butler.viewer.ui.viewer

import androidx.core.net.toUri
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerUndecodableImageException
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import testhelpers.error.recordingIncidentStore
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The failures this page shows - reported by the image source or resolved by the workspace - which
 * source they belong to, and which incident each of them is frozen into.
 *
 * Saving a stream replaces this tab under its own id, so the ViewModel outlives the swap: a failure
 * recorded for the stream must not follow the tab onto the file that was written from it.
 *
 * Robolectric because the streamed source is built around a `content://` URI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerRenderFailureTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mime = MimeInfo("image/jpeg")
    private val savedPath = LocalPath.build("/sdcard/Download/photo.jpg")
    private val streamed = ViewerSource.Streamed(
        uri = "content://com.example.files/document/42".toUri(),
        displayName = "photo.jpg",
        mime = mime,
        sizeBytes = 4096L,
        arrivalId = "arrival-1",
    )
    private val stored = ViewerSource.Stored(savedPath)

    /** The failure callback the image source was handed, per source it was created for. */
    private val onErrors = mutableMapOf<ViewerSource, (Throwable) -> Unit>()

    /** What the share action handed to the chrome. */
    private val shared = mutableListOf<ErrorIncident>()

    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>

    /** The state of the workspace this tab is currently bound to, to publish content into. */
    private lateinit var workspaceState: MutableStateFlow<ViewerWorkspace.State>

    private val spoolDir = Files.createTempDirectory("incident-spool").toFile()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        onErrors.clear()
        shared.clear()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        spoolDir.deleteRecursively()
    }

    private fun makeWorkspace(source: ViewerSource) = mockk<ViewerWorkspace>().apply {
        val states = MutableStateFlow(ViewerWorkspace.State(content = ViewerContent.Image(mime)))
        workspaceState = states
        every { state } returns states
        every { this@apply.source } returns source
        every { storedPath } returns (source as? ViewerSource.Stored)?.path
        every { listingSourceId } returns null
        every { sharedCaption } returns null
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = source.displayName.toCaString()),
        )
        every { reload() } just Runs
    }

    private val incidentStore = recordingIncidentStore(spoolDir)

    private fun makeViewModel(trashEnabled: Flow<Boolean> = flowOf(false)): ViewerWorkspaceViewModel {
        workspaces = MutableStateFlow(makeWorkspace(streamed))
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
            imageSourceFactory = mockk<GatewayZoomableImageSource.Factory>().apply {
                every { create(any(), any()) } answers {
                    onErrors[firstArg()] = secondArg()
                    mockk(relaxed = true)
                }
            },
            pdfPreviewLoader = mockk(relaxed = true),
            textPreviewLoader = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns trashEnabled
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
                    every { shareWorkspaceError(any(), any()) } answers { shared += firstArg<ErrorIncident>() }
                }
            },
        )
    }

    /** The state only renders while it is collected, so every case needs a live subscriber. */
    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private val ViewerWorkspaceViewModel.readyState: ViewerWorkspaceViewModel.State.Ready
        get() = state.value as ViewerWorkspaceViewModel.State.Ready

    /** What the workspace resolved the file to, as the page sees it. */
    private fun publish(content: ViewerContent) {
        workspaceState.value = ViewerWorkspace.State(content = content)
    }

    private fun spooledLogs(): List<File> = spoolDir.listFiles()?.toList() ?: emptyList()

    @Test
    fun `a failure from the source on display is shown`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val boom = IllegalStateException("decode failed")
        onErrors.getValue(streamed)(boom)

        vm.readyState.content shouldBe ViewerContent.Failed(boom)
    }

    @Test
    fun `a failed stream that gets saved shows the saved file, not the old failure`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val boom = IllegalStateException("decode failed")
        onErrors.getValue(streamed)(boom)
        vm.readyState.content shouldBe ViewerContent.Failed(boom)

        // What the save-then-rebind does: the same tab id, now bound to the file that was written.
        workspaces.value = makeWorkspace(stored)

        vm.readyState.source shouldBe stored
        vm.readyState.content shouldBe ViewerContent.Image(mime)
    }

    /**
     * The share action must find the incident frozen when the failure was published, not mint one
     * from the log trail as it looks whenever the user reaches for Share.
     */
    @Test
    fun `sharing a render failure hands over the incident it was frozen into`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val sentinel = IllegalStateException("decode failed")
        onErrors.getValue(streamed)(sentinel)
        vm.readyState.content shouldBe ViewerContent.Failed(sentinel)

        vm.shareError(sentinel)

        val incident = shared.single()
        (incident.error === sentinel) shouldBe true
        incident.occurredAtIsApproximate shouldBe false
        incident.context.containsKey("incident.frozenAtShare") shouldBe false
        incident.context["viewer.contentType"] shouldBe "image/jpeg"
    }

    /**
     * A failure that reaches the state pipeline itself renders as an error card with the same Share
     * action, so it has to be frozen there too.
     */
    @Test
    fun `a failure of the state pipeline is frozen where it surfaces`() = runTest2 {
        val sentinel = IllegalStateException("state pipeline blew up")
        // One of the flows the state is composed from fails right after its first emission,
        // possibly before the viewer has resolved any source.
        val vm = makeViewModel(
            trashEnabled = flow {
                emit(false)
                throw sentinel
            },
        )
        startCollecting(vm)

        // Crossing the coroutine boundary copies the throwable, so this is not the instance thrown above.
        val published = (vm.state.value as ViewerWorkspaceViewModel.State.Error).error
        published.message shouldBe "state pipeline blew up"

        vm.shareError(published)

        val incident = shared.single()
        // The pipeline can fail before any viewer source is resolved, so this incident carries no
        // content context. `sharing a render failure hands over the incident it was frozen into`
        // covers the populated case.
        (incident.error === published) shouldBe true
        incident.occurredAtIsApproximate shouldBe false
        incident.context.containsKey("incident.frozenAtShare") shouldBe false
    }

    /**
     * The workspace reloads whenever the tab is resumed, so a file that cannot be decoded fails
     * again on a fresh throwable. The report has to keep the time the user actually hit the failure
     * and the log trail from around it, and one failure may only spool one trail.
     */
    @Test
    fun `a failure that comes back on reload keeps the incident it was frozen with`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val first = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(first))
        vm.readyState.content shouldBe ViewerContent.Failed(first)
        val incident = incidentStore.get(first).shouldNotBeNull()

        // What a resume runs: the reload seeds Loading and the decode fails again, on a new instance
        publish(ViewerContent.Loading)
        vm.readyState.content shouldBe ViewerContent.Loading
        val second = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(second))
        vm.readyState.content shouldBe ViewerContent.Failed(second)

        val recurrence = incidentStore.get(second).shouldNotBeNull()
        recurrence.incidentId shouldBe incident.incidentId
        recurrence.occurredAt shouldBe incident.occurredAt
        spooledLogs().size shouldBe 1
    }

    /**
     * The store is bounded, so unrelated errors elsewhere in the app can drop the entry the tab
     * anchored on while the failure is still on screen. The recurrence then has nothing to point at
     * and has to be recorded in its own right - a report stamped with the recurrence beats one
     * stamped with the moment the user reached for Share.
     */
    @Test
    fun `a recurrence is recorded on its own once the anchored incident is gone`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val first = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(first))
        incidentStore.get(first).shouldNotBeNull()

        repeat(ErrorIncidentStore.MAX_ENTRIES) { incidentStore.remember(IOException("elsewhere $it")) }
        incidentStore.get(first) shouldBe null

        publish(ViewerContent.Loading)
        val second = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(second))
        vm.readyState.content shouldBe ViewerContent.Failed(second)

        val recurrence = incidentStore.get(second).shouldNotBeNull()
        recurrence.context.containsKey("incident.frozenAtShare") shouldBe false
        recurrence.occurredAtIsApproximate shouldBe false
    }

    @Test
    fun `a failure that differs in type or message is its own incident`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val first = IllegalStateException("decode failed")
        publish(ViewerContent.Failed(first))
        // Same message, different type
        val second = IOException("decode failed")
        publish(ViewerContent.Failed(second))
        // Same type as the last one, different message
        val third = IOException("stream closed")
        publish(ViewerContent.Failed(third))

        val incidents = listOf(first, second, third).map { incidentStore.get(it).shouldNotBeNull() }
        incidents.map { it.incidentId }.distinct().size shouldBe 3
        spooledLogs().size shouldBe 3
    }

    @Test
    fun `a file that renders between two failures gets a fresh incident`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        val first = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(first))
        publish(ViewerContent.Image(mime))
        vm.readyState.content shouldBe ViewerContent.Image(mime)
        val second = ViewerUndecodableImageException("photo.jpg")
        publish(ViewerContent.Failed(second))

        val before = incidentStore.get(first).shouldNotBeNull()
        val after = incidentStore.get(second).shouldNotBeNull()
        after.incidentId shouldNotBe before.incidentId
        spooledLogs().size shouldBe 2
    }

    @Test
    fun `a late failure from the replaced stream cannot poison the saved file`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        workspaces.value = makeWorkspace(stored)

        // The disposed source reporting after the swap: it names the stream, not what is on display.
        val late = IllegalStateException("decode failed")
        onErrors.getValue(streamed)(late)

        vm.readyState.content shouldBe ViewerContent.Image(mime)
        // A failure the page never shows must not cost a store slot and a log trail either.
        incidentStore.get(late) shouldBe null
        spooledLogs().size shouldBe 0
    }
}
