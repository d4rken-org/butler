package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rendersAsPro
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Instant

/**
 * A window is recommended one pane here (412dp wide), so a pinned dual split is above the free tier
 * while the single-pane rail is not.
 *
 * Both sides of the gate read the same generous predicate the badge does, so the three states a
 * paying user can be in - settled Pro, still connecting, and a failed entitlement read - all keep
 * the pinned geometry. Only a settled "no purchase" collapses it.
 */
@Config(qualifiers = "w412dp-h915dp-port")
class WorkspaceDesignProGateTest : ComposeTest() {

    private fun info(
        isPro: Boolean,
        isSettled: Boolean = true,
        error: Throwable? = null,
    ) = object : UpgradeRepo.Info {
        override val type = UpgradeRepo.Type.GPLAY
        override val isPro = isPro
        override val isSettled = isSettled
        override val upgradedAt: Instant? = null
        override val error = error
    }

    private fun state(mode: WorkspacePanelMode) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(portraitPanelMode = mode, landscapePanelMode = mode),
        focusedWorkspace = null,
        selectedWorkspaces = emptyMap(),
        isUpgraded = false,
    )

    private fun design(mode: WorkspacePanelMode, info: UpgradeRepo.Info?): WorkspaceDesign {
        lateinit var captured: WorkspaceDesign
        composeTestRule.setContent {
            PreviewWrapper {
                captured = rememberWorkspaceDesign(state(mode), isPro = info.rendersAsPro())
            }
        }
        composeTestRule.waitForIdle()
        return captured
    }

    @Test
    fun `a pro user keeps the pinned dual split`() {
        design(WorkspacePanelMode.DUAL_HORIZONTAL, info(isPro = true)).layout shouldBe
            WorkspaceDesign.Layout.DUAL_HORIZONTAL
    }

    @Test
    fun `a free user falls back to what the window is recommended`() {
        design(WorkspacePanelMode.DUAL_HORIZONTAL, info(isPro = false)).layout shouldBe
            WorkspaceDesign.Layout.SINGLE
    }

    /**
     * Without this the downgrade would also take the rail, and the rail's Butler button is the only
     * route to the Layout dialog - the free user could never see the badged rows or reach the offer.
     */
    @Test
    fun `the downgraded window keeps its navigation rail`() {
        design(WorkspacePanelMode.DUAL_HORIZONTAL, info(isPro = false)).hasNavigationRail shouldBe true
    }

    @Test
    fun `an entitlement that has not settled yet keeps the pinned dual split`() {
        design(
            WorkspacePanelMode.DUAL_HORIZONTAL,
            info(isPro = false, isSettled = false),
        ).layout shouldBe WorkspaceDesign.Layout.DUAL_HORIZONTAL
    }

    @Test
    fun `a settled entitlement carrying a read error keeps the pinned dual split`() {
        design(
            WorkspacePanelMode.DUAL_HORIZONTAL,
            info(isPro = false, error = IllegalStateException("billing is down")),
        ).layout shouldBe WorkspaceDesign.Layout.DUAL_HORIZONTAL
    }

    @Test
    fun `a free user keeps a single-pane rail, which is what this window is recommended`() {
        val design = design(WorkspacePanelMode.SINGLE_RAIL, info(isPro = false))

        design.layout shouldBe WorkspaceDesign.Layout.SINGLE
        design.hasNavigationRail shouldBe true
    }

    @Test
    fun `the classic surface stays rail-less for a free user`() {
        val design = design(WorkspacePanelMode.SINGLE, info(isPro = false))

        design.layout shouldBe WorkspaceDesign.Layout.SINGLE
        design.hasNavigationRail shouldBe false
    }
}
