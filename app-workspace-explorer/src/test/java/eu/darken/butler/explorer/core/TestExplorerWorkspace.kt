package eu.darken.butler.explorer.core

import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

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
) = ExplorerWorkspace(
    id = id,
    creationArguments = arguments,
    dispatcherProvider = TestDispatcherProvider(dispatcher),
    browsingEngineFactory = browsingEngine
        ?.let { engine -> mockk<BrowsingEngine.Factory> { every { create(any(), any()) } returns engine } }
        ?: mockk(relaxed = true),
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
    errorIncidentStore = errorIncidentStore,
)

/** The real store, so identity keying and mint-once behave as they do in production. */
internal fun recordingIncidentStore(spoolDir: File? = null): ErrorIncidentStore =
    ErrorIncidentStore(recordingIncidentFactory(spoolDir))

/**
 * Freezes real [ErrorIncident]s (a relaxed mock would hand back a mocked throwable, and the states
 * under test are read through `error`), with a fresh id per call so a test can tell a re-freeze
 * from a carried-over incident. With a [spoolDir] it also writes one file per freeze, which is what
 * a test counting log trails goes by.
 */
internal fun recordingIncidentFactory(spoolDir: File? = null): ErrorIncidentFactory = mockk {
    var counter = 0
    coEvery { freeze(any(), any(), any()) } answers {
        val incidentId = "incident-${counter++}"
        ErrorIncident(
            incidentId = incidentId,
            occurredAt = thirdArg<Instant?>() ?: Clock.System.now(),
            occurredAtIsApproximate = thirdArg<Instant?>() == null,
            error = firstArg(),
            context = secondArg<Map<String, String?>>().filterValues { it != null }.mapValues { it.value!! },
            logFile = spoolDir?.let { dir ->
                dir.mkdirs()
                File(dir, "$incidentId.log").apply { writeText("log trail") }
            },
        )
    }
}
