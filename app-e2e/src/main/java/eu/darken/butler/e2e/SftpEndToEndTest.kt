package eu.darken.butler.e2e

import android.os.Build
import android.os.SystemClock
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unlocks Pro in a FOSS build, adds the SFTP test server (tools/sftp-test-server.sh) and opens a
 * file on it. Runs through tools/sftp-e2e-test.sh, which starts the server. Instrumentation
 * arguments: `sftpHost` (default 10.0.2.2, `off` skips the test), `sftpPort` (default 2222),
 * `sftpUser` and `sftpPassword`.
 */
@RunWith(AndroidJUnit4::class)
class SftpEndToEndTest {

    private val app = ButlerApp()
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("sftpHost") ?: "10.0.2.2"
    private val port = args.getString("sftpPort") ?: "2222"
    private val user = args.getString("sftpUser") ?: "butler"
    private val password = args.getString("sftpPassword") ?: "butlerpass"

    @get:Rule
    val failureCapture = FailureCapture(app.device)

    @Before
    fun setup() {
        assumeTrue("SFTP disabled by sftpHost=off", host != "off")
        // Selecting a package for a domain needs Android 12.
        assumeTrue("Needs API 31+ to route the sponsor link", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        routeSponsorLinkToStub()
        app.resetToFirstRunState()
    }

    @Test
    fun proUserOpensAFileOnAnSftpServer() {
        app.launch()
        app.completeOnboarding()
        app.finishTour("tour_first_tab_title")

        app.click(app.text("upgrade_prompt_title"))
        app.click(app.text("upgrade_screen_sponsor_action"))
        app.await(By.text(SponsorPageStubActivity.TEXT))
        // Butler only unlocks after the sponsor page stayed in front for 5 seconds.
        SystemClock.sleep(SPONSOR_VISIT_MS)
        app.device.pressBack()
        // The upgrade screen closes itself once Pro is unlocked, and the upgrade entry goes away.
        app.await(app.createTabAction())
        app.awaitGone(app.text("upgrade_prompt_title"))

        app.click(app.createTabAction())
        app.finishTour("tour_templates_picker_title")
        app.click(app.text("explorer_title"))
        app.click(app.text("explorer_network_storage_label"))
        app.click(app.text("explorer_network_add_location_action"))
        app.click(app.text("explorer_network_protocol_sftp_label"))

        field("explorer_network_form_label_label").text = LOCATION_NAME
        field("explorer_network_form_host_label").text = host
        field("explorer_network_form_port_label").text = port
        field("explorer_network_form_username_label").text = user
        field("explorer_network_form_password_label").text = password
        app.click(app.text("explorer_network_form_test_and_save_action"))

        app.await(app.text("explorer_sftp_host_key_unknown_title"))
        app.await(By.text(HOST_KEY_FINGERPRINT))
        app.click(app.text("explorer_sftp_host_key_accept_action"))
        app.awaitGone(app.text("explorer_network_form_test_and_save_action"))

        app.click(By.text(LOCATION_NAME))
        app.click(By.text("upload"))
        app.click(By.text("hello.txt"))
        app.click(app.text("explorer_file_action_open_subtitle"))
        app.await(By.textContains("Hello from Butler"))
    }

    private fun routeSponsorLinkToStub() {
        val pkg = InstrumentationRegistry.getInstrumentation().context.packageName
        val result = app.device
            .executeShellCommand("pm set-app-links-user-selection --user 0 --package $pkg true $SPONSOR_DOMAIN")
            .trim()
        if (result.isNotEmpty()) throw AssertionError("Selecting $pkg for $SPONSOR_DOMAIN failed: $result")
    }

    private fun field(labelName: String) = app.await(field(app.text(labelName)))

    private fun field(label: BySelector): BySelector = By.clazz(EditText::class.java).hasDescendant(label)

    companion object {
        private const val SPONSOR_DOMAIN = "github.com"
        private const val SPONSOR_VISIT_MS = 6_000L
        private const val LOCATION_NAME = "Butler e2e SFTP"
        // tools/sftp-test-server.sh's fixed ed25519 host key.
        private const val HOST_KEY_FINGERPRINT = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM"
    }
}
