package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.location.SmbLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The segment-to-SMB-path conversion, the one place where separators exist. */
class SmbFileSystemOpsPathTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun location(basePath: List<String> = emptyList()) = SmbLocation(
        id = locationId,
        label = null,
        host = "nas.local",
        share = "media",
        basePath = basePath,
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `the share root is the empty path`() {
        SmbFileSystemOps.smbPath(location(), SmbPath.root(locationId)) shouldBe ""
    }

    @Test
    fun `segments join with backslashes`() {
        val path = SmbPath(locationId, listOf("movies", "2024", "a.mkv"))
        SmbFileSystemOps.smbPath(location(), path) shouldBe "movies\\2024\\a.mkv"
    }

    @Test
    fun `the location base path is prepended`() {
        val path = SmbPath(locationId, listOf("a.mkv"))
        SmbFileSystemOps.smbPath(location(listOf("movies", "2024")), path) shouldBe "movies\\2024\\a.mkv"
    }

    @Test
    fun `a base path that slipped past validation is still rejected here`() {
        val path = SmbPath.root(locationId)
        shouldThrow<IllegalArgumentException> {
            SmbFileSystemOps.smbPath(location(listOf("..", "etc")), path)
        }
        shouldThrow<IllegalArgumentException> {
            SmbFileSystemOps.smbPath(location(listOf("a\\b")), path)
        }
    }
}
