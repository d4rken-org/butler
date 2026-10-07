package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.network.NetworkEndpointState
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkStorageLabelsTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Fixed, so the wording does not depend on when the test runs. */
    private val now = Instant.parse("2026-08-25T12:00:00Z")

    private fun item(
        reachability: NetworkEndpointState.Reachability,
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
        lastSeenAt: Instant? = null,
    ) = MockDataProvider.createMockStorageNetwork(
        status = status,
        endpoint = NetworkEndpointState("192.168.1.50", reachability),
        lastSeenAt = lastSeenAt,
    )

    @Test
    fun `a reachable server is available`() {
        item(NetworkEndpointState.Reachability.REACHABLE).statusLabel(context, now) shouldBe "Available"
    }

    @Test
    fun `a server that was never reached has no ago to state`() {
        item(NetworkEndpointState.Reachability.UNREACHABLE).statusLabel(context, now) shouldBe "Unavailable"
    }

    @Test
    fun `an unreachable server says when it was last seen`() {
        val lastSeenAt = now - 3.hours
        val expected = "Unavailable (${formatRelativeTime(context, lastSeenAt, now)})"

        item(
            reachability = NetworkEndpointState.Reachability.UNREACHABLE,
            lastSeenAt = lastSeenAt,
        ).statusLabel(context, now) shouldBe expected
    }

    @Test
    fun `an SFTP server reads like an SMB share`() {
        val lastSeenAt = now - 3.hours
        MockDataProvider.createMockStorageSftp(
            endpoint = NetworkEndpointState("192.168.1.20", NetworkEndpointState.Reachability.REACHABLE),
        ).statusLabel(context, now) shouldBe "Available"
        MockDataProvider.createMockStorageSftp(
            endpoint = NetworkEndpointState("192.168.1.20", NetworkEndpointState.Reachability.UNREACHABLE),
            lastSeenAt = lastSeenAt,
        ).statusLabel(context, now) shouldBe "Unavailable (${formatRelativeTime(context, lastSeenAt, now)})"
        MockDataProvider.createMockStorageSftp(
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        ).statusLabel(context, now) shouldBe "Sign-in required"
    }

    private fun sftpBasePath(basePath: String) =
        (MockDataProvider.createMockStorageSftp(basePath = basePath).location as NetworkLocation.Sftp).location

    @Test
    fun `an empty SFTP base path is the server's initial folder`() {
        sftpBasePath("").basePathLabel(context) shouldBe "The server's initial folder"
    }

    @Test
    fun `an absolute SFTP base path is shown as-is`() {
        sftpBasePath("/srv/media").basePathLabel(context) shouldBe "/srv/media"
    }

    @Test
    fun `a relative SFTP base path is marked as relative to the initial folder`() {
        sftpBasePath("media/photos").basePathLabel(context) shouldBe
            "media/photos (inside the server's initial folder)"
    }

    /** A credential problem outranks reachability, so it wins over any "last seen" suffix. */
    @Test
    fun `a sign-in problem outranks the last seen suffix`() {
        item(
            reachability = NetworkEndpointState.Reachability.UNREACHABLE,
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
            lastSeenAt = now - 3.hours,
        ).statusLabel(context, now) shouldBe "Sign-in required"
    }
}
