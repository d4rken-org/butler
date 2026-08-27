package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/** The bar renders the origin of both clip variants, so each gets the registry cases of its own. */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardEntryRowOriginTest : ComposeTest() {

    private val origin = Workspace.Id()

    private val pathsClip = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Download/a.txt"),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    private val textClip = ClipboardClip.Text(
        origin = origin,
        content = "Hello",
    )

    @Composable
    private fun Case(
        clip: ClipboardClip,
        titles: Map<Workspace.Id, String>?,
    ) {
        PreviewWrapper {
            CompositionLocalProvider(LocalWorkspaceTitles provides titles) {
                ClipboardEntryRow(
                    entry = clip,
                    workspaceType = Workspace.Type.EXPLORER,
                    onPasteClick = {},
                    onEntryClick = {},
                    showOrigin = true,
                )
            }
        }
    }

    @Test
    fun `a paths entry names its origin`() {
        composeTestRule.setContent {
            Case(clip = pathsClip, titles = mapOf(origin to "Holiday photos"))
        }

        composeTestRule.onNodeWithText("Origin: Holiday photos").assertIsDisplayed()
    }

    @Test
    fun `a text entry names its origin`() {
        composeTestRule.setContent {
            Case(clip = textClip, titles = mapOf(origin to "Holiday photos"))
        }

        composeTestRule.onNodeWithText("Origin: Holiday photos").assertIsDisplayed()
    }

    @Test
    fun `a paths entry omits the origin line without a registry`() {
        composeTestRule.setContent {
            Case(clip = pathsClip, titles = null)
        }

        composeTestRule.onNodeWithText("Origin: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a text entry omits the origin line without a registry`() {
        composeTestRule.setContent {
            Case(clip = textClip, titles = null)
        }

        composeTestRule.onNodeWithText("Origin: ", substring = true).assertDoesNotExist()
    }
}
