package eu.darken.butler.bugreport.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/** The bug report page's dialogs render from the overlay slot, not from the page host. */
class BugReportWorkspaceOverlaysTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing renders while no dialog is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_share_consent_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_recording_short_title))
            .assertDoesNotExist()
    }

    @Test
    fun `the share consent dialog renders and reports the awaiting report`() {
        var consentedFor: String? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(
                            shareConsentReportId = "report-1",
                        ),
                        onShareConsent = { consentedFor = it },
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_share_consent_title))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.bugreport_share_action)).performClick()

        composeTestRule.runOnIdle { consentedFor shouldBe "report-1" }
    }

    @Test
    fun `the short recording warning renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(
                            showShortRecordingWarning = true,
                        ),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_recording_short_title))
            .assertIsDisplayed()
    }
}
