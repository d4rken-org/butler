package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.PasteFileReader
import eu.darken.butler.editor.core.PasteTooLargeException
import eu.darken.butler.editor.core.engine.ClipboardCapacityException
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.ReadOnlyFileException
import eu.darken.butler.editor.core.engine.EditorEngine.CutSnapshot
import eu.darken.butler.editor.core.engine.StaleMatchException
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import kotlin.uuid.Uuid

class EditorClipboardControllerTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val epoch = Uuid.random()

    private fun path(name: String) = LocalPath.build(File("/tmp/clip-test", name))

    private fun fileLookup(name: String, fileType: FileType = FileType.FILE) = LocalPathLookup(
        lookedUp = path(name),
        fileType = fileType,
        size = null,
        modifiedAt = null,
    )

    /** Mirrors ViewModel3's error handler: thrown controller errors surface here, not as crashes. */
    private val surfacedErrors = mutableListOf<Throwable>()

    /** Deletes the controller routed onto the ViewModel's ordered edit queue instead of the workspace. */
    private var queuedDeletes = 0

    /** Result the queued verified delete reports back; overridden to simulate a conflicted cut. */
    private var deleteResult: Result<String>? = null

    private fun mockWorkspace(
        selection: String = "selected text",
        copyResult: Result<String> = Result.success(selection),
    ): EditorWorkspace {
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
            coEvery { copySelection(any()) } returns copyResult
            coEvery { prepareCut(any()) } returns copyResult.map { snapshot(it) }
            coEvery { applyCut(any()) } answers { deleteResult ?: Result.success(firstArg<CutSnapshot>().text) }
            coEvery { performEdit(any(), any()) } returns EditorEngine.EditOutcome.Applied()
            coEvery { readFileContent(any()) } returns Result.success("file content")
        }
    }

    private fun snapshot(text: String) = CutSnapshot(
        text = text,
        startOffset = 0L,
        token = EditorEngine.DocumentToken(epoch, structuralVersion = 1L),
    )

    private fun mockRepo(entries: List<ClipboardClip> = emptyList()): ClipboardRepo =
        mockk<ClipboardRepo>().apply {
            every { state } returns MutableStateFlow(ClipboardRepo.State(entries = entries))
            coEvery { add(any()) } just Runs
        }

    private fun CoroutineScope.controller(
        workspace: EditorWorkspace = mockWorkspace(),
        helper: SystemClipboardHelper = mockk(relaxed = true),
        repo: ClipboardRepo = mockRepo(),
        // Default mirrors the ViewModel's guarded insert: the intent goes to the engine and only
        // an APPLIED outcome counts as pasted.
        guardedInsert: suspend (String) -> Boolean = { text ->
            workspace.performEdit(EditorEngine.EditIntent.InsertAtCursor(text), epoch) is
                EditorEngine.EditOutcome.Applied
        },
    ) = EditorClipboardController(
        id = workspaceId,
        doLaunch = { block ->
            launch {
                try {
                    block()
                } catch (e: Exception) {
                    surfacedErrors += e
                }
            }
        },
        workspace = { workspace },
        guardedInsert = guardedInsert,
        // Stands in for the ViewModel's queued verified-delete command: counts the trip through the
        // queue and then applies it on the workspace, like the edit-command consumer does.
        deleteCut = { snapshot -> queuedDeletes++; workspace.applyCut(snapshot) },
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
        coVerify(exactly = 0) { workspace.applyCut(any()) }
    }

    @Test
    fun `cut copies and deletes the selection`() = runTest {
        val workspace = mockWorkspace(selection = "hello")
        val controller = controller(workspace)

        controller.cutToClipboard()
        runCurrent()

        coVerify { workspace.applyCut(any()) }
    }

    @Test
    fun `both cut variants delete through the edit queue`() = runTest {
        val controller = controller()

        // A copy mutates no document, so it never reaches the queue
        controller.copyToClipboard()
        controller.copyToButlerClipboard()
        runCurrent()
        queuedDeletes shouldBe 0

        // The deletion must stay ordered against typing, so it goes through the ViewModel's queue
        // instead of being applied on the workspace directly
        controller.cutToClipboard()
        controller.cutToButlerClipboard()
        runCurrent()
        queuedDeletes shouldBe 2
    }

    @Test
    fun `cut hands the captured snapshot to the queued delete`() = runTest {
        // The delete must carry the copied range, not re-resolve "the current selection" later
        val captured = mutableListOf<CutSnapshot>()
        val workspace = mockWorkspace(selection = "hello").apply {
            coEvery { applyCut(any()) } answers { captured += firstArg<CutSnapshot>(); Result.success("hello") }
        }
        val controller = controller(workspace)

        controller.cutToClipboard()
        runCurrent()

        captured.single().text shouldBe "hello"
        coVerify { workspace.prepareCut(EditorClipboardController.MAX_SYSTEM_CLIPBOARD_CHARS) }
    }

    @Test
    fun `a cut whose delete is rejected keeps the clipboard copy and raises no error`() = runTest {
        // The document moved on while the delete waited: deleting nothing is a legitimate outcome
        val workspace = mockWorkspace(selection = "hello")
        val helper = mockk<SystemClipboardHelper>(relaxed = true)
        deleteResult = Result.failure(StaleMatchException())
        val controller = controller(workspace, helper)

        controller.cutToClipboard()
        runCurrent()

        coVerify { helper.copyToClipboard("hello") }
        surfacedErrors shouldHaveSize 0
    }

    // ==================== System clipboard size guard ====================

    @Test
    fun `system copy passes the char cap to the engine and surfaces refusals`() = runTest {
        val workspace = mockWorkspace(
            copyResult = Result.failure(ClipboardCapacityException(EditorClipboardController.MAX_SYSTEM_CLIPBOARD_CHARS)),
        )
        val helper = mockk<SystemClipboardHelper>(relaxed = true)
        val controller = controller(workspace, helper)

        controller.copyToClipboard()
        runCurrent()

        coVerify { workspace.copySelection(EditorClipboardController.MAX_SYSTEM_CLIPBOARD_CHARS) }
        coVerify(exactly = 0) { helper.copyToClipboard(any()) }
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
    }

    @Test
    fun `system cut must not delete when the engine refuses the copy`() = runTest {
        val workspace = mockWorkspace(
            copyResult = Result.failure(ClipboardCapacityException(EditorClipboardController.MAX_SYSTEM_CLIPBOARD_CHARS)),
        )
        val controller = controller(workspace)

        controller.cutToClipboard()
        runCurrent()

        coVerify(exactly = 0) { workspace.applyCut(any()) }
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
    }

    @Test
    fun `system cut must not delete when setPrimaryClip throws`() = runTest {
        // The char cap is a heuristic - a binder failure past it must still surface and keep the text
        val workspace = mockWorkspace(selection = "hello")
        val helper = mockk<SystemClipboardHelper>(relaxed = true).apply {
            every { copyToClipboard(any()) } throws RuntimeException("TransactionTooLarge")
        }
        val controller = controller(workspace, helper)

        controller.cutToClipboard()
        runCurrent()

        coVerify(exactly = 0) { workspace.applyCut(any()) }
        controller.hasSystemClipboardContent.value shouldBe false
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
    }

    @Test
    fun `non-capacity copy failures stay log-only`() = runTest {
        val workspace = mockWorkspace(copyResult = Result.failure(IllegalStateException("No selection to copy")))
        val helper = mockk<SystemClipboardHelper>(relaxed = true)
        val controller = controller(workspace, helper)

        controller.copyToClipboard()
        runCurrent()

        coVerify(exactly = 0) { helper.copyToClipboard(any()) }
        surfacedErrors shouldHaveSize 0
    }

    // ==================== Butler clipboard size guard ====================

    @Test
    fun `butler copy passes the byte-cap prefilter to the engine`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)

        controller.copyToButlerClipboard()
        runCurrent()

        coVerify { workspace.copySelection(EditorClipboardController.BUTLER_CLIPBOARD_PREFILTER_CHARS) }
    }

    @Test
    fun `butler-clipboard copy respects the size cap and surfaces the refusal`() = runTest {
        val huge = "x".repeat(ClipboardClip.Text.MAX_SIZE_BYTES + 1)
        val workspace = mockWorkspace(selection = huge)
        val repo = mockRepo()
        val controller = controller(workspace, repo = repo)

        controller.copyToButlerClipboard()
        runCurrent()

        coVerify(exactly = 0) { repo.add(any()) }
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
    }

    @Test
    fun `butler-clipboard copy accepts content exactly at the byte cap`() = runTest {
        val atCap = "x".repeat(ClipboardClip.Text.MAX_SIZE_BYTES)
        val workspace = mockWorkspace(selection = atCap)
        val repo = mockRepo()
        val controller = controller(workspace, repo = repo)

        controller.copyToButlerClipboard()
        runCurrent()

        coVerify { repo.add(any()) }
        surfacedErrors shouldHaveSize 0
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
        coVerify(exactly = 0) { workspace.applyCut(any()) }
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
    }

    @Test
    fun `multibyte content under the char prefilter is still rejected by the exact byte check`() = runTest {
        // 100k CJK chars pass the char prefilter (262144) but encode to ~300KB UTF-8 (> 256KB)
        val cjk = "好".repeat(100_000)
        val workspace = mockWorkspace(selection = cjk)
        val repo = mockRepo()
        val controller = controller(workspace, repo = repo)

        controller.cutToButlerClipboard()
        runCurrent()

        coVerify(exactly = 0) { repo.add(any()) }
        coVerify(exactly = 0) { workspace.applyCut(any()) }
        surfacedErrors.single().shouldBeInstanceOf<ClipboardCapacityException>()
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

        coVerify { workspace.performEdit(EditorEngine.EditIntent.InsertAtCursor("clip text"), epoch) }
    }

    @Test
    fun `a paste the engine refused is never reported as pasted`() = runTest {
        // Read-only / backing-lost document: the guarded insert completes FALSE, so the controller
        // must not log a success for text that never reached the document
        val workspace = mockWorkspace().apply {
            coEvery { performEdit(any(), any()) } returns EditorEngine.EditOutcome.Failed(
                ReadOnlyFileException("File is read-only"),
            )
        }
        val controller = controller(workspace)
        val logged = CapturingLogger().also { Logging.install(it) }

        controller.pasteFromClipboard(ClipboardClip.Text(origin = workspaceId, content = "clip text"))
        runCurrent()
        Logging.remove(logged)

        coVerify(exactly = 1) { workspace.performEdit(EditorEngine.EditIntent.InsertAtCursor("clip text"), epoch) }
        logged.messages.none { it.contains("Pasted") } shouldBe true
    }

    private class CapturingLogger : Logging.Logger {
        val messages = mutableListOf<String>()
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            messages += message
        }
    }

    @Test
    fun `paste routes text through the guarded insert`() = runTest {
        val inserted = mutableListOf<String>()
        val workspace = mockWorkspace()
        // Simulate the gate deferring the insert behind the confirm dialog (returns false).
        val controller = controller(workspace, guardedInsert = { text -> inserted += text; false })
        val clip = ClipboardClip.Text(origin = workspaceId, content = "huge clip")

        controller.pasteFromClipboard(clip)
        runCurrent()

        inserted shouldBe listOf("huge clip")
        coVerify(exactly = 0) { workspace.performEdit(any(), any()) }
    }

    @Test
    fun `pasting a paths clip reads the first text file`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("archive.zip"), fileLookup("notes.txt")),
        )

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify { workspace.readFileContent(match { it.name == "notes.txt" }) }
        coVerify { workspace.performEdit(EditorEngine.EditIntent.InsertAtCursor("file content"), epoch) }
    }

    @Test
    fun `paste-from-file failures surface instead of being swallowed`() = runTest {
        val workspace = mockWorkspace().apply {
            coEvery { readFileContent(any()) } returns Result.failure(
                PasteTooLargeException(PasteFileReader.MAX_PASTE_FILE_SIZE),
            )
        }
        val controller = controller(workspace)

        controller.pasteFromClipboardFile(path("big.txt"))
        runCurrent()

        coVerify(exactly = 0) { workspace.performEdit(any(), any()) }
        surfacedErrors.single().shouldBeInstanceOf<PasteTooLargeException>()
    }

    @Test
    fun `pasting a paths clip without text files inserts nothing`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("archive.zip")),
        )

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify(exactly = 0) { workspace.performEdit(any(), any()) }
    }

    @Test
    fun `pasteable clipboard only surfaces path clips containing text files`() = runTest {
        val textClip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("notes.md")),
        )
        val binaryClip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("image.png")),
        )
        val textEntry = ClipboardClip.Text(origin = workspaceId, content = "text")
        val controller = controller(repo = mockRepo(listOf(textClip, binaryClip, textEntry)))

        val pasteable = controller.pasteableClipboard.first()

        pasteable shouldHaveSize 1
        pasteable.single().paths.single().name shouldBe "notes.md"
    }

    @Test
    fun `pasteable clipboard suggests extensionless and known-name text files but not directories`() = runTest {
        val extensionless = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("changelog")),
        )
        val dotfile = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup(".gitignore")),
        )
        val directory = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("logs", fileType = FileType.DIRECTORY)),
        )
        val controller = controller(repo = mockRepo(listOf(extensionless, dotfile, directory)))

        val pasteable = controller.pasteableClipboard.first()

        pasteable shouldHaveSize 2
        pasteable.flatMap { it.paths }.map { it.name } shouldBe listOf("changelog", ".gitignore")
    }

    @Test
    fun `pasteable clipboard suggests every extension the shared text table knows`() = runTest {
        val clips = listOf("script.lua", "main.dart", "readme.rst", "rules.pro").map { name ->
            ClipboardClip.Paths(
                origin = workspaceId,
                mode = ClipboardClip.Paths.Mode.COPY,
                paths = listOf(fileLookup(name)),
            )
        }
        val controller = controller(repo = mockRepo(clips))

        val pasteable = controller.pasteableClipboard.first()

        pasteable.flatMap { it.paths }.map { it.name } shouldBe
            listOf("script.lua", "main.dart", "readme.rst", "rules.pro")
    }

    @Test
    fun `pasting a paths clip skips directories`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace)
        val clip = ClipboardClip.Paths(
            origin = workspaceId,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(fileLookup("subdir", fileType = FileType.DIRECTORY), fileLookup("notes.txt")),
        )

        controller.pasteFromClipboard(clip)
        runCurrent()

        coVerify { workspace.readFileContent(match { it.name == "notes.txt" }) }
    }
}
