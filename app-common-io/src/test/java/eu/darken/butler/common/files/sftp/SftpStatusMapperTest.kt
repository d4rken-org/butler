package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialUnavailableException
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.ssh.HostKey
import eu.darken.ssh.SshException
import eu.darken.ssh.SshException.Kind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.uuid.Uuid

class SftpStatusMapperTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val path = SftpPath(locationId, listOf("a"))
    private val endpoint = "darken@nas.local"

    private val location = testSftpLocation(locationId, hostKey = testHostKey(1))
    private val presented: HostKey = testHostKey(2).toHostKey()

    private fun apiError(kind: Kind, hostKey: HostKey? = null) = SshException(kind, presentedHostKey = hostKey)

    // region connect

    @Test
    fun `an unknown host is unreachable`() {
        SftpStatusMapper.mapConnect(UnknownHostException("nas.local"), location)
            .shouldBeInstanceOf<SftpUnreachableException>()
    }

    @Test
    fun `a transport failure while connecting is unreachable`() {
        SftpStatusMapper.mapConnect(apiError(Kind.TRANSPORT), location)
            .shouldBeInstanceOf<SftpUnreachableException>()
        SftpStatusMapper.mapConnect(SocketTimeoutException(), location)
            .shouldBeInstanceOf<SftpUnreachableException>()
    }

    @Test
    fun `a rejected sign-in is an auth failure`() {
        val mapped = SftpStatusMapper.mapConnect(apiError(Kind.AUTHENTICATION), location)

        mapped.shouldBeInstanceOf<SftpAuthException>()
        mapped.isSftpSignInFailure() shouldBe true
    }

    @Test
    fun `an unknown host key carries the presented key`() {
        val mapped = SftpStatusMapper.mapConnect(apiError(Kind.HOST_KEY_UNKNOWN, presented), location)

        mapped.shouldBeInstanceOf<SftpHostKeyUnknownException>().presentedKey shouldBe presented
        mapped.isSftpSignInFailure() shouldBe false
    }

    @Test
    fun `a changed host key carries both fingerprints`() {
        val mapped = SftpStatusMapper.mapConnect(apiError(Kind.HOST_KEY_MISMATCH, presented), location)

        mapped.shouldBeInstanceOf<SftpHostKeyChangedException>()
        mapped.storedFingerprint shouldBe testHostKey(1).fingerprint
        mapped.presentedFingerprint shouldBe presented.sha256Fingerprint
        mapped.presentedKey shouldBe TrustedHostKey.from(presented)
        mapped.isSftpSignInFailure() shouldBe false
    }

    @Test
    fun `a changed host key names the location and the endpoint it was pinned for`() {
        val pinnedFor = testSftpLocation(locationId, host = "fe80::1", port = 2222, trustRevision = 3)

        val mapped = SftpStatusMapper.mapConnect(apiError(Kind.HOST_KEY_MISMATCH, presented), pinnedFor)
            .shouldBeInstanceOf<SftpHostKeyChangedException>()

        mapped.locationId shouldBe locationId
        mapped.host shouldBe "fe80::1"
        mapped.port shouldBe 2222
        mapped.trustRevision shouldBe 3
        mapped.storedKey shouldBe pinnedFor.hostKey
        mapped.endpoint shouldBe pinnedFor.endpointLabel
    }

    @Test
    fun `key problems are sign-in failures of their own`() {
        SftpStatusMapper.mapConnect(apiError(Kind.KEY_FORMAT), location).let {
            it.shouldBeInstanceOf<SftpKeyFormatException>()
            it.isSftpSignInFailure() shouldBe true
        }
        SftpStatusMapper.mapConnect(apiError(Kind.KEY_PASSPHRASE), location).let {
            it.shouldBeInstanceOf<SftpKeyPassphraseException>()
            it.isSftpSignInFailure() shouldBe true
        }
    }

    @Test
    fun `a denied base path is not a sign-in failure`() {
        val mapped = SftpStatusMapper.mapRoot(apiError(Kind.ACCESS_DENIED), endpoint, "/srv", SftpPath.root(locationId))

        mapped.shouldBeInstanceOf<SftpAccessDeniedException>().basePath shouldBe "/srv"
        mapped.isSftpSignInFailure() shouldBe false
    }

    @Test
    fun `a missing base path stays recognisable as missing`() {
        val mapped = SftpStatusMapper.mapRoot(apiError(Kind.MISSING), endpoint, "/srv", SftpPath.root(locationId))

        mapped.shouldBeInstanceOf<ReadException>()
        SftpStatusMapper.isMissing(mapped) shouldBe true
    }

    @Test
    fun `a sign-in failure is found below wrappers`() {
        WriteException("copy failed", path, ReadException(cause = SftpAuthException(endpoint)))
            .isSftpSignInFailure() shouldBe true
        ReadException(cause = SftpCredentialUnavailableException(locationId, "gone"))
            .isSftpSignInFailure() shouldBe true
        ReadException(cause = SftpUnreachableException(endpoint)).isSftpSignInFailure() shouldBe false
    }

    @Test
    fun `cancellation passes through unmapped`() {
        val cancel = CancellationException("stop")
        SftpStatusMapper.mapConnect(cancel, location) shouldBe cancel
        SftpStatusMapper.mapOperation(cancel, path, "lookup", write = false) shouldBe cancel
    }

    @Test
    fun `an already mapped failure is not wrapped again`() {
        val original = SftpAuthException(endpoint)
        SftpStatusMapper.mapConnect(original, location) shouldBe original
        SftpStatusMapper.mapOperation(original, path, "lookup", write = false) shouldBe original

        val pro = SftpProRequiredException()
        SftpStatusMapper.mapOperation(pro, path, "lookup", write = false) shouldBe pro
    }

    // endregion

    // region operations

    @Test
    fun `a missing path becomes a read failure`() {
        val mapped = SftpStatusMapper.mapOperation(apiError(Kind.MISSING), path, "lookup", write = false)
        mapped.shouldBeInstanceOf<ReadException>()
        SftpStatusMapper.isMissing(mapped) shouldBe true
        SftpStatusMapper.isMissing(apiError(Kind.ACCESS_DENIED)) shouldBe false
    }

    @Test
    fun `a name collision becomes PathAlreadyExists`() {
        SftpStatusMapper.mapOperation(apiError(Kind.ALREADY_EXISTS), path, "move", write = true)
            .shouldBeInstanceOf<PathAlreadyExistsException>()
    }

    @Test
    fun `access denied becomes a permission denial`() {
        SftpStatusMapper.mapOperation(apiError(Kind.ACCESS_DENIED), path, "delete", write = true)
            .shouldBeInstanceOf<PathPermissionDeniedException>()
            .reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
    }

    @Test
    fun `a non-empty directory and a full disk become write failures`() {
        SftpStatusMapper.mapOperation(apiError(Kind.DIRECTORY_NOT_EMPTY), path, "delete", write = true)
            .shouldBeInstanceOf<WriteException>()
        SftpStatusMapper.mapOperation(apiError(Kind.DISK_FULL), path, "write", write = true)
            .shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `not a directory becomes a read failure`() {
        SftpStatusMapper.mapOperation(apiError(Kind.NOT_DIRECTORY), path, "listFiles", write = false)
            .shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `other failures follow the operation direction`() {
        listOf(Kind.IS_DIRECTORY, Kind.UNSUPPORTED, Kind.TRANSPORT, Kind.OTHER).forEach { kind ->
            SftpStatusMapper.mapOperation(apiError(kind), path, "write", write = true)
                .shouldBeInstanceOf<WriteException>()
            SftpStatusMapper.mapOperation(apiError(kind), path, "read", write = false)
                .shouldBeInstanceOf<ReadException>()
        }
    }

    // endregion

    @Test
    fun `transport loss is recognised, a status failure alone is not`() {
        SftpStatusMapper.isTransportLost(apiError(Kind.TRANSPORT)) shouldBe true
        SftpStatusMapper.isTransportLost(SocketTimeoutException()) shouldBe true
        SftpStatusMapper.isTransportLost(apiError(Kind.ACCESS_DENIED)) shouldBe false
        SftpStatusMapper.isTransportLost(apiError(Kind.OTHER)) shouldBe false
    }
}
