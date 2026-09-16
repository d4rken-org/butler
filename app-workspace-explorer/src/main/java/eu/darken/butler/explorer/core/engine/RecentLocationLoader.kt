package eu.darken.butler.explorer.core.engine

import android.os.Environment
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.recent.RecentFilesReader
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Files the media index has seen recently, newest first — the Explorer's "Recent" location. */
class RecentLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val recentFilesReader: RecentFilesReader,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "RecentLoader")

    /**
     * Stays subscribed to the permission monitor instead of sampling it once: on Android 11+ without
     * All-Files-Access the first emission needs action and the listing is skipped, and the emission
     * that follows the grant has to run the query without the user refreshing by hand.
     */
    fun loadRecent(): Flow<ExplorerLocation> = pathPermissionCheck
        .monitor(LocalPath.build(Environment.getExternalStorageDirectory()))
        .flatMapLatest { setupRequirements ->
            flow {
                log(tag, INFO) { "loadRecent(): Loading with setup requirements: $setupRequirements" }

                val context = LocationLoaderContext(
                    initialState = ExplorerLocation.Recent(
                        setupRequirements = setupRequirements,
                        progress = Progress.Data(
                            primary = R.string.explorer_loader_progress_recent_loading.toCaString(),
                        ),
                    ),
                    emit = ::emit,
                )
                context.emitState()

                if (setupRequirements.needsAction) {
                    log(tag, WARN) { "loadRecent(): Action required: $setupRequirements" }
                    emit(
                        ExplorerLocation.Recent(
                            setupRequirements = setupRequirements,
                            progress = null,
                        )
                    )
                    return@flow
                }

                val entries = recentFilesReader.read(
                    cutoff = Clock.System.now() - RECENT_WINDOW,
                    limit = RECENT_LIMIT,
                )
                log(tag, INFO) { "loadRecent(): Read ${entries.size} entries" }

                val classifier = FileTypeClassifier()
                val items = entries.map { entry ->
                    ExplorerItem.RegularFile(
                        lookup = entry.lookup,
                        mimeType = classifier.getMimeType(entry.lookup.name),
                    )
                }

                context.updateState {
                    copy(
                        items = items,
                        indexedAt = entries.associate { it.lookup.path to it.indexedAt },
                        info = ExplorerLocation.Recent.Info(
                            fileCount = items.size,
                            totalSize = entries.sumOf { it.lookup.size ?: 0L },
                            windowDays = RECENT_WINDOW.inWholeDays.toInt(),
                        ),
                        progress = null,
                    )
                }
            }
        }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): RecentLocationLoader
    }
}

/**
 * A month covers "I downloaded it a while ago" without turning the shortlist into an archive.
 * Shared with the Home shortcut, whose subtitle names the same window.
 */
internal val RECENT_WINDOW = 30.days

/** Past a few hundred rows this stops being a shortlist and the user is better served by a search. */
private const val RECENT_LIMIT = 500
