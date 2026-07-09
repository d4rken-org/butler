package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ContentSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

class EditorDialogsControllerTest : BaseTest() {

    private fun mockWorkspace(isModified: Boolean = false, encoding: String = "UTF-8"): EditorWorkspace {
        val source = ContentSource.File(
            path = LocalPath.build(File("/tmp/dialogs-test", "doc.txt")),
            size = 10L,
            lastModified = null,
            canWrite = true,
            detectedCharset = charset(encoding),
        )
        val wsState = MutableStateFlow<EditorWorkspace.State>(
            EditorWorkspace.State.Ready(
                EditorWorkspace.EditorState(contentSource = source, isModified = isModified),
            ),
        )
        return mockk<EditorWorkspace>().apply {
            every { state } returns wsState
            coEvery { reopenWithCharset(any()) } returns Unit
        }
    }

    private fun CoroutineScope.controller(workspace: EditorWorkspace) = EditorDialogsController(
        doLaunch = { block -> launch { block() } },
        workspace = { workspace },
    )

    @Test
    fun `selecting a new encoding on an unmodified document reopens immediately`() = runTest {
        val workspace = mockWorkspace(isModified = false)
        val controller = controller(workspace)
        controller.showEncodingDialog()

        controller.selectEncoding("ISO-8859-1")
        runCurrent()

        val state = controller.state.first()
        state.showEncodingDialog shouldBe false
        state.pendingEncoding.shouldBeNull()
        coVerify(exactly = 1) { workspace.reopenWithCharset("ISO-8859-1") }
    }

    @Test
    fun `selecting a new encoding on a modified document asks for discard confirmation`() = runTest {
        val workspace = mockWorkspace(isModified = true)
        val controller = controller(workspace)

        controller.selectEncoding("ISO-8859-1")
        runCurrent()

        controller.state.first().pendingEncoding shouldBe "ISO-8859-1"
        coVerify(exactly = 0) { workspace.reopenWithCharset(any()) }

        controller.confirmEncodingDiscard()
        runCurrent()

        controller.state.first().pendingEncoding.shouldBeNull()
        coVerify(exactly = 1) { workspace.reopenWithCharset("ISO-8859-1") }
    }

    @Test
    fun `dismissing the encoding discard keeps the document untouched`() = runTest {
        val workspace = mockWorkspace(isModified = true)
        val controller = controller(workspace)
        controller.selectEncoding("ISO-8859-1")
        runCurrent()

        controller.dismissEncodingDiscard()
        runCurrent()

        controller.state.first().pendingEncoding.shouldBeNull()
        coVerify(exactly = 0) { workspace.reopenWithCharset(any()) }
    }

    @Test
    fun `re-selecting the current encoding is a no-op`() = runTest {
        val workspace = mockWorkspace(isModified = false, encoding = "UTF-8")
        val controller = controller(workspace)

        controller.selectEncoding("utf-8")
        runCurrent()

        coVerify(exactly = 0) { workspace.reopenWithCharset(any()) }
    }

    @Test
    fun `save-as overwrite pending is take-once`() = runTest {
        val controller = controller(mockWorkspace())
        val destination = LocalPath.build(File("/tmp/dialogs-test", "target.txt"))

        controller.setPendingSaveAsOverwrite(destination)
        controller.state.first().pendingSaveAsOverwrite shouldBe destination

        controller.takePendingSaveAsOverwrite() shouldBe destination
        controller.takePendingSaveAsOverwrite().shouldBeNull()
        controller.state.first().pendingSaveAsOverwrite.shouldBeNull()
    }

    @Test
    fun `dialog visibility transitions`() = runTest {
        val controller = controller(mockWorkspace())

        controller.showGoToLineDialog()
        controller.showCloseConfirmDialog()
        var state = controller.state.first()
        state.showGoToLineDialog shouldBe true
        state.showCloseConfirmDialog shouldBe true

        controller.dismissGoToLineDialog()
        controller.dismissCloseConfirmDialog()
        state = controller.state.first()
        state.showGoToLineDialog shouldBe false
        state.showCloseConfirmDialog shouldBe false
    }

    @Test
    fun `large delete confirm dialog visibility transitions`() = runTest {
        val controller = controller(mockWorkspace())

        controller.state.first().showLargeDeleteConfirmDialog shouldBe false

        controller.showLargeDeleteConfirmDialog()
        controller.state.first().showLargeDeleteConfirmDialog shouldBe true

        controller.dismissLargeDeleteConfirmDialog()
        controller.state.first().showLargeDeleteConfirmDialog shouldBe false
    }

    @Test
    fun `backup notice dismissal can be re-armed`() = runTest {
        val controller = controller(mockWorkspace())

        controller.dismissBackupNotice()
        controller.state.first().backupNoticeDismissed shouldBe true

        controller.rearmBackupNotice()
        controller.state.first().backupNoticeDismissed shouldBe false
    }
}
