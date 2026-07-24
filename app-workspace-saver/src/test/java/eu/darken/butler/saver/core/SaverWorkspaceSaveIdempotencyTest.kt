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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A rapid double-tap on Save must not submit two operations. [SaverWorkspace.save] check-and-sets
 * its initial state under a lock and no-ops when a save is already in progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceSaveIdempotencyTest {

    private val operationsManager = mockk<OperationsManager> {
        coEvery { submit(any()) } returns Operation.Id()
        every { operations } returns MutableStateFlow<List<ManagedOperation>>(emptyList())
    }

    private fun makeWorkspace(): SaverWorkspace {
        val sourceInfo = ContentUriHelper.SourceInfo(
            uri = mockk<Uri>(),
            displayName = "app.apk",
            size = 4L,
            mimeType = "application/vnd.android.package-archive",
            isAccessible = true,
        )
        val contentUriHelper = mockk<ContentUriHelper> {
            every { extractInfo(any()) } returns sourceInfo
        }
        return SaverWorkspace(
            id = Workspace.Id(),
            arguments = SaverArguments.Default(
                sourceUris = listOf("content://provider/app.apk"),
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = contentUriHelper,
            operationsManager = operationsManager,
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
    fun `two concurrent saves submit only one operation`() = runTest {
        val workspace = makeWorkspace()

        // Model a double-tap: both coroutines race into save(). The mutex-guarded check-and-set must
        // let exactly one win; the other sees the non-Idle state at its suspension point and drops.
        val first = launch { workspace.save() }
        val second = launch { workspace.save() }
        first.join()
        second.join()

        coVerify(exactly = 1) { operationsManager.submit(any()) }
    }

    @Test
    fun `a save is rejected while one is already in progress`() = runTest {
        val workspace = makeWorkspace()

        workspace.save()
        // The first save left state in Saving (no operation completion emitted), so this is dropped.
        workspace.save()

        coVerify(exactly = 1) { operationsManager.submit(any()) }
    }
}
