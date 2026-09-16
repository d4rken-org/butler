package eu.darken.butler.templates.ui

import androidx.compose.runtime.CompositionLocalProvider
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.GuidedTourAccess
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourSession
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.templates.ui.tour.TemplatesTour
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The picker's tour start effect is the only thing that hands the controller a definition built by
 * the current composition. [eu.darken.butler.common.compose.tour.GuidedTourController.tryStart]
 * adopts such a refreshed definition into a session that is already live, so the effect has to keep
 * calling it while its own tour runs - a rebuilt page whose call is suppressed leaves the session
 * holding prepare hooks that scroll a disposed LazyListState.
 */
@Config(qualifiers = "w720dp-h1600dp")
class TemplatesWorkspacePageHostTourTest : ComposeTest() {

    private val workspaceId = Workspace.Id()

    private val explorerTemplate = object : WorkspaceTemplate {
        override val type: Workspace.Type = Workspace.Type.EXPLORER
        override val icon = Workspace.Type.EXPLORER.icon
        override val title: CaString = "Explorer".toCaString()
        override val subtitle: CaString = "Browse files".toCaString()
        override val arguments: Workspace.Arguments = Workspace.Type.EXPLORER.defaultArguments!!
        override val sortOrder: Int = 0
    }

    /** Records every definition handed to the controller, without a real controller's persistence. */
    private class RecordingTourAccess : GuidedTourAccess {
        private val _session = MutableStateFlow<TourSession?>(null)
        override val session: StateFlow<TourSession?> = _session
        val startedDefinitions = mutableListOf<TourDefinition>()

        /** Puts a tour on screen, as a start from an earlier composition of this page would. */
        fun holdSession(definition: TourDefinition) {
            _session.value = TourSession(definition, stepIndex = 0)
        }

        override suspend fun shouldStart(definition: TourDefinition): Boolean = true

        override suspend fun start(definition: TourDefinition) {
            tryStart(definition)
        }

        // Mirrors the real controller, which adopts a refreshed definition into the live session and
        // reports false - it did not publish one.
        override suspend fun tryStart(definition: TourDefinition): Boolean {
            startedDefinitions += definition
            val live = _session.value
            if (live != null) {
                _session.value = live.copy(definition = definition)
                return false
            }
            _session.value = TourSession(definition, stepIndex = 0)
            return true
        }

        override suspend fun skipForNow() {
            _session.value = null
        }
    }

    // The real ViewModel's state flow reads BuildConfigWrap, whose initializer needs the app
    // module's generated BuildConfig class and throws in a library module's unit tests. A mock
    // keeps the page's own gating - which is what is under test - reachable.
    private fun mockVm() = mockk<TemplatesWorkspaceViewModel>(relaxed = true).apply {
        every { state } returns MutableStateFlow(
            TemplatesWorkspaceViewModel.State(
                id = workspaceId,
                isUpgraded = false,
                templates = listOf(explorerTemplate),
                versionDescription = "1.0.0-test",
            ),
        )
    }

    private fun setHost(tourAccess: RecordingTourAccess) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalGuidedTourController provides tourAccess,
                    LocalTourTargetRegistry provides TourTargetRegistry(),
                    LocalLayerActive provides true,
                ) {
                    TemplatesWorkspacePageHost(
                        id = workspaceId,
                        // Multi-pane composes no Butler button, whose mascot is an infinite Lottie
                        // loop that floods Robolectric's ShadowTrace per frame.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        vm = mockVm(),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        // The page renders nothing until the state flow has reached composition, and the tour effect
        // is keyed on that. The clock is parked, so each recomposition needs its own frame.
        repeat(20) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    /** Control for the case below: proves this harness composes an effect that does reach tryStart. */
    @Test
    fun `the picker starts its tour when nothing holds the controller`() {
        val tourAccess = RecordingTourAccess()

        setHost(tourAccess)

        tourAccess.startedDefinitions.map { it.id } shouldBe listOf(TemplatesTour.id)
    }

    @Test
    fun `the picker hands its tour on even while that tour is the live session`() {
        val tourAccess = RecordingTourAccess()
        // The state an activity recreation or a pane move lands in: this page's own tour is running,
        // against prepare hooks that belong to a composition that no longer exists.
        tourAccess.holdSession(
            TemplatesTour.definition(prepareFirstTemplate = {}, ownerKey = workspaceId.longTag),
        )

        setHost(tourAccess)

        tourAccess.startedDefinitions.map { it.id } shouldBe listOf(TemplatesTour.id)
    }
}
