package eu.darken.butler.main.core.external

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.cache.CacheRepo
import eu.darken.butler.common.files.MimeInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalContentImporterTest : BaseTest() {

    private lateinit var application: Application
    private val cacheRepo = mockk<CacheRepo>()

    private val uri: Uri = Uri.parse("content://com.example.files/document/42")

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        coEvery { cacheRepo.canSpare(any()) } returns true
    }

    private fun create() = ExternalContentImporter(
        context = application,
        dispatcherProvider = TestDispatcherProvider(),
        cacheRepo = cacheRepo,
    )

    private val importDir: File
        get() = File(application.cacheDir, "external_open")

    private fun register(uri: Uri, stream: () -> InputStream) {
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri, stream)
    }

    private fun failingStream(bytesBeforeFailure: Int, error: () -> Throwable) = object : InputStream() {
        private var served = 0

        override fun read(): Int {
            if (served >= bytesBeforeFailure) throw error()
            served++
            return 'a'.code
        }
    }

    @Test
    fun `content is copied into the cache under its display name`() = runTest {
        register(uri) { ByteArrayInputStream("Hello World".toByteArray()) }

        val imported = create().importToCache(uri, "notes.txt", MimeInfo("text/plain")).shouldNotBeNull()

        imported.file.name shouldBe "notes.txt"
        imported.file.readText() shouldBe "Hello World"
        imported.file.parentFile!!.parentFile shouldBe importDir
    }

    @Test
    fun `path separators and null chars are stripped from the display name`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "../etc/pass\\wd\u0000 .txt", null).shouldNotBeNull()

        imported.file.name shouldBe "..etcpasswd .txt"
    }

    @Test
    fun `a blank display name falls back`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        create().importToCache(uri, "   ", null).shouldNotBeNull().file.name shouldBe "file"
    }

    @Test
    fun `an extensionless image gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "holiday", MimeInfo("image/png")).shouldNotBeNull()

        imported.file.name shouldBe "holiday.png"
    }

    @Test
    fun `an extensionless text file gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "transcript", MimeInfo("text/plain")).shouldNotBeNull()

        imported.file.name shouldBe "transcript.txt"
    }

    /** MimeInfo knows the extensionless text names, so those already reach the right renderer. */
    @Test
    fun `a text file named readme keeps its name`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "readme", MimeInfo("text/plain")).shouldNotBeNull()

        imported.file.name shouldBe "readme"
    }

    /**
     * Every other viewable type names one specific format, but senders declare text as anything from
     * text/x-log to text/x-vendor-format. Without a generic fallback the copy keeps a name the
     * viewer then classifies as an unsupported blob.
     */
    @Test
    fun `an unrecognized text subtype still lands on a text name`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create()
            .importToCache(uri, "payload.data", MimeInfo("text/x-vendor-format"))
            .shouldNotBeNull()

        imported.file.name shouldBe "payload.data.txt"
    }

    @Test
    fun `an extensionless pdf gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "invoice", MimeInfo("application/pdf")).shouldNotBeNull()

        imported.file.name shouldBe "invoice.pdf"
    }

    @Test
    fun `an extensionless apk gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "download", MimeInfo(MimeInfo.MIME_APK)).shouldNotBeNull()

        imported.file.name shouldBe "download.apk"
    }

    @Test
    fun `an apk named like an archive still gets an apk extension`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "bundle.zip", MimeInfo(MimeInfo.MIME_APK)).shouldNotBeNull()

        imported.file.name shouldBe "bundle.zip.apk"
    }

    @Test
    fun `an apk that already has a matching name is left alone`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "app.apk", MimeInfo(MimeInfo.MIME_APK)).shouldNotBeNull()

        imported.file.name shouldBe "app.apk"
    }

    @Test
    fun `an image that already has a matching name is left alone`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "holiday.jpg", MimeInfo("image/jpeg")).shouldNotBeNull()

        imported.file.name shouldBe "holiday.jpg"
    }

    @Test
    fun `a pdf named like an image still gets a pdf extension`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "invoice.jpg", MimeInfo("application/pdf")).shouldNotBeNull()

        imported.file.name shouldBe "invoice.jpg.pdf"
    }

    @Test
    fun `an image named like a pdf still gets an image extension`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "scan.pdf", MimeInfo("image/png")).shouldNotBeNull()

        imported.file.name shouldBe "scan.pdf.png"
    }

    @Test
    fun `a failure mid-stream leaves nothing behind`() = runTest {
        register(uri) { failingStream(8) { IOException("Connection lost") } }

        create().importToCache(uri, "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `cancellation is rethrown and leaves nothing behind`() = runTest {
        register(uri) { failingStream(8) { CancellationException("Nevermind") } }

        shouldThrow<CancellationException> {
            create().importToCache(uri, "notes.txt", null)
        }

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an import is refused when the cache has no room`() = runTest {
        val source = File(application.cacheDir, "source.txt").apply { writeText("Hello World") }
        coEvery { cacheRepo.canSpare(any()) } returns false

        create().importToCache(Uri.fromFile(source), "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an import is refused when the cache has no room and the size is unknown`() = runTest {
        register(uri) { ByteArrayInputStream("Hello World".toByteArray()) }
        coEvery { cacheRepo.canSpare(any()) } returns false

        create().importToCache(uri, "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `importing leaves earlier imports alone`() = runTest {
        // Deleting is ExternalImportSweeper's job now: this class cannot see who still holds one,
        // and age alone would take the file a week-old restored session still points at.
        val existing = File(importDir, "existing").apply { mkdirs() }
        val existingFile = File(existing, "old.txt").apply { writeText("old") }
        existing.setLastModified(System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L)
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        create().importToCache(uri, "notes.txt", null).shouldNotBeNull()

        existing.exists() shouldBe true
        existingFile.exists() shouldBe true
    }
}
