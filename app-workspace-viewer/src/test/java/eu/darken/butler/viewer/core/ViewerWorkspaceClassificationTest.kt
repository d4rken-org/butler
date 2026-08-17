package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * A restored session can point at a path that has since become a directory, a dangling symlink or a
 * truncated file. None of those may end up as a blank image canvas.
 */
class ViewerWorkspaceClassificationTest : BaseTest() {

    private val imagePath = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val imageProbe = mockk<ImageProbe>()
    private val pdfPreviewLoader = mockk<PdfPreviewLoader>()

    private fun lookup(
        path: LocalPath,
        fileType: FileType = FileType.FILE,
        size: Long? = 1024L,
        target: LocalPath? = null,
    ) = LocalPathLookup(
        lookedUp = path,
        fileType = fileType,
        size = size,
        modifiedAt = null,
        target = target,
    )

    @Suppress("UNCHECKED_CAST")
    private fun setupGateway(vararg lookups: Pair<String, Any>) {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        val byPath = lookups.toMap()
        coEvery { gatewaySwitch.lookup(any(), any()) } coAnswers {
            when (val result = byPath[firstArg<APath<*>>().path]) {
                is Throwable -> throw result
                null -> throw ReadException("No stub for ${firstArg<APath<*>>().path}", firstArg<APath<*>>())
                else -> result as APathLookup<APath<*>>
            }
        }
        coEvery { gatewaySwitch.exists(any()) } returns true
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
    }

    private fun workspace(path: LocalPath = imagePath) = ViewerWorkspace(
        id = Workspace.Id(),
        creationArguments = ViewerArguments.Default(filePath = path),
        dispatcherProvider = TestDispatcherProvider(),
        gatewaySwitch = gatewaySwitch,
        imageProbe = imageProbe,
        pdfPreviewLoader = pdfPreviewLoader,
    )

    @Test
    fun `a readable image resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>().mime.rawType shouldBe "image/jpeg"
        state.fileInfo?.imageInfo?.width shouldBe 4032
        state.fileInfo?.imageInfo?.height shouldBe 3024
    }

    @Test
    fun `a directory never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath, fileType = FileType.DIRECTORY))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerNotAFileException>()
    }

    @Test
    fun `a missing file never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to ReadException("Does not exist", imagePath))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
    }

    @Test
    fun `a file deleted behind the viewer reports as gone, not as access denied`() = runTest2 {
        // The gateway maps a vanished file to a permission error, which would send the user
        // looking for an access problem that does not exist.
        setupGateway(imagePath.path to PathPermissionDeniedException(imagePath, "lookup", Reason.NO_MECHANISM))
        coEvery { gatewaySwitch.exists(any()) } returns false

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerFileGoneException>()
    }

    @Test
    fun `a file that is there but unreadable keeps the original error`() = runTest2 {
        val denied = PathPermissionDeniedException(imagePath, "lookup", Reason.NO_MECHANISM)
        setupGateway(imagePath.path to denied)
        coEvery { gatewaySwitch.exists(any()) } returns true

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>().error shouldBe denied
    }

    @Test
    fun `a failing existence check keeps the original error`() = runTest2 {
        val denied = PathPermissionDeniedException(imagePath, "lookup", Reason.NO_MECHANISM)
        setupGateway(imagePath.path to denied)
        coEvery { gatewaySwitch.exists(any()) } throws ReadException("gateway gave up", imagePath)

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>().error shouldBe denied
    }

    @Test
    fun `a zero-byte file never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath, size = 0L))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerEmptyFileException>()
    }

    @Test
    fun `a symlink with an unreadable target never resolves to Image`() = runTest2 {
        val target = LocalPath.build("/storage/emulated/0/DCIM/gone.jpg")
        setupGateway(
            imagePath.path to lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = target),
            target.path to ReadException("Does not exist", target),
        )

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerBrokenSymlinkException>()
    }

    @Test
    fun `a symlink without a target never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = null))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerBrokenSymlinkException>()
    }

    @Test
    fun `a symlink to a real image resolves to Image`() = runTest2 {
        val target = LocalPath.build("/storage/emulated/0/DCIM/real.jpg")
        setupGateway(
            imagePath.path to lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = target),
            target.path to lookup(target),
        )

        workspace().state.first().content.shouldBeInstanceOf<ViewerContent.Image>()
    }

    @Test
    fun `a non-image file resolves to Unsupported`() = runTest2 {
        val archive = LocalPath.build("/storage/emulated/0/Download/backup.zip")
        setupGateway(archive.path to lookup(archive))

        workspace(archive).state.first()
            .content.shouldBeInstanceOf<ViewerContent.Unsupported>()
            .mime.rawType shouldBe "application/zip"
    }

    @Test
    fun `a renderable pdf resolves to PdfPreview`() = runTest2 {
        val pdf = LocalPath.build("/storage/emulated/0/Download/manual.pdf")
        setupGateway(pdf.path to lookup(pdf))
        coEvery { pdfPreviewLoader.pageCount(any()) } returns 3

        val content = workspace(pdf).state.first().content.shouldBeInstanceOf<ViewerContent.PdfPreview>()

        content.pageCount shouldBe 3
        content.mime.rawType shouldBe "application/pdf"
    }

    @Test
    fun `a pdf that cannot be rendered resolves to Unsupported`() = runTest2 {
        // Encrypted, corrupt, or on a path with no seekable descriptor - the placeholder still
        // offers "Open with", where a blank canvas would offer nothing.
        val pdf = LocalPath.build("/storage/emulated/0/Download/locked.pdf")
        setupGateway(pdf.path to lookup(pdf))
        coEvery { pdfPreviewLoader.pageCount(any()) } returns null

        workspace(pdf).state.first()
            .content.shouldBeInstanceOf<ViewerContent.Unsupported>()
            .mime.rawType shouldBe "application/pdf"
    }

    @Test
    fun `an unreadable stream never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath))
        val probeError = IllegalStateException("stream died")
        coEvery { imageProbe.probe(any()) } returns ProbeResult.ProbeFailed(probeError)

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>().error shouldBe probeError
    }

    @Test
    fun `a raster file the decoder cannot read never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath))
        // A corrupt or truncated JPEG: BitmapFactory reports no dimensions at all.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.NoRasterDimensions

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerUndecodableImageException>()
    }

    @Test
    fun `a truncated raster never resolves to Image`() = runTest2 {
        setupGateway(imagePath.path to lookup(imagePath))
        // Header intact, body cut short: the probe's structure check catches what the header check
        // cannot, and the file must not be announced as a viewable image.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.ProbeFailed(ViewerUndecodableImageException(imagePath))

        val state = workspace().state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerUndecodableImageException>()
    }

    @Test
    fun `an animated gif still resolves to Image`() = runTest2 {
        val gif = LocalPath.build("/storage/emulated/0/Download/dancing.gif")
        setupGateway(gif.path to lookup(gif))
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(320, 240, "image/gif")

        val state = workspace(gif).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>().mime.rawType shouldBe "image/gif"
        state.fileInfo?.imageInfo?.format shouldBe "image/gif"
    }

    @Test
    fun `a vector image without raster dimensions still resolves to Image`() = runTest2 {
        val svg = LocalPath.build("/storage/emulated/0/Download/diagram.svg")
        setupGateway(svg.path to lookup(svg))
        // Expected for SVG, and must not be confused with the corrupt-raster case above.
        coEvery { imageProbe.probe(any()) } returns ProbeResult.NoRasterDimensions

        val state = workspace(svg).state.first()

        state.content.shouldBeInstanceOf<ViewerContent.Image>().mime.rawType shouldBe "image/svg+xml"
        state.fileInfo?.imageInfo?.format shouldBe "image/svg+xml"
        state.fileInfo?.imageInfo?.width shouldBe null
        state.fileInfo?.imageInfo?.height shouldBe null
    }
}
