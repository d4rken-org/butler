package eu.darken.butler.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoldenPathTest {

    private val app = ButlerApp()

    @get:Rule
    val failureCapture = FailureCapture(app.device)

    @Before
    fun resetToFirstRunState() = app.resetToFirstRunState()

    @Test
    fun newUserGetsFromOnboardingToTheFirstScreen() {
        app.launch()
        app.completeOnboarding()
        app.finishTour("tour_first_tab_title")
        app.await(app.createTabAction())

        app.forceStop()
        app.launch()
        // A returning tour has to fail as such, not as a missing "Create tab".
        app.await(app.anyText("tour_first_tab_title", "workspace_empty_create_action", "workspace_adaptive_add_action"))
        if (app.device.wait(Until.hasObject(app.text("tour_first_tab_title")), TOUR_GRACE_MS)) {
            throw AssertionError("The first-tab tour came back after a relaunch")
        }
        app.await(app.createTabAction())
    }

    companion object {
        private const val TOUR_GRACE_MS = 3_000L
    }
}
