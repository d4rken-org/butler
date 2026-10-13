package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.LocalUserActivity
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.UserActivitySignal
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Duration

/**
 * The text assertions match whole strings only: the tip card in the same tree shows a random tip,
 * and several tips mention panes.
 */
class EmptyAdaptiveWorkspaceContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Parks the looping mascot animation so the Robolectric clock can reach idle. */
    private object NeverActive : UserActivitySignal {
        override fun isActive(idleAfter: Duration): Flow<Boolean> = MutableStateFlow(false)
    }

    private var settingsOpened = 0

    private val buttonProvider = object : WorkspaceButtonProvider by FakeWorkspaceButtonProvider() {
        override fun navToSettings() {
            settingsOpened++
        }
    }

    private fun render(paneNumber: Int?) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalUserActivity provides NeverActive,
                    LocalWorkspaceButtonProvider provides buttonProvider,
                ) {
                    EmptyAdaptiveWorkspaceContent(
                        paneNumber = paneNumber,
                        onAddWorkspace = {},
                    )
                }
            }
        }
    }

    @Test
    fun `without a pane number the empty screen names no pane`() {
        render(paneNumber = null)

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_classic_empty_tabs_closed))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_adaptive_pane_ready, 1))
            .assertDoesNotExist()
    }

    @Test
    fun `with a pane number the empty screen names that pane`() {
        render(paneNumber = 2)

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_adaptive_pane_ready, 2))
            .assertIsDisplayed()
    }

    @Test
    fun `a single pane offers the same create and settings actions as the classic layout`() {
        render(paneNumber = null)

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_empty_create_action))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_empty_settings_action))
            .assertIsDisplayed()
            .performClick()

        settingsOpened shouldBe 1
    }

    @Test
    fun `numbered panes offer only their own create action`() {
        render(paneNumber = 2)

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_empty_create_action))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_empty_settings_action))
            .assertDoesNotExist()
    }
}
