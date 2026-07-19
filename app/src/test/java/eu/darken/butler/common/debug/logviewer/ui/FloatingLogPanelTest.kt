package eu.darken.butler.common.debug.logviewer.ui

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logviewer.core.LogLine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import testhelpers.ComposeTest

class FloatingLogPanelTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)

    private val sampleState = FloatingLogPanelViewModel.State(
        lines = listOf(
            LogLine(1, Logging.Priority.DEBUG, "Tag", "hello world"),
            LogLine(2, Logging.Priority.ERROR, "Tag", "boom"),
        ),
    )

    @Test
    fun `pause invokes callback from the overflow menu`() {
        var paused = 0
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingLogPanel(
                    stateSource = MutableStateFlow(sampleState),
                    onTogglePause = { paused++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.debug_logview_more_action)).performClick()
        composeTestRule.onNodeWithText(str(R.string.debug_logview_pause_action)).performClick()
        composeTestRule.runOnIdle { assertEquals(1, paused) }
    }

    @Test
    fun `close invokes callback from the overflow menu`() {
        var closed = 0
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingLogPanel(
                    stateSource = MutableStateFlow(sampleState),
                    onClose = { closed++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.debug_logview_more_action)).performClick()
        // Close lives at the bottom of a long menu — scroll it into view before clicking.
        composeTestRule.onNodeWithText(str(R.string.debug_logview_close_action)).performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun `log level opens a dialog and selecting a level invokes callback`() {
        var picked: Logging.Priority? = null
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingLogPanel(
                    stateSource = MutableStateFlow(sampleState),
                    onSetLevel = { picked = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.debug_logview_more_action)).performClick()
        composeTestRule.onNodeWithText(str(R.string.debug_logview_level_action)).performClick()
        composeTestRule.onNodeWithText("Warn").performClick()
        composeTestRule.runOnIdle { assertEquals(Logging.Priority.WARN, picked) }
    }

    @Test
    fun `search field reflects typed text via local editing state`() {
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingLogPanel(stateSource = MutableStateFlow(sampleState))
            }
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.debug_logview_more_action)).performClick()
        composeTestRule.onNodeWithText(str(R.string.debug_logview_search_action)).performClick()
        // The field is driven by hoisted local state, so the typed text must appear immediately.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("boom")
        composeTestRule.onNodeWithText("boom").assertExists()
    }

    @Test
    fun `clear invokes callback from the overflow menu`() {
        var cleared = 0
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingLogPanel(
                    stateSource = MutableStateFlow(sampleState),
                    onClear = { cleared++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.debug_logview_more_action)).performClick()
        composeTestRule.onNodeWithText(str(R.string.debug_logview_clear_action)).performClick()
        composeTestRule.runOnIdle { assertEquals(1, cleared) }
    }
}
