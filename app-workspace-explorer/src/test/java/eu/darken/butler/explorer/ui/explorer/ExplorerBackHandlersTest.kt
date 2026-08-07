package eu.darken.butler.explorer.ui.explorer

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ExplorerBackHandlersTest : ComposeTest() {

    private class Outcome {
        var goBack = false
        var cancelPicker = false
        var clearSelection = false
        var outerBack = false
    }

    private fun pressBack(
        hasPickerConfig: Boolean = false,
        useBackButtonForNavigation: Boolean = true,
        canGoBack: Boolean = false,
        isSelectionMode: Boolean = false,
    ): Outcome {
        val outcome = Outcome()
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                // Composed first, so it only fires if the subject didn't consume the press.
                BackHandler(enabled = true) { outcome.outerBack = true }
                ExplorerBackHandlers(
                    hasPickerConfig = hasPickerConfig,
                    useBackButtonForNavigation = useBackButtonForNavigation,
                    canGoBack = canGoBack,
                    isSelectionMode = isSelectionMode,
                    onGoBack = { outcome.goBack = true },
                    onCancelPicker = { outcome.cancelPicker = true },
                    onClearSelection = { outcome.clearSelection = true },
                )
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        return outcome
    }

    @Test
    fun `top level without history does not consume back`() {
        val outcome = pressBack(canGoBack = false)

        outcome.outerBack shouldBe true
        outcome.goBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.clearSelection shouldBe false
    }

    @Test
    fun `with history the back press navigates`() {
        val outcome = pressBack(canGoBack = true)

        outcome.goBack shouldBe true
        outcome.outerBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.clearSelection shouldBe false
    }

    @Test
    fun `setting disabled does not consume back even with history`() {
        val outcome = pressBack(useBackButtonForNavigation = false, canGoBack = true)

        outcome.outerBack shouldBe true
        outcome.goBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.clearSelection shouldBe false
    }

    @Test
    fun `picker at its root cancels regardless of the setting`() {
        val outcome = pressBack(
            hasPickerConfig = true,
            useBackButtonForNavigation = false,
            canGoBack = false,
        )

        outcome.cancelPicker shouldBe true
        outcome.goBack shouldBe false
        outcome.clearSelection shouldBe false
        outcome.outerBack shouldBe false
    }

    @Test
    fun `picker with history navigates regardless of the setting`() {
        val outcome = pressBack(
            hasPickerConfig = true,
            useBackButtonForNavigation = false,
            canGoBack = true,
        )

        outcome.goBack shouldBe true
        outcome.cancelPicker shouldBe false
        outcome.clearSelection shouldBe false
        outcome.outerBack shouldBe false
    }

    @Test
    fun `selection outranks history navigation`() {
        val outcome = pressBack(isSelectionMode = true, canGoBack = true)

        outcome.clearSelection shouldBe true
        outcome.goBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.outerBack shouldBe false
    }

    @Test
    fun `selection outranks the picker`() {
        val outcome = pressBack(
            isSelectionMode = true,
            hasPickerConfig = true,
            canGoBack = true,
        )

        outcome.clearSelection shouldBe true
        outcome.goBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.outerBack shouldBe false
    }

    @Test
    fun `selection at top level clears instead of falling through`() {
        val outcome = pressBack(isSelectionMode = true, canGoBack = false)

        outcome.clearSelection shouldBe true
        outcome.goBack shouldBe false
        outcome.cancelPicker shouldBe false
        outcome.outerBack shouldBe false
    }
}
