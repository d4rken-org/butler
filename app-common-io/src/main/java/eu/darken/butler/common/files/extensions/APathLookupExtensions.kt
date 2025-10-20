package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.metadata.FileType
import kotlinx.coroutines.flow.Flow
import okio.FileHandle


val APathLookup<*>.isDirectory: Boolean
    get() = fileType == FileType.DIRECTORY

val APathLookup<*>.isSymlink: Boolean
    get() = fileType == FileType.SYMBOLIC_LINK

val APathLookup<*>.isFile: Boolean
    get() = fileType == FileType.FILE

suspend fun <P : APath<P>, PL : APathLookup<P>, GT : APathGateway<P, PL>> PL.walk(
    gateway: GT,
    options: APathGateway.WalkOptions<P, PL> = APathGateway.WalkOptions()
): Flow<PL> = lookedUp.walk(gateway, options)

suspend fun <P : APath<P>, PL : APathLookup<P>, GT : APathGateway<P, PL>> PL.du(
    gateway: GT,
    options: APathGateway.DuOptions<P, PL> = APathGateway.DuOptions()
): Long = lookedUp.du(gateway, options)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.exists(
    gateway: APathGateway<P, out APathLookup<P>>
): Boolean = lookedUp.exists(gateway)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.delete(
    gateway: APathGateway<P, PL>,
    options: DeleteAction.Options<P>,
) = setOf(this).delete(
    gateway = gateway,
    options = options
)

suspend fun <P : APath<P>, PL : APathLookup<P>> Collection<PL>.delete(
    gateway: APathGateway<P, PL>,
    options: DeleteAction.Options<P>,
) = this.map { it.lookedUp }.delete(
    gateway = gateway,
    options = options
)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.file(
    gateway: APathGateway<P, out APathLookup<P>>,
    readWrite: Boolean
): FileHandle = lookedUp.file(gateway, readWrite)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.canRead(
    gateway: APathGateway<P, out APathLookup<P>>
): Boolean = lookedUp.canRead(gateway)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.canWrite(
    gateway: APathGateway<P, out APathLookup<P>>
): Boolean = lookedUp.canWrite(gateway)

suspend fun <P : APath<P>, PL : APathLookup<P>> PL.lookupFiles(
    gateway: APathGateway<P, out APathLookup<P>>
): Collection<APathLookup<*>> = lookedUp.lookupFiles(gateway)

fun APathLookup<*>.matches(other: APath<*>): Boolean = lookedUp.matches(other)
fun APath<*>.matches(other: APathLookup<*>): Boolean = matches(other.lookedUp)
fun APathLookup<*>.matches(other: APathLookup<*>): Boolean = lookedUp.matches(other.lookedUp)

fun APathLookup<*>.startsWith(prefix: APath<*>): Boolean = lookedUp.startsWith(prefix)
fun APathLookup<*>.startsWith(prefix: APathLookup<*>): Boolean = lookedUp.startsWith(prefix.lookedUp)
fun APath<*>.startsWith(prefix: APathLookup<*>): Boolean = startsWith(prefix.lookedUp)

fun APath<*>.isChildOf(parent: APathLookup<*>): Boolean = isChildOf(parent.lookedUp)
fun APathLookup<*>.isChildOf(parent: APathLookup<*>): Boolean = lookedUp.isChildOf(parent.lookedUp)
fun APathLookup<*>.isChildOf(parent: APath<*>): Boolean = lookedUp.isChildOf(parent)

fun APathLookup<*>.isAncestorOf(descendant: APath<*>): Boolean = lookedUp.isAncestorOf(descendant)
fun APath<*>.isAncestorOf(descendant: APathLookup<*>): Boolean = isAncestorOf(descendant.lookedUp)
fun APathLookup<*>.isAncestorOf(descendant: APathLookup<*>): Boolean = lookedUp.isAncestorOf(descendant.lookedUp)

fun APathLookup<*>.isDescendantOf(ancestor: APath<*>): Boolean = lookedUp.isDescendantOf(ancestor)
fun APath<*>.isDescendantOf(ancestor: APathLookup<*>) = isDescendantOf(ancestor.lookedUp)
fun APathLookup<*>.isDescendantOf(ancestor: APathLookup<*>): Boolean = lookedUp.isDescendantOf(ancestor.lookedUp)

fun APathLookup<*>.isDescendantOfOrSelf(ancestor: APath<*>): Boolean = lookedUp.isDescendantOfOrSelf(ancestor)
fun APath<*>.isDescendantOfOrSelf(ancestor: APathLookup<*>): Boolean = isDescendantOfOrSelf(ancestor.lookedUp)
fun APathLookup<*>.isDescendantOfOrSelf(ancestor: APathLookup<*>): Boolean = lookedUp.isDescendantOfOrSelf(ancestor.lookedUp)

fun APathLookup<*>.isAncestorOfOrSelf(descendant: APath<*>): Boolean = lookedUp.isAncestorOfOrSelf(descendant)
fun APath<*>.isAncestorOfOrSelf(descendant: APathLookup<*>): Boolean = isAncestorOfOrSelf(descendant.lookedUp)
fun APathLookup<*>.isAncestorOfOrSelf(descendant: APathLookup<*>): Boolean = lookedUp.isAncestorOfOrSelf(descendant.lookedUp)

fun APathLookup<*>.isParentOf(child: APath<*>): Boolean = lookedUp.isParentOf(child)
fun APath<*>.isParentOf(child: APathLookup<*>): Boolean = isParentOf(child.lookedUp)
fun APathLookup<*>.isParentOf(child: APathLookup<*>): Boolean = lookedUp.isParentOf(child.lookedUp)

fun APathLookup<*>.removePrefix(prefix: APathLookup<*>, overlap: Int = 0) =
    lookedUp.removePrefix(prefix.lookedUp, overlap)

fun APath<*>.removePrefix(prefix: APathLookup<*>, overlap: Int = 0) =
    this.removePrefix(prefix.lookedUp, overlap)

fun APathLookup<*>.removePrefix(prefix: APath<*>, overlap: Int = 0) =
    lookedUp.removePrefix(prefix, overlap)

fun Collection<APathLookup<*>>.filterDistinctRoots(): Set<APathLookup<*>> {
    log(VERBOSE) { "Creating lookup map..." }
    val lookupMap = this.associateBy { it.lookedUp }
    log(VERBOSE) { "Lookup map created with ${lookupMap.size} entries, now filtering..." }
    return lookupMap.keys
        .filterDistinctRoots()
        .map { lookupMap.getValue(it) }
        .toSet()
        .also { log(VERBOSE) { "After filtering we got ${it.size} distinct roots" } }
}

val APathLookup<*>.extension: String?
    get() = lookedUp.extension