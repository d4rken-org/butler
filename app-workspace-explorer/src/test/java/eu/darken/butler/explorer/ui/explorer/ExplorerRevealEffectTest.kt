package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import testhelpers.ComposeTest

class ExplorerRevealEffectTest : ComposeTest() {

    private val folder = "location://directory//storage/emulated/0/Download"

    private fun path(name: String) = LocalPath.build("/storage/emulated/0/Download/$name")

    private fun file(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(lookedUp = path(name), fileType = FileType.FILE, size = 1024L, modifiedAt = null),
        mimeType = MimeInfo("text/plain"),
    )

    @Test
    fun `a reveal after navigating scrolls the list that is on screen`() {
        val rows = (0 until 40).map { file("file_%02d.txt".format(it)) }
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(
            ExplorerWorkspaceViewModel.State(locationId = folder, listingLocationId = folder, items = rows),
        )
        val reveals = MutableSharedFlow<ExplorerWorkspaceViewModel.RevealRequest>(extraBufferCapacity = 1)
        val first = LazyListState()
        val second = LazyListState()
        var listState by mutableStateOf(first)
        val gridState = LazyGridState()

        composeTestRule.setContent {
            ExplorerRevealEffect(
                revealRequests = reveals,
                mainStateSource = states,
                listState = listState,
                gridState = gridState,
            )
            LazyColumn(state = listState, modifier = Modifier.height(400.dp)) {
                items(rows.size) { Box(Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { listState = second }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            reveals.tryEmit(ExplorerWorkspaceViewModel.RevealRequest(path = path("file_30.txt")))
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertTrue(
                "Reveal scrolled a stale LazyListState: on-screen list still starts at " +
                    "${second.firstVisibleItemIndex}, row 30 not visible",
                second.layoutInfo.visibleItemsInfo.any { it.index == 30 },
            )
        }
    }
}
