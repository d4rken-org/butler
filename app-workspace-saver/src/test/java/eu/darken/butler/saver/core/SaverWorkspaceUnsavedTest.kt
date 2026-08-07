package eu.darken.butler.saver.core

import android.net.Uri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.saver.core.operations.SaveFilesReport
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
 * The shared content only exists inside a Saver tab until it is written somewhere, so the tab has to
 * say so in the vocabulary the close paths read - otherwise the limit dialog offers it up and a
 * plain close discards it without asking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceUnsavedTest {

    private val operationId = Operation.Id()

    private val operationState = MutableStateFlow<Operation.State>(
        Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0))
    )

    private fun makeWorkspace(): SaverWorkspace {
        val sourceInfo = ContentUriHelper.SourceInfo(
            uri = mockk<Uri>(),
            displayName = "app.apk",
            size = 4L,
            mimeType = "application/vnd.android.package-archive",
            isAccessible = true,
        )
        val managed = mockk<ManagedOperation>().apply {
            every { id } returns operationId
            every { state } returns operationState
        }
        return SaverWorkspace(
            id = Workspace.Id(),
            arguments = SaverArguments.Default(
                sourceUris = listOf("content://provider/app.apk"),
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = mockk<ContentUriHelper> { every { extractInfo(any()) } returns sourceInfo },
            operationsManager = mockk<OperationsManager> {
                coEvery { submit(any()) } returns operationId
                every { operations } returns MutableStateFlow(listOf(managed))
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

    /**
     * The seed matters on its own: close paths read [Workspace.Info] synchronously, so a tab closed
     * before its first real emission would otherwise look clean.
     */
    @Test
    fun `a fresh saver already reports unsaved content`() = runTest {
        makeWorkspace().info.value.hasUnsavedChanges shouldBe true
    }

    @Test
    fun `a fully saved export reports nothing unsaved`() = runTest {
        val workspace = makeWorkspace()
        operationState.value = completed(
            SaveFilesReport.FileResult.Success(
                filename = "app.apk",
                savedPath = LocalPath.build("/save/app.apk"),
                bytes = 4L,
            )
        )

        workspace.save()

        workspace.info.value.hasUnsavedChanges shouldBe false
    }

    /**
     * The operation gathers per-file failures into its report and still completes normally, so
     * "finished" is not "saved" - those sources are still only in this tab.
     */
    @Test
    fun `an export that finished with per-file errors still reports unsaved content`() = runTest {
        val workspace = makeWorkspace()
        operationState.value = completed(
            SaveFilesReport.FileResult.Success(
                filename = "ok.apk",
                savedPath = LocalPath.build("/save/ok.apk"),
                bytes = 4L,
            ),
            SaveFilesReport.FileResult.Error(
                filename = "app.apk",
                error = IllegalStateException("Disk full"),
            ),
        )

        workspace.save()

        workspace.info.value.hasUnsavedChanges shouldBe true
    }

    /** A file the user chose to skip is not content Butler is still holding for them. */
    @Test
    fun `an export whose only leftover was skipped reports nothing unsaved`() = runTest {
        val workspace = makeWorkspace()
        operationState.value = completed(
            SaveFilesReport.FileResult.Skipped(
                filename = "app.apk",
                reason = SaveFilesReport.FileResult.Skipped.SkipReason.USER_SKIPPED,
            )
        )

        workspace.save()

        workspace.info.value.hasUnsavedChanges shouldBe false
    }

    private fun completed(vararg results: SaveFilesReport.FileResult) = SaveFilesOperation.State.Completed(
        startedAt = Instant.fromEpochSeconds(0),
        completedAt = Instant.fromEpochSeconds(1),
        error = null,
        report = SaveFilesReport(results = results.toList()),
    )
}
