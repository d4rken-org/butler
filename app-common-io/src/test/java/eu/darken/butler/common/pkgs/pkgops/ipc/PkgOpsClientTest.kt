package eu.darken.butler.common.pkgs.pkgops.ipc

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PkgOpsClientTest : BaseTest() {

    @Test
    fun `setComponentEnabledSetting delegates in order`() {
        val connection = mockk<PkgOpsConnection>(relaxed = true)
        val client = PkgOpsClient(connection)

        client.setComponentEnabledSetting(
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            newState = 2,
            flags = 1,
        )

        verify {
            connection.setComponentEnabledSetting("com.example.app", "com.example.app.MainActivity", 2, 1)
        }
    }

    @Test
    fun `setApplicationEnabledSetting stays a package level call`() {
        val connection = mockk<PkgOpsConnection>(relaxed = true)
        val client = PkgOpsClient(connection)

        client.setApplicationEnabledSetting(packageName = "com.example.app", newState = 2, flags = 1)

        verify { connection.setApplicationEnabledSetting("com.example.app", 2, 1) }
    }
}
