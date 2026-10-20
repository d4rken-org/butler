package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.operations.ExtractOperationReport
import eu.darken.butler.workspace.core.operations.Operation

/** What to reveal once an extraction finished: the folder it created in [ExtractReveal.path]. */
internal data class ExtractReveal(
    val path: APath<*>,
    val destination: String?,
    val awaitDestinationLoad: Boolean,
)

/**
 * @param destinationArrived whether [destinationDir] was the open location when the extraction
 *        started; only then may the reveal wait for that folder's listing to finish loading.
 */
internal fun extractReveal(
    completed: Operation.State.Completed,
    destinationDir: APath<*>,
    destinationArrived: Boolean,
): ExtractReveal? {
    if (completed.error != null) return null
    val outputDir = (completed.report as? ExtractOperationReport)?.outputDir ?: return null
    return ExtractReveal(
        path = outputDir,
        destination = if (destinationArrived) ExplorerLocation.Directory.locationIdOf(destinationDir) else null,
        awaitDestinationLoad = destinationArrived,
    )
}
