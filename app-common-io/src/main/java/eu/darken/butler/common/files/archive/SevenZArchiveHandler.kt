package eu.darken.butler.common.files.archive

internal class SevenZArchiveHandler {

    companion object {
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.RANDOM,
            sequentialExtractionFallback = false,
            readEncryption = setOf(
                ArchiveCapabilities.EncryptionScope.ENTRY_DATA,
                ArchiveCapabilities.EncryptionScope.HEADERS,
            ),
            wrongPasswordDistinguishable = false,
            writable = false,
            writeEncryption = false,
        )
    }
}
