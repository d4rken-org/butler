package eu.darken.butler.explorer.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

class TrashLocationLoader @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val trashRepo: TrashRepo,
    private val gatewaySwitch: GatewaySwitch,
    private val metadataRepo: MetadataRepo,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "TrashLoader")

    private suspend fun checkLocationRequirements(): PathRequirements {
        log(tag) { "checkLocationRequirements(): Checking requirements for trash" }
        return PathRequirements()
    }

    /**
     * Load root trash view showing all deleted items.
     */
    fun loadRoot(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadRoot(): Loading trash root" }

        val setupRequirements = checkLocationRequirements()
        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Trash.Root(
                setupRequirements = setupRequirements,
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_trash_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        try {
            // Get all trash items
            val trashItems = trashRepo.getAllItems().first()
            log(tag, DEBUG) { "loadRoot(): Found ${trashItems.size} items in trash" }

            // Convert repository items to ExplorerItems
            val explorerItems = trashItems.map { item ->
                ExplorerItem.Trash.Root(
                    itemId = item.id,
                    originalLookup = item.originalLookup,
                    trashLookup = item.trashLookup,
                    deletedAt = item.deletedAt,
                )
            }.sortedByDescending { it.deletedAt }

            val info = ExplorerLocation.Trash.Root.Info(
                itemCount = trashItems.size,
                totalSize = trashItems.sumOf { it.size },
                oldestItem = trashItems.minByOrNull { it.deletedAt }?.deletedAt,
            )

            log(tag, INFO) { "loadRoot(): Loaded ${explorerItems.size} items" }

            context.updateState {
                copy(
                    items = explorerItems,
                    info = info,
                    progress = null,
                )
            }
            context.emitState()
        } catch (e: Exception) {
            log(tag, ERROR) { "loadRoot(): Failed to load trash: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Load nested view inside a trashed folder.
     */
    fun loadNested(
        parentRef: TrashItemReference,
        relativePath: String,
    ): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadNested(): parent=${parentRef.itemId}, path='$relativePath'" }

        val absolutePath = if (relativePath.isEmpty()) {
            parentRef.trashPath
        } else {
            parentRef.trashPath.child(relativePath)
        }

        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Trash.Nested(
                parentItem = parentRef,
                currentPath = absolutePath,
                relativePath = relativePath,
                progress = Progress.Data(
                    primary = R.string.explorer_trash_nested_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        try {
            // Verify parent trash item still exists in database
            val repoItem = trashRepo.getById(parentRef.itemId)
            if (repoItem == null) {
                log(tag, ERROR) { "Parent trash item no longer exists: ${parentRef.itemId}" }
                throw IllegalStateException(
                    "Trash item has been deleted or restored"
                )
            }

            // Only a definitive "not there" earns this wording. A folder that cannot be inspected
            // falls through to the listing below, whose own failure names the real reason.
            if (gatewaySwitch.existsStrict(absolutePath) == Existence.ABSENT) {
                log(tag, ERROR) { "Trash path no longer exists: $absolutePath" }
                throw IllegalStateException("Folder contents no longer available")
            }

            currentCoroutineContext().ensureActive()

            // Load directory contents using standard gateway
            gatewaySwitch.useRes {
                val lookups = gatewaySwitch.lookupFiles(
                    absolutePath,
                    LookupOptions(
                        continueOnError = true,
                        fallbackToUnknown = true,
                        fetchSize = true,
                        fetchModifiedAt = true,
                    ),
                )
                log(tag, DEBUG) { "loadNested(): Found ${lookups.size} items" }

                currentCoroutineContext().ensureActive()

                val fileClassifier = FileTypeClassifier()
                val items = lookups.map { lookup ->
                    val metadata = metadataRepo.extract(lookup)
                    val pathItem = fileClassifier.classify(lookup, metadata)

                    // Compute relative path for this item
                    val itemRelativePath = if (relativePath.isEmpty()) {
                        lookup.name
                    } else {
                        "$relativePath/${lookup.name}"
                    }

                    // Wrap in TrashNestedItem with context
                    ExplorerItem.Trash.Nested(
                        inner = pathItem,
                        parentRef = parentRef,
                        relativePath = itemRelativePath,
                    )
                }

                val parent = computeParent(parentRef, relativePath)

                var fileCount = 0
                var directoryCount = 0
                var totalSize = 0L

                items.forEach { item ->
                    if (item.isDirectory) {
                        directoryCount++
                    } else {
                        fileCount++
                        totalSize += item.lookup.size ?: 0L
                    }
                }

                context.updateState {
                    copy(
                        items = items,
                        info = ExplorerLocation.Trash.Nested.Info(
                            fileCount = fileCount,
                            directoryCount = directoryCount,
                            totalSize = if (totalSize > 0) totalSize else null,
                        ),
                        progress = null,
                        parent = parent,
                    )
                }
            }
            context.emitState()
        } catch (e: Exception) {
            log(tag, ERROR) { "loadNested() failed: ${e.asLog()}" }
            throw e
        }
    }

    private fun computeParent(
        parentRef: TrashItemReference,
        relativePath: String,
    ): ExplorerNavigation.Target {
        return if (relativePath.isEmpty()) {
            // At root of trashed folder -> parent is Trash root
            ExplorerNavigation.Target.Trash.Root
        } else {
            // Inside trashed folder -> parent is parent directory within trash
            val parentRelative = relativePath.substringBeforeLast("/", "")
            ExplorerNavigation.Target.Trash.Nested(parentRef, parentRelative)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): TrashLocationLoader
    }
}