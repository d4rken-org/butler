package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.uuid.Uuid

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
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

    private fun setSheetContent(item: ExplorerItem.File, trashEnabled: Boolean = false) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    FileOptionsBottomSheet(
                        item = item,
                        trashEnabled = trashEnabled,
                        onDismiss = {},
                        onAction = {},
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
}
