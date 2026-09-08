package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import eu.darken.smb.SmbException
import eu.darken.smb.SmbException.Kind
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmbStatusMapperTest : BaseTest() {

    private val path = LocalPath.build("/tmp/whatever")

    private fun apiError(kind: Kind) = SmbException(kind)

    // region connect

    @Test
    fun `an unknown host is unreachable`() {
        SmbStatusMapper.mapConnect(UnknownHostException("nas.local"), "nas.local", "media")
            .shouldBeInstanceOf<SmbUnreachableException>()
    }

    @Test
    fun `a connect timeout is unreachable`() {
        SmbStatusMapper.mapConnect(SocketTimeoutException(), "nas.local", "media")
            .shouldBeInstanceOf<SmbUnreachableException>()
    }

    @Test
    fun `a logon failure is an auth failure`() {
        SmbStatusMapper.mapConnect(apiError(Kind.AUTHENTICATION), "nas.local", "media")
            .shouldBeInstanceOf<SmbAuthException>()
    }

    @Test
    fun `access denied while connecting is an auth failure`() {
        SmbStatusMapper.mapConnect(apiError(Kind.ACCESS_DENIED), "nas.local", "media")
            .shouldBeInstanceOf<SmbAuthException>()
    }

    @Test
    fun `access denied while authenticating is an auth failure`() {
        SmbStatusMapper.mapAuthenticate(apiError(Kind.ACCESS_DENIED), "nas.local")
            .shouldBeInstanceOf<SmbAuthException>()
        SmbStatusMapper.mapAuthenticate(apiError(Kind.AUTHENTICATION), "nas.local")
            .shouldBeInstanceOf<SmbAuthException>()
    }

    @Test
    fun `access denied while opening the share is not a sign-in failure`() {
        val mapped = SmbStatusMapper.mapConnectShare(apiError(Kind.ACCESS_DENIED), "nas.local", "media")

        mapped.shouldBeInstanceOf<SmbShareAccessDeniedException>().share shouldBe "media"
        mapped.isSmbSignInFailure() shouldBe false
    }

    @Test
    fun `a phase mapper leaves everything else to the generic mapping`() {
        val badName = apiError(Kind.SHARE_MISSING)
        SmbStatusMapper.mapAuthenticate(badName, "nas.local") shouldBe badName
        SmbStatusMapper.mapConnectShare(badName, "nas.local", "media") shouldBe badName
    }

    @Test
    fun `a share access denial survives the generic connect mapping`() {
        val original = SmbShareAccessDeniedException("nas.local", "media")
        SmbStatusMapper.mapConnect(original, "nas.local", "media") shouldBe original
        SmbStatusMapper.mapOperation(original, path, "lookup", write = false) shouldBe original
    }

    @Test
    fun `a bad network name is a missing share`() {
        val mapped = SmbStatusMapper.mapConnect(apiError(Kind.SHARE_MISSING), "nas.local", "media")
        mapped.shouldBeInstanceOf<SmbShareNotFoundException>().share shouldBe "media"
    }

    @Test
    fun `an SMB1-only server is reported as such`() {
        SmbStatusMapper.mapConnect(SmbException(Kind.UNSUPPORTED_DIALECT), "nas.local", "media")
            .shouldBeInstanceOf<SmbDialectNotSupportedException>()
    }

    @Test
    fun `cancellation passes through unmapped`() {
        val cancel = CancellationException("stop")
        SmbStatusMapper.mapConnect(cancel, "nas.local", "media") shouldBe cancel
        SmbStatusMapper.mapOperation(cancel, path, "lookup", write = false) shouldBe cancel
    }

    @Test
    fun `an already mapped failure is not wrapped again`() {
        val original = SmbAuthException("nas.local")
        SmbStatusMapper.mapConnect(original, "nas.local", "media") shouldBe original
        SmbStatusMapper.mapOperation(original, path, "lookup", write = false) shouldBe original
    }

    // endregion

    // region operations

    @Test
    fun `missing statuses are recognised`() {
        SmbStatusMapper.isMissing(apiError(Kind.MISSING)) shouldBe true
        SmbStatusMapper.isMissing(apiError(Kind.MISSING)) shouldBe true
        SmbStatusMapper.isMissing(apiError(Kind.MISSING)) shouldBe true
        SmbStatusMapper.isMissing(apiError(Kind.ACCESS_DENIED)) shouldBe false
    }

    @Test
    fun `a missing path becomes a read failure`() {
        SmbStatusMapper.mapOperation(
            apiError(Kind.MISSING),
            path,
            "lookup",
            write = false,
        ).shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `a name collision becomes PathAlreadyExists`() {
        SmbStatusMapper.mapOperation(
            apiError(Kind.ALREADY_EXISTS),
            path,
            "createFile",
            write = true,
        ).shouldBeInstanceOf<PathAlreadyExistsException>()
    }

    @Test
    fun `access denied becomes a permission denial`() {
        val mapped = SmbStatusMapper.mapOperation(
            apiError(Kind.ACCESS_DENIED),
            path,
            "delete",
            write = true,
        )
        mapped.shouldBeInstanceOf<PathPermissionDeniedException>()
            .reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
    }

    @Test
    fun `a non-empty directory becomes a write failure`() {
        SmbStatusMapper.mapOperation(
            apiError(Kind.DIRECTORY_NOT_EMPTY),
            path,
            "delete",
            write = true,
        ).shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `a full disk becomes a write failure`() {
        SmbStatusMapper.mapOperation(
            apiError(Kind.DISK_FULL),
            path,
            "write",
            write = true,
        ).shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `not a directory becomes a read failure`() {
        SmbStatusMapper.mapOperation(
            apiError(Kind.NOT_DIRECTORY),
            path,
            "listFiles",
            write = false,
        ).shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `a sharing violation follows the operation direction`() {
        SmbStatusMapper.mapOperation(apiError(Kind.SHARING_VIOLATION), path, "write", write = true)
            .shouldBeInstanceOf<WriteException>()
        SmbStatusMapper.mapOperation(apiError(Kind.SHARING_VIOLATION), path, "read", write = false)
            .shouldBeInstanceOf<ReadException>()
    }

    // endregion

    @Test
    fun `transport loss is recognised, an NT status failure alone is not`() {
        SmbStatusMapper.isTransportLost(SmbException(Kind.TRANSPORT)) shouldBe true
        SmbStatusMapper.isTransportLost(apiError(Kind.TRANSPORT)) shouldBe true
        SmbStatusMapper.isTransportLost(apiError(Kind.ACCESS_DENIED)) shouldBe false
    }
}
