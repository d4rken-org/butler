package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.compose.LocalUserActivity
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.UserActivitySignal
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceRevealOrigin
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceRevealOrigin
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Duration

/**
 * The reveal origin records which button the user pressed to open the manager, and the exit
 * animation shrinks back into it. The manager's own content contains a WorkspaceButton that is
 * there to be looked at, not pressed, so nothing the overlay hosts may reach that slot.
 */
class ManagerRevealOriginScopeTest : ComposeTest() {

    /** Where the rail button that opened the manager sat, in root coordinates. */
    private val pressedButton = Offset(80f, 112f)

    /**
     * The illustrative mascot animates on a loop the card gives no way to override, and the
     * composition-local default reports the user as permanently active, so the loop would keep the
     * Robolectric clock from ever reaching idle. Reporting no activity parks it instead.
     */
    private object NeverActive : UserActivitySignal {
        override fun isActive(idleAfter: Duration): Flow<Boolean> = MutableStateFlow(false)
    }

    @Composable
    private fun OverlayHarness(origin: WorkspaceRevealOrigin) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalUserActivity provides NeverActive,
                LocalWorkspaceRevealOrigin provides origin,
            ) {
                ManagerRevealOverlay(
                    // Already open on the first frame, so the reveal snaps and the overlay's
                    // pre-settle input barrier is never in the way of the gesture below.
                    state = rememberManagerRevealState(visible = true),
                    revealOrigin = origin,
                ) {
                    WorkspaceBadgeExplanationCard(onDismiss = {})
                }
            }
        }
    }

    @Test
    fun `the manager's own content cannot move the reveal origin`() {
        val origin = WorkspaceRevealOrigin().apply { offset = pressedButton }

        composeTestRule.setContent { OverlayHarness(origin) }

        composeTestRule.onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG)
            .performTouchInput { longClick() }

        withClue(
            "The illustrative WorkspaceButton in WorkspaceBadgeExplanationCard overwrote the reveal " +
                "origin with its own centre. The manager would then shrink back into that card " +
                "instead of into the button the user opened it from."
        ) {
            origin.offset shouldBe pressedButton
        }

        // Guards the assertion above against a false pass: had the overlay swallowed the gesture,
        // the origin would survive for the wrong reason. The same node still takes a tap.
        composeTestRule.onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG).performClick()
        composeTestRule.onNodeWithText("Tab manager").assertExists()
    }
}
