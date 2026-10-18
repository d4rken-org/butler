package eu.darken.butler.common.files.network

import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.testSftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.testWebDavLocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkLocationTest : BaseTest() {

    private val id = Uuid.parse("11111111-1111-1111-1111-111111111111")

    private fun smb(authType: SmbLocation.AuthType, remember: Boolean) = NetworkLocation.Smb(
        SmbLocation(
            id = id,
            label = null,
            host = "nas.local",
            share = "media",
            username = "darken".takeIf { authType == SmbLocation.AuthType.PASSWORD },
            authType = authType,
            rememberCredential = remember,
            credentialVersion = 1,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
    )

    private fun sftp(
        authType: SftpLocation.AuthType,
        remember: Boolean,
        rememberPassphrase: Boolean = true,
    ) = NetworkLocation.Sftp(
        testSftpLocation(id, authType = authType).copy(
            rememberCredential = remember,
            rememberPassphrase = rememberPassphrase,
        )
    )

    private fun webDav(authType: WebDavLocation.AuthType, remember: Boolean) = NetworkLocation.WebDav(
        testWebDavLocation(id, authType = authType).copy(rememberCredential = remember)
    )

    @Test
    fun `an SMB password that is not remembered is a session credential`() {
        smb(SmbLocation.AuthType.PASSWORD, remember = false).usesSessionCredential shouldBe true
        smb(SmbLocation.AuthType.PASSWORD, remember = true).usesSessionCredential shouldBe false
    }

    @Test
    fun `SMB guest access has no session credential`() {
        smb(SmbLocation.AuthType.GUEST, remember = false).usesSessionCredential shouldBe false
    }

    @Test
    fun `an SFTP password or key that is not remembered is a session credential`() {
        sftp(SftpLocation.AuthType.PASSWORD, remember = false).usesSessionCredential shouldBe true
        sftp(SftpLocation.AuthType.PASSWORD, remember = true).usesSessionCredential shouldBe false
        sftp(SftpLocation.AuthType.PRIVATE_KEY, remember = false).usesSessionCredential shouldBe true
        sftp(SftpLocation.AuthType.PRIVATE_KEY, remember = true).usesSessionCredential shouldBe false
    }

    @Test
    fun `an SFTP key whose passphrase is not remembered is a session credential`() {
        sftp(SftpLocation.AuthType.PRIVATE_KEY, remember = true, rememberPassphrase = false)
            .usesSessionCredential shouldBe true
        sftp(SftpLocation.AuthType.PRIVATE_KEY, remember = true, rememberPassphrase = true)
            .usesSessionCredential shouldBe false
        sftp(SftpLocation.AuthType.PRIVATE_KEY, remember = false, rememberPassphrase = false)
            .usesSessionCredential shouldBe true
    }

    @Test
    fun `a WebDAV password that is not remembered is a session credential`() {
        webDav(WebDavLocation.AuthType.BASIC, remember = false).usesSessionCredential shouldBe true
        webDav(WebDavLocation.AuthType.BASIC, remember = true).usesSessionCredential shouldBe false
    }

    @Test
    fun `anonymous WebDAV access has no session credential`() {
        webDav(WebDavLocation.AuthType.ANONYMOUS, remember = false).usesSessionCredential shouldBe false
    }
}
