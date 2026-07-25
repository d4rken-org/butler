package eu.darken.butler.saver.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The live workspace must seed its [Workspace.Info] from the same derivation the dormant stand-in
 * uses; enriching it once the shared URIs resolve is expected.
 *
 * The workspace scope runs on an unadvanced [StandardTestDispatcher], so `info.value` is still the
 * explicit seed and not the first emission of the eagerly shared upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun makeWorkspace(
        arguments: SaverArguments.Default,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    ) = SaverWorkspace(
        id = Workspace.Id(),
        arguments = arguments,
        dispatcherProvider = TestDispatcherProvider(dispatcher),
        contentUriHelper = mockk<ContentUriHelper>(relaxed = true).apply {
            // Named sources take over the second line; an unnamed one leaves it to the destination
            every { extractInfo(any()) } returns ContentUriHelper.SourceInfo(
                uri = mockk(relaxed = true),
                displayName = "",
                size = null,
                mimeType = null,
                isAccessible = true,
            )
        },
        operationsManager = mockk<OperationsManager>(relaxed = true).apply {
            every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        },
        issueHandler = mockk<IssueHandler>(relaxed = true),
        saveFilesOperationFactory = mockk<SaveFilesOperation.Factory>(relaxed = true),
        pkgOps = mockk<PkgOps>(relaxed = true),
        json = mockk<Json>(relaxed = true),
        storageEnvironment = mockk<StorageEnvironment>(relaxed = true).apply {
            every { downloadsDirectory } returns null
        },
    )

    private fun assertSeedMatchesDerivation(arguments: SaverArguments.Default) {
        val derived = deriveSaverDisplay(arguments)
        val seed = makeWorkspace(arguments).info.value

        seed.title.get(context) shouldBe derived!!.title!!.get(context)
        seed.subtitle?.get(context) shouldBe derived.subtitle?.get(context)
    }

    @Test
    fun `a single share seeds the derived identity`() {
        assertSeedMatchesDerivation(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/file"),
                destinationPath = LocalPath.build("/sdcard/Download"),
            ),
        )
    }

    @Test
    fun `a batch share seeds the derived file count`() {
        assertSeedMatchesDerivation(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/a", "content://provider/b"),
            ),
        )
    }

    /**
     * The destination changes while the tab is open and a session save persists the CURRENT one,
     * so the live tab must describe that one - not the destination it was created with.
     */
    @Test
    fun `changing the destination moves the tab's second line with it`() = runTest {
        val workspace = makeWorkspace(
            SaverArguments.Default(
                sourceUris = listOf("content://provider/file"),
                destinationPath = LocalPath.build("/sdcard/Download"),
            ),
            UnconfinedTestDispatcher(testScheduler),
        )
        workspace.info.value.subtitle!!.get(context) shouldBe "Download"

        workspace.setDestination(LocalPath.build("/sdcard/Music"))

        workspace.info.value.subtitle!!.get(context) shouldBe "Music"
        deriveSaverDisplay(workspace.createArguments())!!.subtitle!!.get(context) shouldBe
            workspace.info.value.subtitle!!.get(context)
        workspace.release()
    }
}
