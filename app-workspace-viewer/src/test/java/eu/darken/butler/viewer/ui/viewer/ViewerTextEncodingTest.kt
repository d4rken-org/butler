package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.viewer.core.TextPreview
import eu.darken.butler.viewer.core.TextPreviewLoader
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSettings
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import testhelpers.mockDataStoreValue
import java.nio.charset.Charset

/**
 * The encoding a text preview is read with: the user's pick reaches the preview and the Editor, and
 * a step to another file starts on detection again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerTextEncodingTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val notes = LocalPath.build("/storage/emulated/0/Documents/notes.txt")
    private val other = LocalPath.build("/storage/emulated/0/Documents/other.txt")
    private val mime = MimeInfo("text/plain")

    private val creates = mutableListOf<WorkspaceAction.Create>()
    private val charsetPicks = mutableListOf<Charset?>()

    /** What automatic detection reports for each file. */
    private val detected = mutableMapOf<APath<*>, Charset>()

    private lateinit var workspaces: MutableStateFlow<ViewerWorkspace>
    private val textPreviewLoader = mockk<TextPreviewLoader>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        creates.clear()
        charsetPicks.clear()
        detected.clear()
        coEvery { textPreviewLoader.preview(any(), any()) } coAnswers {
            val source = firstArg<ViewerSource.Stored>()
            TextPreview(lines = listOf("text"), charset = secondArg<Charset?>() ?: detected.getValue(source.path))
        }
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** Stands in for the workspace's own re-classification, which ViewerWorkspaceClassificationTest pins. */
    private fun makeWorkspace(
        path: APath<*>,
        content: ViewerContent = ViewerContent.Text(mime),
    ) = mockk<ViewerWorkspace>().apply {
        val state = MutableStateFlow(ViewerWorkspace.State(content = content))
        every { this@apply.state } returns state
        every { source } returns ViewerSource.Stored(path)
        every { storedPath } returns path
        every { listingSourceId } returns null
        every { stepPaths } returns null
        every { sharedCaption } returns null
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.VIEWER, title = path.name.toCaString()),
        )
        every { setTextCharset(any()) } answers {
            val charset = firstArg<Charset?>()
            charsetPicks.add(charset)
            state.value = state.value.copy(content = ViewerContent.Text(mime, charset))
        }
    }

    private fun makeViewModel(workspace: ViewerWorkspace): ViewerWorkspaceViewModel {
        workspaces = MutableStateFlow(workspace)
        val remote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { events } returns emptyFlow()
            every { state } returns MutableStateFlow(WorkspaceRemote.State(infos = emptyList()))
            coEvery { execute(any()) } coAnswers {
                val action = firstArg<WorkspaceAction>()
                if (action is WorkspaceAction.Create) creates.add(action)
                WorkspaceAction.Create.Result.Success(Workspace.Id())
            }
        }
        return ViewerWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns workspaces
            },
            workspaceRemote = remote,
            imageSourceFactory = mockk(relaxed = true),
            pdfPreviewLoader = mockk(relaxed = true),
            textPreviewLoader = textPreviewLoader,
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
                every { swipeBetweenFiles } returns mockk<DataStoreValue<Boolean>>().apply {
                    every { flow } returns MutableStateFlow(true)
                }
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

    private fun TestScope.startCollecting(vm: ViewerWorkspaceViewModel) {
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { } }
    }

    private val ViewerWorkspaceViewModel.readyState: ViewerWorkspaceViewModel.State.Ready
        get() = state.value as ViewerWorkspaceViewModel.State.Ready

    private val editorOverride: String?
        get() = creates.single().arguments.shouldBeInstanceOf<EditorArguments.Default>().charsetOverride

    @Test
    fun `a picked encoding reloads the preview with it and keeps the detected one`() = runTest2 {
        detected[notes] = Charsets.UTF_8
        val vm = makeViewModel(makeWorkspace(notes))
        startCollecting(vm)
        vm.readyState.textPreview!!.preview!!.charset shouldBe Charsets.UTF_8

        vm.showEncodingDialog()
        vm.encodingDialog.value shouldBe true
        vm.selectEncoding(TextCharsets.GB18030)

        vm.encodingDialog.value shouldBe false
        charsetPicks shouldBe listOf(TextCharsets.GB18030)
        coVerify { textPreviewLoader.preview(ViewerSource.Stored(notes), TextCharsets.GB18030) }
        val preview = vm.readyState.textPreview!!
        preview.preview!!.charset shouldBe TextCharsets.GB18030
        // Still known, so the dialog can name what "Automatic" would go back to.
        preview.autoCharset shouldBe Charsets.UTF_8
    }

    @Test
    fun `automatic clears the pick`() = runTest2 {
        detected[notes] = Charsets.UTF_8
        val vm = makeViewModel(makeWorkspace(notes, ViewerContent.Text(mime, TextCharsets.GB18030)))
        startCollecting(vm)

        vm.selectEncoding(null)

        charsetPicks shouldBe listOf(null)
        vm.readyState.textPreview!!.preview!!.charset shouldBe Charsets.UTF_8
    }

    /** A step replaces the workspace (see ViewerFileStepTest); nothing here may carry the pick over. */
    @Test
    fun `the pick does not survive a step to another file`() = runTest2 {
        detected[notes] = Charsets.UTF_8
        detected[other] = Charsets.ISO_8859_1
        val vm = makeViewModel(makeWorkspace(notes, ViewerContent.Text(mime, TextCharsets.GB18030)))
        startCollecting(vm)

        workspaces.value = makeWorkspace(other)

        vm.readyState.content shouldBe ViewerContent.Text(mime)
        vm.readyState.textPreview!!.preview!!.charset shouldBe Charsets.ISO_8859_1
        coVerify { textPreviewLoader.preview(ViewerSource.Stored(other), null) }
        vm.openInEditor()
        editorOverride shouldBe null
    }

    @Test
    fun `a rejected text file offers the encoding and reads as text once one is picked`() = runTest2 {
        val workspace = makeWorkspace(notes, ViewerContent.Unsupported(mime))
        val vm = makeViewModel(workspace)
        startCollecting(vm)
        vm.readyState.actions.contains(ViewerActionBarItem.Encoding) shouldBe true
        vm.readyState.actions.contains(ViewerActionBarItem.OpenInEditor) shouldBe false

        vm.selectEncoding(TextCharsets.GB18030)

        vm.readyState.content shouldBe ViewerContent.Text(mime, TextCharsets.GB18030)
        vm.readyState.textPreview!!.preview!!.charset shouldBe TextCharsets.GB18030
        vm.readyState.actions.contains(ViewerActionBarItem.OpenInEditor) shouldBe true
        verify { workspace.setTextCharset(TextCharsets.GB18030) }
    }

    // ==================== the Editor hand-off ====================

    @Test
    fun `the editor gets a picked encoding`() = runTest2 {
        detected[notes] = Charsets.UTF_8
        val vm = makeViewModel(makeWorkspace(notes, ViewerContent.Text(mime, Charsets.UTF_16LE)))
        startCollecting(vm)

        vm.openInEditor()

        editorOverride shouldBe "UTF-16LE"
    }

    @Test
    fun `the editor gets a detected gb18030`() = runTest2 {
        detected[notes] = TextCharsets.GB18030
        val vm = makeViewModel(makeWorkspace(notes))
        startCollecting(vm)

        vm.openInEditor()

        editorOverride shouldBe "GB18030"
    }

    @Test
    fun `the editor detects utf-8 on its own`() = runTest2 {
        detected[notes] = Charsets.UTF_8
        val vm = makeViewModel(makeWorkspace(notes))
        startCollecting(vm)

        vm.openInEditor()

        editorOverride shouldBe null
    }

    @Test
    fun `the editor detects latin-1 on its own`() = runTest2 {
        detected[notes] = Charsets.ISO_8859_1
        val vm = makeViewModel(makeWorkspace(notes))
        startCollecting(vm)

        vm.openInEditor()

        editorOverride shouldBe null
    }
}
