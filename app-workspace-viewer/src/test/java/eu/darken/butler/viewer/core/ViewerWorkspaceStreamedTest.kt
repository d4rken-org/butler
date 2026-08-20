package eu.darken.butler.viewer.core

import android.os.ParcelFileDescriptor
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The viewer showing content another app streamed to it.
 *
 * Robolectric, unlike its sibling [ViewerWorkspaceClassificationTest], purely because the arguments
 * carry a `content://` URI and `Uri.parse` is not available on a plain JVM. The stored-path tests
 * stay on the JVM so they keep running at JVM speed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerWorkspaceStreamedTest : BaseTest() {

    private val gatewaySwitch = mockk<GatewaySwitch>(relaxed = true)
    private val imageProbe = mockk<ImageProbe>()
    private val apkArchiveParser = mockk<ApkArchiveParser>(relaxed = true)
    private val pkgRepo = mockk<PkgRepo>(relaxed = true)
    private val userManager2 = mockk<UserManager2>(relaxed = true)
    private val pdfPreviewLoader = mockk<PdfPreviewLoader>(relaxed = true)

    private fun arguments(
        displayName: String = "holiday.jpg",
        mimeType: String = "image/jpeg",
        sizeBytes: Long? = 2_411_200L,
        caption: String? = null,
    ) = ViewerArguments.Streamed(
        uriString = "content://com.example.files/document/42",
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        arrivalId = "arrival-1",
        caption = caption,
    )

    private fun seekableDescriptor(size: Long = 2_411_200L) = mockk<ParcelFileDescriptor>(relaxed = true).apply {
        every { statSize } returns size
    }

    /**
     * A reader that serves content: [bytes] worth of stream, plus a seekable descriptor unless
     * [seekable] says the provider only offers a pipe.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readableReader(
        bytes: ByteArray = ByteArray(64),
        seekable: Boolean = true,
    ) = mockk<ViewerContentReader>(relaxed = true).apply {
        coEvery { readInput(any(), any<suspend (java.io.InputStream) -> Any?>()) } coAnswers {
            val block = secondArg<suspend (java.io.InputStream) -> Any?>()
            java.io.ByteArrayInputStream(bytes).use { block(it) }
        }
        coEvery { openReadPfd(any()) } returns if (seekable) seekableDescriptor() else null
    }

    private fun workspace(
        arguments: ViewerArguments.Streamed = arguments(),
        contentReader: ViewerContentReader = readableReader(),
    ) = ViewerWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        dispatcherProvider = TestDispatcherProvider(),
        gatewaySwitch = gatewaySwitch,
        imageProbe = imageProbe,
        contentReader = contentReader,
        apkArchiveParser = apkArchiveParser,
        pkgRepo = pkgRepo,
        userManager2 = userManager2,
        pdfPreviewLoader = pdfPreviewLoader,
        operationsManager = mockk(relaxed = true),
        issueHandler = mockk(relaxed = true),
        deleteOperationFactory = mockk(relaxed = true),
    )

    @Test
    fun `streamed content is classified by its declared type, not its name`() = runTest {
        // No extension at all. The old name-based classification would have called this an
        // octet-stream and refused to render it, which is why a copy used to be made.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")

        val state = workspace(arguments(displayName = "IMG_4821")).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>()
    }

    @Test
    fun `streamed content never asks the gateway to look anything up`() = runTest {
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")

        workspace().state.first()

        coVerify(exactly = 0) { gatewaySwitch.lookup(any(), any()) }
    }

    @Test
    fun `streamed content is never probed for external changes`() = runTest {
        // There is no path to re-look-up, and the sending app's grant is the only thing that could
        // change - which a metadata probe cannot see.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
        val workspace = workspace()
        workspace.state.first()

        workspace.checkExternalChange()

        workspace.state.value.externalChange.shouldBeNull()
        coVerify(exactly = 0) { gatewaySwitch.lookup(any(), any()) }
    }

    @Test
    fun `a streamed workspace refuses to be persisted or paused`() = runTest {
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
        val workspace = workspace()

        // The SETTLED emission, not .value: Info is hand-built in a map block that replaces the
        // seed, so a flag set only on the seed would read correct here and revert in practice.
        val info = workspace.info.first { it.lifecycleState is Workspace.LifecycleState.Ready }

        info.isPersistable shouldBe false
        info.isPausable shouldBe false
        info.contentPath.shouldBeNull()
    }

    @Test
    fun `content that reads as empty says empty`() = runTest {
        val state = workspace(contentReader = readableReader(bytes = ByteArray(0))).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerEmptyFileException>()
    }

    @Test
    fun `a stale size of zero does not reject content that actually has bytes`() = runTest {
        // Cloud providers report a placeholder size for a file they have not downloaded yet.
        // Trusting it over a real read would refuse a file that reads back perfectly well.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")

        val state = workspace(arguments(sizeBytes = 0L)).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>()
    }

    @Test
    fun `a pdf from a provider that cannot seek is unreadable, not unsupported`() = runTest {
        // PdfRenderer has to seek. Without this the document renders blank pages and reads as
        // corrupt rather than as a source that cannot be used this way.
        val state = workspace(
            arguments(displayName = "invoice", mimeType = "application/pdf"),
            readableReader(seekable = false),
        ).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerContentUnreadableException>()
    }

    @Test
    fun `an image from a provider that cannot seek still renders`() = runTest {
        // The image path reads streams end to end - the probe, telephoto's content-URI source and
        // Coil each open their own - so a pipe-backed provider is no obstacle to it. Demanding a
        // descriptor for every source would have rejected this.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")

        val state = workspace(contentReader = readableReader(seekable = false)).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>()
    }

    @Test
    fun `a grant that lapsed is reported as unreadable`() = runTest {
        val reader = mockk<ViewerContentReader>(relaxed = true)
        coEvery { reader.readInput(any(), any<suspend (java.io.InputStream) -> Any?>()) } throws
            SecurityException("grant revoked")

        val state = workspace(contentReader = reader).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerContentUnreadableException>()
    }

    /**
     * Archives are classified by name before any type-driven branch runs. Senders label them with
     * whatever they like, and reaching the image probe or the PDF seek check with a container would
     * report it as damaged or unreadable instead of as something to browse.
     */
    @Test
    fun `a streamed archive resolves to an Archive that needs a copy`() = runTest {
        val content = workspace(arguments(displayName = "backup.zip", mimeType = "application/zip"))
            .state.first().content.shouldBeInstanceOf<ViewerContent.Archive>()

        content.format shouldBe ArchiveFormat.ZIP
        content.access shouldBe ViewerContent.Archive.Access.NEEDS_COPY
    }

    @Test
    fun `a generically typed archive is still an archive`() = runTest {
        workspace(arguments(displayName = "backup.tar.gz", mimeType = "application/octet-stream"))
            .state.first().content.shouldBeInstanceOf<ViewerContent.Archive>()
            .format shouldBe ArchiveFormat.TAR_GZ
    }

    @Test
    fun `an archive the sender declared as an image never reaches the image probe`() = runTest {
        coEvery { imageProbe.probe(any()) } returns ProbeResult.NoRasterDimensions

        workspace(arguments(displayName = "backup.zip", mimeType = "image/png"))
            .state.first().content.shouldBeInstanceOf<ViewerContent.Archive>()
    }

    @Test
    fun `an archive the sender declared as a pdf is not held to the seek check`() = runTest {
        // The seekability question belongs to the PDF branch, after classification: asking it up
        // front rejected a container from a pipe-backed provider before it was ever recognized.
        workspace(
            arguments(displayName = "backup.zip", mimeType = "application/pdf"),
            readableReader(seekable = false),
        ).state.first().content.shouldBeInstanceOf<ViewerContent.Archive>()
    }

    @Test
    fun `streamed content that cannot be read is still rejected before classification`() = runTest {
        val reader = mockk<ViewerContentReader>(relaxed = true)
        coEvery { reader.readInput(any(), any<suspend (java.io.InputStream) -> Any?>()) } throws
            SecurityException("grant revoked")

        workspace(arguments(displayName = "backup.zip", mimeType = "application/zip"), reader)
            .state.first().content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerContentUnreadableException>()
    }

    /** A share carrying a file and text opens the file, so the text has to show up next to it. */
    @Test
    fun `a shared message reaches the info card`() = runTest {
        val state = workspace(
            arguments(displayName = "backup.zip", mimeType = "application/zip", caption = "look at this"),
        ).state.first()

        state.fileInfo?.sharedCaption shouldBe "look at this"
    }

    @Test
    fun `content shared without a message has no caption`() = runTest {
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")

        workspace().state.first().fileInfo?.sharedCaption shouldBe null
    }

    @Test
    fun `a streamed pdf is rendered without a copy`() = runTest {
        coEvery { pdfPreviewLoader.pageCount(any()) } returns 3

        val state = workspace(arguments(displayName = "invoice", mimeType = "application/pdf")).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.PdfPreview>().pageCount shouldBe 3
    }
}
