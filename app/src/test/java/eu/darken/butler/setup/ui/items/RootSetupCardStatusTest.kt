package eu.darken.butler.setup.ui.items

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The card is where the headline and the connection sub-line meet, so this is what catches them
 * disagreeing about one state. The icon is not asserted: it carries no content description and
 * Robolectric cannot draw, [RootCardStatusTest] covers the decision both of them render.
 */
class RootSetupCardStatusTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun renderCard(serviceState: RootServiceState) {
        composeTestRule.setContent {
            PreviewWrapper {
                RootShizukuActions(
                    item = SetupItem(
                        type = SetupModule.Type.ROOT,
                        state = RootSetupModule.Result(
                            useRoot = true,
                            isInstalled = false,
                            serviceState = serviceState,
                        ),
                        isRequired = false,
                        priority = 5,
                    ),
                    onExecuteAction = {},
                    switchLabel = context.getString(R.string.setup_use_root_label),
                )
            }
        }
    }

    private fun assertNotShown(text: String) = composeTestRule.onAllNodesWithText(text).assertCountEquals(0)

    @Test
    fun `an answering service is reported as connected`() {
        renderCard(RootServiceState.Available)

        composeTestRule.onNodeWithText(context.getString(R.string.setup_status_connected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.setup_status_completed)).assertIsDisplayed()
        assertNotShown(context.getString(R.string.setup_status_not_installed))
    }

    @Test
    fun `a pending probe is reported as connecting`() {
        renderCard(RootServiceState.Connecting)

        composeTestRule.onNodeWithText(context.getString(R.string.setup_status_connecting)).assertIsDisplayed()
        assertNotShown(context.getString(R.string.setup_status_not_installed))
    }
}
