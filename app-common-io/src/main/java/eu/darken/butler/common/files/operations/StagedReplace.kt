package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.DataKeptException
import eu.darken.butler.common.files.errors.WriteException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Replacing a file means writing a staging sibling first and swapping it in afterwards, so a
 * failure mid-transfer (transport loss, full volume, process death, cancellation) can never leave
 * the user with neither the old nor the complete new content.
 *
 * ```
 * val staging = StagedReplace.stagingPathFor(destination, ops)   // /dir/.a1b2c3d4.part.photo.jpg
 * try {
 *     writeInto(staging)
 * } catch (e: Exception) {
 *     StagedReplace.discard(staging, ops)                        // nothing was destroyed yet
 *     throw e
 * }
 * StagedReplace.commit(staging, destination, ops)                // /dir/photo.jpg
 * ```
 *
 * [discard] belongs on the failure path only. A successful transfer may already have deleted the
 * source (move), which makes the staging file the only copy of the data.
 */
object StagedReplace {

    /**
     * A hidden sibling of [destination] that keeps the original file extension, because the
     * extension is what SAF document creation derives the document MIME type from.
     *
     * @throws WriteException if [destination] has no parent, or no free name was found
     */
    suspend fun <P : APath<P>, PL : APathLookup<P>> stagingPathFor(destination: P, ops: FileSystemOps<P, PL>): P =
        siblingPathFor(destination, "part", ops)

    /**
     * Swap the fully written [staging] file in for [destination].
     *
     * An existing [destination] is renamed aside and only removed once the replacement is in place,
     * so a swap-in that fails halfway can put the original back. A gateway that cannot rename the
     * original aside keeps the delete-first behaviour instead.
     *
     * @throws DataKeptException if the existing [destination] could not be cleared for the
     * replacement, or if the replacement could not be put in place: the transfer that filled
     * [staging] may already have consumed its source, so the recovery names where the old and the
     * new content survive. A swap-in that throws may have completed before throwing, so nothing
     * further is mutated and destination, staging file and, where one was renamed aside, backup are
     * all named.
     */
    suspend fun <P : APath<P>, PL : APathLookup<P>> commit(staging: P, destination: P, ops: FileSystemOps<P, PL>) {
        withContext(NonCancellable) {
            var backup: P? = null
            if (ops.exists(destination)) {
                val candidate = siblingPathFor(destination, "backup", ops)
                val asideOutcome = try {
                    ops.move(destination, candidate)
                } catch (e: Exception) {
                    throw clearFailure(staging, destination, candidate, ops, e)
                }
                if (asideOutcome is MoveOutcome.Moved) {
                    backup = candidate
                } else {
                    log(TAG, WARN) { "Cannot rename $destination aside ($asideOutcome), deleting it instead" }
                    val deleted = try {
                        ops.delete(destination, recursive = false)
                    } catch (e: Exception) {
                        throw clearFailure(staging, destination, null, ops, e)
                    }
                    if (!deleted) throw clearFailure(staging, destination, null, ops, null)
                }
            }

            val outcome = try {
                ops.move(staging, destination)
            } catch (e: Exception) {
                // The rename may have gone through and thrown afterwards - SAF verifies the name the
                // provider produced once the document has already moved. The replacement can be the
                // destination by now, so putting the original back would delete the only copy of the
                // new data. Name every place the data can be instead of guessing.
                val candidates = listOfNotNull(destination.name, staging.name, backup?.name)
                throw DataKeptException(
                    "Could not replace ${destination.name}, the data is in one of ${candidates.joinToString()}",
                    destination,
                    DataKeptException.Recovery.Uncertain(original = backup?.name, newData = staging.name),
                    e,
                )
            }
            if (outcome is MoveOutcome.Moved) {
                backup?.let { discardBackup(it, ops) }
                return@withContext
            }

            // A provider without rename support would leave the replacement behind a hidden name,
            // so stream it into place instead.
            log(TAG, WARN) { "Staging rename unavailable ($outcome), streaming $staging into $destination" }
            try {
                ops.createFile(destination)
                ops.openInputStream(staging).use { input ->
                    ops.openOutputStream(destination).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                backup?.let { throw restore(it, staging, destination, ops, e) }
                throw DataKeptException(
                    "Could not replace ${destination.name}, data kept as ${staging.name}",
                    destination,
                    DataKeptException.Recovery.NewData(staging.name),
                    e,
                )
            }
            runCatching { ops.delete(staging) }
                .onFailure { log(TAG, WARN) { "Staging copy survived the fallback: $staging" } }
            backup?.let { discardBackup(it, ops) }
        }
    }

    /** Best-effort removal of a staging file whose transfer failed. */
    suspend fun <P : APath<P>, PL : APathLookup<P>> discard(staging: P, ops: FileSystemOps<P, PL>) {
        withContext(NonCancellable) {
            runCatching { if (ops.exists(staging)) ops.delete(staging, recursive = false) }
                .onFailure { log(TAG, WARN) { "Could not discard staging file $staging: ${it.asLog()}" } }
        }
    }

    /**
     * A hidden sibling of [destination] carrying [marker] and the original file extension, because
     * the extension is what SAF document creation derives the document MIME type from.
     *
     * @throws WriteException if [destination] has no parent, or no free name was found
     */
    private suspend fun <P : APath<P>, PL : APathLookup<P>> siblingPathFor(
        destination: P,
        marker: String,
        ops: FileSystemOps<P, PL>,
    ): P {
        val parent = destination.parent
            ?: throw WriteException("Destination has no parent directory", destination)

        repeat(NAME_ATTEMPTS) {
            val candidate = parent.child(".${Uuid.random().toString().take(8)}.$marker.${destination.name}")
            if (!ops.exists(candidate)) return candidate
        }

        throw WriteException("Could not find a free .$marker. sibling name for ${destination.name}", destination)
    }

    /**
     * Put [backup] back at [destination] after a failed swap-in and describe what survived where:
     * the replacement stays at [staging] either way, the original only moves back if the restore
     * itself worked.
     */
    private suspend fun <P : APath<P>, PL : APathLookup<P>> restore(
        backup: P,
        staging: P,
        destination: P,
        ops: FileSystemOps<P, PL>,
        cause: Exception,
    ): DataKeptException {
        runCatching { if (ops.exists(destination)) ops.delete(destination, recursive = false) }
            .onFailure { log(TAG, WARN) { "Partial replacement blocks restoring $destination: ${it.asLog()}" } }

        val restored = runCatching { ops.move(backup, destination) }
            .onFailure { log(TAG, ERROR) { "Could not restore $destination from $backup: ${it.asLog()}" } }
            .getOrNull() is MoveOutcome.Moved

        return if (restored) {
            DataKeptException(
                "Could not replace ${destination.name}, the original was restored and the new data kept as ${staging.name}",
                destination,
                DataKeptException.Recovery.OriginalInPlace(staging.name),
                cause,
            )
        } else {
            DataKeptException(
                "Could not replace ${destination.name}, the original is kept as ${backup.name}, the new data as ${staging.name}",
                destination,
                DataKeptException.Recovery.BothKept(original = backup.name, newData = staging.name),
                cause,
            )
        }
    }

    /**
     * Clearing [destination] for the replacement failed, and a rename aside can still have consumed
     * it: SAF verifies the name a provider produced once the document has already moved. The data
     * that was staged stays where it is either way, so name it, plus [backupCandidate] whenever the
     * destination is no longer answering as present.
     */
    private suspend fun <P : APath<P>, PL : APathLookup<P>> clearFailure(
        staging: P,
        destination: P,
        backupCandidate: P?,
        ops: FileSystemOps<P, PL>,
        cause: Exception?,
    ): DataKeptException {
        val stillThere = runCatching { ops.exists(destination) }
            .onFailure { log(TAG, WARN) { "Cannot tell whether $destination survived: ${it.asLog()}" } }
            .getOrDefault(false)

        return when {
            stillThere -> DataKeptException(
                "Could not clear ${destination.name} for the replacement, the new data is kept as ${staging.name}",
                destination,
                DataKeptException.Recovery.OriginalInPlace(staging.name),
                cause,
            )

            backupCandidate != null -> DataKeptException(
                "Could not move ${destination.name} aside, it may be ${backupCandidate.name}, " +
                    "the new data is kept as ${staging.name}",
                destination,
                DataKeptException.Recovery.BothKept(original = backupCandidate.name, newData = staging.name),
                cause,
            )

            else -> DataKeptException(
                "Could not clear ${destination.name} for the replacement, the new data is kept as ${staging.name}",
                destination,
                DataKeptException.Recovery.NewData(staging.name),
                cause,
            )
        }
    }

    private suspend fun <P : APath<P>, PL : APathLookup<P>> discardBackup(backup: P, ops: FileSystemOps<P, PL>) {
        val deleted = runCatching { ops.delete(backup, recursive = false) }
            .onFailure { log(TAG, WARN) { "Could not remove replaced original $backup: ${it.asLog()}" } }
            .getOrDefault(false)
        if (!deleted) log(TAG, WARN) { "Replaced original survived as $backup" }
    }

    private const val NAME_ATTEMPTS = 8
    private val TAG = logTag("IO", "StagedReplace")
}
