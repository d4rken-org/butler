package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.uuid.Uuid

/**
 * The sheet anchors to the bottom of its pane, so the root has to be tall enough to hold every row -
 * a touch below the root lands on nothing while still reporting success.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h1600dp")
class SearchResultItemDetailsTest : ComposeTest() {

    private fun localResult(name: String, fileType: FileType = FileType.FILE): SearchItem =
        SearchItem.fromLookup(
            lookup = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
                fileType = fileType,
                size = 1024L,
                modifiedAt = null,
            ),
            matchedQuery = name,
        )

    private val networkResult: SearchItem = SearchItem.fromLookup(
        lookup = SmbPathLookup(
            lookedUp = SmbPath(Uuid.parse("11111111-2222-3333-4444-555555555555"), listOf("notes.txt")),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        ),
        matchedQuery = "notes",
    )

    private val safResult: SearchItem = SearchItem.fromLookup(
        lookup = SAFPathLookup(
            lookedUp = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3A",
                "Download",
                "notes.txt",
            ),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        ),
        matchedQuery = "notes",
    )

    private var lastAction: SearcherActionBarItem? = null

    /** Swapping this restages the sheet without a second `setContent` call. */
    private var currentResult by mutableStateOf<SearchItem>(localResult("notes.txt"))

    private fun setSheetContent(result: SearchItem) {
        currentResult = result
        lastAction = null
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SearchResultItemDetails(
                        result = currentResult,
                        trashEnabled = false,
                        onAction = { lastAction = it },
                        onLongPress = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun countOf(text: String): Int =
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `install is offered for every installable format`() {
        setSheetContent(localResult("app.apk"))

        listOf("app.apk", "app.apks", "app.xapk", "app.apkm").forEach { name ->
            lastAction = null
            currentResult = localResult(name)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Install").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Install").performClick()

            lastAction.shouldBeInstanceOf<SearcherActionBarItem.Install>()
        }
    }

    @Test
    fun `install is not offered for other files`() {
        setSheetContent(localResult("notes.txt"))

        countOf("Install") shouldBe 0
    }

    /** A folder named like a package is still a folder, and inspecting it would only throw. */
    @Test
    fun `install is not offered for a folder that is named like a package`() {
        setSheetContent(localResult("app.apk", fileType = FileType.DIRECTORY))

        countOf("Install") shouldBe 0
    }

    @Test
    fun `install sits above open`() {
        setSheetContent(localResult("app.apk"))

        val install = composeTestRule.onNodeWithText("Install").performScrollTo().getUnclippedBoundsInRoot()
        val open = composeTestRule.onNodeWithText("Open").performScrollTo().getUnclippedBoundsInRoot()

        (install.top.value < open.top.value) shouldBe true
    }

    @Test
    fun `a local file can be shared and opened with another app`() {
        setSheetContent(localResult("notes.txt"))

        countOf("Open with") shouldBe 1
        countOf("Share") shouldBe 1
    }

    /** Both hand a URI to another app, which a file on a server has none of. */
    @Test
    fun `a file on network storage offers neither sharing nor opening with another app`() {
        setSheetContent(networkResult)

        countOf("Open with") shouldBe 0
        countOf("Share") shouldBe 0
        countOf("Copy") shouldBe 1
    }

    /** A SAF grant we were given cannot be passed on, so neither hand-off can work on one. */
    @Test
    fun `a file behind a storage grant offers neither sharing nor opening with another app`() {
        setSheetContent(safResult)

        countOf("Open with") shouldBe 0
        countOf("Share") shouldBe 0
        countOf("Copy") shouldBe 1
    }
}
