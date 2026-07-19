package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.workspace.ui.preview.TEST_TAG_FOLDER_PREVIEW_COLLAGE
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest
import java.io.File
import kotlin.time.Instant

class SelectableFileGridCollageTest : ComposeTest() {

    private fun mediaLookup(name: String) = LocalPathLookup(
        lookedUp = LocalPath.build(File("/tmp/collage-test/$name")),
        fileType = FileType.FILE,
        size = 42L,
        modifiedAt = Instant.DISTANT_PAST,
    )

    @Test
    fun `directory with media children renders collage`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"), mediaLookup("b.jpg"))) }) {
                    Box(Modifier.size(200.dp)) {
                        SelectableFileGrid(
                            result = SearcherMockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            isSelectionMode = false,
                            onClick = {},
                            onLongPress = {},
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `directory without media children falls back to plain thumbnail`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(emptyList()) }) {
                    SelectableFileGrid(
                        result = SearcherMockDataProvider.createMockDirectory(name = "Docs"),
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = {},
                        onLongPress = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TEST_TAG_SEARCHER_GRID_THUMBNAIL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `file result never renders a collage even with observer present`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"))) }) {
                    SelectableFileGrid(
                        result = SearcherMockDataProvider.createMockTextFile(name = "readme.txt"),
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = {},
                        onLongPress = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `without provided observer directories render the plain thumbnail`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SelectableFileGrid(
                    result = SearcherMockDataProvider.createMockDirectory(name = "Photos"),
                    isSelected = false,
                    isSelectionMode = false,
                    onClick = {},
                    onLongPress = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TEST_TAG_SEARCHER_GRID_THUMBNAIL, useUnmergedTree = true).assertExists()
    }
}
