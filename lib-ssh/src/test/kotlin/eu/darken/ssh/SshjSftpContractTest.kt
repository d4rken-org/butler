package eu.darken.ssh

internal class SshjSftpContractTest : SftpContractTest() {
    override fun connector(config: SftpConfig): SftpConnector = SshjSftpConnector(config)
}

internal class SshjExtensionlessSftpContractTest : ExtensionlessSftpContractTest() {
    override fun connector(config: SftpConfig): SftpConnector = SshjSftpConnector(config)
}

internal class SshjSftpPerformanceTest : SftpPerformanceTest() {
    override val connectorName: String = "sshj"

    override fun connector(config: SftpConfig): SftpConnector = SshjSftpConnector(config)
}
