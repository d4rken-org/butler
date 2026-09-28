package eu.darken.butler.workspace.ui.dialogs

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/** Keyboard focus of the delete dialog stacked above a pane's content layer, as a workspace pane composes it. */
@Config(qualifiers = "w400dp-h800dp")
class DeleteConfirmationDialogFocusTest : ComposeTest() {

    private val localFile = LocalPath.build("/storage/emulated/0/Download/local.txt")

    private val toggleLabel = "Delete permanently instead"
    private val cancelAction = "Cancel"
    private val moveAction = "Move"
    private val deleteAction = "Delete Permanently"

    private var baseFocusCount = 0
    private var dismissCount = 0
    private var confirmCount = 0

    private fun setHostedDialog(trashEnabled: Boolean) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.fillMaxSize(),
                        rank = PaneLayerRank.contentAt(0),
                        modal = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag(BASE_TAG)
                                .onFocusChanged { if (it.isFocused) baseFocusCount++ }
                                .focusable(),
                        )
                    }
                    CompositionLocalProvider(LocalPaneLayerRank provides PaneLayerRank.overlayAt(0)) {
                        DeleteConfirmationDialog(
                            items = setOf(localFile),
                            trashEnabled = trashEnabled,
                            onDismiss = { dismissCount++ },
                            onConfirm = { _, _ -> confirmCount++ },
                        )
                    }
                }
            }
        }
    }

    private fun focusedLabel(): String? {
        val focused = composeTestRule.onAllNodes(isFocused()).fetchSemanticsNodes()
        if (focused.isEmpty()) return null
        return focused.single().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    }

    private fun assertKeysStayInside(controls: List<String>, keyCodes: List<Pair<Int, Int>>) {
        val visited = mutableSetOf<String>()
        keyCodes.forEach { (keyCode, metaState) ->
            composeTestRule.pressDialogKey(keyCode, metaState)
            val label = focusedLabel()
            withClue("after ${NativeKeyEvent.keyCodeToString(keyCode)} meta=$metaState") {
                label shouldBeIn controls
            }
            visited += label!!
        }
        withClue("controls reached") { visited shouldContainExactlyInAnyOrder controls }
        baseFocusCount shouldBe 0
    }

    /** Walks [order] one [key] press at a time from its first entry, then presses once more past its end. */
    private fun assertFocusWalk(order: List<String>, key: Pair<Int, Int>) {
        val (keyCode, metaState) = key
        composeTestRule.onNodeWithText(order.first()).requestFocus()
        composeTestRule.onNodeWithText(order.first()).assertIsFocused()
        order.drop(1).forEach { expected ->
            composeTestRule.pressDialogKey(keyCode, metaState)
            withClue("next after ${NativeKeyEvent.keyCodeToString(keyCode)} meta=$metaState") {
                focusedLabel() shouldBe expected
            }
        }
        composeTestRule.pressDialogKey(keyCode, metaState)
        withClue("one press past the end") { focusedLabel() shouldBeIn order }
        baseFocusCount shouldBe 0
    }

    private val tab = NativeKeyEvent.KEYCODE_TAB to 0
    private val shiftTab = NativeKeyEvent.KEYCODE_TAB to NativeKeyEvent.META_SHIFT_ON

    private fun arrows(count: Int) = listOf(
        NativeKeyEvent.KEYCODE_DPAD_UP,
        NativeKeyEvent.KEYCODE_DPAD_LEFT,
        NativeKeyEvent.KEYCODE_DPAD_DOWN,
        NativeKeyEvent.KEYCODE_DPAD_RIGHT,
    ).flatMap { key -> List(count) { key to 0 } }

    @Test
    fun `Tab walks the dialog controls with the trash toggle and stays inside`() {
        setHostedDialog(trashEnabled = true)
        composeTestRule.onNodeWithText(moveAction).assertIsFocused()

        assertFocusWalk(listOf(toggleLabel, cancelAction, moveAction), tab)
    }

    @Test
    fun `Shift+Tab walks the dialog controls with the trash toggle and stays inside`() {
        setHostedDialog(trashEnabled = true)
        composeTestRule.onNodeWithText(moveAction).assertIsFocused()

        assertFocusWalk(listOf(moveAction, cancelAction, toggleLabel), shiftTab)
    }

    @Test
    fun `arrow keys keep focus among the dialog controls with the trash toggle`() {
        setHostedDialog(trashEnabled = true)
        composeTestRule.onNodeWithText(moveAction).assertIsFocused()

        assertKeysStayInside(listOf(toggleLabel, cancelAction, moveAction), arrows(3))
    }

    @Test
    fun `Tab walks the dialog controls without the trash toggle and stays inside`() {
        setHostedDialog(trashEnabled = false)
        composeTestRule.onNodeWithText(deleteAction).assertIsFocused()

        assertFocusWalk(listOf(cancelAction, deleteAction), tab)
    }

    @Test
    fun `Shift+Tab walks the dialog controls without the trash toggle and stays inside`() {
        setHostedDialog(trashEnabled = false)
        composeTestRule.onNodeWithText(deleteAction).assertIsFocused()

        assertFocusWalk(listOf(deleteAction, cancelAction), shiftTab)
    }

    @Test
    fun `arrow keys keep focus among the dialog controls without the trash toggle`() {
        setHostedDialog(trashEnabled = false)
        composeTestRule.onNodeWithText(deleteAction).assertIsFocused()

        assertKeysStayInside(listOf(cancelAction, deleteAction), arrows(2))
    }

    @Test
    fun `escape dismisses the dialog while the confirm button holds focus`() {
        setHostedDialog(trashEnabled = true)
        composeTestRule.onNodeWithText(moveAction).assertIsFocused()

        composeTestRule.pressDialogKey(NativeKeyEvent.KEYCODE_ESCAPE)

        withClue("dismiss count") { dismissCount shouldBe 1 }
        withClue("confirm count") { confirmCount shouldBe 0 }
    }

    companion object {
        private const val BASE_TAG = "pane.base"
    }
}
