package eu.darken.butler.searcher.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.move
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

class RenameOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: SearcherCommand.Rename,
    @Assisted private val moveListener: MoveListener,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
) : SearcherOperation() {

    /** Receives what the move confirmed, before the filesystem events about it go out. */
    fun interface MoveListener {
        suspend fun onMoved(
            operationId: Operation.Id,
            moved: Set<Pair<APathLookup<*>, APathLookup<*>>>,
            skipped: Set<APathLookup<*>>,
        )
    }

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "Operation", "Rename")

    private val source: APath<*> = command.source.path
    private val destination: APath<*>? = source.parent?.child(command.newName)

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Searcher(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.DriveFileRenameOutline
        override val title = R.string.searcher_operation_rename_title.toCaString()
        override val description = caString { cx ->
            cx.getString(R.string.searcher_operation_rename_description, source.name, command.newName)
        }
        override val kind = Operation.Metadata.Kind.MOVE
        override val intent = Operation.Metadata.Intent.RENAME
        override val pathPlan = OperationPathPlan(
            targets = listOf(source),
            destination = destination?.let { OperationPathPlan.Destination.RequestedTarget(it) },
        )
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val target = destination ?: throw WriteException("Can't rename a path without a parent", source)

        // An existing directory under the new name turns the rename into a move INTO it.
        val existing = try {
            gatewaySwitch.lookup(target, LookupOptions(fallbackToUnknown = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "perform(): Destination lookup failed, leaving it to the move: ${e.asLog()}" }
            null
        }
        if (existing?.fileType == FileType.DIRECTORY && !isLookupOfSourceItself(target)) {
            log(tag, WARN) { "perform(): $target is an existing directory, not renaming $source" }
            throw PathAlreadyExistsException(path = target)
        }

        val reportBuilder = RenameOperationReport.Builder()
        var lastPerformanceHistory: PerformanceHistory? = null

        val result = setOf(source)
            .move(
                gateway = gatewaySwitch,
                destination = target,
                options = MoveAction.Options(preserveAttributes = true),
                onIssue = { issue ->
                    send(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )
                    val resolution = issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    send(stateActive)
                    resolution
                },
            )
            .onEach { moveState ->
                if (moveState !is MoveAction.State.Active<*, *, *, *>) return@onEach
                val perfHistory = moveState.primaryProgress.extra as? PerformanceHistory
                lastPerformanceHistory = perfHistory
                stateActive = stateActive.copy(
                    primaryProgress = moveState.primaryProgress,
                    secondaryProgress = moveState.secondaryProgress,
                    performanceHistory = perfHistory,
                )
                send(stateActive)
            }
            .last()

        result as MoveAction.State.Completed<*, *, *, *>

        val moved: Set<Pair<APathLookup<*>, APathLookup<*>>> = result.movedFiles
        val skipped: Set<APathLookup<*>> = result.skippedFiles

        // Before the hints, so whatever the listener rewrites is in place when the Removed event
        // for the old path goes out.
        moveListener.onMoved(operationContext.id, moved, skipped)

        // Confirmed pairs only, so skipped items aren't reported as removed.
        fileSystemHinter.trackPathsRemoved(operationContext.id, moved.map { it.first }.toSet())
        fileSystemHinter.trackPathsAdded(operationContext.id, moved.map { it.second }.toSet())

        reportBuilder.setSubjectPath(
            moved.firstOrNull { (from, _) -> from.lookedUp == source }?.second?.lookedUp
        )
        reportBuilder.addMovedItems(moved)
        reportBuilder.setSkipped(skipped)
        reportBuilder.setBytesMoved(result.bytesMoved)
        reportBuilder.setPerformanceHistory(lastPerformanceHistory)

        send(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = reportBuilder.build(),
            )
        )
    }

    /**
     * A case-only rename on case-insensitive storage finds the source itself under the new name.
     * With `docs` and `Docs` both listed in the parent, `Docs` is a separate directory.
     */
    private suspend fun isLookupOfSourceItself(target: APath<*>): Boolean {
        if (!source.name.equals(command.newName, ignoreCase = true)) return false
        val parent = source.parent ?: return false
        val names = try {
            gatewaySwitch.listFiles(parent).map { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "perform(): Listing $parent failed, can't tell $target from $source: ${e.asLog()}" }
            return false
        }
        return source.name in names && command.newName !in names
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: SearcherCommand.Rename,
            moveListener: MoveListener,
        ): RenameOperation
    }
}
