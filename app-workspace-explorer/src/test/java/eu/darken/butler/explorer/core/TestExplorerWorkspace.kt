package eu.darken.butler.explorer.core

import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.operations.CalculateSizesOperation
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

/**
 * Workspace with every collaborator mocked away.
 *
 * The browsing engine never reports a location, which models a navigation that is still in flight
 * (or one that failed): the tab has a requested target but nothing loaded behind it. Pass
 * [browsingEngine] to drive it instead.
 *
 * With the default unadvanced [StandardTestDispatcher] the workspace scope never runs, so `info`
 * still holds its explicit seed; pass an unconfined dispatcher to let init navigate.
 */
internal fun testExplorerWorkspace(
    arguments: ExplorerArguments,
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    id: Workspace.Id = Workspace.Id(),
    browsingEngine: BrowsingEngine? = null,
    errorIncidentStore: ErrorIncidentStore = recordingIncidentStore(),
    fileSystemHinter: FileSystemHinter = mockk(relaxed = true),
    calculateSizesOperationFactory: CalculateSizesOperation.Factory = mockk(relaxed = true),
    operationsManager: OperationsManager = mockk<OperationsManager>(relaxed = true).apply {
        every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
    },
) = ExplorerWorkspace(
    id = id,
    creationArguments = arguments,
    dispatcherProvider = TestDispatcherProvider(dispatcher),
    browsingEngineFactory = browsingEngine
        ?.let { engine -> mockk<BrowsingEngine.Factory> { every { create(any(), any()) } returns engine } }
        ?: mockk(relaxed = true),
    fileSystemHinter = fileSystemHinter,
    pathAccessTracker = mockk(relaxed = true),
    issueHandler = mockk(relaxed = true),
    operationsManager = operationsManager,
    deleteOperationFactory = mockk(relaxed = true),
    createOperationFactory = mockk(relaxed = true),
    createTextFileOperationFactory = mockk(relaxed = true),
    copyOperationFactory = mockk(relaxed = true),
    moveOperationFactory = mockk(relaxed = true),
    compressOperationFactory = mockk(relaxed = true),
    extractOperationFactory = mockk(relaxed = true),
    downloadLocalCopyOperationFactory = mockk(relaxed = true),
    restoreOperationFactory = mockk(relaxed = true),
    calculateSizesOperationFactory = calculateSizesOperationFactory,
    explorerSettings = mockk(relaxed = true),
    errorIncidentStore = errorIncidentStore,
)
