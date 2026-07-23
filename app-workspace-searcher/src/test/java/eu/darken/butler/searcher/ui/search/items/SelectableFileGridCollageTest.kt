package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.workspace.ui.preview.TEST_TAG_FOLDER_PREVIEW_COLLAGE
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test
import testhelpers.ComposeTest
import java.io.File
import kotlin.time.Instant

class SelectableFileGridCollageTest : ComposeTest() {

    @After
    fun resetDeferFlag() {
        Bugs.deferSearcherPreviews = false
    }

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

    @Test
    fun `deferral retains an already-visible collage when scrolling starts`() {
        Bugs.deferSearcherPreviews = true
        lateinit var settled: MutableState<Boolean>
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"), mediaLookup("b.jpg"))) }) {
                    settled = remember { mutableStateOf(true) }
                    Box(Modifier.size(200.dp)) {
                        SelectableFileGrid(
                            result = SearcherMockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            isSelectionMode = false,
                            onClick = {},
                            onLongPress = {},
                            previewsSettled = settled,
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertExists()

        // Scrolling starts -> previews un-settle. The already-loaded collage must NOT blank out.
        composeTestRule.runOnIdle { settled.value = false }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `deferral suppresses the preview query for a tile composed while scrolling`() {
        Bugs.deferSearcherPreviews = true
        var observerCalls = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ observerCalls++; flowOf(listOf(mediaLookup("a.jpg"))) }) {
                    val settled = remember { mutableStateOf(false) }
                    SelectableFileGrid(
                        result = SearcherMockDataProvider.createMockDirectory(name = "Photos"),
                        isSelected = false,
                        isSelectionMode = false,
                        onClick = {},
                        onLongPress = {},
                        previewsSettled = settled,
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        // Deferred from first composition: no observer query, folder-icon fallback instead of collage.
        assert(observerCalls == 0) { "observer should not be queried while deferred, was $observerCalls" }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TEST_TAG_SEARCHER_GRID_THUMBNAIL, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `deferral loads the collage once scrolling settles`() {
        Bugs.deferSearcherPreviews = true
        lateinit var settled: MutableState<Boolean>
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"), mediaLookup("b.jpg"))) }) {
                    settled = remember { mutableStateOf(false) }
                    Box(Modifier.size(200.dp)) {
                        SelectableFileGrid(
                            result = SearcherMockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            isSelectionMode = false,
                            onClick = {},
                            onLongPress = {},
                            previewsSettled = settled,
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.runOnIdle { settled.value = true }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertExists()
    }
}
