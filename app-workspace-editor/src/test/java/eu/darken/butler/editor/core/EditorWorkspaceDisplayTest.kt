package eu.darken.butler.editor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a file tab is named by its file name and described by its path`() {
        val display = deriveEditorDisplay(
            EditorArguments.Default(filePath = LocalPath.build("/sdcard/Download/notes.txt")),
        )

        display.title!!.get(context) shouldBe "notes.txt"
        display.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `the second line is the containing folder, not the full path`() {
        // The name is already the line above it; repeating it below is noise
        val display = deriveEditorDisplay(
            EditorArguments.Default(filePath = LocalPath.build("/sdcard/Download/notes.txt")),
        )

        display.subtitle!!.get(context) shouldBe "/sdcard/Download"
        display.subtitle!!.get(context) shouldBe
            editorLocationSubtitle(LocalPath.build("/sdcard/Download/notes.txt"))!!.get(context)
    }

    @Test
    fun `a scratch tab keeps its suggested name`() {
        val display = deriveEditorDisplay(EditorArguments.Default(suggestedTitle = "Shopping list"))

        display.title!!.get(context) shouldBe "Shopping list"
        display.subtitle shouldBe null
    }

    @Test
    fun `a file name wins over a suggested name`() {
        val display = deriveEditorDisplay(
            EditorArguments.Default(
                filePath = LocalPath.build("/sdcard/notes.txt"),
                suggestedTitle = "Shopping list",
            ),
        )

        display.title!!.get(context) shouldBe "notes.txt"
    }

    @Test
    fun `an empty tab falls back to the untitled resource, not the type label`() {
        val display = deriveEditorDisplay(EditorArguments.Default())

        display.title!!.get(context) shouldBe context.getString(R.string.editor_file_untitled)
        display.subtitle shouldBe null
    }

    @Test
    fun `an empty suggested name is not a name`() {
        // A share with an empty EXTRA_SUBJECT arrives like this
        val display = deriveEditorDisplay(EditorArguments.Default(suggestedTitle = ""))

        display.title!!.get(context) shouldBe context.getString(R.string.editor_file_untitled)
    }

    @Test
    fun `a whitespace-only suggested name is not a name`() {
        val display = deriveEditorDisplay(EditorArguments.Default(suggestedTitle = "   "))

        display.title!!.get(context) shouldBe context.getString(R.string.editor_file_untitled)
    }
}
