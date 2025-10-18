package testhelpers

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Custom Kotest matchers for working with APathLookup collections in tests.
 *
 * These matchers simplify test assertions by automatically extracting the underlying
 * APath from APathLookup wrapper objects, making tests more readable.
 */

/**
 * Asserts that a set of paired lookups contains a specific path pair.
 *
 * @receiver Set of lookup pairs (SPL, DPL)
 * @param pair The expected path pair (SP, DP) to find
 *
 * Example:
 * ```
 * result.copied shouldContainPath (sourcePath to destPath)
 * ```
 */
infix fun <SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>, DPL : APathLookup<DP>>
        Set<Pair<SPL, DPL>>.shouldContainPath(pair: Pair<SP, DP>) {
    val paths = this.map { it.first.lookedUp to it.second.lookedUp }
    paths shouldContain pair
}

/**
 * Asserts that a set of lookups contains a specific path.
 *
 * @receiver Set of lookups (PL)
 * @param path The expected path (P) to find
 *
 * Example:
 * ```
 * result.skipped shouldContainPath sourcePath
 * ```
 */
infix fun <P : APath<P>, PL : APathLookup<P>>
        Set<PL>.shouldContainPath(path: P) {
    val paths = this.map { it.lookedUp }
    paths shouldContain path
}

/**
 * Asserts that a set of lookups equals a set of paths.
 *
 * @receiver Set of lookups (PL)
 * @param paths The expected set of paths (P)
 *
 * Example:
 * ```
 * result.skipped shouldBePaths setOf(path1, path2)
 * ```
 */
infix fun <P : APath<P>, PL : APathLookup<P>>
        Set<PL>.shouldBePaths(paths: Set<P>) {
    this.map { it.lookedUp }.toSet() shouldBe paths
}

/**
 * Asserts that a set of lookups does NOT equal a set of paths.
 *
 * @receiver Set of lookups (PL)
 * @param paths The paths that should NOT match
 *
 * Example:
 * ```
 * result.copied.map { it.first } shouldNotBePaths setOf(wrongPath)
 * ```
 */
infix fun <P : APath<P>, PL : APathLookup<P>>
        Set<PL>.shouldNotBePaths(paths: Set<P>) {
    this.map { it.lookedUp }.toSet() shouldBe paths
}

/**
 * Gets the first path pair from a set of paired lookups.
 *
 * @receiver Set of lookup pairs (SPL, DPL)
 * @return The first path pair (SP, DP)
 *
 * Example:
 * ```
 * result.copied.firstPath() shouldBe (sourcePath to destPath)
 * ```
 */
fun <SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>, DPL : APathLookup<DP>>
        Set<Pair<SPL, DPL>>.firstPath(): Pair<SP, DP> {
    val first = this.first()
    return first.first.lookedUp to first.second.lookedUp
}

/**
 * Converts a set of paired lookups to a set of path pairs.
 *
 * @receiver Set of lookup pairs (SPL, DPL)
 * @return Set of path pairs (SP, DP)
 *
 * Example:
 * ```
 * val pathPairs = result.copied.toPathPairs()
 * ```
 */
fun <SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>, DPL : APathLookup<DP>>
        Set<Pair<SPL, DPL>>.toPathPairs(): Set<Pair<SP, DP>> {
    return this.map { it.first.lookedUp to it.second.lookedUp }.toSet()
}

/**
 * Converts a set of lookups to a set of paths.
 *
 * @receiver Set of lookups (PL)
 * @return Set of paths (P)
 *
 * Example:
 * ```
 * val paths = result.skipped.toPaths()
 * ```
 */
fun <P : APath<P>, PL : APathLookup<P>>
        Set<PL>.toPaths(): Set<P> {
    return this.map { it.lookedUp }.toSet()
}
