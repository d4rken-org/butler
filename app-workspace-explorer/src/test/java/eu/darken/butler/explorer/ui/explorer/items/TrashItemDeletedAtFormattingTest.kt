package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.grid.TrashItemGrid
import eu.darken.butler.explorer.ui.explorer.items.row.TrashItemRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest
import java.util.TimeZone
import kotlin.time.Instant

/**
 * Beyond `formatSmartTime`'s 7 day threshold the trash items fall back to an absolute timestamp,
 * the row in [DateTimeStyle.FULL] and the grid in the narrower [DateTimeStyle.COMPACT].
 */
class TrashItemDeletedAtFormattingTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locale = context.resources.configuration.locales[0]
    private val is24Hour = DateFormat.is24HourFormat(context)
    private val zone: TimeZone = TimeZone.getDefault()

    private val oldItem = MockDataProvider.createMockTrashItem(
        name = OLD_NAME,
        deletedHoursAgo = 400L * 24,
    )
    private val recentItem = MockDataProvider.createMockTrashItem(
        name = RECENT_NAME,
        deletedHoursAgo = 1,
    )

    /** Same call the composable overload makes, so the expectation follows the runtime locale. */
    private fun absolute(timestamp: Instant, style: DateTimeStyle) = formatDateTime(
        timestamp = timestamp,
        zone = zone,
        locale = locale,
        is24Hour = is24Hour,
        style = style,
    )

    @Test
    fun `a long deleted row renders the full absolute timestamp`() {
        val full = absolute(oldItem.deletedAt, DateTimeStyle.FULL)
        val compact = absolute(oldItem.deletedAt, DateTimeStyle.COMPACT)
        // FULL carries the century and the seconds, COMPACT neither - if they ever collapse into
        // the same string the style assertions below stop proving anything.
        full shouldNotBe compact

        renderRow(oldItem)

        composeTestRule.onNodeWithText(OLD_NAME).assertExists()
        composeTestRule.onNodeWithText(full).assertExists()
        composeTestRule.onNodeWithText(compact).assertDoesNotExist()
    }

    @Test
    fun `a long deleted grid tile renders the compact absolute timestamp`() {
        val full = absolute(oldItem.deletedAt, DateTimeStyle.FULL)
        val compact = absolute(oldItem.deletedAt, DateTimeStyle.COMPACT)
        full shouldNotBe compact

        composeTestRule.setContent {
            PreviewWrapper {
                TrashItemGrid(item = oldItem)
            }
        }

        composeTestRule.onNodeWithText(OLD_NAME).assertExists()
        composeTestRule.onNodeWithText(compact).assertExists()
        composeTestRule.onNodeWithText(full).assertDoesNotExist()
    }

    @Test
    fun `a freshly deleted row stays on the relative rendering`() {
        renderRow(recentItem)

        // Deleted an hour ago, so the relative rendering stays on the hours bucket for the whole
        // test - the expectation cannot race the clock the composable reads.
        val relative = formatRelativeTime(context, recentItem.deletedAt)

        // The name proves the row rendered at all, so the missing timestamp is a real branch choice
        composeTestRule.onNodeWithText(RECENT_NAME).assertExists()
        composeTestRule.onNodeWithText(relative).assertExists()
        composeTestRule.onNodeWithText(absolute(recentItem.deletedAt, DateTimeStyle.FULL))
            .assertDoesNotExist()
    }

    private fun renderRow(item: ExplorerItem.Trash.Root) {
        composeTestRule.setContent {
            PreviewWrapper {
                TrashItemRow(item = item)
            }
        }
    }

    companion object {
        private const val OLD_NAME = "long_gone.txt"
        private const val RECENT_NAME = "just_deleted.txt"
    }
}
