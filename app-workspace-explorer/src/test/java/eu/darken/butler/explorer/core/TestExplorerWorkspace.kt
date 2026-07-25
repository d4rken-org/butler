package eu.darken.butler.explorer.core

import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Workspace with every collaborator mocked away.
 *
 * The browsing engine never reports a location, which models a navigation that is still in flight
 * (or one that failed): the tab has a requested target but nothing loaded behind it.
 *
 * With the default unadvanced [StandardTestDispatcher] the workspace scope never runs, so `info`
 * still holds its explicit seed; pass an unconfined dispatcher to let init navigate.
 */
internal fun testExplorerWorkspace(
    arguments: ExplorerArguments,
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    id: Workspace.Id = Workspace.Id(),
) = ExplorerWorkspace(
    id = id,
    creationArguments = arguments,
    dispatcherProvider = TestDispatcherProvider(dispatcher),
    browsingEngineFactory = mockk(relaxed = true),
    fileSystemHinter = mockk(relaxed = true),
    pathAccessTracker = mockk(relaxed = true),
    issueHandler = mockk(relaxed = true),
    operationsManager = mockk<OperationsManager>(relaxed = true).apply {
        every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
    },
    deleteOperationFactory = mockk(relaxed = true),
    createOperationFactory = mockk(relaxed = true),
    createTextFileOperationFactory = mockk(relaxed = true),
    copyOperationFactory = mockk(relaxed = true),
    moveOperationFactory = mockk(relaxed = true),
    compressOperationFactory = mockk(relaxed = true),
    extractOperationFactory = mockk(relaxed = true),
    downloadLocalCopyOperationFactory = mockk(relaxed = true),
    restoreOperationFactory = mockk(relaxed = true),
    explorerSettings = mockk(relaxed = true),
)
