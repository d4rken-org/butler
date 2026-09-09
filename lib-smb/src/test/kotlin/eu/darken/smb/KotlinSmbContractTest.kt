package eu.darken.smb

internal class KotlinSmbContractTest : SmbContractTest() {
    override fun connector(config: SmbConfig): SmbConnector = KotlinSmbClient(config)
}
