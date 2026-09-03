package eu.darken.butler.workspace.ui.settings.clipboard

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

@Config(qualifiers = "w400dp-h800dp")
class ClipboardSettingsScreenTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val badgeLabel = context.getString(CommonR.string.app_name_upgrade_postfix)
    private val removeOnPasteTitle = context.getString(R.string.clipboard_settings_remove_on_paste_title)
    private val maxItemsTitle = context.getString(R.string.clipboard_settings_max_items_title)

    private var toggles = 0
    private var maxItems: Int? = null
    private var upgrades = 0

    private fun setScreen(isUpgraded: Boolean) {
        composeTestRule.setContent {
            PreviewWrapper {
                ClipboardSettingsScreen(
                    state = ClipboardSettingsViewModel.State(
                        removeOnPaste = false,
                        maxItems = 3,
                        isUpgraded = isUpgraded,
                    ),
                    onNavigateUp = {},
                    onToggleRemoveOnPaste = { toggles++ },
                    onSetMaxItems = { maxItems = it },
                    onUpgradeButler = { upgrades++ },
                )
            }
        }
    }

    @Test
    fun `both rows are badged for free users`() {
        setScreen(isUpgraded = false)

        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(2)
    }

    @Test
    fun `the gated remove-on-paste row goes to the upgrade instead of toggling`() {
        setScreen(isUpgraded = false)

        composeTestRule.onNode(isToggleable()).assertDoesNotExist()

        composeTestRule.onNodeWithText(removeOnPasteTitle).performClick()
        upgrades shouldBe 1
        toggles shouldBe 0
    }

    @Test
    fun `the gated max-items row goes to the upgrade instead of opening the dialog`() {
        setScreen(isUpgraded = false)

        composeTestRule.onNodeWithText(maxItemsTitle).performClick()
        upgrades shouldBe 1
        composeTestRule.onNodeWithText(DIALOG_ONLY_OPTION).assertDoesNotExist()
        maxItems shouldBe null
    }

    @Test
    fun `pro users get no badge and working controls`() {
        setScreen(isUpgraded = true)

        composeTestRule.onAllNodesWithText(badgeLabel).assertCountEquals(0)

        composeTestRule.onNode(isToggleable()).performClick()
        toggles shouldBe 1

        composeTestRule.onNodeWithText(maxItemsTitle).performClick()
        composeTestRule.onNodeWithText(DIALOG_ONLY_OPTION).assertIsDisplayed()

        upgrades shouldBe 0
    }

    companion object {
        /** Only the max-items dialog offers 10, the row itself shows the current value of 3. */
        private const val DIALOG_ONLY_OPTION = "10"
    }
}
