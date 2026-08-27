package eu.darken.butler.workspace.ui.manager.preview

import androidx.compose.runtime.Composable
import eu.darken.butler.common.compose.tour.GuidedTourAccess
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.compose.tour.NoOpGuidedTourAccess
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Tab thumbnails are composed off-screen, outside `MainActivity` and outside `PreviewWrapper`, so
 * this composition has to establish every local the real pages read. `LocalGuidedTourController`
 * has no default and throws when unprovided, and the capture service's catch-all turns that into a
 * silently null thumbnail — the Templates page reads it, so its previews would just stop appearing.
 *
 * Deliberately composed WITHOUT `PreviewWrapper`: that wrapper provides the very local under test
 * and would mask a regression here.
 */
class WorkspacePreviewCompositionLocalsTest : ComposeTest() {

    private object BlankPageHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private val pageHosts = mapOf<Workspace.Type, WorkspacePageHostEntry>(
        Workspace.Type.TEMPLATES to BlankPageHost,
    )

    private val workspaceTitles = mapOf(
        Workspace.Id() to "Downloads",
    )

    @Test
    fun `a captured page can read the tour controller, the page hosts and the focus flag`() {
        var tourAccess: GuidedTourAccess? = null
        var seenHosts: Map<Workspace.Type, WorkspacePageHostEntry>? = null
        var focused: Boolean? = null

        composeTestRule.setContent {
            WorkspacePreviewCompositionLocals(
                pageHosts = pageHosts,
                workspaceTitles = workspaceTitles,
            ) {
                tourAccess = LocalGuidedTourController.current
                seenHosts = LocalWorkspacePageHosts.current
                focused = LocalWorkspaceFocused.current
            }
        }
        composeTestRule.waitForIdle()

        // The no-op access is what keeps a thumbnail capture from starting a real tour behind the
        // user's back, on top of not throwing.
        tourAccess shouldBe NoOpGuidedTourAccess
        seenHosts shouldBe pageHosts
        focused shouldBe false
    }

    @Test
    fun `a captured page reads the live workspace titles, not an empty registry`() {
        var seenTitles: Map<Workspace.Id, String>? = null

        composeTestRule.setContent {
            WorkspacePreviewCompositionLocals(
                pageHosts = pageHosts,
                workspaceTitles = workspaceTitles,
            ) {
                seenTitles = LocalWorkspaceTitles.current
            }
        }
        composeTestRule.waitForIdle()

        // An unprovided (null) registry would omit a clipboard entry's origin, an empty one would
        // label every live workspace as closed.
        seenTitles shouldBe workspaceTitles
    }
}
