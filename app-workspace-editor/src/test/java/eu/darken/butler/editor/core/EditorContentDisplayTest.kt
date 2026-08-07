package eu.darken.butler.editor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.engine.ContentSource
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the tab is called as the engine's content source changes.
 *
 * An engine emits an in-memory source before its file is loaded and keeps doing so when loading
 * fails, so the source type alone must not decide whether this is a scratch buffer. The identity
 * path decides: the file the TAB claims, which is set while a file is loading or failed to load
 * and cleared when the user cancels the open or closes the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorContentDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notes = LocalPath.build("/sdcard/Download/notes.txt")
    private val scratchTitle = "Shopping list".toCaString()

    private fun fileSource(size: Long = 4L) = ContentSource.File(
        path = notes,
        size = size,
        lastModified = null,
        canWrite = true,
    )

    @Test
    fun `a loaded file names the tab and describes it by its path`() {
        val display = editorContentDisplay(
            contentSource = fileSource(),
            identityPath = notes,
            scratchTitle = scratchTitle,
        )

        display.title!!.get(context) shouldBe "notes.txt"
        display.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `a file that is still loading keeps its name`() {
        // The engine publishes an in-memory placeholder until the file is indexed; the tab still
        // claims the file
        val display = editorContentDisplay(
            contentSource = ContentSource.Memory(size = 0L),
            identityPath = notes,
            scratchTitle = scratchTitle,
        )

        display.title!!.get(context) shouldBe "notes.txt"
        display.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `a file that failed to load keeps its name instead of becoming a scratch buffer`() {
        // A failed load leaves the engine on its in-memory source permanently, and leaves the
        // tab's claim on the file - which is what a session save persists too
        val display = editorContentDisplay(
            contentSource = ContentSource.Memory(size = 0L, suggestedName = null),
            identityPath = notes,
            scratchTitle = scratchTitle,
        )

        display.title!!.get(context) shouldBe "notes.txt"
        display.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `a real scratch buffer uses the scratch name`() {
        // Also covers a cancelled open: cancelling clears the tab's claim, so the tab becomes an
        // ordinary scratch buffer with no identity path - the same inputs as a real scratch buffer
        val display = editorContentDisplay(
            contentSource = ContentSource.Memory(size = 0L),
            identityPath = null,
            scratchTitle = scratchTitle,
        )

        display.title!!.get(context) shouldBe "Shopping list"
        display.subtitle shouldBe null
    }

    @Test
    fun `a blank in-memory name falls back to the scratch name`() {
        listOf("", "   ").forEach { blank ->
            val display = editorContentDisplay(
                contentSource = ContentSource.Memory(size = 0L, suggestedName = blank),
                identityPath = null,
                scratchTitle = scratchTitle,
            )

            display.title!!.get(context) shouldBe "Shopping list"
        }
    }

    @Test
    fun `a named in-memory source wins over the scratch name`() {
        val display = editorContentDisplay(
            contentSource = ContentSource.Memory(size = 0L, suggestedName = "Pasted text"),
            identityPath = null,
            scratchTitle = scratchTitle,
        )

        display.title!!.get(context) shouldBe "Pasted text"
        display.subtitle shouldBe null
    }
}
