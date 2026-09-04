package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.ipc.IpcHostModule
import eu.darken.butler.common.issue.Issue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeTypeOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.io.IOException

/**
 * What crosses the issue callback in both directions: the exception that caused the issue, and the
 * one a cancelling resolution answers with, rebuilt rather than reduced to their rendered text. The
 * failures here are shaped like the ones the local file system ops actually raise, a
 * [WriteException] wrapping the errno failure as its cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FileOperationIssueTransportTest : BaseTest() {

    private val target = LocalPath.build("/data/subtree/target.txt")
    private val client = object : IpcClientModule {}
    private val host = object : IpcHostModule {}

    private inline fun <reified T : Parcelable> T.throughParcel(): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(this, 0)
            parcel.setDataPosition(0)
            parcel.readParcelable<T>(T::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    private fun PathActionIssue.roundTrip(): PathActionIssue = with(client) {
        toPathActionIssue(this@roundTrip.toFileOperationIssue().throughParcel())
    }

    private fun hostSideFailure(cause: Throwable) = WriteException(path = target, cause = cause)

    @Test
    fun `a permission denial arrives with its cause chain, so the kernel reason still classifies`() {
        val issue = PathActionIssue.InsufficientPermission(
            destinationPath = target,
            exception = hostSideFailure(IOException("EROFS (Read-only file system)")),
        )

        val rebuilt = issue.roundTrip() as PathActionIssue.InsufficientPermission

        PermissionErrorClassifier.classify(rebuilt.exception!!) shouldBe
            PathPermissionDeniedException.Reason.READONLY_FILESYSTEM
    }

    @Test
    fun `an unknown error arrives as the host type, with path and cause`() {
        val issue = PathActionIssue.UnknownError(
            destinationPath = target,
            exception = hostSideFailure(IOException("no space left on device")),
        )

        val rebuilt = (issue.roundTrip() as PathActionIssue.UnknownError).exception

        rebuilt.shouldBeTypeOf<WriteException>()
        rebuilt.path?.path shouldBe target.path
        rebuilt.cause?.message shouldContain "no space left on device"
    }

    @Test
    fun `an unknown error arrives with the host stack the sheet renders`() {
        val issue = PathActionIssue.UnknownError(
            destinationPath = target,
            exception = raisedInASentinelHostFrame(),
        )

        val rebuilt = (issue.roundTrip() as PathActionIssue.UnknownError).exception

        rebuilt.stackTrace.any { it.methodName == "raisedInASentinelHostFrame" } shouldBe true
    }

    private fun raisedInASentinelHostFrame() = IOException("host side boom")

    @Test
    fun `an unmarked carrier is still used as a message`() {
        val denial = FileOperationIssue(
            issueId = Issue.Id().id.toString(),
            issueType = FileOperationIssue.IssueType.PERMISSION_DENIED,
            destinationPath = target,
            error = "legacy denial text",
        )
        val unknown = denial.copy(
            issueType = FileOperationIssue.IssueType.UNKNOWN_ERROR,
            error = "legacy unknown text",
        )

        with(client) {
            val rebuiltDenial = toPathActionIssue(denial.throughParcel())
            rebuiltDenial.shouldBeTypeOf<PathActionIssue.InsufficientPermission>()
            rebuiltDenial.exception!!.cause?.message shouldBe "legacy denial text"

            val rebuiltUnknown = toPathActionIssue(unknown.throughParcel())
            rebuiltUnknown.shouldBeTypeOf<PathActionIssue.UnknownError>()
            rebuiltUnknown.exception.message shouldBe "legacy unknown text"
        }
    }

    @Test
    fun `a cancelling resolution reaches the host as the exception it was given`() {
        val issue = PathActionIssue.UnknownError(destinationPath = target, exception = IOException("boom"))
        val cancel = PathActionIssue.UnknownError.Resolution.Cancel(SecurityException("x"))

        val sent = with(client) { toFileOperationIssueResolution(cancel) }.throughParcel()
        val rebuilt = with(host) { toPathActionIssueResolution(sent, issue) }

        rebuilt.shouldBeTypeOf<PathActionIssue.UnknownError.Resolution.Cancel>()
        rebuilt.error.shouldBeTypeOf<SecurityException>().message shouldBe "x"
    }

    @Test
    fun `an unmarked cancel error is still used as a message`() {
        val issue = PathActionIssue.UnknownError(destinationPath = target, exception = IOException("boom"))
        val sent = FileOperationIssueResolution(
            resolutionType = FileOperationIssueResolution.ResolutionType.CANCEL,
            cancelled = true,
            error = "legacy cancel text",
        ).throughParcel()

        val rebuilt = with(host) { toPathActionIssueResolution(sent, issue) }

        rebuilt.shouldBeTypeOf<PathActionIssue.UnknownError.Resolution.Cancel>()
        rebuilt.error.shouldBeTypeOf<IOException>().message shouldBe "legacy cancel text"
    }
}
