package eu.darken.butler.upgrade.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest

class FossUpgradeScreenTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun setView(view: FossUpgradeView) {
        composeTestRule.setContent {
            PreviewWrapper {
                UpgradeScreen(view = view)
            }
        }
    }

    // Free and upgraded render the SAME words ("Butler FOSS"), so text matching is blind to the
    // difference: the styling is the whole message and it lives in the span colors.
    private fun titleSpanColors(): List<Color> = composeTestRule
        .onNodeWithTag(UpgradeScreenTags.TITLE)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .first()
        .spanStyles
        .map { it.item.color }

    @Test
    fun `the pitch view shows the plain title and the sponsor action`() {
        setView(FossUpgradeView.PITCH)

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.TITLE).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.FOSS_SPONSOR).assertCountEquals(1)
    }

    @Test
    fun `the pitch shows what you get before how to help`() {
        setView(FossUpgradeView.PITCH)

        // Family order: the benefits answer "what do I get" first, the sponsor mechanics follow.
        val benefitsTop = composeTestRule
            .onNodeWithText(context.getString(R.string.upgrade_benefits_title))
            .getUnclippedBoundsInRoot().top
        val howTop = composeTestRule
            .onNodeWithText(context.getString(R.string.upgrade_screen_how_title))
            .getUnclippedBoundsInRoot().top

        check(benefitsTop < howTop) {
            "Expected the benefits card above the how-to-help card, got benefits=$benefitsTop how=$howTop"
        }
    }

    @Test
    fun `the free status shows the app title without the upgraded styling`() {
        setView(FossUpgradeView.STATUS_FREE)

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.FOSS_STATUS_FREE).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.FOSS_SHOW_OPTIONS).assertCountEquals(1)

        val colors = titleSpanColors()
        colors.size shouldBe 2
        // Uniform color across base and postfix: the free status must not wear the earned styling.
        colors[0] shouldBe colors[1]
    }

    @Test
    fun `the free status keeps the happy mascot`() {
        setView(FossUpgradeView.STATUS_FREE)

        // Not being a supporter isn't a problem state — no sad face for the default case.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_HAPPY).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_GRUMPY).assertCountEquals(0)
    }

    @Test
    fun `the upgraded status highlights the flavor postfix`() {
        setView(FossUpgradeView.STATUS_UPGRADED)

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.FOSS_STATUS_UPGRADED).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.FOSS_DONATE).assertCountEquals(1)

        val colors = titleSpanColors()
        colors.size shouldBe 2
        colors[0] shouldNotBe colors[1]
    }
}
