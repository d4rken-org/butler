package eu.darken.butler.searcher.ui.search.elements

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.ui.search.dialogs.DateConditionEditSheet
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import java.util.TimeZone
import kotlin.time.Instant
import eu.darken.butler.common.R as CommonR

/**
 * A date condition stores an absolute cutoff. The UI used to re-derive a preset from it by matching
 * the elapsed time against preset durations with a 60-minute tolerance, so an hour after it was
 * picked the chip degraded to the literal word "Date" and reopening the sheet showed "Last 7 days"
 * regardless of what was chosen - applying then silently rewrote a 90-day cutoff to 7 days.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h800dp")
class StaleDateFilterTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // Far outside every preset window and well past the old 60-minute tolerance
    private val staleCutoff = Instant.parse("2026-05-04T12:34:56.789Z")

    private val condition = FilterCondition.ModifiedDate(
        comparator = FilterComparator.GT,
        instant = staleCutoff,
    )

    private fun expectedDate() = formatDateTime(
        timestamp = staleCutoff,
        zone = TimeZone.getDefault(),
        locale = context.resources.configuration.locales[0],
        is24Hour = DateFormat.is24HourFormat(context),
        style = DateTimeStyle.COMPACT,
    )

    @Test
    fun `a stale condition renders its date in the chip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                FilterChipBar(
                    filter = SearchFilter(conditions = listOf(condition)),
                    onConditionClick = {},
                    onAddSizeCondition = {},
                    onAddDateCondition = {},
                    onAddTypeCondition = {},
                    onRemoveCondition = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText(expectedDate(), substring = true).assertCountEquals(1)
        // The old fallback: the chip named the value field's label instead of the value
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.searcher_filter_date_value_label))
            .assertCountEquals(0)
    }

    @Test
    fun `reopening a stale condition shows its date, not a preset`() {
        setSheet()

        composeTestRule.onAllNodesWithText(expectedDate(), substring = true).assertCountEquals(1)
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.searcher_filter_date_7d))
            .assertCountEquals(0)
    }

    @Test
    fun `reopening and applying a stale condition leaves the cutoff unchanged`() {
        var applied: FilterCondition.ModifiedDate? = null
        setSheet(onApply = { applied = it })

        composeTestRule
            .onNodeWithText(context.getString(CommonR.string.general_apply_action))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        applied shouldBe condition
    }

    private fun setSheet(onApply: (FilterCondition.ModifiedDate) -> Unit = {}) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    DateConditionEditSheet(
                        visible = true,
                        existingCondition = condition,
                        onDismiss = {},
                        onApply = onApply,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
