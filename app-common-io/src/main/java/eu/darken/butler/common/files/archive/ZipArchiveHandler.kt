package eu.darken.butler.common.files.archive

internal class ZipArchiveHandler {

    companion object {
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.RANDOM,
            sequentialExtractionFallback = true,
            readEncryption = setOf(ArchiveCapabilities.EncryptionScope.ENTRY_DATA),
            wrongPasswordDistinguishable = true,
            writable = true,
            writeEncryption = true,
        )
    }
}
