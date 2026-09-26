package eu.darken.butler.e2e

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
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
    private val appLabel = instrumentation.context.packageManager.let {
        it.getApplicationLabel(it.getApplicationInfo(PKG, 0)).toString()
    }

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
        repeat(MAX_TOUR_STEPS) {
            val isLast = click(controls) { it.contentDescription == done }
            if (isLast) {
                awaitGone(controls)
                return
            }
        }
        throw AssertionError("The '$titleName' tour did not reach Done within $MAX_TOUR_STEPS steps")
    }

    fun text(name: String): BySelector = By.text(string(name))

    fun anyText(vararg names: String): BySelector = By.text(alternatives(names))

    fun createTabAction(): BySelector = anyText("workspace_empty_create_action", "workspace_adaptive_add_action")

    fun await(selector: BySelector): UiObject2 =
        poll("$selector") { device.wait(Until.findObject(selector), POLL_MS) }

    fun awaitGone(selector: BySelector) {
        // An ANR dialog hides the app's window, so its nodes read as gone while it is up.
        poll("$selector to disappear") {
            device.wait(Until.gone(selector), POLL_MS).takeIf { it && !foreignAnrShowing() }
        }
    }

    fun click(selector: BySelector) = click(selector) {}

    /**
     * Clicks the node [selector] finds and returns what [read] took from it before the click. An ANR dialog
     * that appears while the node settles hides its window and leaves it stale, so that starts over.
     */
    private fun <T> click(selector: BySelector, read: (UiObject2) -> T): T {
        repeat(MAX_CLICK_ATTEMPTS) {
            try {
                val node = await(selector)
                val value = read(node)
                click(node)
                return value
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("$selector went stale $MAX_CLICK_ATTEMPTS times before it could be clicked")
    }

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

    /**
     * Repeats [probe] until it returns a result. Time spent behind another app's "isn't responding" dialog,
     * on a slow emulator usually System UI's, does not count against [TIMEOUT_MS].
     */
    private fun <T : Any> poll(what: String, probe: () -> T?): T {
        var timeLeft = TIMEOUT_MS
        var hangLeft = MAX_FOREIGN_HANG_MS
        while (true) {
            val start = SystemClock.uptimeMillis()
            probe()?.let { return it }
            val anrShowing = foreignAnrShowing()
            val elapsed = SystemClock.uptimeMillis() - start
            if (anrShowing) {
                hangLeft -= elapsed
                if (hangLeft < 0) {
                    throw AssertionError(
                        "Another app stayed unresponsive for ${MAX_FOREIGN_HANG_MS}ms while waiting for $what",
                    )
                }
            } else {
                timeLeft -= elapsed
                if (timeLeft < 0) throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $what")
            }
        }
    }

    // A dialog that closes mid-read reads as still showing, so the next poll looks again.
    private fun foreignAnrShowing(): Boolean {
        if (!device.hasObject(ANR_WAIT)) return false
        val title = try {
            device.findObject(ANR_TITLE)?.text.orEmpty()
        } catch (_: StaleObjectException) {
            return true
        }
        if (title.contains(appLabel)) throw AssertionError("ANR dialog: $title")
        return true
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
        private const val POLL_MS = 1_000L
        private val ANR_WAIT = By.res("android", "aerr_wait")
        private val ANR_TITLE = By.res("android", "alertTitle")
        private const val MAX_TOUR_STEPS = 10
        private const val MAX_CLICK_ATTEMPTS = 5
        private const val MAX_FOREIGN_HANG_MS = 120_000L
    }
}
