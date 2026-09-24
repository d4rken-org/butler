package eu.darken.butler.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Drives the installed app the way a user does. Selectors resolve the app's own string resources,
 * so they follow the device locale.
 */
class ButlerApp {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val resources = instrumentation.context.packageManager.getResourcesForApplication(PKG)

    fun resetToFirstRunState() {
        val result = device.executeShellCommand("pm clear $PKG").trim()
        if (result != "Success") throw AssertionError("pm clear $PKG failed: $result")
    }

    fun launch() {
        val component = instrumentation.context.packageManager.getLaunchIntentForPackage(PKG)?.component
            ?: throw AssertionError("$PKG has no launcher activity")
        val result = device.executeShellCommand("am start -W -n ${component.flattenToShortString()}")
        if (result.contains("Error")) throw AssertionError("Launching $PKG failed: $result")
    }

    fun forceStop() {
        device.executeShellCommand("am force-stop $PKG")
        awaitGone(By.pkg(PKG))
    }

    fun completeOnboarding() {
        click(text("onboarding_welcome_action"))
        click(text("onboarding_workspaces_action"))
        click(text("onboarding_beta_action"))
        click(text("onboarding_privacy_action"))
    }

    /** Waits for the tour titled [titleName] and steps through it until Done. */
    fun finishTour(titleName: String) {
        await(text(titleName))
        val done = string("general_done_action")
        val controls = anyDesc("tour_action_next", "general_done_action")
        while (true) {
            val node = await(controls)
            val isLast = node.contentDescription == done
            click(node)
            if (isLast) break
        }
        awaitGone(controls)
    }

    fun text(name: String): BySelector = By.text(string(name))

    fun anyText(vararg names: String): BySelector = By.text(alternatives(names))

    fun createTabAction(): BySelector = anyText("workspace_empty_create_action", "workspace_adaptive_add_action")

    fun await(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), TIMEOUT_MS)
            ?: throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $selector")

    fun awaitGone(selector: BySelector) {
        if (!device.wait(Until.gone(selector), TIMEOUT_MS)) {
            throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $selector to disappear")
        }
    }

    fun click(selector: BySelector) = click(await(selector))

    // Onboarding pages slide in, and a click at a moving node lands where it was a frame earlier.
    private fun click(node: UiObject2) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var bounds = node.visibleBounds
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$node never stopped moving")
            SystemClock.sleep(SETTLE_POLL_MS)
            val current = node.visibleBounds
            if (current == bounds && !current.isEmpty) break
            bounds = current
        }
        node.click()
    }

    private fun anyDesc(vararg names: String): BySelector = By.desc(alternatives(names))

    private fun alternatives(names: Array<out String>): Pattern =
        Pattern.compile(names.joinToString("|") { Pattern.quote(string(it)) })

    private fun string(name: String): String {
        val id = resources.getIdentifier(name, "string", PKG)
        if (id == 0) throw AssertionError("$PKG has no string resource '$name'")
        return resources.getString(id)
    }

    companion object {
        const val PKG = "eu.darken.butler"
        private const val TIMEOUT_MS = 30_000L
        private const val SETTLE_POLL_MS = 150L
    }
}
