package eu.darken.butler.common.files.archive

data class ArchiveCapabilities(
    /** RANDOM: the handler seeks and throws ArchiveNotSeekableException on stream-only storage. SEQUENTIAL: reads front to back. */
    val access: Access,
    /** A random-access format that can still be extracted front to back from stream-only storage. */
    val sequentialExtractionFallback: Boolean,
    /** What a password can protect when reading: empty, ENTRY_DATA, HEADERS (entry list). */
    val readEncryption: Set<EncryptionScope>,
    /**
     * Reporting policy for a failed password attempt: true when the format has a password check (zip: the cipher
     * header verifier / AES MAC / ZipCrypto CRC surfaced by zip4j as WRONG_PASSWORD), so the failure is reported as a
     * wrong password; false when a wrong key is indistinguishable from damaged data (7z AES has no check value), so the
     * prompt carries the "or the archive is damaged" caveat. Not an integrity or verification guarantee.
     */
    val wrongPasswordDistinguishable: Boolean,
    val writable: Boolean,
    val writeEncryption: Boolean,
) {
    enum class Access { RANDOM, SEQUENTIAL }
    enum class EncryptionScope { ENTRY_DATA, HEADERS }
}
