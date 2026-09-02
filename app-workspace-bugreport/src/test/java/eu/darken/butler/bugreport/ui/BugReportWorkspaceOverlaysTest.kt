package eu.darken.butler.bugreport.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.bugreport.ui.BugReportWorkspaceViewModel.ActiveDialog
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest
import testhelpers.coroutine.TestDispatcherProvider
import eu.darken.butler.common.R as CommonR

/** The bug report page's dialogs render from the overlay slot, not from the page host. */
class BugReportWorkspaceOverlaysTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createVM() = BugReportWorkspaceViewModel(
        id = Workspace.Id(),
        dispatchers = TestDispatcherProvider(),
        bugReportRepo = mockk<BugReportRepo>(relaxed = true).apply {
            every { reports } returns flowOf(emptyList())
        },
        bugReportRecorder = mockk<BugReportRecorder>(relaxed = true).apply {
            every { state } returns MutableStateFlow(BugReportRecorder.State())
        },
    )

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
        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_delete_all_confirm_title))
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
                            activeDialog = ActiveDialog.ShareConsent("report-1"),
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
    fun `the share consent offers the privacy policy`() {
        var opened = 0

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(
                            activeDialog = ActiveDialog.ShareConsent("report-1"),
                        ),
                        onPrivacyPolicy = { opened++ },
                    )
                }
            }
        }

        val label = context.getString(CommonR.string.general_privacy_policy_action)
        composeTestRule.onNodeWithText(label).assertIsDisplayed()

        composeTestRule.onNodeWithText(label).performClick()

        composeTestRule.runOnIdle { opened shouldBe 1 }
    }

    @Test
    fun `the short recording warning renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(
                            activeDialog = ActiveDialog.ShortRecordingWarning,
                        ),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_recording_short_title))
            .assertIsDisplayed()
    }

    @Test
    fun `the delete all confirmation renders and reports both answers`() {
        var confirmed = 0
        var dismissed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspaceOverlays(
                        overlayState = BugReportWorkspaceViewModel.OverlayState(
                            activeDialog = ActiveDialog.DeleteAllConfirmation,
                        ),
                        onConfirmDeleteAll = { confirmed++ },
                        onDismissDeleteAll = { dismissed++ },
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_delete_all_confirm_title))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(context.getString(R.string.bugreport_cancel_action)).performClick()
        composeTestRule.runOnIdle { dismissed shouldBe 1 }

        composeTestRule.onNodeWithText(context.getString(R.string.bugreport_delete_all_action)).performClick()
        composeTestRule.runOnIdle { confirmed shouldBe 1 }
    }

    @Test
    fun `the delete all confirmation is requested and dismissed through the slot`() {
        val vm = createVM()

        vm.requestDeleteAllConfirmation()
        vm.overlayState.value.activeDialog shouldBe ActiveDialog.DeleteAllConfirmation

        vm.dismissDeleteAllConfirmation()
        vm.overlayState.value.activeDialog shouldBe null
    }

    @Test
    fun `a dialog request while another dialog is showing is dropped`() {
        val vm = createVM()

        vm.requestDeleteAllConfirmation()
        vm.requestShareConsent("report-1")

        vm.overlayState.value.activeDialog shouldBe ActiveDialog.DeleteAllConfirmation
    }
}
