package eu.darken.butler.saver.core

import android.net.Uri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The APK exports hand the Saver a name per source because their `file://` sources are all called
 * `base.apk`. Asserted on the command that reaches the operation, i.e. on what is written to disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceSourceNamesTest {

    private val commandSlot = slot<SaveFilesOperation.Command>()

    private val operationsManager = mockk<OperationsManager> {
        every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        coEvery { submitManaged(any()) } returns mockk<ManagedOperation> {
            every { id } returns Operation.Id()
            every { state } returns MutableStateFlow(
                Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0))
            )
        }
    }

    private fun makeWorkspace(
        uris: List<String>,
        names: List<String> = emptyList(),
        unreadableUris: Set<String> = emptySet(),
    ): SaverWorkspace {
        val contentUriHelper = mockk<ContentUriHelper> {
            every { extractInfo(any()) } answers {
                val uri = firstArg<Uri>()
                if (uri.toString() in unreadableUris) throw IllegalStateException("unreadable")
                ContentUriHelper.SourceInfo(
                    uri = uri,
                    displayName = uri.lastPathSegment!!,
                    size = 4L,
                    mimeType = "application/vnd.android.package-archive",
                    isAccessible = true,
                )
            }
        }
        return SaverWorkspace(
            id = Workspace.Id(),
            arguments = SaverArguments.Default(
                sourceUris = uris,
                sourceNames = names,
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = contentUriHelper,
            operationsManager = operationsManager,
            issueHandler = mockk<IssueHandler>(relaxed = true),
            saveFilesOperationFactory = mockk<SaveFilesOperation.Factory> {
                every { create(any(), capture(commandSlot)) } returns mockk<SaveFilesOperation>(relaxed = true)
            },
            pkgOps = mockk<PkgOps>(relaxed = true),
            json = mockk<Json>(relaxed = true),
            storageEnvironment = mockk<StorageEnvironment>(relaxed = true),
        )
    }

    private fun savedPairs(): List<Pair<String, String>> =
        commandSlot.captured.sources.map { it.uri.toString() to it.filename }

    @Test
    fun `each source is written under the name supplied for it`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("content://provider/a.apk", "content://provider/b.apk"),
            names = listOf("Alpha_com.alpha_1.apk", "Beta_com.beta_2.apk"),
        )

        workspace.save()

        savedPairs() shouldBe listOf(
            "content://provider/a.apk" to "Alpha_com.alpha_1.apk",
            "content://provider/b.apk" to "Beta_com.beta_2.apk",
        )
    }

    @Test
    fun `no supplied names leaves the derived names alone`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("content://provider/a.apk", "content://provider/b.apk"),
        )

        workspace.save()

        savedPairs() shouldBe listOf(
            "content://provider/a.apk" to "a.apk",
            "content://provider/b.apk" to "b.apk",
        )
    }

    @Test
    fun `a short name list leaves the tail alone`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("content://provider/a.apk", "content://provider/b.apk"),
            names = listOf("Alpha_com.alpha_1.apk"),
        )

        workspace.save()

        savedPairs() shouldBe listOf(
            "content://provider/a.apk" to "Alpha_com.alpha_1.apk",
            "content://provider/b.apk" to "b.apk",
        )
    }

    @Test
    fun `a source that cannot be read does not shift the names behind it`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("content://provider/a.apk", "content://provider/b.apk", "content://provider/c.apk"),
            names = listOf("Alpha_com.alpha_1.apk", "Beta_com.beta_2.apk", "Gamma_com.gamma_3.apk"),
            unreadableUris = setOf("content://provider/b.apk"),
        )

        workspace.save()

        savedPairs() shouldBe listOf(
            "content://provider/a.apk" to "Alpha_com.alpha_1.apk",
            "content://provider/c.apk" to "Gamma_com.gamma_3.apk",
        )
    }

    @Test
    fun `a restored tab keeps its supplied names`() = runTest {
        val workspace = makeWorkspace(
            uris = listOf("content://provider/a.apk", "content://provider/b.apk"),
            names = listOf("Alpha_com.alpha_1.apk", "Beta_com.beta_2.apk"),
        )

        val restored = workspace.createArguments() as SaverArguments.Default

        restored.sourceNames shouldBe listOf("Alpha_com.alpha_1.apk", "Beta_com.beta_2.apk")
    }
}
