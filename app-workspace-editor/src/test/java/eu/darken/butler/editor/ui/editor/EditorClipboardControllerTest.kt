package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File

class EditorClipboardControllerTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun path(name: String) = LocalPath.build(File("/tmp/clip-test", name))

    private fun mockWorkspace(selection: String = "selected text"): EditorWorkspace {
        val wsState = MutableStateFlow<EditorWorkspace.State>(
            EditorWorkspace.State.Ready(
                EditorWorkspace.EditorState(
                    contentSource = ContentSource.File(
                        path = path("doc.txt"),
                        size = 10L,
                        lastModified = null,
                        canWrite = true,
                    ),
                ),
            ),
        )
        return mockk<EditorWorkspace>().apply {
            every { state } returns wsState
            coEvery { copySelection() } returns Result.success(selection)
            coEvery { deleteSelection() } returns Result.success(selection)
            coEvery { insertText(any()) } returns Unit
            coEvery { readFileContent(any()) } returns Result.success("file content")
        }
    }

    private fun mockRepo(entries: List<ClipboardClip> = emptyList()): ClipboardRepo =
        mockk<ClipboardRepo>().apply {
            every { state } returns MutableStateFlow(ClipboardRepo.State(entries = entries))
            coEvery { add(any()) } just Runs
        }

    private fun CoroutineScope.controller(
        workspace: EditorWorkspace = mockWorkspace(),
        helper: SystemClipboardHelper = mockk(relaxed = true),
        repo: ClipboardRepo = mockRepo(),
    ) = EditorClipboardController(
        id = workspaceId,
        doLaunch = { block -> launch { block() } },
        workspace = { workspace },
        clipboardHelper = helper,
        clipboardRepo = repo,
        tag = "test",
    )

    @Test
    fun `copy puts the selection on the system clipboard`() = runTest {
        val workspace = mockWorkspace(selection = "hello")
        val helper = mockk<SystemClipboardHelper>(relaxed = true).apply {
            every { hasClipboardContent() } returns false
        }
        val controller = controller(workspace, helper)

        controller.copyToClipboard()
        runCurrent()

        coVerify { helper.copyToClipboard("hello") }
        controller.hasSystemClipboardContent.value shouldBe true
        coVerify(exactly = 0) { workspace.deleteSelection() }
    }

    @Test
    fun `cut copies and deletes the selection`() = runTest {
        val workspace = mockWorkspace(selection = "hello")
        val controller = controller(workspace)

        controller.cutToClipboard()
        runCurrent()

        coVerify { workspace.deleteSelection() }
    }

    @Test
    fun `butler-clipboard copy respects the size cap`() = runTest {
        val huge = "x".repeat(ClipboardClip.Text.MAX_SIZE_BYTES + 1)
        val workspace = mockWorkspace(selection = huge)
        val repo = mockRepo()
        val controller = controller(workspace, repo = repo)

        controller.copyToButlerClipboard()
        runCurrent()

        coVerify(exactly = 0) { repo.add(any()) }
    }

    @Test
    fun `butler-clipboard cut must not delete when the size cap rejects the copy`() = runTest {
        val huge = "x".repeat(ClipboardClip.Text.MAX_SIZE_BYTES + 1)
        val workspace = mockWorkspace(selection = huge)
        val repo = mockRepo()
        val controller = controller(workspace, repo = repo)

        controller.cutToButlerClipboard()
        runCurrent()

        coVerify(exactly = 0) { repo.add(any()) }
        // Nothing was clipped, so nothing may be deleted - otherwise the text is silently lost
        coVerify(exactly = 0) { workspace.deleteSelection() }
    }

    @Test
    fun `butler-clipboard copy records the source file`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo)

        controller.copyToButlerClipboard()
        runCurrent()

        coVerify {
            repo.add(
                match<ClipboardClip> {
                    it is ClipboardClip.Text && it.content == "selected text" && it.sourcePath?.name == "doc.txt"
                },
            )
        }
    }

    @Test
    fun `pasting a text clip inserts its content`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Text(origin = workspaceId, content = "clip text")

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify { workspace.insertText("clip text") }
    }

    @Test
    fun `pasting a paths clip reads the first text file`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(path("archive.zip"), path("notes.txt")),
        )

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify { workspace.readFileContent(match { it.name == "notes.txt" }) }
        coVerify { workspace.insertText("file content") }
    }

    @Test
    fun `pasting a paths clip without text files inserts nothing`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(path("archive.zip")),
        )

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify(exactly = 0) { workspace.insertText(any()) }
    }

    @Test
    fun `pasteable clipboard only surfaces path clips containing text files`() = runTest {
        val textClip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(path("notes.md")),
        )
        val binaryClip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(path("image.png")),
        )
        val textEntry = ClipboardClip.Text(origin = workspaceId, content = "text")
        val controller = controller(repo = mockRepo(listOf(textClip, binaryClip, textEntry)))

        val pasteable = controller.pasteableClipboard.first()

        pasteable shouldHaveSize 1
        pasteable.single().paths.single().name shouldBe "notes.md"
    }
}
