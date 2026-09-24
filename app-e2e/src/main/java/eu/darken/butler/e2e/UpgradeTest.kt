package eu.darken.butler.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sets up a user's state in an older release and checks it after an in-place upgrade. The two
 * phases run as separate instrumentation runs with the app swapped in between.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeTest {

    private val app = ButlerApp()

    @get:Rule
    val failureCapture = FailureCapture(app.device)

    @Test
    fun beforeUpgrade() {
        app.resetToFirstRunState()
        app.launch()
        app.completeOnboarding()
        app.finishTour("tour_first_tab_title")
        app.click(app.createTabAction())
        app.finishTour("tour_templates_picker_title")
        app.click(app.text("explorer_title"))
        app.await(app.text("explorer_navigation_recent"))

        // Give the tab time to be saved, then prove this build kept it before the upgrade replaces it.
        SystemClock.sleep(SESSION_SAVE_SETTLE_MS)
        app.forceStop()
        app.launch()
        app.await(app.text("explorer_navigation_recent"))
    }

    @Test
    fun afterUpgrade() {
        app.launch()
        app.await(app.text("explorer_navigation_recent"))
    }

    companion object {
        private const val SESSION_SAVE_SETTLE_MS = 2_000L
    }
}
