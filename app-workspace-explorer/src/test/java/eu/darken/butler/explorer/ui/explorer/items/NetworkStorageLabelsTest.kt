package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.smb.SmbEndpointState
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
        reachability: SmbEndpointState.Reachability,
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
        lastSeenAt: Instant? = null,
    ) = MockDataProvider.createMockStorageNetwork(
        status = status,
        endpoint = SmbEndpointState("192.168.1.50", reachability),
        lastSeenAt = lastSeenAt,
    )

    @Test
    fun `a reachable server is available`() {
        item(SmbEndpointState.Reachability.REACHABLE).statusLabel(context, now) shouldBe "Available"
    }

    @Test
    fun `a server that was never reached has no ago to state`() {
        item(SmbEndpointState.Reachability.UNREACHABLE).statusLabel(context, now) shouldBe "Unavailable"
    }

    @Test
    fun `an unreachable server says when it was last seen`() {
        val lastSeenAt = now - 3.hours
        val expected = "Unavailable (${formatRelativeTime(context, lastSeenAt, now)})"

        item(
            reachability = SmbEndpointState.Reachability.UNREACHABLE,
            lastSeenAt = lastSeenAt,
        ).statusLabel(context, now) shouldBe expected
    }

    /** A credential problem outranks reachability, so it wins over any "last seen" suffix. */
    @Test
    fun `a sign-in problem outranks the last seen suffix`() {
        item(
            reachability = SmbEndpointState.Reachability.UNREACHABLE,
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
            lastSeenAt = now - 3.hours,
        ).statusLabel(context, now) shouldBe "Sign-in required"
    }
}
