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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

/**
 * A Saver tab reports a save as running work from the operation's own state, not from [SaveState]:
 * a save stopped on a conflict prompt stays [SaveState.Saving] while nothing is being written, and
 * that is precisely what the running marker has to leave out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceActiveCountTest {

    private val workspaceId = Workspace.Id()

    private val operationState = MutableStateFlow<Operation.State>(
        Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0))
    )

    private val managed = mockk<ManagedOperation>().apply {
        every { id } returns Operation.Id()
        every { state } returns operationState
        every { metadata } returns mockk {
            every { origin } returns Operation.Metadata.Origin.Saver(workspaceId)
        }
    }

    private fun makeWorkspace(): SaverWorkspace {
        val sourceInfo = ContentUriHelper.SourceInfo(
            uri = mockk<Uri>(),
            displayName = "app.apk",
            size = 4L,
            mimeType = "application/vnd.android.package-archive",
            isAccessible = true,
        )
        return SaverWorkspace(
            id = workspaceId,
            arguments = SaverArguments.Default(
                sourceUris = listOf("content://provider/app.apk"),
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = mockk<ContentUriHelper> { every { extractInfo(any()) } returns sourceInfo },
            operationsManager = mockk<OperationsManager> {
                every { operations } returns MutableStateFlow(listOf(managed))
                coEvery { submitManaged(any()) } returns managed
            },
            issueHandler = mockk<IssueHandler>(relaxed = true),
            saveFilesOperationFactory = mockk<SaveFilesOperation.Factory> {
                every { create(any(), any()) } returns mockk<SaveFilesOperation>(relaxed = true)
            },
            pkgOps = mockk<PkgOps>(relaxed = true),
            json = mockk<Json>(relaxed = true),
            storageEnvironment = mockk<StorageEnvironment>(relaxed = true),
        )
    }

    @Test
    fun `a save that has not started yet is not running`() = runTest {
        val workspace = makeWorkspace()

        workspace.save()

        workspace.info.value.operationCount shouldBe 1
        workspace.info.value.activeCount shouldBe 0
    }

    @Test
    fun `a save that is writing is running`() = runTest {
        val workspace = makeWorkspace()

        workspace.save()
        operationState.value = mockk<Operation.State.Active>()

        workspace.info.value.operationCount shouldBe 1
        workspace.info.value.activeCount shouldBe 1
    }

    @Test
    fun `a save waiting on a conflict prompt is not running`() = runTest {
        val workspace = makeWorkspace()

        workspace.save()
        operationState.value = mockk<Operation.State.Active>()
        operationState.value = mockk<Operation.State.Waiting>()

        workspace.info.value.operationCount shouldBe 1
        workspace.info.value.activeCount shouldBe 0
    }
}
