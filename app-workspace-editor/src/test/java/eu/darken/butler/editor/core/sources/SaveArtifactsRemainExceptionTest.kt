package eu.darken.butler.editor.core.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.workspace.ui.states.PathGoneBody
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The interrupted-save case is the one where the user's content still exists, so the screen has to
 * say that and name what to look for - the opposite message from a plain deletion.
 */
class SaveArtifactsRemainExceptionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val path = LocalPath.build("/storage/emulated/0/Documents/doc.txt")
    private val backup = LocalPath.build("/storage/emulated/0/Documents/doc.txt.butler-save-bak-1a2b3c4d")

    private val error = SaveArtifactsRemainException(path, listOf(backup))

    @Test
    fun `routes to the gone presentation`() {
        error.shouldBeInstanceOf<PathGoneError>()
    }

    @Test
    fun `the rendered wording names the surviving backup, not the exception class`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PathGoneBody(error = error)
            }
        }

        composeTestRule.onNodeWithText("doc.txt.butler-save-bak-1a2b3c4d", substring = true).assertIsDisplayed()

        // The generic localized fallback puts the class name in the title; landing on it here would
        // mean the override is missing and the screen is lying about how bad the situation is.
        val rendered = error.localized(context).label.get(context)
        rendered shouldNotContain "SaveArtifactsRemainException"
    }
}
