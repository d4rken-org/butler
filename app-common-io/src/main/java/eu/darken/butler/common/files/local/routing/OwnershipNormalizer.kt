package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import kotlinx.coroutines.CancellationException

class OwnershipNormalizationException(
    val root: LocalPath,
    val failures: List<Pair<LocalPath, Exception>>,
) : Exception("Failed to normalize ownership below $root (${failures.size} failures)")

class OwnershipNormalizer {
    suspend fun normalizeRecursively(
        root: LocalPath,
        owner: Ownership,
        ops: FileSystemOps<LocalPath, LocalPathLookup>,
    ) {
        val failures = mutableListOf<Pair<LocalPath, Exception>>()
        normalize(root, owner, ops, failures)
        if (failures.isNotEmpty()) throw OwnershipNormalizationException(root, failures)
    }

    private suspend fun normalize(
        path: LocalPath,
        owner: Ownership,
        ops: FileSystemOps<LocalPath, LocalPathLookup>,
        failures: MutableList<Pair<LocalPath, Exception>>,
    ) {
        val lookup = try {
            ops.lookup(path, LookupOptions.BASE.copy(fallbackToUnknown = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(path to e)
            return
        }

        if (lookup.fileType == FileType.UNKNOWN) {
            failures.add(path to IllegalStateException("Path disappeared during ownership normalization"))
            return
        }

        if (lookup.fileType == FileType.DIRECTORY) {
            val children = try {
                ops.listFiles(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add(path to e)
                // Keep trying to fix the parent even when child enumeration failed.
                emptyList()
            }
            children.forEach { normalize(it, owner, ops, failures) }
        }

        try {
            if (!ops.setOwnership(path, owner)) {
                failures.add(path to IllegalStateException("setOwnership returned false"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(path to e)
        }
    }
}
