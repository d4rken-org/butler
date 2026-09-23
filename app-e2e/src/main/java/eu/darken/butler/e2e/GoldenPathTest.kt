package eu.darken.butler.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Drives the installed app the way a new user does. Selectors resolve the app's own string
 * resources, so they follow the device locale.
 */
@RunWith(AndroidJUnit4::class)
class GoldenPathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val appResources = instrumentation.context.packageManager.getResourcesForApplication(APP_PKG)

    // A failed step leaves the screen and its accessibility tree behind for diagnosis.
    @get:Rule
    val failureCapture = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
            device.takeScreenshot(File(dir, "${description.methodName}.png"))
            device.dumpWindowHierarchy(File(dir, "${description.methodName}.xml"))
        }
    }

    @Before
    fun resetToFirstRunState() {
        val result = device.executeShellCommand("pm clear $APP_PKG").trim()
        if (result != "Success") throw AssertionError("pm clear $APP_PKG failed: $result")
    }

    @Test
    fun newUserGetsFromOnboardingToTheFirstScreen() {
        launchApp()

        click(By.text(string("onboarding_welcome_action")))
        click(By.text(string("onboarding_workspaces_action")))
        click(By.text(string("onboarding_beta_action")))
        click(By.text(string("onboarding_privacy_action")))

        val tourTitle = By.text(string("tour_first_tab_title"))
        await(tourTitle)
        click(By.desc(string("general_done_action")))
        awaitGone(tourTitle)
        await(createTabAction())

        device.executeShellCommand("am force-stop $APP_PKG")
        awaitGone(By.pkg(APP_PKG))
        launchApp()
        // A returning tour has to fail as such, not as a missing "Create tab".
        await(anyText("tour_first_tab_title", "workspace_empty_create_action", "workspace_adaptive_add_action"))
        if (device.wait(Until.hasObject(tourTitle), TOUR_GRACE_MS)) {
            throw AssertionError("The first-tab tour came back after a relaunch")
        }
        await(createTabAction())
    }

    private fun launchApp() {
        val component = instrumentation.context.packageManager.getLaunchIntentForPackage(APP_PKG)?.component
            ?: throw AssertionError("$APP_PKG has no launcher activity")
        val result = device.executeShellCommand("am start -W -n ${component.flattenToShortString()}")
        if (result.contains("Error")) throw AssertionError("Launching $APP_PKG failed: $result")
    }

    private fun createTabAction(): BySelector =
        anyText("workspace_empty_create_action", "workspace_adaptive_add_action")

    private fun anyText(vararg names: String): BySelector =
        By.text(Pattern.compile(names.joinToString("|") { Pattern.quote(string(it)) }))

    private fun string(name: String): String {
        val id = appResources.getIdentifier(name, "string", APP_PKG)
        if (id == 0) throw AssertionError("$APP_PKG has no string resource '$name'")
        return appResources.getString(id)
    }

    private fun await(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), TIMEOUT_MS)
            ?: throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $selector")

    private fun awaitGone(selector: BySelector) {
        if (!device.wait(Until.gone(selector), TIMEOUT_MS)) {
            throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $selector to disappear")
        }
    }

    // Onboarding pages slide in, and a click at a moving node lands where it was a frame earlier.
    private fun click(selector: BySelector) {
        val node = await(selector)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var bounds = node.visibleBounds
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$selector never stopped moving")
            SystemClock.sleep(SETTLE_POLL_MS)
            val current = node.visibleBounds
            if (current == bounds && !current.isEmpty) break
            bounds = current
        }
        node.click()
    }

    companion object {
        private const val APP_PKG = "eu.darken.butler"
        private const val TIMEOUT_MS = 30_000L
        private const val SETTLE_POLL_MS = 150L
        private const val TOUR_GRACE_MS = 3_000L
    }
}
