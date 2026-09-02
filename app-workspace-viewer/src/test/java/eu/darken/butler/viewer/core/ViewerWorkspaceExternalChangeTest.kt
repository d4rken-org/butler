package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The metadata probe behind the viewer's "this file changed" notice.
 *
 * The flag only ever escalates, so most of these cases are about what must *not* clear or downgrade
 * it: a restored baseline, an unreadable file, or a verdict from a probe that outlived its load.
 */
class ViewerWorkspaceExternalChangeTest : BaseTest() {

    private val imagePath = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val imageProbe = mockk<ImageProbe>()
    private val apkArchiveParser = mockk<ApkArchiveParser>(relaxed = true)
    private val pkgRepo = mockk<PkgRepo>(relaxed = true)
    private val userManager2 = mockk<UserManager2>(relaxed = true)
    private val pdfPreviewLoader = mockk<PdfPreviewLoader>(relaxed = true)

    /** What the gateway finds per path right now: a lookup to return, or a throwable to throw. */
    private val disk = mutableMapOf<String, Any>()
    private var exists: (String) -> Existence = { Existence.PRESENT }

    /** One-shot: holds the next lookup of that path in flight, so a test can interleave around it. */
    private var lookupGate: Pair<String, CompletableDeferred<Unit>>? = null

    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @BeforeEach
    @Suppress("UNCHECKED_CAST")
    fun setup() {
        disk.clear()
        exists = { Existence.PRESENT }
        lookupGate = null
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.lookup(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>()
            lookupGate?.takeIf { it.first == path.path }?.let { gate ->
                lookupGate = null
                gate.second.await()
            }
            when (val result = disk[path.path]) {
                is Throwable -> throw result
                null -> throw ReadException("No stub for ${path.path}", path)
                else -> result as APathLookup<APath<*>>
            }
        }
        coEvery { gatewaySwitch.existsStrict(any()) } answers { exists(firstArg<APath<*>>().path) }
        coEvery { imageProbe.probe(any()) } returns ProbeResult.Probed(4032, 3024, "image/jpeg")
    }

    private fun lookup(
        path: LocalPath,
        fileType: FileType = FileType.FILE,
        size: Long? = 1024L,
        modifiedAt: Instant? = baseTime,
        target: LocalPath? = null,
    ) = LocalPathLookup(
        lookedUp = path,
        fileType = fileType,
        size = size,
        modifiedAt = modifiedAt,
        target = target,
    )

    private fun workspace(path: LocalPath = imagePath) = ViewerWorkspace(
        id = Workspace.Id(),
        creationArguments = ViewerArguments.Default(filePath = path),
        dispatcherProvider = TestDispatcherProvider(),
        gatewaySwitch = gatewaySwitch,
        imageProbe = imageProbe,
        contentReader = readerFor(gatewaySwitch),
        apkArchiveParser = apkArchiveParser,
        appInstallInspector = mockk(relaxed = true),
        pkgRepo = pkgRepo,
        userManager2 = userManager2,
        pdfPreviewLoader = pdfPreviewLoader,
        textPreviewLoader = mockk(relaxed = true),
        operationsManager = mockk(relaxed = true),
        issueHandler = mockk(relaxed = true),
        deleteOperationFactory = mockk(relaxed = true),
    )

    /** A loaded image workspace, i.e. one that has a baseline to compare against. */
    private fun loadedWorkspace(): ViewerWorkspace {
        disk[imagePath.path] = lookup(imagePath)
        return workspace().also { it.state.value.contentLookup!!.size shouldBe 1024L }
    }

    @Test
    fun `unchanged metadata leaves the flag clear`() = runTest2 {
        val ws = loadedWorkspace()

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `a file that grew is reported as Modified`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = lookup(imagePath, size = 4096L)

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `a rewritten file of the same size is reported as Modified`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = lookup(imagePath, modifiedAt = baseTime + 1.minutes)

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `a gateway without a usable mtime reports nothing on its own`() = runTest2 {
        // SAF and the root/ADB gateways answer a coarse or absent mtime, so a same-size in-place
        // edit is undetectable there. Guessing from a one-sided null would be a false positive.
        disk[imagePath.path] = lookup(imagePath, modifiedAt = null)
        val ws = workspace()
        disk[imagePath.path] = lookup(imagePath, modifiedAt = baseTime)

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null

        disk[imagePath.path] = lookup(imagePath, modifiedAt = null)
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `a deleted file is reported as Gone`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = ReadException("Does not exist", imagePath)
        exists = { Existence.ABSENT }

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `a file that cannot be reached is no evidence of anything`() = runTest2 {
        // A gateway that lost its root session fails both the lookup and the existence check. That
        // says nothing about the file, so the flag must stay exactly as it was.
        val ws = loadedWorkspace()
        disk[imagePath.path] = ReadException("gateway gave up", imagePath)
        exists = { throw ReadException("gateway gave up", imagePath) }

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `a file that cannot be verified is no evidence of anything`() = runTest2 {
        // A denied stat, a dead provider or an unreachable host answer UNKNOWN. The lookup failed,
        // but nothing here says the file is gone.
        val ws = loadedWorkspace()
        disk[imagePath.path] = ReadException("gateway gave up", imagePath)
        exists = { Existence.UNKNOWN }

        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `a restored baseline keeps the Modified flag`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        ws.checkExternalChange()
        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified

        // Even byte-identical metadata is not proof the bytes came back: only a reload is.
        disk[imagePath.path] = lookup(imagePath)
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `Modified upgrades to Gone`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        ws.checkExternalChange()
        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified

        disk[imagePath.path] = ReadException("Does not exist", imagePath)
        exists = { Existence.ABSENT }
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `Gone never returns to Modified`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = ReadException("Does not exist", imagePath)
        exists = { Existence.ABSENT }
        ws.checkExternalChange()
        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone

        // A file recreated under the same name is a different file, and nothing here reloaded it.
        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        exists = { Existence.PRESENT }
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `a probe suspended across a reload does not land its verdict`() = runTest2 {
        val ws = loadedWorkspace()
        val gate = CompletableDeferred<Unit>()
        lookupGate = imagePath.path to gate

        val probe = launch(Dispatchers.Unconfined) { ws.checkExternalChange() }

        // The reload picks up the new file and becomes the baseline; the in-flight probe is still
        // holding a verdict about the one before it.
        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        ws.reload()
        ws.state.value.contentLookup!!.size shouldBe 4096L

        gate.complete(Unit)
        probe.join()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `overlapping probes leave the newer verdict`() = runTest2 {
        val ws = loadedWorkspace()
        val gate = CompletableDeferred<Unit>()
        lookupGate = imagePath.path to gate

        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        val first = launch(Dispatchers.Unconfined) { ws.checkExternalChange() }

        // Queued behind the first one, which is still parked in its lookup.
        disk[imagePath.path] = ReadException("Does not exist", imagePath)
        exists = { Existence.ABSENT }
        val second = launch(Dispatchers.Unconfined) { ws.checkExternalChange() }

        gate.complete(Unit)
        first.join()
        second.join()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `a symlink is probed against its target, not the link`() = runTest2 {
        // LocalFileSystemOps reports the link node's own size, so probing the link would never see
        // a target rewrite at all.
        val target = LocalPath.build("/storage/emulated/0/DCIM/real.jpg")
        disk[imagePath.path] = lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = target)
        disk[target.path] = lookup(target)
        val ws = workspace()
        ws.state.value.contentLookup!!.lookedUp shouldBe target

        disk[target.path] = lookup(target, size = 8192L)
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `a symlink whose target was deleted is reported as Gone`() = runTest2 {
        val target = LocalPath.build("/storage/emulated/0/DCIM/real.jpg")
        disk[imagePath.path] = lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = target)
        disk[target.path] = lookup(target)
        val ws = workspace()

        disk[target.path] = ReadException("Does not exist", target)
        exists = { if (it == target.path) Existence.ABSENT else Existence.PRESENT }
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Gone
    }

    @Test
    fun `a file that was empty at load is watched for content`() = runTest2 {
        // The download or sync that is still running is the case this whole feature is for: the file
        // is rejected as empty, but it is exactly the one that has a reason to be watched.
        disk[imagePath.path] = lookup(imagePath, size = 0L)
        val ws = workspace()
        ws.state.value.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerEmptyFileException>()
        ws.state.value.contentLookup!!.size shouldBe 0L

        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `a symlink to an empty target is watched against the target`() = runTest2 {
        val target = LocalPath.build("/storage/emulated/0/DCIM/real.jpg")
        disk[imagePath.path] = lookup(imagePath, fileType = FileType.SYMBOLIC_LINK, target = target)
        disk[target.path] = lookup(target, size = 0L)
        val ws = workspace()
        ws.state.value.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerEmptyFileException>()
        ws.state.value.contentLookup!!.lookedUp shouldBe target

        disk[target.path] = lookup(target, size = 4096L)
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified
    }

    @Test
    fun `a directory is not watched at all`() = runTest2 {
        // A directory's mtime moves whenever anything inside it changes, and "File changed" over a
        // "Not a file" placeholder names nothing the user could act on.
        disk[imagePath.path] = lookup(imagePath, fileType = FileType.DIRECTORY)
        val ws = workspace()
        ws.state.value.content.shouldBeInstanceOf<ViewerContent.Failed>()
            .error.shouldBeInstanceOf<ViewerNotAFileException>()
        ws.state.value.contentLookup shouldBe null

        disk[imagePath.path] = lookup(
            imagePath,
            fileType = FileType.DIRECTORY,
            size = 8192L,
            modifiedAt = baseTime + 1.minutes,
        )
        ws.checkExternalChange()

        ws.state.value.externalChange shouldBe null
    }

    @Test
    fun `reloading clears the flag`() = runTest2 {
        val ws = loadedWorkspace()
        disk[imagePath.path] = lookup(imagePath, size = 4096L)
        ws.checkExternalChange()
        ws.state.value.externalChange shouldBe ViewerExternalChange.Modified

        ws.reload()

        ws.state.value.externalChange shouldBe null
    }
}
