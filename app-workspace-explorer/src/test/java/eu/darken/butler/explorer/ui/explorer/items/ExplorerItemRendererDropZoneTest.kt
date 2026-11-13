package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.dnd.DropZoneRegistry
import eu.darken.butler.workspace.ui.dnd.LocalDropZoneRegistry
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

class ExplorerItemRendererDropZoneTest : ComposeTest() {

    @Test
    fun `a share row listed at a server root stays a drop destination`() {
        val sharePath = SmbPath(LOCATION_ID, listOf(SHARE_NAME))
        val share = ExplorerItem.RegularDirectory(
            lookup = SmbPathLookup(
                lookedUp = sharePath,
                fileType = FileType.DIRECTORY,
                size = null,
                modifiedAt = null,
            ),
        )
        val serverRoot = ExplorerLocation.Directory(
            items = listOf(share),
            info = ExplorerLocation.Directory.Info(isWritable = false, entriesFixed = true),
            path = SmbPath(LOCATION_ID, emptyList()),
        )
        val registry = DropZoneRegistry()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalDropZoneRegistry provides registry) {
                    ExplorerItemRenderer(
                        item = share,
                        viewStyle = ExplorerViewStyle(),
                        state = MockDataProvider.createReadyState(location = serverRoot),
                        isFocused = false,
                        onItemClick = {},
                        onItemLongClick = {},
                        onItemMoreClick = {},
                        onNavigate = {},
                        onToggleSelection = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val rowCenter = composeTestRule.onNodeWithText(SHARE_NAME).fetchSemanticsNode().boundsInRoot.center
        registry.zoneAt(rowCenter)?.destination shouldBe sharePath
    }

    companion object {
        private const val SHARE_NAME = "media"
        private val LOCATION_ID = Uuid.parse("11111111-2222-3333-4444-555555555555")
    }
}
