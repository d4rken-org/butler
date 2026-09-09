package eu.darken.butler.apps.core.operations

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.StopCircle
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.icons.Snowflake
import eu.darken.butler.common.compose.icons.SnowflakeOff
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.pkgs.uninstaller.AppUninstallConfirmationIssue
import eu.darken.butler.common.pkgs.uninstaller.SystemUninstaller
import eu.darken.butler.common.pkgs.uninstaller.UninstallDeclinedException
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Runs one [PackageCommand] as a workspace operation: one outcome per target, a receipt for all of
 * them, and - for a removal without elevated access - Android's own dialog in between.
 *
 * Not cancellable: every command is either a blocking IPC whose peer keeps going or a system dialog
 * Butler does not own. [Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN] because the receipt and the
 * confirmation sheet live on the tab that submitted it.
 */
class PackageActionOperation @AssistedInject constructor(
    @Assisted private val actionOrigin: Operation.Metadata.Origin,
    @Assisted private val command: PackageCommand,
    @ApplicationContext private val context: Context,
    private val pkgOps: PkgOps,
    private val pkgRepo: PkgRepo,
    private val appSizeCache: AppSizeCache,
    private val systemUninstaller: SystemUninstaller,
) : Operation {

    private val tag = logTag("Apps", "Operation", "PackageAction", actionOrigin.workspaceId.shortTag)

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = actionOrigin
        override val icon: ImageVector = when (command) {
            is PackageCommand.Enable -> Icons.TwoTone.SnowflakeOff
            is PackageCommand.Disable -> Icons.TwoTone.Snowflake
            is PackageCommand.ForceStop -> Icons.TwoTone.StopCircle
            is PackageCommand.ClearData -> Icons.TwoTone.DeleteSweep
            is PackageCommand.Uninstall -> Icons.TwoTone.Delete
            is PackageCommand.SetComponents -> Icons.TwoTone.Extension
        }
        override val title: CaString = when (command) {
            is PackageCommand.SetComponents -> caString {
                val template = when {
                    command.enabled -> R.plurals.apps_operation_components_enable_title
                    else -> R.plurals.apps_operation_components_disable_title
                }
                it.resources.getQuantityString(template, command.entries.size, command.entries.size)
            }

            else -> caString {
                val template = when (command) {
                    is PackageCommand.Enable -> R.plurals.apps_operation_enable_title
                    is PackageCommand.Disable -> R.plurals.apps_operation_disable_title
                    is PackageCommand.ForceStop -> R.plurals.apps_operation_force_stop_title
                    is PackageCommand.ClearData -> R.plurals.apps_operation_clear_data_title
                    else -> R.plurals.apps_operation_uninstall_title
                }
                it.resources.getQuantityString(template, command.targets.size, command.targets.size)
            }
        }
        override val description: CaString = when {
            command.targets.size == 1 -> command.targets.first().label
            else -> caString {
                it.resources.getQuantityString(
                    R.plurals.apps_operation_targets_description,
                    command.targets.size,
                    command.targets.size,
                )
            }
        }
        override val kind = when (command) {
            is PackageCommand.Enable -> Operation.Metadata.Kind.ENABLE
            is PackageCommand.Disable -> Operation.Metadata.Kind.DISABLE
            is PackageCommand.ForceStop -> Operation.Metadata.Kind.FORCE_STOP
            is PackageCommand.ClearData -> Operation.Metadata.Kind.CLEAR_DATA
            is PackageCommand.Uninstall -> Operation.Metadata.Kind.UNINSTALL
            is PackageCommand.SetComponents -> Operation.Metadata.Kind.COMPONENTS
        }
        override val intent = when {
            command !is PackageCommand.SetComponents -> null
            command.enabled -> Operation.Metadata.Intent.ENABLE_COMPONENTS
            else -> Operation.Metadata.Intent.DISABLE_COMPONENTS
        }
        override val isCancellable = false
        override val closePolicy = Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN
    }

    override fun perform(operationContext: Operation.Context): Flow<Operation.State> = channelFlow {
        log(tag, INFO) { "perform(): $command" }
        val startedAt = operationContext.startedAt
        val outcomes = mutableListOf<Operation.Report.Packages.Outcome>()

        when (command) {
            is PackageCommand.SetComponents -> command.entries.forEachIndexed { index, entry ->
                // Built lazily from the CaString, never by interpolating the CaString itself,
                // which would render as its toString().
                val label = caString {
                    "${command.target.label.get(it)} · ${entry.className.substringAfterLast('.')}"
                }
                send(activeState(startedAt, label, command.target.installId.pkgId.name, index, command.entries.size))
                outcomes += runTarget(label, command.target.installId.pkgId.name) {
                    pkgOps.changeComponentState(
                        entry.packageName.toPkgId(),
                        entry.className,
                        enabled = command.enabled,
                    )
                }
            }

            else -> command.targets.forEachIndexed { index, target ->
                send(activeState(startedAt, target.label, target.installId.pkgId.name, index, command.targets.size))
                outcomes += when (command) {
                    is PackageCommand.Enable -> runTarget(target.label, target.installId.pkgId.name) {
                        pkgOps.changePackageState(target.installId.pkgId, enabled = true)
                    }

                    is PackageCommand.Disable -> runTarget(target.label, target.installId.pkgId.name) {
                        pkgOps.changePackageState(target.installId.pkgId, enabled = false)
                    }

                    is PackageCommand.ForceStop -> runTarget(target.label, target.installId.pkgId.name) {
                        pkgOps.forceStop(target.installId.pkgId)
                    }

                    is PackageCommand.ClearData -> runTarget(target.label, target.installId.pkgId.name) {
                        pkgOps.clearData(target.installId)
                    }

                    is PackageCommand.Uninstall -> uninstall(
                        target = target,
                        viaSystemDialog = command.viaSystemDialog,
                        onWaiting = { issue -> send(WaitingState(startedAt = startedAt, issue = issue)) },
                        onResumed = {
                            send(activeState(startedAt, target.label, target.installId.pkgId.name, index, command.targets.size))
                        },
                    )

                    is PackageCommand.SetComponents -> throw IllegalStateException("Handled above")
                }
            }
        }

        refreshAfterCommand()

        // Built before any verdict: even a run that reads as cancelled keeps its per-target receipt.
        val report = Operation.Report.Packages(summary = buildSummary(outcomes), outcomes = outcomes)

        val statuses = outcomes.map { it.status }
        if (statuses.isNotEmpty() && statuses.all { it == Operation.Report.Packages.Outcome.Status.DECLINED }) {
            // Emitted rather than thrown: the framework's own cancellation path would replace this
            // state with one that carries no report.
            send(
                CompletedState(
                    startedAt = startedAt,
                    summary = report.summary,
                    report = report,
                    error = CancellationException("The user declined the removal"),
                )
            )
            return@channelFlow
        }

        val failures = outcomes.filter { it.status == Operation.Report.Packages.Outcome.Status.FAILED }
        send(
            CompletedState(
                startedAt = startedAt,
                summary = report.summary,
                report = report,
                // Only a run where nothing at all worked reads as failed; anything else is partial.
                error = if (failures.isNotEmpty() && failures.size == outcomes.size) failures.first().error else null,
            )
        )
    }

    private fun activeState(
        startedAt: Instant,
        label: CaString,
        pkgName: String,
        index: Int,
        total: Int,
    ) = ActiveState(
        startedAt = startedAt,
        primaryProgress = Progress.Data(
            primary = label,
            // A package with no resolvable app label falls back to its own id, which would then
            // fill both progress lines with the same text.
            secondary = caString { if (label.get(it) == pkgName) "" else pkgName },
            count = Progress.Count.Counter(index, total),
        ),
    )

    private suspend inline fun runTarget(
        label: CaString,
        packageName: String,
        block: () -> Unit,
    ): Operation.Report.Packages.Outcome = try {
        block()
        outcome(label, packageName, Operation.Report.Packages.Outcome.Status.DONE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Target failed: ${e.asLog()}" }
        outcome(label, packageName, Operation.Report.Packages.Outcome.Status.FAILED, e)
    }

    private suspend fun uninstall(
        target: PackageCommand.Target,
        viaSystemDialog: Boolean,
        onWaiting: suspend (AppUninstallConfirmationIssue) -> Unit,
        onResumed: suspend () -> Unit,
    ): Operation.Report.Packages.Outcome {
        var useSystemDialog = viaSystemDialog
        if (!useSystemDialog) {
            try {
                pkgOps.uninstall(target.installId)
                return outcome(target.label, target.installId.pkgId.name, Operation.Report.Packages.Outcome.Status.DONE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elevationLost = generateSequence<Throwable>(e) { it.cause }
                    .any { it is ElevatedAccessUnavailableException }
                if (!elevationLost) {
                    log(tag, WARN) { "Elevated uninstall failed: ${e.asLog()}" }
                    return outcome(
                        target.label,
                        target.installId.pkgId.name,
                        Operation.Report.Packages.Outcome.Status.FAILED,
                        e,
                    )
                }
                // Elevated access was lost between the submit and here; Android's dialog is what
                // is left to ask with.
                log(tag, WARN) { "Elevated access unavailable, falling back to the system dialog" }
                useSystemDialog = true
            }
        }
        return try {
            systemUninstaller.uninstall(
                installId = target.installId,
                label = target.label.get(context),
                onConfirmationRequired = onWaiting,
            )
            onResumed()
            outcome(target.label, target.installId.pkgId.name, Operation.Report.Packages.Outcome.Status.DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UninstallDeclinedException) {
            log(tag, INFO) { "The removal was declined" }
            onResumed()
            outcome(target.label, target.installId.pkgId.name, Operation.Report.Packages.Outcome.Status.DECLINED)
        } catch (e: Exception) {
            log(tag, WARN) { "System uninstall failed: ${e.asLog()}" }
            onResumed()
            outcome(target.label, target.installId.pkgId.name, Operation.Report.Packages.Outcome.Status.FAILED, e)
        }
    }

    private fun outcome(
        label: CaString,
        packageName: String,
        status: Operation.Report.Packages.Outcome.Status,
        error: Throwable? = null,
    ) = Operation.Report.Packages.Outcome(
        label = label,
        packageName = packageName,
        status = status,
        error = error,
    )

    /**
     * The state the command changed is the operation's to publish; a failure here is swallowed
     * because the command itself already happened, and a source error still reaches the screen
     * through [PkgRepo].
     */
    private suspend fun refreshAfterCommand() {
        if (command is PackageCommand.ClearData) {
            appSizeCache.invalidate(command.targets.map { it.installId })
        }
        // A force stop changes no package data, and a component toggle is refreshed by the page.
        val needsRefresh = when (command) {
            is PackageCommand.Enable,
            is PackageCommand.Disable,
            is PackageCommand.Uninstall,
            is PackageCommand.ClearData,
                -> true

            is PackageCommand.ForceStop, is PackageCommand.SetComponents -> false
        }
        if (!needsRefresh) return
        try {
            pkgRepo.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Refresh after package operation failed: ${e.asLog()}" }
        }
    }

    private fun buildSummary(outcomes: List<Operation.Report.Packages.Outcome>): CaString {
        val done = outcomes.count { it.status == Operation.Report.Packages.Outcome.Status.DONE }
        val failed = outcomes.count { it.status == Operation.Report.Packages.Outcome.Status.FAILED }
        val declined = outcomes.count { it.status == Operation.Report.Packages.Outcome.Status.DECLINED }

        outcomes.singleOrNull()?.let { single ->
            // A single target has a name, so the counting wording of a batch says less than it does.
            return when (single.status) {
                Operation.Report.Packages.Outcome.Status.DONE -> singleDoneSummary(single.label)
                Operation.Report.Packages.Outcome.Status.FAILED -> caString {
                    it.getString(R.string.apps_operation_single_failed_summary, single.label.get(it))
                }

                Operation.Report.Packages.Outcome.Status.DECLINED -> caString {
                    it.getString(R.string.apps_operation_single_declined_summary, single.label.get(it))
                }
            }
        }

        return caString {
            buildString {
                append(it.getString(R.string.apps_operation_batch_summary, done, outcomes.size))
                if (failed > 0) append(it.getString(R.string.apps_operation_batch_summary_failed, failed))
                if (declined > 0) append(it.getString(R.string.apps_operation_batch_summary_declined, declined))
            }
        }
    }

    private fun singleDoneSummary(label: CaString): CaString = when (command) {
        is PackageCommand.SetComponents -> caString {
            val template = when {
                command.enabled -> R.plurals.apps_operation_components_enable_summary
                else -> R.plurals.apps_operation_components_disable_summary
            }
            it.resources.getQuantityString(template, 1, 1)
        }

        else -> caString {
            val template = when (command) {
                is PackageCommand.Enable -> R.string.apps_operation_enable_summary
                is PackageCommand.Disable -> R.string.apps_operation_disable_summary
                is PackageCommand.ForceStop -> R.string.apps_operation_force_stop_summary
                is PackageCommand.ClearData -> R.string.apps_operation_clear_data_summary
                else -> R.string.apps_operation_uninstall_summary
            }
            it.getString(template, label.get(it))
        }
    }

    private data class ActiveState(
        override val startedAt: Instant,
        override val primaryProgress: Progress.Data,
        override val secondaryProgress: Progress.Data? = null,
    ) : Operation.State.Active

    private data class WaitingState(
        override val startedAt: Instant,
        override val waitingSince: Instant = Clock.System.now(),
        override val issue: AppUninstallConfirmationIssue,
    ) : Operation.State.Waiting {
        override val reason: CaString get() = issue.reason
    }

    private data class CompletedState(
        override val startedAt: Instant,
        override val completedAt: Instant = Clock.System.now(),
        override val summary: CaString,
        override val report: Operation.Report? = null,
        override val error: Throwable? = null,
    ) : Operation.State.Completed

    @AssistedFactory
    interface Factory {
        fun create(
            actionOrigin: Operation.Metadata.Origin,
            command: PackageCommand,
        ): PackageActionOperation
    }
}
