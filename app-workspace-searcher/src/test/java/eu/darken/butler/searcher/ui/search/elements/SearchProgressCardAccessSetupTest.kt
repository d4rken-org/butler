package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class SearchProgressCardAccessSetupTest : ComposeTest() {

    private fun progress(accessErrorCount: Int) = listOf(
        SearchEngine.SearchTargetProgress(
            target = SearchTarget.Path.from(LocalPath.build("/storage/emulated/0")),
            itemsScanned = 143,
            resultsFound = 0,
            status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
            accessErrorCount = accessErrorCount,
            accessErrorPaths = List(accessErrorCount) { LocalPath.build("/storage/emulated/0/Android/data") },
        ),
    )

    private fun setCard(
        targetProgress: List<SearchEngine.SearchTargetProgress>,
        onAccessErrorsClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchProgressCard(
                    targetProgress = targetProgress,
                    overallProgress = null,
                    searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
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
}
