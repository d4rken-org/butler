package eu.darken.butler.editor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.EditorState as EngineState
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The live workspace must seed its [Workspace.Info] from the same derivation the dormant stand-in
 * uses, otherwise a restored tab renames itself the moment it is hydrated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorWorkspaceSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notes = LocalPath.build("/sdcard/notes.txt")

    private fun makeWorkspace(
        arguments: EditorArguments,
        engineFilePath: APath<*>? = null,
        contentSources: Flow<ContentSource> = emptyFlow(),
        engineState: EngineState = EngineState.Empty,
        onInitialize: suspend () -> Result<Unit> = { Result.success(Unit) },
    ) = EditorWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        gatewaySwitch = mockk(relaxed = true),
        editorEngineFactory = mockk<EditorEngine.Factory>(relaxed = true).apply {
            every { create(any(), any(), any(), any()) } returns mockk<EditorEngine>(relaxed = true).apply {
                coEvery { initialize() } coAnswers { onInitialize() }
                every { contentSource } returns contentSources
                every { state } returns MutableStateFlow(engineState)
                every { filePath } returns engineFilePath
            }
        },
        editorSettings = mockk(relaxed = true),
        operationsManager = mockk<OperationsManager>(relaxed = true).apply {
            every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        },
        pasteFileReader = mockk(relaxed = true),
    )

    private fun assertSeedMatchesDerivation(arguments: EditorArguments) {
        val derived = deriveEditorDisplay(arguments)
        val seed = makeWorkspace(arguments).info.value

        seed.title.get(context) shouldBe (derived.title?.get(context) ?: Workspace.Type.EDITOR.label.get(context))
        seed.subtitle?.get(context) shouldBe derived.subtitle?.get(context)
    }

    @Test
    fun `file tab seeds the derived file name and path`() {
        assertSeedMatchesDerivation(EditorArguments.Default(filePath = LocalPath.build("/sdcard/notes.txt")))
    }

    @Test
    fun `scratch tab seeds the derived suggested name`() {
        assertSeedMatchesDerivation(EditorArguments.Default(suggestedTitle = "Shopping list"))
    }

    @Test
    fun `empty tab seeds the derived untitled name`() {
        assertSeedMatchesDerivation(EditorArguments.Default())
    }

    @Test
    fun `the content source observer publishes what the engine reports`() = runBlocking {
        val workspace = makeWorkspace(
            arguments = EditorArguments.Default(),
            contentSources = MutableStateFlow(ContentSource.Memory(size = 0L, suggestedName = "Pasted text")),
        )

        // The workspace scope runs on its own real dispatcher (runBlocking, not virtual time),
        // so wait for the observer to publish
        val published = withTimeout(10.seconds) {
            workspace.info.first { it.title.get(context) == "Pasted text" }
        }

        published.subtitle shouldBe null
        workspace.release()
    }

    /**
     * Waits for [predicate], or fails the test. Real time: the workspace scope runs on its own
     * dispatcher, not the test scheduler.
     */
    private suspend fun EditorWorkspace.awaitInfo(predicate: (Workspace.Info) -> Boolean) =
        withTimeout(10.seconds) { info.first(predicate) }

    /**
     * The contract: what a restore of this tab would show must equal what the live tab shows -
     * subtitle included, not just the title.
     */
    private suspend fun EditorWorkspace.assertRestoreMatchesLive() {
        val live = info.value
        val restored = deriveEditorDisplay(createArguments())

        restored.title!!.get(context) shouldBe live.title.get(context)
        restored.subtitle?.get(context) shouldBe live.subtitle?.get(context)
    }

    /** Fails if the tab name changes away from [name] within [window]. */
    private suspend fun EditorWorkspace.assertNameStays(name: String, window: Duration = 500.milliseconds) {
        val changed = withTimeoutOrNull(window) { info.first { it.title.get(context) != name } }
        changed?.title?.get(context) shouldBe null
    }

    @Test
    fun `a file tab keeps its name while the file loads`() = runBlocking {
        val workspace = makeWorkspace(
            arguments = EditorArguments.Default(filePath = notes),
            engineFilePath = notes,
            // The engine reports its in-memory placeholder until the file is indexed
            contentSources = MutableStateFlow(ContentSource.Memory(size = 0L)),
            engineState = EngineState.Loading(notes),
            onInitialize = { delay(1.minutes); Result.success(Unit) },
        )

        workspace.assertNameStays("notes.txt")
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard"
        // Contract: a save right now persists the file, so a restore shows the same identity
        workspace.assertRestoreMatchesLive()
        workspace.release()
    }

    @Test
    fun `a file tab that never finishes loading keeps its name`() = runBlocking {
        val workspace = makeWorkspace(
            arguments = EditorArguments.Default(filePath = notes),
            engineFilePath = notes,
            contentSources = MutableStateFlow(ContentSource.Memory(size = 0L)),
            engineState = EngineState.Loading(notes),
            onInitialize = { awaitCancellation() },
        )

        workspace.assertNameStays("notes.txt")
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard"
        workspace.assertRestoreMatchesLive()
        workspace.release()
    }

    @Test
    fun `a file tab whose load fails keeps its name`() = runBlocking {
        val boom = IOException("Permission denied")
        val workspace = makeWorkspace(
            arguments = EditorArguments.Default(filePath = notes),
            engineFilePath = notes,
            // A failed load leaves the engine on its in-memory source permanently
            contentSources = MutableStateFlow(ContentSource.Memory(size = 0L)),
            engineState = EngineState.Error(boom, EngineState.Empty),
            onInitialize = { Result.failure(boom) },
        )

        val failed = workspace.awaitInfo { it.lifecycleState is Workspace.LifecycleState.Error }

        failed.title.get(context) shouldBe "notes.txt"
        failed.subtitle!!.get(context) shouldBe "/sdcard"
        workspace.assertRestoreMatchesLive()
        workspace.release()
    }

    @Test
    fun `a cancelled open turns the tab into a scratch buffer`() = runBlocking {
        val workspace = makeWorkspace(
            arguments = EditorArguments.Default(filePath = notes),
            engineFilePath = notes,
            contentSources = MutableStateFlow(ContentSource.Memory(size = 0L)),
            // Cancelling leaves the engine empty: the tab holds no file anymore
            engineState = EngineState.Empty,
            onInitialize = { Result.failure(CancellationException("Cancelled by user")) },
        )

        val cancelled = workspace.awaitInfo { it.contentPath == null }

        cancelled.title.get(context) shouldBe context.getString(R.string.editor_file_untitled)
        cancelled.subtitle shouldBe null
        // Contract: the save also drops the file, so live and restored agree on "no file"
        val restored = workspace.createArguments() as EditorArguments.Default
        restored.filePath shouldBe null
        deriveEditorDisplay(restored).title!!.get(context) shouldBe
            context.getString(R.string.editor_file_untitled)
        workspace.assertRestoreMatchesLive()
        workspace.release()
    }

    @Test
    fun `a restored scratch tab keeps its suggested name`() = runTest {
        val workspace = makeWorkspace(EditorArguments.Default(suggestedTitle = "Shopping list"))

        val restored = workspace.createArguments() as EditorArguments.Default

        restored.suggestedTitle shouldBe "Shopping list"
        deriveEditorDisplay(restored).title!!.get(context) shouldBe "Shopping list"
    }
}
