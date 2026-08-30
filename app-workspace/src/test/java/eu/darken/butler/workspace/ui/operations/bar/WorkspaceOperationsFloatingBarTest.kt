package eu.darken.butler.workspace.ui.operations.bar

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * The wrapper concentrates the operations-bar packaging that used to be copied per workspace, so a
 * wrong default or an inverted conditional here regresses Explorer, Searcher and Editor at once.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspaceOperationsFloatingBarTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val emitted = mutableListOf<OperationsBarAction>()
    private lateinit var stackState: FloatingBarStackState

    private val defaultStartedAt = Clock.System.now() - 5.minutes

    /**
     * A collapsed row renders a running operation's progress primary instead of its title
     * (`OperationEntryRow`), so both carry [title] and one finder works in either expansion state.
     */
    private fun operation(
        title: String,
        state: OperationDisplay.State = running(title),
        id: Operation.Id = Operation.Id(),
        startedAt: Instant = defaultStartedAt,
    ) = OperationDisplay(
        id = id,
        title = title.toCaString(),
        description = "$title details".toCaString(),
        icon = Icons.TwoTone.ContentCopy,
        state = state,
        canCancel = true,
        startedAt = startedAt,
    )

    private fun running(primary: String) = OperationDisplay.State.Running(
        primaryProgress = Progress.Data(
            primary = primary.toCaString(),
            secondary = "Working".toCaString(),
            count = Progress.Count.Percent(5, 10),
        ),
    )

    private fun completed() = OperationDisplay.State.Completed(
        summary = "Done".toCaString(),
        completedAt = Clock.System.now(),
        report = null,
    )

    private fun setBar(
        initialExpanded: Boolean = false,
        operations: () -> List<OperationDisplay>,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
                FloatingBarStack(
                    position = BarPosition.BOTTOM,
                    state = stackState,
                ) {
                    WorkspaceOperationsFloatingBar(
                        key = KEY,
                        operations = operations(),
                        initialExpanded = initialExpanded,
                        onAction = { emitted.add(it) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun collapseBar(fraction: Float) {
        runBlocking { stackState.applyCollapse(mapOf(KEY to fraction)) }
        composeTestRule.waitForIdle()
    }

    /**
     * An empty list composes no row at all, so its absence - not a hidden node - is the observable.
     */
    @Test
    fun `the bar appears only once there is an operation`() {
        var operations by mutableStateOf(emptyList<OperationDisplay>())
        setBar { operations }

        composeTestRule.onNodeWithText(FIRST_TITLE).assertDoesNotExist()

        operations = listOf(operation(FIRST_TITLE))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(FIRST_TITLE).assertIsDisplayed()
    }

    /**
     * `applyCollapse` skips [eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior.Static] bars
     * by construction, which is exactly the distinction the active-state classifier decides.
     */
    @Test
    fun `an active operation pins the bar against scroll while a terminal one lets it vanish`() {
        var state: OperationDisplay.State by mutableStateOf(running(FIRST_TITLE))
        setBar { listOf(operation(FIRST_TITLE, state)) }

        val cases = listOf(
            OperationDisplay.State.Queued to true,
            running(FIRST_TITLE) to true,
            OperationDisplay.State.Waiting("Name conflict".toCaString()) to true,
            completed() to false,
            OperationDisplay.State.Failed(
                summary = "Failed".toCaString(),
                completedAt = Clock.System.now(),
                report = null,
            ) to false,
            OperationDisplay.State.Cancelled(
                completedAt = Clock.System.now(),
                report = null,
            ) to false,
        )

        cases.forEach { (operationState, staysPinned) ->
            state = operationState
            composeTestRule.waitForIdle()
            collapseBar(1f)

            withClue(operationState.toString()) {
                if (staysPinned) {
                    composeTestRule.onNodeWithText(FIRST_TITLE).assertIsDisplayed()
                } else {
                    composeTestRule.onNodeWithText(FIRST_TITLE).assertIsNotDisplayed()
                }
            }

            collapseBar(0f)
        }
    }

    @Test
    fun `an expanded bar shows every operation`() {
        setBar(initialExpanded = true) {
            listOf(
                operation(FIRST_TITLE, completed(), startedAt = defaultStartedAt),
                operation(SECOND_TITLE, completed(), startedAt = defaultStartedAt + 1.minutes),
            )
        }

        composeTestRule.onNodeWithText(FIRST_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(SECOND_TITLE).assertIsDisplayed()
    }

    /**
     * A separate composition from the expanded case: `isExpanded` is seeded by an unkeyed `remember`
     * in `OperationsBar`, so flipping the parameter in place deliberately does not update it.
     *
     * With nothing active, the collapsed branch renders the most recently started operation only.
     */
    @Test
    fun `a collapsed bar shows only the most recently started operation`() {
        setBar(initialExpanded = false) {
            listOf(
                operation(FIRST_TITLE, completed(), startedAt = defaultStartedAt),
                operation(SECOND_TITLE, completed(), startedAt = defaultStartedAt + 1.minutes),
            )
        }

        composeTestRule.onNodeWithText(SECOND_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(FIRST_TITLE).assertDoesNotExist()
    }

    /** The waiting-to-conflict decision is the only branching logic either wrapper owns. */
    @Test
    fun `a waiting operation routes to the conflict sheet and any other to the details`() {
        val waitingId = Operation.Id()
        val completedId = Operation.Id()
        setBar(initialExpanded = true) {
            listOf(
                operation(FIRST_TITLE, OperationDisplay.State.Waiting("Name conflict".toCaString()), waitingId),
                operation(SECOND_TITLE, completed(), completedId),
            )
        }

        composeTestRule.onNodeWithText(FIRST_TITLE).performClick()
        emitted shouldBe listOf(OperationsBarAction.ShowConflict(waitingId))

        emitted.clear()
        composeTestRule.onNodeWithText(SECOND_TITLE).performClick()
        emitted shouldBe listOf(OperationsBarAction.ShowDetails(completedId))
    }

    /**
     * Both affordances are plain buttons, and clear-completed only forwards straight through while
     * the bar is collapsed - expanded it waits out the cascading dismiss animation first.
     */
    @Test
    fun `the cancel and clear-completed buttons emit their actions`() {
        val runningId = Operation.Id()
        setBar {
            listOf(
                operation(FIRST_TITLE, running(FIRST_TITLE), runningId),
                operation(SECOND_TITLE, completed()),
            )
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(WorkspaceR.string.operations_cancel_operation))
            .performClick()
        emitted shouldBe listOf(OperationsBarAction.RequestCancel(runningId))

        emitted.clear()
        composeTestRule
            .onNodeWithText(context.getString(WorkspaceR.string.operations_clear_completed))
            .performClick()
        emitted shouldBe listOf(OperationsBarAction.ClearCompleted)
    }

    companion object {
        private const val KEY = "operations"
        private const val FIRST_TITLE = "Copying files"
        private const val SECOND_TITLE = "Moving documents"
    }
}
