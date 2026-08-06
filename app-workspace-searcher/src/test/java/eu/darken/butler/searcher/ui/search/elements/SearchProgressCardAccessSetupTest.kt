package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class SearchProgressCardAccessSetupTest : ComposeTest() {

    private fun progress(
        accessErrorCount: Int,
        errorCount: Int = 0,
    ) = listOf(
        SearchEngine.SearchTargetProgress(
            target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0")),
            itemsScanned = 143,
            resultsFound = 0,
            status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
            errorCount = errorCount,
            accessErrorCount = accessErrorCount,
            accessErrorPaths = List(accessErrorCount) { LocalPath.build("/storage/emulated/0/Android/data") },
        ),
    )

    private fun setCard(
        targetProgress: List<SearchEngine.SearchTargetProgress>,
        searchStatus: SearcherWorkspace.State.SearchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
        onAccessErrorsClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchProgressCard(
                    targetProgress = targetProgress,
                    overallProgress = null,
                    searchStatus = searchStatus,
                    resultCount = 0,
                    onAccessErrorsClick = onAccessErrorsClick,
                    onCancel = {},
                    onClear = {},
                    onErrorClick = { _, _ -> },
                    initiallyExpanded = true,
                )
            }
        }
    }

    @Test
    fun `inaccessible items line is shown and opens the detail sheet`() {
        var opened = false
        setCard(
            targetProgress = progress(accessErrorCount = 2),
            onAccessErrorsClick = { opened = true },
        )

        composeTestRule.onNodeWithText("2 items couldn't be accessed").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 items couldn't be accessed").performClick()
        opened shouldBe true
    }

    @Test
    fun `no access errors show no inaccessible items line`() {
        setCard(targetProgress = progress(accessErrorCount = 0))

        composeTestRule.onNodeWithText("couldn't be accessed", substring = true).assertDoesNotExist()
    }

    // The strip is one merged button node rather than a text label with a clickable ancestor —
    // that is what makes the whole row the target and what TalkBack announces as a button.
    @Test
    fun `the access errors row is a button node`() {
        setCard(targetProgress = progress(accessErrorCount = 2))

        composeTestRule
            .onNodeWithText("2 items couldn't be accessed")
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    // The reported defect was target size: the old line was a wrap-content row roughly 16dp tall,
    // so a near-miss hit the header's expand toggle instead. Clicking the node centre alone would
    // pass against that old layout too, hence the explicit height floor and the off-centre click.
    @Test
    fun `the access errors row is a full-height target beyond its text`() {
        var opened = false
        setCard(
            targetProgress = progress(accessErrorCount = 2),
            onAccessErrorsClick = { opened = true },
        )

        composeTestRule
            .onNodeWithText("2 items couldn't be accessed")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(centerRight) }
        opened shouldBe true
    }

    @Test
    fun `partial errors show the plain notice instead of the access row`() {
        setCard(targetProgress = progress(accessErrorCount = 0, errorCount = 3))

        composeTestRule.onNodeWithText("Some items couldn't be fully searched").assertIsDisplayed()
        composeTestRule.onNodeWithText("couldn't be accessed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a search in error state suppresses the access row`() {
        setCard(
            targetProgress = progress(accessErrorCount = 2),
            searchStatus = SearcherWorkspace.State.SearchStatus.ERROR,
        )

        composeTestRule.onNodeWithText("couldn't be accessed", substring = true).assertDoesNotExist()
    }

    // Asserted separately from the access row: with both counts set the access branch wins, so a
    // combined case would pass without the partial notice ever being suppressed on its own.
    @Test
    fun `a search in error state suppresses the partial notice`() {
        setCard(
            targetProgress = progress(accessErrorCount = 0, errorCount = 3),
            searchStatus = SearcherWorkspace.State.SearchStatus.ERROR,
        )

        composeTestRule.onNodeWithText("Some items couldn't be fully searched").assertDoesNotExist()
    }

    @Test
    fun `a failed location row still opens its error details`() {
        var errorPath: String? = null
        composeTestRule.setContent {
            PreviewWrapper {
                SearchProgressCard(
                    targetProgress = listOf(
                        SearchEngine.SearchTargetProgress(
                            target = SearchTarget.Path.from(LocalPath.build("/storage/usb")),
                            itemsScanned = 0,
                            resultsFound = 0,
                            status = SearchEngine.SearchTargetProgress.Status.ERROR,
                            exception = SecurityException("Permission denied"),
                        ),
                    ),
                    overallProgress = null,
                    searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
                    resultCount = 0,
                    onCancel = {},
                    onClear = {},
                    onErrorClick = { path, _ -> errorPath = path },
                    initiallyExpanded = true,
                )
            }
        }

        composeTestRule
            .onNodeWithText("/storage/usb")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        errorPath shouldBe "/storage/usb"
    }
}
