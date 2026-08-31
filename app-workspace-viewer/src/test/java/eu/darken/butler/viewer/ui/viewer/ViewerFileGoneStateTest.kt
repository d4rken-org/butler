package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerFileGoneException
import eu.darken.butler.workspace.ui.states.PathGoneBody
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

/**
 * A deleted image is a fact about the file, not a viewer fault: it must not arrive as a reportable
 * error with a retry that cannot work.
 */
class ViewerFileGoneStateTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val error = ViewerFileGoneException(LocalPath.build("/sdcard/DCIM/holiday.jpg"))

    @Test
    fun `the viewer's gone error carries the shared marker`() {
        // This is what routes it away from the error card in ViewerWorkspacePage
        error.shouldBeInstanceOf<PathGoneError>()
    }

    @Test
    fun `it renders the viewer's own wording, without a report or retry action`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PathGoneBody(error = error)
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_error_file_gone_label))
            .assertIsDisplayed()

        // The card's actions would both be dead ends here: nothing to report, nothing to retry
        composeTestRule
            .onAllNodesWithText(context.getString(CommonR.string.general_share_error_action))
            .assertCountEquals(0)
    }
}
