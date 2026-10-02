package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.sftp.SftpAccessDeniedException
import eu.darken.butler.common.files.sftp.SftpAuthException
import eu.darken.butler.common.files.sftp.SftpHostKeyChangedException
import eu.darken.butler.common.files.sftp.SftpHostKeyUnknownException
import eu.darken.butler.common.files.sftp.SftpKeyPassphraseException
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.smb.SmbAuthException
import eu.darken.butler.common.files.smb.SmbShareAccessDeniedException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class ExplorerNetworkSignInTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val sftpRoot = ExplorerNavigation.Target.Directory(SftpPath.root(locationId))

    private fun state(
        error: Throwable?,
        target: ExplorerNavigation.Target? = ExplorerNavigation.Target.Directory(SmbPath.root(locationId)),
    ) = ExplorerWorkspace.State.Ready(
        currentTarget = target,
        // A failed load publishes the error with no loaded location left
        currentLocation = null,
        error = error,
    )

    @Test
    fun `an auth failure names the location that has to be signed in to`() {
        state(SmbAuthException("nas.local")).networkSignInRequest() shouldBe NetworkSignInRequest.Smb(locationId)
    }

    @Test
    fun `a wrapped auth failure still names the location`() {
        val wrapped = ReadException("browsing failed", cause = SmbAuthException("nas.local"))
        state(wrapped).networkSignInRequest() shouldBe NetworkSignInRequest.Smb(locationId)
    }

    @Test
    fun `a share permission denial does not ask for a sign-in`() {
        state(SmbShareAccessDeniedException("nas.local", "media")).networkSignInRequest() shouldBe null
    }

    @Test
    fun `an unrelated failure asks for nothing`() {
        state(ReadException("boom")).networkSignInRequest() shouldBe null
        state(error = null).networkSignInRequest() shouldBe null
    }

    @Test
    fun `a failure outside network storage asks for nothing`() {
        val local = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
        state(SmbAuthException("nas.local"), target = local).networkSignInRequest() shouldBe null
        state(SmbAuthException("nas.local"), target = ExplorerNavigation.Target.Home).networkSignInRequest() shouldBe null
    }

    @Test
    fun `an SFTP auth failure asks for an SFTP sign-in`() {
        state(SftpAuthException("nas.local"), target = sftpRoot).networkSignInRequest() shouldBe
            NetworkSignInRequest.Sftp(locationId)
    }

    @Test
    fun `a wrapped SFTP key failure asks for an SFTP sign-in`() {
        val wrapped = ReadException("browsing failed", cause = SftpKeyPassphraseException("nas.local"))
        state(wrapped, target = sftpRoot).networkSignInRequest() shouldBe NetworkSignInRequest.Sftp(locationId)
    }

    private fun hostKeyChange() = SftpHostKeyChangedException(
        endpoint = "darken@nas.local",
        locationId = locationId,
        host = "nas.local",
        port = 22,
        trustRevision = 1,
        storedKey = TrustedHostKey("ssh-ed25519", ByteArray(51), "SHA256:stored"),
        presentedKey = TrustedHostKey("ssh-ed25519", ByteArray(51) { 1 }, "SHA256:presented"),
    )

    /**
     * A host key problem needs a trust decision, other credentials cannot fix it. The unknown-key
     * failure is mocked because its presented key's type is internal to the IO module.
     */
    @Test
    fun `an SFTP host key failure does not ask for a sign-in`() {
        val unknown = mockk<SftpHostKeyUnknownException> { every { cause } returns null }

        state(unknown, target = sftpRoot).networkSignInRequest() shouldBe null
        state(ReadException("browsing failed", cause = hostKeyChange()), target = sftpRoot)
            .networkSignInRequest() shouldBe null
    }

    @Test
    fun `a changed SFTP host key is found below wrappers`() {
        val change = hostKeyChange()

        ReadException("browsing failed", cause = ReadException(cause = change)).sftpHostKeyChange() shouldBe change
        SftpAuthException("nas.local").sftpHostKeyChange() shouldBe null
    }

    @Test
    fun `an SFTP permission denial does not ask for a sign-in`() {
        state(SftpAccessDeniedException("nas.local", "/srv"), target = sftpRoot).networkSignInRequest() shouldBe null
    }

    @Test
    fun `a failure of the other protocol asks for nothing`() {
        state(SmbAuthException("nas.local"), target = sftpRoot).networkSignInRequest() shouldBe null
        state(SftpAuthException("nas.local")).networkSignInRequest() shouldBe null
    }
}
