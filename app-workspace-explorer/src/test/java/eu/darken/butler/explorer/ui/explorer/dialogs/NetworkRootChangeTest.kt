package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.smb.location.SmbLocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkRootChangeTest : BaseTest() {

    private val smbShare = SmbLocation(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        label = "Home NAS",
        host = "nas.local",
        share = "media",
        basePath = listOf("movies", "2024"),
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val smbServer = smbShare.copy(share = null, basePath = emptyList())

    private val sftp = SftpLocation(
        id = Uuid.parse("66666666-7777-8888-9999-000000000000"),
        label = "Build server",
        host = "build.lan",
        username = "darken",
        basePath = "/srv",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = TrustedHostKey(
            type = "ssh-ed25519",
            blob = ByteArray(51),
            fingerprint = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM",
        ),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    // https://cloud.example.com/remote.php/dav/files/alice/
    private val webDav = previewWebDavLocation()

    @Test
    fun `smb share and folder as saved are no change`() {
        smbShare.startsElsewhere(share = "media", basePath = "movies/2024") shouldBe false
        smbServer.startsElsewhere(share = "", basePath = "") shouldBe false
    }

    @Test
    fun `smb separators and spaces around the share and folder do not count`() {
        smbShare.startsElsewhere(share = " /media\\ ", basePath = "\\movies\\2024\\") shouldBe false
        smbShare.startsElsewhere(share = "media", basePath = "/movies//2024/") shouldBe false
        smbServer.startsElsewhere(share = " / ", basePath = "") shouldBe false
    }

    @Test
    fun `smb another share or folder is a change`() {
        smbShare.startsElsewhere(share = "photos", basePath = "movies/2024") shouldBe true
        smbShare.startsElsewhere(share = "media", basePath = "movies") shouldBe true
        smbShare.startsElsewhere(share = "media", basePath = "") shouldBe true
    }

    @Test
    fun `smb switching between a share and all shares is a change`() {
        smbShare.startsElsewhere(share = "", basePath = "") shouldBe true
        smbServer.startsElsewhere(share = "media", basePath = "") shouldBe true
    }

    @Test
    fun `sftp base path changes count, surrounding spaces do not`() {
        sftp.startsElsewhere(basePath = "/srv") shouldBe false
        sftp.startsElsewhere(basePath = " /srv ") shouldBe false
        sftp.startsElsewhere(basePath = "/srv/builds") shouldBe true
        sftp.startsElsewhere(basePath = "") shouldBe true
    }

    @Test
    fun `webdav path changes count`() {
        webDav.startsElsewhere(url = "https://cloud.example.com/remote.php/dav/files/bob/") shouldBe true
        webDav.startsElsewhere(url = "https://cloud.example.com/") shouldBe true
    }

    @Test
    fun `webdav host, port, scheme and trailing slash changes do not count`() {
        webDav.startsElsewhere(url = "https://cloud.example.com/remote.php/dav/files/alice/") shouldBe false
        webDav.startsElsewhere(url = "https://cloud.example.com/remote.php/dav/files/alice") shouldBe false
        webDav.startsElsewhere(url = " HTTPS://Cloud.Example.com/remote.php/dav/files/alice/ ") shouldBe false
        webDav.startsElsewhere(url = "http://nas.local:8080/remote.php/dav/files/alice/") shouldBe false
    }

    @Test
    fun `webdav percent-encoding of the same path does not count`() {
        val spaced = webDav.copy(url = "https://cloud.example.com/my%20files/")
        spaced.startsElsewhere(url = "https://cloud.example.com/my files/") shouldBe false
        spaced.startsElsewhere(url = "https://cloud.example.com/my%20files") shouldBe false
    }

    @Test
    fun `webdav brackets in the path are a change, not a crash`() {
        webDav.startsElsewhere(url = "https://cloud.example.com/dav/[2026]/") shouldBe true
    }

    @Test
    fun `webdav incomplete percent escapes do not crash`() {
        webDav.startsElsewhere(url = "https://cloud.example.com/dav/%") shouldBe true
        webDav.startsElsewhere(url = "https://cloud.example.com/dav/%2") shouldBe true
    }

    @Test
    fun `webdav an encoded slash is not a real one`() {
        val nested = webDav.copy(url = "https://cloud.example.com/dav/team/archive/")
        nested.startsElsewhere(url = "https://cloud.example.com/dav/team%2Farchive/") shouldBe true
    }

    @Test
    fun `webdav an empty segment inside the path is a change`() {
        val nested = webDav.copy(url = "https://cloud.example.com/dav/team/archive/")
        nested.startsElsewhere(url = "https://cloud.example.com/dav/team//archive/") shouldBe true
    }

    @Test
    fun `webdav url that does not parse is no change`() {
        webDav.startsElsewhere(url = "") shouldBe false
        webDav.startsElsewhere(url = "cloud.example.com/other/") shouldBe false
        webDav.startsElsewhere(url = "https://cloud.example.com/other/?x=1") shouldBe false
    }
}
