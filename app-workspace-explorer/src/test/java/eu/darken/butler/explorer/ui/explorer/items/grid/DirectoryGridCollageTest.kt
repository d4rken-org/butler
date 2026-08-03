package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import eu.darken.butler.workspace.ui.preview.TEST_TAG_FOLDER_PREVIEW_COLLAGE
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest
import java.io.File
import kotlin.time.Instant

/**
 * Folder tiles resolve their collage through a directory listing, so the query must stay suppressed
 * while the grid is flinging. Mirrors `SelectableFileGridCollageTest` in the Searcher module.
 */
class DirectoryGridCollageTest : ComposeTest() {

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
                        LookupItemGrid(
                            item = MockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = false,
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `without provided observer directories render no collage`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(Modifier.size(200.dp)) {
                    LookupItemGrid(
                        item = MockDataProvider.createMockDirectory(name = "Photos"),
                        isSelected = false,
                        onToggleSelection = {},
                        onClick = {},
                        showSelection = false,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `deferral suppresses the preview query for a tile composed while scrolling`() {
        var observerCalls = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ observerCalls++; flowOf(listOf(mediaLookup("a.jpg"))) }) {
                    val settled = remember { mutableStateOf(false) }
                    Box(Modifier.size(200.dp)) {
                        LookupItemGrid(
                            item = MockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = false,
                            previewsSettled = settled,
                        )
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        // The listing is the expensive part - it must not even be requested while deferred.
        composeTestRule.runOnIdle { observerCalls shouldBe 0 }
        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `deferral retains an already-visible collage when scrolling starts`() {
        lateinit var settled: MutableState<Boolean>
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"), mediaLookup("b.jpg"))) }) {
                    settled = remember { mutableStateOf(true) }
                    Box(Modifier.size(200.dp)) {
                        LookupItemGrid(
                            item = MockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = false,
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
    fun `deferral loads the collage once scrolling settles`() {
        lateinit var settled: MutableState<Boolean>
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"), mediaLookup("b.jpg"))) }) {
                    settled = remember { mutableStateOf(false) }
                    Box(Modifier.size(200.dp)) {
                        LookupItemGrid(
                            item = MockDataProvider.createMockDirectory(name = "Photos"),
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = false,
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

    @Test
    fun `file tiles never render a collage even with observer present`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(listOf(mediaLookup("a.jpg"))) }) {
                    Box(Modifier.size(200.dp)) {
                        LookupItemGrid(
                            item = MockDataProvider.createMockRegularFile("readme.txt"),
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = false,
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE, useUnmergedTree = true).assertDoesNotExist()
    }
}
