package eu.darken.butler.workspace.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.InstallMobile
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallPlan
import eu.darken.butler.common.pkgs.installer.AppInstaller
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Runs one [AppInstallPlan] as a workspace operation.
 *
 * [events] is how anything that is not progress reaches the caller: [OperationsManager.submit]
 * hands back an id and [ManagedOperation] relays nothing but [Operation.State], so there is no
 * framework channel for the rest. The submitting ViewModel subscribes to it before submitting.
 */
class AppInstallOperation @AssistedInject constructor(
    @Assisted private val installOrigin: Operation.Metadata.Origin,
    @Assisted private val plan: AppInstallPlan,
    @Assisted private val events: MutableSharedFlow<AppInstallEvent>,
    private val appInstaller: AppInstaller,
) : Operation {

    private val tag = logTag("Workspace", "Operation", "AppInstall", installOrigin.workspaceId.shortTag)

    private val displayName: String = plan.baseInfo?.label
        ?: plan.pkgId?.name
        ?: plan.source.name

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = installOrigin
        override val icon: ImageVector = Icons.TwoTone.InstallMobile
        override val title = R.string.workspace_operation_install_title.toCaString()
        override val description = caString {
            it.getString(R.string.workspace_operation_install_description, displayName)
        }
        // kind stays null: installing an app changes no file, so it has no place in file history.
        override val intendedPaths = listOf(plan.source)
    }

    override fun perform(operationContext: Operation.Context): Flow<Operation.State> = channelFlow {
        log(tag, INFO) { "perform(): ${plan.format} ${plan.source}" }
        send(ActiveState(startedAt = operationContext.startedAt))

        var terminal: AppInstallEvent? = null
        appInstaller.install(plan).collect { event ->
            when (event) {
                is AppInstallEvent.Progress -> send(
                    ActiveState(
                        startedAt = operationContext.startedAt,
                        primaryProgress = event.toProgressData(),
                    )
                )

                is AppInstallEvent.ObbFailed -> events.emit(event)
                is AppInstallEvent.Success -> terminal = event
                is AppInstallEvent.Failure -> terminal = event
            }
        }

        when (val result = terminal) {
            is AppInstallEvent.Success -> {
                log(tag, INFO) { "perform(): installed ${result.pkgId} via ${result.viaMode}" }
                send(
                    CompletedState(
                        startedAt = operationContext.startedAt,
                        summary = caString {
                            val template = when {
                                plan.obbEntries.isEmpty() || result.obbPlaced ->
                                    R.string.workspace_operation_install_summary_success

                                else -> R.string.workspace_operation_install_summary_success_partial
                            }
                            it.getString(template, displayName)
                        },
                    )
                )
            }

            is AppInstallEvent.Failure -> throw result.error
            else -> throw IllegalStateException("The install ended without a result")
        }
    }

    private fun AppInstallEvent.Progress.toProgressData() = Progress.Data(
        primary = stageLabel(stage).toCaString(),
        secondary = label?.toCaString() ?: CaString.EMPTY,
        count = when {
            total <= 0L -> Progress.Count.Indeterminate()
            stage == AppInstallEvent.Stage.EXTRACTING -> Progress.Count.Size(current, total)
            else -> Progress.Count.Counter(current, total)
        },
    )

    private fun stageLabel(stage: AppInstallEvent.Stage): Int = when (stage) {
        AppInstallEvent.Stage.INSPECTING -> R.string.workspace_operation_install_progress_inspecting
        AppInstallEvent.Stage.EXTRACTING -> R.string.workspace_operation_install_progress_extracting
        AppInstallEvent.Stage.WRITING -> R.string.workspace_operation_install_progress_writing
        AppInstallEvent.Stage.COMMITTING -> R.string.workspace_operation_install_progress_committing
        AppInstallEvent.Stage.PLACING_OBB -> R.string.workspace_operation_install_progress_placing_obb
    }

    private data class ActiveState(
        override val startedAt: Instant,
        override val primaryProgress: Progress.Data = Progress.Data(
            primary = R.string.workspace_operation_install_progress_inspecting.toCaString(),
        ),
        override val secondaryProgress: Progress.Data? = null,
    ) : Operation.State.Active

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
            installOrigin: Operation.Metadata.Origin,
            plan: AppInstallPlan,
            events: MutableSharedFlow<AppInstallEvent>,
        ): AppInstallOperation
    }
}
