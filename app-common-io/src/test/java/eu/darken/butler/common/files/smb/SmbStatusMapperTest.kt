package eu.darken.butler.common.files.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmbStatusMapperTest : BaseTest() {

    private val path = LocalPath.build("/tmp/whatever")

    private fun apiError(status: NtStatus) = SMBApiException(
        status.value,
        SMB2MessageCommandCode.SMB2_CREATE,
        "test",
        null,
    )

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
        SmbStatusMapper.mapConnect(apiError(NtStatus.STATUS_LOGON_FAILURE), "nas.local", "media")
            .shouldBeInstanceOf<SmbAuthException>()
    }

    @Test
    fun `access denied while connecting is an auth failure`() {
        SmbStatusMapper.mapConnect(apiError(NtStatus.STATUS_ACCESS_DENIED), "nas.local", "media")
            .shouldBeInstanceOf<SmbAuthException>()
    }

    @Test
    fun `a bad network name is a missing share`() {
        val mapped = SmbStatusMapper.mapConnect(apiError(NtStatus.STATUS_BAD_NETWORK_NAME), "nas.local", "media")
        mapped.shouldBeInstanceOf<SmbShareNotFoundException>().share shouldBe "media"
    }

    @Test
    fun `an SMB1-only server is reported as such`() {
        SmbStatusMapper.mapConnect(SMB1NotSupportedException(), "nas.local", "media")
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
        SmbStatusMapper.isMissing(apiError(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)) shouldBe true
        SmbStatusMapper.isMissing(apiError(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)) shouldBe true
        SmbStatusMapper.isMissing(apiError(NtStatus.STATUS_NO_SUCH_FILE)) shouldBe true
        SmbStatusMapper.isMissing(apiError(NtStatus.STATUS_ACCESS_DENIED)) shouldBe false
    }

    @Test
    fun `a missing path becomes a read failure`() {
        SmbStatusMapper.mapOperation(
            apiError(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND),
            path,
            "lookup",
            write = false,
        ).shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `a name collision becomes PathAlreadyExists`() {
        SmbStatusMapper.mapOperation(
            apiError(NtStatus.STATUS_OBJECT_NAME_COLLISION),
            path,
            "createFile",
            write = true,
        ).shouldBeInstanceOf<PathAlreadyExistsException>()
    }

    @Test
    fun `access denied becomes a permission denial`() {
        val mapped = SmbStatusMapper.mapOperation(
            apiError(NtStatus.STATUS_ACCESS_DENIED),
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
            apiError(NtStatus.STATUS_DIRECTORY_NOT_EMPTY),
            path,
            "delete",
            write = true,
        ).shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `a full disk becomes a write failure`() {
        SmbStatusMapper.mapOperation(
            apiError(NtStatus.STATUS_DISK_FULL),
            path,
            "write",
            write = true,
        ).shouldBeInstanceOf<WriteException>()
    }

    @Test
    fun `not a directory becomes a read failure`() {
        SmbStatusMapper.mapOperation(
            apiError(NtStatus.STATUS_NOT_A_DIRECTORY),
            path,
            "listFiles",
            write = false,
        ).shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `a sharing violation follows the operation direction`() {
        SmbStatusMapper.mapOperation(apiError(NtStatus.STATUS_SHARING_VIOLATION), path, "write", write = true)
            .shouldBeInstanceOf<WriteException>()
        SmbStatusMapper.mapOperation(apiError(NtStatus.STATUS_SHARING_VIOLATION), path, "read", write = false)
            .shouldBeInstanceOf<ReadException>()
    }

    // endregion

    @Test
    fun `transport loss is recognised, an NT status failure alone is not`() {
        SmbStatusMapper.isTransportLost(TransportException("gone")) shouldBe true
        SmbStatusMapper.isTransportLost(apiError(NtStatus.STATUS_CONNECTION_RESET)) shouldBe true
        SmbStatusMapper.isTransportLost(apiError(NtStatus.STATUS_ACCESS_DENIED)) shouldBe false
    }
}
