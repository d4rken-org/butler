package eu.darken.butler.viewer.ui.settings

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class ViewerSettingsScreenTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val swipeChanges = mutableListOf<Boolean>()
    private val deleteAdvanceChanges = mutableListOf<Boolean>()

    private fun setScreen(state: ViewerSettingsViewModel.State) {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerSettingsScreen(
                    state = state,
                    onNavigateUp = {},
                    onShowNextAfterDeleteChange = { deleteAdvanceChanges.add(it) },
                    onSwipeBetweenFilesChange = { swipeChanges.add(it) },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun switchOf(titleRes: Int): SemanticsNodeInteraction = composeTestRule.onNode(
        isToggleable() and hasAnyAncestor(hasText(context.getString(titleRes))),
    )

    private val swipeSwitch get() = switchOf(R.string.viewer_settings_swipe_between_files_title)
    private val deleteAdvanceSwitch get() = switchOf(R.string.viewer_settings_show_next_after_delete_title)

    @Test
    fun `the swipe switch shows the setting`() {
        setScreen(ViewerSettingsViewModel.State(showNextAfterDelete = true, swipeBetweenFiles = false))

        swipeSwitch.assertIsOff()
        deleteAdvanceSwitch.assertIsOn()
    }

    @Test
    fun `turning swiping off reports false`() {
        setScreen(ViewerSettingsViewModel.State(swipeBetweenFiles = true))
        swipeSwitch.assertIsOn()

        swipeSwitch.performClick()

        swipeChanges shouldBe listOf(false)
        deleteAdvanceChanges shouldBe emptyList()
    }

    @Test
    fun `turning swiping on reports true`() {
        setScreen(ViewerSettingsViewModel.State(swipeBetweenFiles = false))

        swipeSwitch.performClick()

        swipeChanges shouldBe listOf(true)
        deleteAdvanceChanges shouldBe emptyList()
    }
}
