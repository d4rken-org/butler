package eu.darken.butler.main.core.external

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ExternalImportSweeperTest : BaseTest() {

    private lateinit var baseDir: File

    private val importer = mockk<ExternalContentImporter>()
    private val workspaceRepo = mockk<WorkspaceRepo>()
    private val sessionManager = mockk<WorkspaceSessionManager>()
    private val operationsManager = mockk<OperationsManager>()
    private val clipboardRepo = mockk<ClipboardRepo>()

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    @Before
    fun setup() {
        baseDir = Files.createTempDirectory("external_open").toFile()
        every { importer.baseDir } returns baseDir
        every { importer.inFlight } returns emptySet()
        every { workspaceRepo.peekAll() } returns emptyList()
        every { workspaceRepo.peekPendingCreateArguments() } returns emptyList()
        every { operationsManager.operations } returns flowOf(emptyList())
        every { clipboardRepo.state } returns flowOf(ClipboardRepo.State())
        every { sessionManager.state } returns MutableStateFlow(WorkspaceSessionManager.State.Restoring)
    }

    private fun create(
        factoryMap: Map<Workspace.Type, WorkspaceFactory<*>> = emptyMap(),
    ) = ExternalImportSweeper(
        appScope = mockk<CoroutineScope>(),
        dispatcherProvider = TestDispatcherProvider(),
        importer = importer,
        workspaceRepo = workspaceRepo,
        sessionManager = sessionManager,
        operationsManager = operationsManager,
        clipboardRepo = clipboardRepo,
        factoryMap = factoryMap,
        json = Json,
        clock = clock,
    )

    /**
     * An import, by default old enough that the grace window no longer protects it. The payload is
     * backdated too: the sweeper ages an import by the newest stamp in its tree, not by the
     * directory's own.
     */
    private fun importDir(name: String, age: kotlin.time.Duration = 10.minutes): File {
        val stamp = now.toEpochMilliseconds() - age.inWholeMilliseconds
        return File(baseDir, name).apply {
            mkdirs()
            File(this, "payload.bin").apply {
                writeText("content")
                setLastModified(stamp)
            }
            setLastModified(stamp)
        }
    }

    /** A workspace [workspaceRepo] reports from peekAll(), of the given type. */
    private fun fakeWorkspace(
        type: Workspace.Type = Workspace.Type.VIEWER,
    ): Workspace<Workspace.Arguments> {
        val info = Workspace.Info(id = Workspace.Id(), type = type, title = "Fake".toCaString())
        return mockk<Workspace<Workspace.Arguments>>().also {
            every { it.info } returns MutableStateFlow(info)
        }
    }

    /** Makes [workspaceRepo] report one workspace whose arguments serialize to [serialized]. */
    private fun withWorkspace(serialized: String): Map<Workspace.Type, WorkspaceFactory<*>> {
        val workspace = fakeWorkspace()
        coEvery { workspace.createArguments() } returns mockk()
        every { workspaceRepo.peekAll() } returns listOf(workspace)

        val factory = mockk<WorkspaceFactory<Workspace.Arguments>>()
        every { factory.serialize(any(), any()) } returns JsonPrimitive(serialized)
        return mapOf(Workspace.Type.VIEWER to factory)
    }

    @Test
    fun `an import nothing references is deleted`() = runTest {
        val orphan = importDir("11111111-1111-1111-1111-111111111111")

        create().sweep() shouldBe 1

        orphan.exists() shouldBe false
    }

    @Test
    fun `an import a workspace still points at survives`() = runTest {
        val held = importDir("22222222-2222-2222-2222-222222222222")
        val orphan = importDir("33333333-3333-3333-3333-333333333333")
        val factories = withWorkspace("""{"filePath":"$baseDir/${held.name}/payload.bin"}""")

        create(factories).sweep() shouldBe 1

        held.exists() shouldBe true
        orphan.exists() shouldBe false
    }

    @Test
    fun `an import younger than the grace window survives`() = runTest {
        val fresh = importDir("44444444-4444-4444-4444-444444444444", age = 5.seconds)

        create().sweep() shouldBe 0

        fresh.exists() shouldBe true
    }

    @Test
    fun `an import still being written survives however old it looks`() = runTest {
        // A multi-GB copy runs for minutes: the directory is stamped when the copy STARTS, so by
        // mtime it looks sweepable long before the file is finished and handed to a workspace.
        val copying = importDir("99999999-9999-9999-9999-999999999999", age = 30.minutes)
        every { importer.inFlight } returns setOf(copying.name)

        create().sweep() shouldBe 0

        copying.exists() shouldBe true
    }

    @Test
    fun `an import held by a create parked behind the tab limit survives`() = runTest {
        // At the free-tier limit the copy is already written but the create is parked behind the
        // "close a tab first" dialog, which can sit open indefinitely. Nothing else names the file
        // in that window, so without this the user resolves the dialog onto a deleted file.
        val parked = importDir("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val arguments = mockk<Workspace.Arguments>()
        every { arguments.type } returns Workspace.Type.VIEWER
        every { workspaceRepo.peekPendingCreateArguments() } returns listOf(arguments)
        val factory = mockk<WorkspaceFactory<Workspace.Arguments>>()
        every { factory.serialize(any(), any()) } returns
            JsonPrimitive("""{"filePath":"$baseDir/${parked.name}/payload.bin"}""")

        create(mapOf(Workspace.Type.VIEWER to factory)).sweep() shouldBe 0

        parked.exists() shouldBe true
    }

    @Test
    fun `an import an operation is working on survives`() = runTest {
        val busy = importDir("55555555-5555-5555-5555-555555555555")
        val path = mockk<APath<*>>()
        every { path.path } returns "$baseDir/${busy.name}/payload.bin"
        val metadata = mockk<Operation.Metadata>()
        every { metadata.intendedPaths } returns listOf(path)
        val managed = mockk<ManagedOperation>()
        every { managed.operation } returns mockk<Operation>().also { every { it.metadata } returns metadata }
        every { operationsManager.operations } returns flowOf(listOf(managed))

        create().sweep() shouldBe 0

        busy.exists() shouldBe true
    }

    @Test
    fun `an import sitting on the clipboard survives`() = runTest {
        val clipped = importDir("66666666-6666-6666-6666-666666666666")
        val lookup = mockk<APathLookup<*>>()
        every { lookup.path } returns "$baseDir/${clipped.name}/payload.bin"
        val clip = mockk<ClipboardClip.Paths>()
        every { clip.paths } returns listOf(lookup)
        every { clipboardRepo.state } returns flowOf(ClipboardRepo.State(entries = listOf(clip)))

        create().sweep() shouldBe 0

        clipped.exists() shouldBe true
    }

    @Test
    fun `nothing is deleted when a workspace cannot be asked what it holds`() = runTest {
        val orphan = importDir("77777777-7777-7777-7777-777777777777")
        val workspace = fakeWorkspace()
        coEvery { workspace.createArguments() } throws IllegalStateException("nope")
        every { workspaceRepo.peekAll() } returns listOf(workspace)

        create(mapOf(Workspace.Type.VIEWER to mockk())).sweep() shouldBe 0

        orphan.exists() shouldBe true
    }

    @Test
    fun `a missing factory stops the sweep rather than deleting blind`() = runTest {
        val orphan = importDir("88888888-8888-8888-8888-888888888888")
        every { workspaceRepo.peekAll() } returns listOf(fakeWorkspace())

        create(emptyMap()).sweep() shouldBe 0

        orphan.exists() shouldBe true
    }

    @Test
    fun `a second workspace of an unknown type stops the sweep even if the first was readable`() = runTest {
        // The dangerous shape: enumerate holders, fail to read one, delete on the rest. Anything the
        // unreadable holder pointed at would go with it.
        val orphan = importDir("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val readable = fakeWorkspace(Workspace.Type.VIEWER)
        coEvery { readable.createArguments() } returns mockk()
        every { workspaceRepo.peekAll() } returns listOf(readable, fakeWorkspace(Workspace.Type.EDITOR))
        val factory = mockk<WorkspaceFactory<Workspace.Arguments>>()
        every { factory.serialize(any(), any()) } returns JsonPrimitive("{}")

        create(mapOf(Workspace.Type.VIEWER to factory)).sweep() shouldBe 0

        orphan.exists() shouldBe true
    }
}
