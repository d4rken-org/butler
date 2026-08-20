package eu.darken.butler.viewer.ui.viewer

import androidx.core.net.toUri
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.Runs
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
 * Render failures the image source reports, and which source they belong to.
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

    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        onErrors.clear()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeWorkspace(source: ViewerSource) = mockk<ViewerWorkspace>().apply {
        every { state } returns MutableStateFlow(ViewerWorkspace.State(content = ViewerContent.Image(mime)))
        every { this@apply.source } returns source
        every { storedPath } returns (source as? ViewerSource.Stored)?.path
        every { sharedCaption } returns null
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = source.displayName.toCaString()),
        )
        every { reload() } just Runs
    }

    private fun makeViewModel(): ViewerWorkspaceViewModel {
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
            openWithIntentUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            trashSettings = mockk<TrashSettings>(relaxed = true).apply {
                every { enabled.flow } returns flowOf(false)
            },
            operationsManager = mockk(relaxed = true),
            apkIconExporter = mockk(relaxed = true),
            filenameValidator = FilenameValidator(),
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>().apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingConflicts } returns flowOf(emptyMap())
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

    @Test
    fun `a late failure from the replaced stream cannot poison the saved file`() = runTest2 {
        val vm = makeViewModel()
        startCollecting(vm)

        workspaces.value = makeWorkspace(stored)

        // The disposed source reporting after the swap: it names the stream, not what is on display.
        onErrors.getValue(streamed)(IllegalStateException("decode failed"))

        vm.readyState.content shouldBe ViewerContent.Image(mime)
    }
}
