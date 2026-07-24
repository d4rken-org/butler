package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AccessErrorsSheetContentTest : ComposeTest() {

    private fun progress(
        path: String = "/storage/emulated/0",
        errorPaths: List<String> = listOf(
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb",
        ),
        accessErrorCount: Int = errorPaths.size,
    ) = SearchEngine.SearchTargetProgress(
        target = SearchTarget.Path.from(LocalPath.build(path)),
        itemsScanned = 143,
        resultsFound = 0,
        status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
        accessErrorCount = accessErrorCount,
        accessErrorPaths = errorPaths.map { LocalPath.build(it) },
    )

    private fun setSheet(
        targetProgress: List<SearchEngine.SearchTargetProgress>,
        requirements: PathRequirements,
        onUnlockAccess: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                AccessErrorsSheetContent(
                    targetProgress = targetProgress,
                    accessErrorRequirements = requirements,
                    onUnlockAccess = onUnlockAccess,
                )
            }
        }
    }

    @Test
    fun `viable fix shows unlockable body and action`() {
        var unlocked = false
        setSheet(
            targetProgress = listOf(progress()),
            requirements = PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT))),
            onUnlockAccess = { unlocked = true },
        )

        composeTestRule.onNodeWithText("2 items couldn't be accessed").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Android protects these locations. Additional access lets Butler search them.")
            .assertIsDisplayed()
        // Paths are shown in full — a file explorer must be exact about locations
        composeTestRule.onNodeWithText("/storage/emulated/0/Android/data").assertIsDisplayed()
        composeTestRule.onNodeWithText("/storage/emulated/0/Android/obb").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock access").performClick()
        unlocked shouldBe true
    }

    @Test
    fun `no viable fix shows protected body without action`() {
        setSheet(
            targetProgress = listOf(progress()),
            requirements = PathRequirements(),
        )

        composeTestRule
            .onNodeWithText("Android protects these locations. There is no way to grant access on this device.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock access").assertDoesNotExist()
    }

    @Test
    fun `multiple errored locations list full paths with a single truncation line`() {
        setSheet(
            targetProgress = listOf(
                progress(accessErrorCount = 5),
                progress(
                    path = "/storage/ABCD-1234",
                    errorPaths = listOf("/storage/ABCD-1234/Android/data"),
                ),
            ),
            requirements = PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT))),
        )

        composeTestRule.onNodeWithText("6 items couldn't be accessed").assertIsDisplayed()
        composeTestRule.onNodeWithText("/storage/emulated/0/Android/data").assertIsDisplayed()
        composeTestRule.onNodeWithText("/storage/ABCD-1234/Android/data").assertIsDisplayed()
        // First location reported 5 errors but retained only 2 paths
        composeTestRule.onNodeWithText("…and 3 more").assertIsDisplayed()
    }

    @Test
    fun `same path reported by overlapping targets is listed once`() {
        setSheet(
            targetProgress = listOf(
                progress(errorPaths = listOf("/storage/emulated/0/Android/data")),
                progress(
                    path = "/storage/emulated/0/Android",
                    errorPaths = listOf("/storage/emulated/0/Android/data"),
                ),
            ),
            requirements = PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT))),
        )

        composeTestRule.onAllNodesWithText("/storage/emulated/0/Android/data").assertCountEquals(1)
    }
}
