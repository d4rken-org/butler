package eu.darken.butler.common.ipc

import android.app.Application
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.ServiceConnectionLostException as FileServiceConnectionLostException
import eu.darken.butler.common.files.errors.UnknownFileTypeException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldBeTypeOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class ExceptionPropagationTest : BaseTest(), IpcHostModule, IpcClientModule {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val filePath = LocalPath.build("/storage/emulated/0/report.txt")

    private fun Throwable.propagate(): Throwable = wrapToPropagate().refineException()

    private fun payload(json: String) = UnsupportedOperationException("${IpcErrorCodec.MARKER}$json")

    @Test
    fun `round-trip - PATH_READ`() {
        val original = ReadException("Can't read from path.", filePath)

        original.propagate().shouldBeTypeOf<ReadException>().apply {
            message shouldBe original.message
            this.path!!.path shouldBe original.path!!.path
        }
    }

    @Test
    fun `round-trip - PATH_WRITE`() {
        val original = WriteException("Can't write to path.", filePath)

        original.propagate().shouldBeTypeOf<WriteException>().apply {
            message shouldBe original.message
            this.path!!.path shouldBe original.path!!.path
        }
    }

    @Test
    fun `round-trip - PATH_ALREADY_EXISTS`() {
        val original = PathAlreadyExistsException(path = filePath)

        original.propagate().shouldBeTypeOf<PathAlreadyExistsException>().apply {
            message shouldBe original.message
            this.path!!.path shouldBe original.path!!.path
        }
    }

    @Test
    fun `round-trip - PATH_PERMISSION_DENIED`() {
        val original = PathPermissionDeniedException(
            path = filePath,
            operation = "createFile",
            reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
        )

        original.propagate().shouldBeTypeOf<PathPermissionDeniedException>().apply {
            message shouldBe original.message
            this.path!!.path shouldBe original.path!!.path
            operation shouldBe "createFile"
            reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
        }
    }

    @Test
    fun `round-trip - PATH_UNKNOWN_FILE_TYPE`() {
        val original = UnknownFileTypeException(
            lookup = LocalPathLookup(
                lookedUp = filePath,
                fileType = FileType.SYMBOLIC_LINK,
                size = null,
                modifiedAt = null,
            ),
        )

        original.propagate().shouldBeTypeOf<UnknownFileTypeException>().apply {
            message shouldBe original.message
            lookup.lookedUp.path shouldBe filePath.path
            lookup.fileType shouldBe FileType.SYMBOLIC_LINK
        }
    }

    @Test
    fun `round-trip - IO`() {
        val original = IOException("Read-only file system")

        original.propagate().shouldBeTypeOf<IOException>().message shouldBe original.message
    }

    @Test
    fun `round-trip - SECURITY`() {
        val original = SecurityException("Not allowed to query packages")

        original.propagate().shouldBeTypeOf<SecurityException>().message shouldBe original.message
    }

    @Test
    fun `round-trip - ILLEGAL_ARGUMENT`() {
        val original = IllegalArgumentException("Non-OK command result! ")

        original.propagate().shouldBeTypeOf<IllegalArgumentException>().message shouldBe original.message
    }

    @Test
    fun `round-trip - UNMAPPED keeps class name and message`() {
        val original = IllegalStateException("Host is in a weird state")

        original.propagate().shouldBeTypeOf<UnwrappedIPCException>()
            .message shouldBe "java.lang.IllegalStateException: Host is in a weird state"
    }

    @Test
    fun `pass-through - local IOException is untouched`() {
        val local = IOException("Stream ended prematurely")

        local.refineException() shouldBeSameInstanceAs local
    }

    @Test
    fun `pass-through - client side ServiceConnectionLostException is untouched`() {
        val local = FileServiceConnectionLostException(IOException("binder died"))

        local.refineException() shouldBeSameInstanceAs local
    }

    @Test
    fun `pass-through - UnsupportedOperationException without marker is untouched`() {
        val local = UnsupportedOperationException("Not implemented")

        local.refineException() shouldBeSameInstanceAs local
    }

    @Test
    fun `fallback - corrupt json`() {
        val decoded = payload("""{"code":"PATH_READ","className":"com.host.Boom","rawMes""").refineException()

        decoded.shouldBeTypeOf<UnwrappedIPCException>().message!! shouldContain "com.host.Boom"
    }

    @Test
    fun `fallback - path code without a path`() {
        val decoded = payload(
            """{"code":"PATH_READ","className":"com.host.Boom","rawMessage":"Can't read from path."}"""
        ).refineException()

        decoded.shouldBeTypeOf<UnwrappedIPCException>()
            .message shouldBe "com.host.Boom: Can't read from path."
    }

    @Test
    fun `fallback - permission denial with an unknown reason`() {
        val decoded = payload(
            """
            {"code":"PATH_PERMISSION_DENIED","className":"com.host.Boom","rawPath":"/data/x",
            "extras":{"reason":"SOMETHING_NEW","operation":"createFile"}}
            """.trimIndent()
        ).refineException()

        decoded.shouldBeTypeOf<UnwrappedIPCException>()
    }

    @Test
    fun `cause chain survives so EROFS is not reclassified as a plain denial`() {
        val original = WriteException("Can't write to path.", filePath, IOException("Read-only file system"))

        val decoded = original.propagate()

        PermissionErrorClassifier.classify(decoded) shouldBe PathPermissionDeniedException.Reason.READONLY_FILESYSTEM
    }

    @Test
    fun `stack frames keep all four fields`() {
        val original = IOException("frames").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.host.Native", "nativeCall", null, -2),
                StackTraceElement("com.host.Hidden", "hiddenCall", null, -1),
                StackTraceElement("com.host.Normal", "normalCall", "Normal.kt", 42),
            )
        }

        val decoded = original.propagate()

        decoded.stackTrace.take(3) shouldBe original.stackTrace.toList()
    }

    @Test
    fun `bounds - a cause cycle terminates`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        val decoded = first.propagate()

        decoded.message shouldBe "first"
        // The walk stops when it revisits `first`, so only `second` is carried
        generateSequence(decoded.cause) { it.cause }.count() shouldBe 1
    }

    @Test
    fun `bounds - oversized message is truncated`() {
        val original = IOException("x".repeat(10_000))

        original.propagate().message!!.length shouldBe 4000
    }

    @Test
    fun `bounds - oversized stack is truncated`() {
        val original = IOException("deep").apply {
            stackTrace = Array(500) { StackTraceElement("com.host.Deep", "call$it", "Deep.kt", it) }
        }

        val decoded = original.propagate()

        decoded.stackTrace.count { it.className == "com.host.Deep" } shouldBe 100
    }

    @Test
    fun `carrier survives a Parcel round-trip`() {
        val original = ReadException("Can't read from path.", filePath)
        val carrier = original.wrapToPropagate()
        val parcel = Parcel.obtain()

        try {
            parcel.writeException(carrier)
            parcel.setDataPosition(0)

            val received = shouldThrow<UnsupportedOperationException> { parcel.readException() }
            received.message shouldBe carrier.message
            received.refineException().shouldBeTypeOf<ReadException>().message shouldBe original.message
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `decoded ReadException renders its localized error`() {
        val original = ReadException("Can't read from path.", filePath, IOException("Read-only file system"))

        val decoded = original.propagate().shouldBeTypeOf<ReadException>()
        val text = decoded.getLocalizedError(LocalizedErrorContext()).description.get(app)

        text shouldContain "Can't access /storage/emulated/0/report.txt"
        text shouldContain "Read-only file system"
    }
}
