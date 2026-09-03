package eu.darken.butler.common.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class SettingsUpgradeBadgeTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val badgeLabel = context.getString(R.string.app_name_upgrade_postfix)

    private var clicks = 0
    private var upgrades = 0
    private var toggles = 0

    @Test
    fun `an ungated preference row has no badge and runs its own action`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    onClick = { clicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText(badgeLabel).assertDoesNotExist()

        composeTestRule.onNodeWithText(TITLE).performClick()
        clicks shouldBe 1
        upgrades shouldBe 0
    }

    @Test
    fun `a gated preference row shows the badge and routes to the upgrade`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    onClick = { clicks++ },
                    onUpgrade = { upgrades++ },
                )
            }
        }

        composeTestRule.onNodeWithText(badgeLabel).assertIsDisplayed()

        composeTestRule.onNodeWithText(TITLE).performClick()
        upgrades shouldBe 1
        clicks shouldBe 0
    }

    @Test
    fun `a gated switch row has no toggle left to hit`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    checked = false,
                    onCheckedChange = { toggles++ },
                    onUpgrade = { upgrades++ },
                )
            }
        }

        // A row tap lands in the title column, so it would miss the Switch either way. The absent
        // toggleable node is what proves the Switch itself cannot be hit.
        composeTestRule.onNode(isToggleable()).assertDoesNotExist()

        composeTestRule.onNodeWithText(TITLE).performClick()
        upgrades shouldBe 1
        toggles shouldBe 0
    }

    @Test
    fun `an ungated switch row keeps its toggle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Settings,
                    title = TITLE,
                    subtitle = SUBTITLE,
                    checked = false,
                    onCheckedChange = { toggles++ },
                )
            }
        }

        composeTestRule.onNodeWithText(badgeLabel).assertDoesNotExist()

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
        composeTestRule.onNode(isToggleable()).performClick()
        toggles shouldBe 1
    }

    companion object {
        private const val TITLE = "Row title"
        private const val SUBTITLE = "Row subtitle"
    }
}
