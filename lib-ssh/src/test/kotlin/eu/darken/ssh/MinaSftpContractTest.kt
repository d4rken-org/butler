package eu.darken.ssh

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MinaSftpContractTest : SftpContractTest() {
    override fun connector(config: SftpConfig): SftpConnector = connectors[config]

    /** The preference is matched by wire name; a name MINA does not know would silently drop to its default order. */
    @Test
    fun `OpenSSH negotiates AES-GCM in both directions`(): Unit = runBlocking {
        connectors[SftpConfig()]
            .connect(
                OpenSshServer.shared.endpoint(),
                SshCredentials.Password(OpenSshServer.PASSWORD_USER, OpenSshServer.PASSWORD.toCharArray()),
                HostKeyPolicy.Pinned(TestKeys.hostEd25519),
            )
            .use { session ->
                val gcm = "aes128-gcm@openssh.com"
                assertEquals(gcm to gcm, (session as MinaSession).negotiatedCiphers)
            }
    }

    companion object {
        private val connectors = MinaConnectors()

        @JvmStatic
        @AfterAll
        fun closeConnectors() = connectors.close()
    }
}

internal class MinaExtensionlessSftpContractTest : ExtensionlessSftpContractTest() {
    override fun connector(config: SftpConfig): SftpConnector = connectors[config]

    companion object {
        private val connectors = MinaConnectors()

        @JvmStatic
        @AfterAll
        fun closeConnectors() = connectors.close()
    }
}

internal class MinaSftpPerformanceTest : SftpPerformanceTest() {
    override val connectorName: String = "mina"

    override fun connector(config: SftpConfig): SftpConnector = connectors[config]

    companion object {
        private val connectors = MinaConnectors()

        @JvmStatic
        @AfterAll
        fun closeConnectors() = connectors.close()
    }
}

/** One connector per config, as Butler holds them: a suite's sessions share one MINA client. */
internal class MinaConnectors : AutoCloseable {
    private val connectors = ConcurrentHashMap<SftpConfig, MinaSftpConnector>()

    operator fun get(config: SftpConfig): MinaSftpConnector =
        connectors.getOrPut(config) { MinaSftpConnector(config) }

    override fun close() {
        connectors.values.forEach { it.close() }
        connectors.clear()
    }
}
