package eu.darken.butler.common.files.permissions

import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathGoneError
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest
import java.io.IOException

class PermissionErrorClassifierTest : BaseTest() {

    @Test
    fun `causeChain - WriteException wrapping IOException - read-only filesystem`() {
        val cause = IOException("Read-only file system")
        val wrapped = WriteException(path = LocalPath.build("/system/foo"), cause = cause)

        PermissionErrorClassifier.classify(wrapped) shouldBe Reason.READONLY_FILESYSTEM
        PermissionErrorClassifier.isPermissionError(wrapped) shouldBe true
    }

    @Test
    fun `causeChain - WriteException wrapping IOException - operation not permitted`() {
        val cause = IOException("Operation not permitted")
        val wrapped = WriteException(path = LocalPath.build("/system/foo"), cause = cause)

        PermissionErrorClassifier.classify(wrapped) shouldBe Reason.NOT_PERMITTED
    }

    @Test
    fun `causeChain - WriteException wrapping IOException - permission denied`() {
        val cause = IOException("Permission denied")
        val wrapped = WriteException(path = LocalPath.build("/sdcard/foo"), cause = cause)

        PermissionErrorClassifier.classify(wrapped) shouldBe Reason.ACCESS_DENIED
    }

    @Test
    fun `IPC-flattened non-IOException with cause text matches kernel reason`() {
        // Simulates how Binder/AIDL flattens a server-side cause chain into a single message
        // string on the client-side wrapper exception (which is not itself an IOException).
        val flat = object : Exception(
            "Server failure\n" +
                "Caused by: java.io.IOException: Operation not permitted",
        ) {}

        PermissionErrorClassifier.classify(flat) shouldBe Reason.NOT_PERMITTED
    }

    @Test
    fun `idempotent - PathPermissionDeniedException returns its own reason`() {
        val e = PathPermissionDeniedException(
            path = LocalPath.build("/system/foo"),
            operation = "createFile",
            reason = Reason.READONLY_FILESYSTEM,
        )

        PermissionErrorClassifier.classify(e) shouldBe Reason.READONLY_FILESYSTEM
    }

    @Test
    fun `ElevatedAccessUnavailableException maps to NO_MECHANISM`() {
        val e = ElevatedAccessUnavailableException()

        PermissionErrorClassifier.classify(e) shouldBe Reason.NO_MECHANISM
    }

    @Test
    fun `SecurityException is ACCESS_DENIED`() {
        val e = SecurityException("not allowed")

        PermissionErrorClassifier.classify(e) shouldBe Reason.ACCESS_DENIED
    }

    @Test
    fun `unrelated RuntimeException returns null`() {
        PermissionErrorClassifier.classify(RuntimeException("oops")) shouldBe null
        PermissionErrorClassifier.isPermissionError(RuntimeException("oops")) shouldBe false
    }

    @Test
    fun `IOException without permission keywords returns null`() {
        PermissionErrorClassifier.classify(IOException("Disk full")) shouldBe null
    }

    @Test
    fun `a full disk is not a permission failure`() {
        val wrapped = WriteException(
            path = LocalPath.build("/sdcard/foo"),
            cause = IOException("No space left on device"),
        )

        PermissionErrorClassifier.classify(wrapped) shouldBe null
        PermissionErrorClassifier.isPermissionError(wrapped) shouldBe false
    }

    @Test
    fun `a named non-permission errno wins over the generic wrapper type`() {
        listOf(
            "Disk quota exceeded",
            "Input/output error",
            "Invalid cross-device link",
            "File exists",
        ).forEach { errno ->
            val wrapped = WriteException(path = LocalPath.build("/sdcard/foo"), cause = IOException(errno))

            PermissionErrorClassifier.classify(wrapped) shouldBe null
        }
    }

    @Test
    fun `a path named after an errno does not suppress the denial`() {
        val bare = WriteException(path = LocalPath.build("/sdcard/file exists"))

        PermissionErrorClassifier.classify(bare) shouldBe Reason.ACCESS_DENIED
        PermissionErrorClassifier.isPermissionError(bare) shouldBe true
    }

    @Test
    fun `a path named after an errno does not suppress the nio denial`() {
        val e = java.nio.file.AccessDeniedException("/sdcard/file exists")

        PermissionErrorClassifier.classify(e) shouldBe Reason.ACCESS_DENIED
        PermissionErrorClassifier.isPermissionError(e) shouldBe true
    }

    @Test
    fun `an errno in the wrapper message still suppresses the denial`() {
        val e = WriteException(message = "No space left on device", path = LocalPath.build("/sdcard/foo"))

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }

    @Test
    fun `an errno in the nio reason still suppresses the denial`() {
        val e = java.nio.file.AccessDeniedException("/sdcard/foo", null, "No space left on device")

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }

    @Test
    fun `a wrapper without a named errno still reads as a denial`() {
        val bare = ReadException(path = LocalPath.build("/data/data/eu.darken.butler"))

        PermissionErrorClassifier.classify(bare) shouldBe Reason.ACCESS_DENIED
        PermissionErrorClassifier.isPermissionError(bare) shouldBe true
    }

    @Test
    fun `a missing path is not a permission failure`() {
        val e = PathNotFoundException(LocalPath.build("/data/data/eu.darken.butler"))

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }

    @Test
    fun `a top-level gone marker is not a permission failure even when it is a PathException`() {
        // The marker, not the one type, is what pass 4 answers to: a gone-error that also happens
        // to be a PathException would otherwise fall through to ACCESS_DENIED and offer the user a
        // permission fix for a file that is simply not there. Only the top level is covered - see
        // the sibling test for what a nested marker does.
        val e = object : ReadException(message = "Path does not exist", path = LocalPath.build("/sdcard/gone.txt")),
            PathGoneError {
            override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
                throwable = this,
                label = "gone".toCaString(),
                description = "gone".toCaString(),
            )
        }

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }

    @Test
    fun `a gone marker nested under a generic wrapper does not veto`() {
        // Documents the limit rather than endorsing it: pass 4 answers for the first matching chain
        // link, and the ReadException wrapper is reached before the marker underneath it. Nothing
        // wraps a gone-error today; if something starts to, this is the test that will say so.
        val wrapped = ReadException(
            path = LocalPath.build("/sdcard/gone.txt"),
            cause = PathNotFoundException(LocalPath.build("/sdcard/gone.txt")),
        )

        PermissionErrorClassifier.classify(wrapped) shouldBe Reason.ACCESS_DENIED
    }

    @Test
    fun `a gone error whose path contains permission is not a denial`() {
        val gone = PathNotFoundException(LocalPath.build("/sdcard/permission-notes.txt"))

        PermissionErrorClassifier.classify(gone) shouldBe null
    }

    @Test
    fun `an existing path is not a permission failure`() {
        val e = PathAlreadyExistsException(path = LocalPath.build("/sdcard/foo"))

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }

    @Test
    fun `an existing path is not a permission failure - with the cause the gateway attaches`() {
        val e = PathAlreadyExistsException(
            path = LocalPath.build("/sdcard/foo"),
            cause = java.nio.file.FileAlreadyExistsException("/sdcard/foo"),
        )

        PermissionErrorClassifier.classify(e) shouldBe null
        PermissionErrorClassifier.isPermissionError(e) shouldBe false
    }
}
