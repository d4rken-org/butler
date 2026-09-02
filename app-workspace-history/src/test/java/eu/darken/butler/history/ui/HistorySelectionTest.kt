package eu.darken.butler.history.ui

import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Which actions the bar offers is derived, so it can be checked without a device. */
class HistorySelectionTest : BaseTest() {

    private val completedAt = Clock.System.now()

    private fun entry(id: String) = HistoryEntry(
        id = id,
        kind = Operation.Metadata.Kind.COPY,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Copy",
        description = "Copying",
        summary = null,
        startedAt = completedAt - 1.seconds,
        completedAt = completedAt,
        duration = 1.seconds,
        outcome = HistoryOutcome.COMPLETED,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = 0,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = emptyList(),
    )

    private val visible = listOf(entry("a"), entry("b"), entry("c"))

    @Test
    fun `an empty selection offers no actions`() {
        historyActionsFor(selected = emptyList(), visible = visible) shouldContainExactly emptyList()
    }

    @Test
    fun `a partial selection can select the rest`() {
        val actions = historyActionsFor(selected = visible.take(1), visible = visible)

        val selectAll = actions.filterIsInstance<HistoryActionBarItem.SelectAll>().single()
        selectAll.ids shouldBe setOf("a", "b", "c")
    }

    @Test
    fun `a full selection drops the select-all action`() {
        val actions = historyActionsFor(selected = visible, visible = visible)

        actions.filterIsInstance<HistoryActionBarItem.SelectAll>() shouldContainExactly emptyList()
        actions shouldContainExactly listOf(
            HistoryActionBarItem.DeselectAll,
            HistoryActionBarItem.Share(visible),
            HistoryActionBarItem.Delete(visible),
        )
    }

    @Test
    fun `the delete action is marked destructive, the others are not`() {
        val actions = historyActionsFor(selected = visible.take(2), visible = visible)

        actions.single { it.isDestructive } shouldBe HistoryActionBarItem.Delete(visible.take(2))
    }
}
