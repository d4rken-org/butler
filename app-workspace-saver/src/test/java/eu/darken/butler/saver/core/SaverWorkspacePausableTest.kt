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

/**
 * The saver is a transient export flow: the chosen filename and the save progress live only in the
 * instance, never in its arguments, so it must never be paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspacePausableTest {

    private fun makeWorkspace(): SaverWorkspace {
        val sourceInfo = ContentUriHelper.SourceInfo(
            uri = mockk<Uri>(),
            displayName = "app.apk",
            size = 4L,
            mimeType = "application/vnd.android.package-archive",
            isAccessible = true,
        )
        return SaverWorkspace(
            id = Workspace.Id(),
            arguments = SaverArguments.Default(
                sourceUris = listOf("content://provider/app.apk"),
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = mockk<ContentUriHelper> { every { extractInfo(any()) } returns sourceInfo },
            operationsManager = mockk<OperationsManager> {
                coEvery { submit(any()) } returns eu.darken.butler.workspace.core.operations.Operation.Id()
                every { operations } returns MutableStateFlow<List<ManagedOperation>>(emptyList())
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
    fun `a saver tab is never pausable`() = runTest {
        val workspace = makeWorkspace()

        workspace.info.value.isPausable shouldBe false

        workspace.save()

        workspace.info.value.isPausable shouldBe false
    }
}
