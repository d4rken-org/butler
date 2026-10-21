package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.archive.ArchiveCapabilities.Access
import eu.darken.butler.common.files.archive.ArchiveCapabilities.EncryptionScope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ArchiveCapabilitiesTest : BaseTest() {

    @Test
    fun `zip capabilities`() {
        ArchiveFormat.ZIP.capabilities shouldBe ArchiveCapabilities(
            access = Access.RANDOM,
            sequentialExtractionFallback = true,
            readEncryption = setOf(EncryptionScope.ENTRY_DATA),
            wrongPasswordDistinguishable = true,
            writable = true,
            writeEncryption = true,
        )
    }

    @Test
    fun `tar family capabilities`() {
        val expected = ArchiveCapabilities(
            access = Access.SEQUENTIAL,
            sequentialExtractionFallback = false,
            readEncryption = emptySet(),
            wrongPasswordDistinguishable = true,
            writable = true,
            writeEncryption = false,
        )
        ArchiveFormat.TAR.capabilities shouldBe expected
        ArchiveFormat.TAR_GZ.capabilities shouldBe expected
        ArchiveFormat.TAR_BZ2.capabilities shouldBe expected
    }

    @Test
    fun `7z capabilities`() {
        ArchiveFormat.SEVEN_Z.capabilities shouldBe ArchiveCapabilities(
            access = Access.RANDOM,
            sequentialExtractionFallback = false,
            readEncryption = setOf(EncryptionScope.ENTRY_DATA, EncryptionScope.HEADERS),
            wrongPasswordDistinguishable = false,
            writable = false,
            writeEncryption = false,
        )
    }

    @Test
    fun `rar capabilities`() {
        ArchiveFormat.RAR.capabilities shouldBe ArchiveCapabilities(
            access = Access.SEQUENTIAL,
            sequentialExtractionFallback = false,
            readEncryption = setOf(EncryptionScope.ENTRY_DATA, EncryptionScope.HEADERS),
            wrongPasswordDistinguishable = true,
            writable = false,
            writeEncryption = false,
        )
    }

    @Test
    fun `only 7z cannot distinguish a wrong password from damage`() {
        ArchiveFormat.entries.filter { !it.capabilities.wrongPasswordDistinguishable } shouldBe
            listOf(ArchiveFormat.SEVEN_Z)
    }

    @Test
    fun `only zip supports write encryption`() {
        ArchiveFormat.entries.filter { it.capabilities.writeEncryption } shouldBe listOf(ArchiveFormat.ZIP)
    }
}
