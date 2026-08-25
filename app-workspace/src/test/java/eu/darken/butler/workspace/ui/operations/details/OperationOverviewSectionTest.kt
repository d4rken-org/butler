package eu.darken.butler.workspace.ui.operations.details

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Handyman
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Layout assertions here are bounds only: Robolectric measures text at a fixed 20px height and 1px
 * per character, so anything about how a value wraps or ellipsizes would pass regardless of the
 * layout. Positions are sound.
 */
class OperationOverviewSectionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val completedOperation = OperationDisplay(
        id = Operation.Id(),
        startedAt = Clock.System.now() - 5.minutes,
        icon = Icons.TwoTone.Handyman,
        title = "Move".toCaString(),
        description = "Moving files".toCaString(),
        state = OperationDisplay.State.Completed(
            summary = "Moved 12 files".toCaString(),
            completedAt = Clock.System.now(),
            report = object : Operation.Report {
                override val summary = "Moved 12 files".toCaString()
                override val affectedPaths = emptyList<Operation.Report.PathChange>()
            },
        ),
    )

    private fun renderAt(width: Dp) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.width(width)) {
                    OperationOverviewSection(operation = completedOperation)
                }
            }
        }
    }

    private fun labelBounds(resId: Int) =
        composeTestRule.onNodeWithText(context.getString(resId)).getUnclippedBoundsInRoot()

    @Test
    fun `status and time share a row when there is room for two columns`() {
        renderAt(OverviewPairingMinWidth + 80.dp)

        val status = labelBounds(R.string.operations_details_status)
        val completed = labelBounds(R.string.operations_details_completed_at)

        (status.top < completed.bottom && completed.top < status.bottom) shouldBe true
        (status.right <= completed.left || completed.right <= status.left) shouldBe true
    }

    @Test
    fun `every entry goes full width in a narrow pane`() {
        renderAt(OverviewPairingMinWidth - 60.dp)

        val labels = listOf(
            R.string.operations_details_status,
            R.string.operations_details_completed_at,
            R.string.operations_details_duration,
            R.string.operations_details_result,
        ).map { labelBounds(it) }

        labels.forEachIndexed { index, first ->
            labels.drop(index + 1).forEach { second ->
                (first.bottom <= second.top || second.bottom <= first.top) shouldBe true
            }
        }
    }

    @Test
    fun `the state icon is decorative and claims no touch target`() {
        renderAt(OverviewPairingMinWidth + 80.dp)

        composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertCountEquals(0)
    }
}
