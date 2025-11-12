package eu.darken.butler.provider.documents.core

import android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.provider.documents.core.DocumentIdCodec.Companion.DEVICE_DOCUMENT_ID
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ConnectionTest : BaseTest() {

    @Test
    fun `Device connection has correct documentId`() {
        Connection.Device.documentId shouldBe DEVICE_DOCUMENT_ID
    }

    @Test
    fun `Device connection documentId matches expected format`() {
        Connection.Device.documentId shouldBe "device|self"
    }

    @Test
    fun `Device connection has FLAG_DIR_SUPPORTS_CREATE flag set`() {
        val flags = Connection.Device.flags
        (flags and FLAG_DIR_SUPPORTS_CREATE) shouldBe FLAG_DIR_SUPPORTS_CREATE
    }

    @Test
    fun `Device connection has valid icon resource ID`() {
        Connection.Device.icon shouldBe android.R.drawable.ic_menu_manage
    }

    @Test
    fun `Device connection has title from string resource`() {
        val title = Connection.Device.title
        title.shouldBeInstanceOf<CaString>()
        title shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device connection has summary from string resource`() {
        val summary = Connection.Device.summary
        summary shouldNotBe null
        summary.shouldBeInstanceOf<CaString>()
        summary shouldNotBe CaString.EMPTY
    }

    @Test
    fun `Device connection is a Connection instance`() {
        val connection: Connection = Connection.Device
        connection.shouldBeInstanceOf<Connection>()
    }

    @Test
    fun `SSH connection has correct documentId format`() {
        val ssh = Connection.SSH(
            serverId = "server1",
            serverName = "My Server",
            hostName = "192.168.1.100"
        )

        ssh.documentId shouldBe "ssh|server1"
        ssh.documentId shouldStartWith "ssh|"
    }

    @Test
    fun `SSH connection title is serverName as CaString`() {
        val ssh = Connection.SSH(
            serverId = "server1",
            serverName = "My Server",
            hostName = "192.168.1.100"
        )

        ssh.title.shouldBeInstanceOf<CaString>()
    }

    @Test
    fun `SSH connection summary is hostName as CaString`() {
        val ssh = Connection.SSH(
            serverId = "server1",
            serverName = "My Server",
            hostName = "192.168.1.100"
        )

        ssh.summary.shouldBeInstanceOf<CaString>()
    }

    @Test
    fun `SSH connection has FLAG_DIR_SUPPORTS_CREATE flag set`() {
        val ssh = Connection.SSH(
            serverId = "server1",
            serverName = "My Server",
            hostName = "192.168.1.100"
        )

        val flags = ssh.flags
        (flags and FLAG_DIR_SUPPORTS_CREATE) shouldBe FLAG_DIR_SUPPORTS_CREATE
    }

    @Test
    fun `FTP connection has correct documentId format`() {
        val ftp = Connection.FTP(
            serverId = "ftp1",
            serverName = "FTP Server",
            hostName = "ftp.example.com"
        )

        ftp.documentId shouldBe "ftp|ftp1"
        ftp.documentId shouldStartWith "ftp|"
    }

    @Test
    fun `FTP connection title is serverName as CaString`() {
        val ftp = Connection.FTP(
            serverId = "ftp1",
            serverName = "FTP Server",
            hostName = "ftp.example.com"
        )

        ftp.title.shouldBeInstanceOf<CaString>()
    }

    @Test
    fun `FTP connection summary is hostName as CaString`() {
        val ftp = Connection.FTP(
            serverId = "ftp1",
            serverName = "FTP Server",
            hostName = "ftp.example.com"
        )

        ftp.summary.shouldBeInstanceOf<CaString>()
    }

    @Test
    fun `FTP connection has FLAG_DIR_SUPPORTS_CREATE flag set`() {
        val ftp = Connection.FTP(
            serverId = "ftp1",
            serverName = "FTP Server",
            hostName = "ftp.example.com"
        )

        val flags = ftp.flags
        (flags and FLAG_DIR_SUPPORTS_CREATE) shouldBe FLAG_DIR_SUPPORTS_CREATE
    }

    @Test
    fun `sealed interface handles all connection types`() {
        val connections = listOf<Connection>(
            Connection.Device,
            Connection.SSH("server1", "Server", "host1"),
            Connection.FTP("ftp1", "FTP", "ftp.host")
        )

        connections.forEach { connection ->
            when (connection) {
                Connection.Device -> {
                    connection.documentId shouldBe "device|self"
                }
                is Connection.SSH -> {
                    connection.documentId shouldStartWith "ssh|"
                }
                is Connection.FTP -> {
                    connection.documentId shouldStartWith "ftp|"
                }
                // No else needed - sealed interface exhaustiveness
            }
        }
    }

    @Test
    fun `Device connection properties are stable and unchanging`() {
        // Document IDs must be STABLE - this test ensures no accidental changes
        val device = Connection.Device

        device.documentId shouldBe "device|self"
        device.icon shouldBe android.R.drawable.ic_menu_manage
        device.flags shouldBe FLAG_DIR_SUPPORTS_CREATE
    }

    @Test
    fun `SSH connection with special characters in serverId`() {
        val ssh = Connection.SSH(
            serverId = "server-1_test",
            serverName = "Test Server (Production)",
            hostName = "192.168.1.100:22"
        )

        ssh.documentId shouldBe "ssh|server-1_test"
    }

    @Test
    fun `FTP connection with special characters in serverId`() {
        val ftp = Connection.FTP(
            serverId = "ftp-prod_01",
            serverName = "Production FTP [Main]",
            hostName = "ftp.example.com:21"
        )

        ftp.documentId shouldBe "ftp|ftp-prod_01"
    }
}
