package eu.darken.butler.workspace.ui.clipboard.details

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.tabLabel
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/**
 * The title registry is three-valued (see `LocalWorkspaceTitles`), so each of its three meanings
 * gets a case of its own here.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardInfoOverviewTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val origin = Workspace.Id()

    private fun lookup(path: String) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = null,
        modifiedAt = null,
    )

    private fun clip(vararg paths: String) = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = paths.map { lookup(it) },
    )

    /** Built the way `WorkspacesScreen` builds the map it provides. */
    private fun titlesOf(vararg infos: Workspace.Info) = infos.associate {
        it.id to it.tabLabel.get(context)
    }

    @Composable
    private fun Case(
        clip: ClipboardClip,
        titles: Map<Workspace.Id, String>?,
    ) {
        PreviewWrapper {
            CompositionLocalProvider(LocalWorkspaceTitles provides titles) {
                PaneLayerHost(
                    modifier = Modifier.fillMaxSize(),
                    paneFocused = true,
                ) {
                    ClipboardInfoBottomSheet(
                        clip = clip,
                        onDismiss = {},
                        onNavigateToSource = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the workspace cell names an unnamed tab by its type`() {
        composeTestRule.setContent {
            Case(
                clip = clip("/storage/emulated/0/Download/a.txt"),
                titles = titlesOf(
                    Workspace.Info(
                        id = origin,
                        type = Workspace.Type.EXPLORER,
                        title = "/storage/emulated/0/Pictures".toCaString(),
                    ),
                ),
            )
        }

        composeTestRule.onNodeWithText("Explorer").assertIsDisplayed()
        composeTestRule.onNodeWithText("/storage/emulated/0/Pictures").assertDoesNotExist()
    }

    @Test
    fun `the workspace cell shows a custom tab name`() {
        composeTestRule.setContent {
            Case(
                clip = clip("/storage/emulated/0/Download/a.txt"),
                titles = titlesOf(
                    Workspace.Info(
                        id = origin,
                        type = Workspace.Type.EXPLORER,
                        title = "Download".toCaString(),
                        customTitle = "Holiday photos",
                    ),
                ),
            )
        }

        composeTestRule.onNodeWithText("Holiday photos").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun `a registry without the origin means the workspace is gone`() {
        composeTestRule.setContent {
            Case(clip = clip("/storage/emulated/0/Download/a.txt"), titles = emptyMap())
        }

        composeTestRule.onNodeWithText("Closed").assertIsDisplayed()
    }

    @Test
    fun `without a registry the workspace cell is omitted and time keeps its half of the row`() {
        val titles = mutableStateOf<Map<Workspace.Id, String>?>(null)
        composeTestRule.setContent {
            Case(clip = clip("/storage/emulated/0/Download/a.txt"), titles = titles.value)
        }

        composeTestRule.onNodeWithText("Workspace").assertDoesNotExist()
        composeTestRule.onNodeWithText("Closed").assertDoesNotExist()
        val omitted = composeTestRule.onNodeWithText("Time").getUnclippedBoundsInRoot().left

        composeTestRule.runOnIdle { titles.value = mapOf(origin to "Download") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Workspace").assertExists()
        composeTestRule.onNodeWithText("Time").getUnclippedBoundsInRoot().left shouldBe omitted
    }

    @Test
    fun `one shared parent is a single source`() {
        composeTestRule.setContent {
            Case(
                clip = clip(
                    "/storage/emulated/0/Download/a.txt",
                    "/storage/emulated/0/Download/b.txt",
                ),
                titles = mapOf(origin to "Download"),
            )
        }

        composeTestRule.onNodeWithText("Source").assertExists()
        composeTestRule.onNodeWithText("Sources").assertDoesNotExist()
        composeTestRule.onNodeWithText("/storage/emulated/0/Download").assertExists()
    }

    @Test
    fun `two parents are listed as sources and the navigate action says first`() {
        composeTestRule.setContent {
            Case(
                clip = clip(
                    "/storage/emulated/0/Download/a.txt",
                    "/storage/emulated/0/Documents/b.txt",
                ),
                titles = mapOf(origin to "Download"),
            )
        }

        composeTestRule.onNodeWithText("Sources").assertExists()
        composeTestRule
            .onNodeWithText("/storage/emulated/0/Download\n/storage/emulated/0/Documents")
            .assertExists()
        composeTestRule.onNodeWithText("Go to first source").assertExists()
    }

    @Test
    fun `a path at the filesystem root contributes a slash of its own`() {
        composeTestRule.setContent {
            Case(
                clip = clip("/", "/storage/emulated/0/Download/a.txt"),
                titles = mapOf(origin to "Download"),
            )
        }

        composeTestRule.onNodeWithText("Sources").assertExists()
        composeTestRule.onNodeWithText("/\n/storage/emulated/0/Download").assertExists()
    }
}
