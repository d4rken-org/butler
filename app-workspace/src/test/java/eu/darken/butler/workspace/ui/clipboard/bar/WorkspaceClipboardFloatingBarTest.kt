package eu.darken.butler.workspace.ui.clipboard.bar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.mockFileLookup
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * The wrapper concentrates the clipboard-bar packaging that used to be copied per workspace, so a
 * wrong default or an inverted conditional here regresses Explorer, Searcher and Editor at once.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspaceClipboardFloatingBarTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val emitted = mutableListOf<ClipboardBarAction>()
    private lateinit var stackState: FloatingBarStackState

    private val pasteLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_paste)
    private val openInExplorerLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_open_in_explorer)
    private val clearAllLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_clear_all)

    /** A single-path clip renders that path as its description, which is what addresses the row. */
    private fun clip(path: String, minutesAgo: Long = 1) = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(mockFileLookup(path)),
        clippedAt = Clock.System.now() - minutesAgo.minutes,
    )

    private fun setBar(
        workspaceType: Workspace.Type = Workspace.Type.EXPLORER,
        initialExpanded: Boolean = false,
        clips: () -> List<ClipboardClip>,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
                FloatingBarStack(
                    position = BarPosition.BOTTOM,
                    state = stackState,
                ) {
                    WorkspaceClipboardFloatingBar(
                        key = KEY,
                        workspaceType = workspaceType,
                        clipboardEntries = clips(),
                        initialExpanded = initialExpanded,
                        onAction = { emitted.add(it) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * An empty list composes no row at all, so its absence - not a hidden node - is the observable.
     */
    @Test
    fun `the bar appears only once there is a clip`() {
        var clips by mutableStateOf(emptyList<ClipboardClip>())
        setBar { clips }

        composeTestRule.onNodeWithText(FIRST_PATH).assertDoesNotExist()

        clips = listOf(clip(FIRST_PATH))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(FIRST_PATH).assertIsDisplayed()
    }

    @Test
    fun `the bar vanishes on scroll`() {
        setBar { listOf(clip(FIRST_PATH)) }

        composeTestRule.onNodeWithText(FIRST_PATH).assertIsDisplayed()

        runBlocking { stackState.applyCollapse(mapOf(KEY to 1f)) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(FIRST_PATH).assertIsNotDisplayed()
    }

    @Test
    fun `an expanded bar shows the older clips too`() {
        setBar(initialExpanded = true) {
            listOf(clip(FIRST_PATH, minutesAgo = 1), clip(SECOND_PATH, minutesAgo = 5))
        }

        composeTestRule.onNodeWithText(FIRST_PATH).assertIsDisplayed()
        composeTestRule.onNodeWithText(SECOND_PATH).assertIsDisplayed()
    }

    /**
     * A separate composition from the expanded case: `isExpanded` is seeded by an unkeyed `remember`
     * in `ClipboardBar`, so flipping the parameter in place deliberately does not update it.
     */
    @Test
    fun `a collapsed bar shows only the latest clip`() {
        setBar(initialExpanded = false) {
            listOf(clip(FIRST_PATH, minutesAgo = 1), clip(SECOND_PATH, minutesAgo = 5))
        }

        composeTestRule.onNodeWithText(FIRST_PATH).assertIsDisplayed()
        composeTestRule.onNodeWithText(SECOND_PATH).assertDoesNotExist()
    }

    /**
     * Clear-all runs collapsed: expanded it is deferred behind the cascading dismiss animation.
     */
    @Test
    fun `the paste button, the row and clear-all emit their actions`() {
        val clip = clip(FIRST_PATH)
        setBar { listOf(clip) }

        composeTestRule.onNodeWithContentDescription(pasteLabel).performClick()
        emitted shouldBe listOf(ClipboardBarAction.Paste(clip))

        emitted.clear()
        composeTestRule.onNodeWithText(FIRST_PATH).performClick()
        emitted shouldBe listOf(ClipboardBarAction.ShowInfo(clip))

        emitted.clear()
        composeTestRule.onNodeWithText(clearAllLabel).performClick()
        emitted shouldBe listOf(ClipboardBarAction.ClearAll)
    }

    /**
     * `workspaceType` is not cosmetic: `ClipboardEntryRow` picks the row's action label from it, and
     * the Searcher opens the clip's location instead of pasting it.
     */
    @Test
    fun `the workspace type selects the row's action label`() {
        val clip = clip(FIRST_PATH)
        composeTestRule.setContent {
            PreviewWrapper {
                FloatingBarStack(position = BarPosition.BOTTOM) {
                    WorkspaceClipboardFloatingBar(
                        key = "explorer-clipboard",
                        workspaceType = Workspace.Type.EXPLORER,
                        clipboardEntries = listOf(clip),
                        onAction = {},
                    )
                    WorkspaceClipboardFloatingBar(
                        key = "searcher-clipboard",
                        workspaceType = Workspace.Type.SEARCHER,
                        clipboardEntries = listOf(clip),
                        onAction = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithContentDescription(pasteLabel).assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription(openInExplorerLabel).assertCountEquals(1)
    }

    companion object {
        private const val KEY = "clipboard"
        private const val FIRST_PATH = "/storage/emulated/0/Documents/report.pdf"
        private const val SECOND_PATH = "/storage/emulated/0/Pictures/photo.jpg"
    }
}
