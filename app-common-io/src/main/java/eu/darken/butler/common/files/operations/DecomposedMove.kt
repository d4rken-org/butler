package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.DataKeptException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.CancellationException

/**
 * A gateway that renames within a folder and reparents under an unchanged name, but cannot do both
 * in one call (SAF), refuses a move that changes folder and basename together. Expressing such a
 * move as those two supported single-change moves keeps it a rename instead of streaming the file.
 *
 * ```
 * /source/file.txt  -> /dest/other.txt     refused
 * /source/file.txt  -> /source/other.txt   rename, same folder
 * /source/other.txt -> /dest/other.txt     reparent, same name
 * ```
 */
object DecomposedMove {

    /**
     * Move [source] to [destination] as a rename followed by a reparent.
     *
     * @return true if the file is now at [destination]; false if nothing was mutated and the caller
     * may fall back to copy+delete
     * @throws WriteException if the file is complete but not where it was asked to go, or if a step
     * threw and where it ended up is no longer certain - the message names the paths involved
     * @throws DataKeptException if the reparent threw: the file is then under the intermediate name
     * in its own folder or already at [destination], neither of which a destination-side message
     * would name
     */
    suspend fun <P : APath<P>, PL : APathLookup<P>> attempt(
        source: P,
        destination: P,
        ops: FileSystemOps<P, PL>,
    ): Boolean {
        val sourceParent = source.parent ?: return false
        if (sourceParent == destination.parent || source.name == destination.name) return false

        val renamed = sourceParent.child(destination.name)

        val renameOutcome = try {
            ops.move(source, renamed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is known about what the throw did, so the file may be at either name.
            throw WriteException("Could not rename $source to ${renamed.name}", source, e)
        }
        if (renameOutcome is MoveOutcome.NotSupported) {
            log(TAG, DEBUG) { "Rename step refused ($renameOutcome), nothing was mutated" }
            return false
        }

        val reparentOutcome = try {
            ops.move(renamed, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The move may have gone through and thrown afterwards, so renaming back could delete
            // the only copy of the data. Name both places it can be instead of guessing: the
            // intermediate name sits in the source folder, where nothing else looks for it.
            throw DataKeptException(
                "Could not move ${renamed.name} to $destination, it is either $renamed or $destination",
                source,
                DataKeptException.Recovery.Intermediate(
                    original = source.name,
                    intermediate = renamed.name,
                    destination = destination,
                ),
                e,
            )
        }
        if (reparentOutcome is MoveOutcome.Moved) return true

        log(TAG, DEBUG) { "Reparent step refused ($reparentOutcome), undoing the rename" }
        val undone = try {
            ops.move(renamed, source) is MoveOutcome.Moved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WriteException("Could not move $destination, the file is complete at $renamed", renamed, e)
        }
        if (!undone) throw WriteException("Could not move $destination, the file is complete at $renamed", renamed)

        return false
    }

    private val TAG = logTag("IO", "DecomposedMove")
}
