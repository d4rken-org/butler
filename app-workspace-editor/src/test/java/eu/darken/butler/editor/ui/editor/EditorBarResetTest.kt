package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a state change counts as fresh content, i.e. when the editor's floating bars un-hide.
 *
 * The failure mode is only visible on a cold restart - the bars pop back in and the user's saved
 * collapse state is overwritten with "expanded" - so the decision is pinned here rather than left
 * to a device run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorBarResetTest {

    private val notes = LocalPath.build("/sdcard/Download/notes.txt")
    private val todo = LocalPath.build("/sdcard/Download/todo.md")

    private fun state(
        contentPath: APath<*>?,
        contentSource: ContentSource,
    ) = EditorWorkspaceViewModel.State(
        id = Workspace.Id(),
        contentPath = contentPath,
        contentSource = contentSource,
        title = caString("irrelevant"),
        subTitle = caString("irrelevant"),
    )

    private fun fileSource(
        path: APath<*>,
        size: Long = 4L,
        lineEnding: LineEnding = LineEnding.LF,
    ) = ContentSource.File(
        path = path,
        size = size,
        lastModified = null,
        canWrite = true,
        lineEnding = lineEnding,
    )

    /** Mirrors the page's OnValueChange: the reset runs exactly when the keyed identity changes. */
    private fun resets(
        before: EditorWorkspaceViewModel.State,
        after: EditorWorkspaceViewModel.State,
    ): Boolean = editorBarResetIdentity(before) != editorBarResetIdentity(after)

    @Test
    fun `a file settling in after its load is not new content`() {
        // The engine reports an in-memory placeholder until the load finishes - by then the page
        // has already composed and restored its bars, so this must not reset them
        val loading = state(contentPath = notes, contentSource = ContentSource.Memory(size = 0L))
        val loaded = state(contentPath = notes, contentSource = fileSource(notes))

        resets(loading, loaded) shouldBe false
    }

    @Test
    fun `opening a different file in the same tab is new content`() {
        // The new path is published as the open starts, while the fresh engine is still on its
        // placeholder - the reset has to fire off the path, not off the source
        val before = state(contentPath = notes, contentSource = fileSource(notes))
        val opening = state(contentPath = todo, contentSource = ContentSource.Memory(size = 0L))

        resets(before, opening) shouldBe true
    }

    @Test
    fun `saving refreshes the content source but is not new content`() {
        val before = state(contentPath = notes, contentSource = fileSource(notes, size = 4L))
        val afterSave = state(
            contentPath = notes,
            contentSource = fileSource(notes, size = 12L, lineEnding = LineEnding.CRLF),
        )

        resets(before, afterSave) shouldBe false
    }

    @Test
    fun `a scratch buffer opening a file is new content`() {
        val scratch = state(contentPath = null, contentSource = ContentSource.Memory(size = 32L))
        val opening = state(contentPath = notes, contentSource = ContentSource.Memory(size = 0L))

        resets(scratch, opening) shouldBe true
    }

    @Test
    fun `closing the file leaves nothing the bars belong to`() {
        val before = state(contentPath = notes, contentSource = fileSource(notes))
        val closed = state(contentPath = null, contentSource = ContentSource.Memory(size = 0L))

        resets(before, closed) shouldBe true
    }
}
