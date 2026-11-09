package eu.darken.butler.explorer.ui.settings.hidden

import android.content.Context
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.hidden.HiddenEntry
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import org.junit.Test
import testhelpers.ComposeTest

class ExplorerHiddenPathsScreenTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val entries = listOf(
        "/storage/emulated/0/Download/report.pdf",
        "/storage/emulated/0/Removed/folder",
    ).map { LocalPath.build(it) }.map { HiddenEntry(it.sortPathKey(), it) }

    private fun setScreen(state: ExplorerHiddenPathsViewModel.State) {
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerHiddenPathsScreen(
                    state = state,
                    onNavigateUp = {},
                    onRemove = {},
                    onOpenSearch = {},
                    onCloseSearch = {},
                    onQueryChange = {},
                    onUnhideAll = {},
                    onConfirmUnhideAll = {},
                    onDismissUnhideAll = {},
                )
            }
        }
    }

    @Test
    fun `an empty hide list offers neither search nor unhide all`() {
        setScreen(ExplorerHiddenPathsViewModel.State(entries = emptyList()))

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.explorer_hidden_paths_unhide_all_action))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.explorer_hidden_paths_search_action))
            .assertDoesNotExist()
    }

    @Test
    fun `the unhide all confirmation names the entry count`() {
        setScreen(ExplorerHiddenPathsViewModel.State(entries = entries, confirmUnhideCount = 2))

        val title = context.resources.getQuantityString(
            R.plurals.explorer_hidden_paths_unhide_all_confirm_title,
            2,
            2,
        )
        composeTestRule.onNodeWithText(title).assertExists()
    }
}
