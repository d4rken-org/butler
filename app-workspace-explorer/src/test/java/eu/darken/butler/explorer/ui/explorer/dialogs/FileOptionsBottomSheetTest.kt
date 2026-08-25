package eu.darken.butler.explorer.ui.explorer.dialogs

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
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The sheet anchors to the bottom of its pane, so the root has to be tall enough to hold every row -
 * a touch below the root lands on nothing while still reporting success.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h1600dp")
class FileOptionsBottomSheetTest : ComposeTest() {

    private val localFile = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents/notes.txt"),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private val networkFile = ExplorerItem.RegularFile(
        lookup = SmbPathLookup(
            lookedUp = SmbPath(Uuid.parse("11111111-2222-3333-4444-555555555555"), listOf("notes.txt")),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private var lastAction: ExplorerActionBarItem? = null

    /** Swapping this restages the sheet without a second `setContent` call. */
    private var currentItem by mutableStateOf<ExplorerItem.File>(localFile)

    private fun fileNamed(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = Clock.System.now(),
        ),
        mimeType = MimeInfo.fromFileName(name),
    )

    private fun setSheetContent(
        item: ExplorerItem.File,
        trashEnabled: Boolean = false,
        openActionsEnabled: Boolean = true,
    ) {
        currentItem = item
        lastAction = null
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    FileOptionsBottomSheet(
                        item = currentItem,
                        trashEnabled = trashEnabled,
                        onDismiss = {},
                        onAction = { lastAction = it },
                        openActionsEnabled = openActionsEnabled,
                    )
                }
            }
        }
    }

    private fun countOf(text: String): Int =
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `a local file can be shared and opened with another app`() {
        setSheetContent(localFile)

        countOf("Open with") shouldBe 1
        countOf("Share") shouldBe 1
    }

    /** Both hand a URI to another app, which a file on a server has none of. */
    @Test
    fun `a file on network storage offers neither sharing nor opening with another app`() {
        setSheetContent(networkFile)

        countOf("Open with") shouldBe 0
        countOf("Share") shouldBe 0
        // The actions that work on it are untouched
        countOf("Copy") shouldBe 1
    }

    @Test
    fun `a local file offers the trash while it is enabled`() {
        setSheetContent(localFile, trashEnabled = true)

        countOf("Move to Trash") shouldBe 1
        countOf("Delete") shouldBe 0
    }

    /** The trash only holds local files, so deleting a file on a server is permanent. */
    @Test
    fun `a file on network storage is deleted rather than trashed`() {
        setSheetContent(networkFile, trashEnabled = true)

        countOf("Move to Trash") shouldBe 0
        countOf("Delete") shouldBe 1
    }

    @Test
    fun `install is offered for every installable format`() {
        setSheetContent(fileNamed("app.apk"), trashEnabled = true)

        listOf("app.apk", "app.apks", "app.xapk", "app.apkm").forEach { name ->
            lastAction = null
            currentItem = fileNamed(name)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Install").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Install").performClick()

            lastAction.shouldBeInstanceOf<ExplorerActionBarItem.File.Install>()
        }
    }

    @Test
    fun `install sits above open`() {
        setSheetContent(fileNamed("app.apk"), trashEnabled = true)

        val install = composeTestRule.onNodeWithText("Install").performScrollTo().getUnclippedBoundsInRoot()
        val open = composeTestRule.onNodeWithText("Open").performScrollTo().getUnclippedBoundsInRoot()

        (install.top.value < open.top.value) shouldBe true
    }

    @Test
    fun `install sits above extract for a bundle`() {
        setSheetContent("app.xapk")

        val install = composeTestRule.onNodeWithText("Install").performScrollTo().getUnclippedBoundsInRoot()
        val extract = composeTestRule.onNodeWithText("Extract").performScrollTo().getUnclippedBoundsInRoot()

        (install.top.value < extract.top.value) shouldBe true
    }

    @Test
    fun `install is not offered for other files`() {
        setSheetContent(fileNamed("notes.txt"), trashEnabled = true)

        countOf("Install") shouldBe 0
    }

    @Test
    fun `install is suppressed inside a picker`() {
        setSheetContent(fileNamed("app.apk"), trashEnabled = true, openActionsEnabled = false)

        countOf("Install") shouldBe 0
    }
}
