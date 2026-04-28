package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.Ownership
import kotlinx.coroutines.flow.Flow

enum class AccessMode { DIRECT, ISOLATED, ROOT, ADB }

sealed interface AccessIntent {
    data object Read : AccessIntent
    data object Write : AccessIntent
    data object Delete : AccessIntent
}

/**
 * Snapshot of routing capabilities (root, ADB) evaluated lazily on first access.
 *
 * Capabilities are queried only when the routing policy actually needs them — i.e. when a
 * path classifies as restricted/system-blocked and the policy needs to fall back to elevated
 * mode. DIRECT-route paths (the common case) never trigger the providers.
 *
 * The providers are idempotent (`useRoot.first()` and `useAdb.first()` return cached values
 * after the first emission), so the @Volatile cache may double-call under concurrency without
 * harm.
 */
class CapabilitySnapshot(
    private val rootProvider: suspend () -> Boolean,
    private val adbProvider: suspend () -> Boolean,
) {
    @Volatile private var rootCached: Boolean? = null
    @Volatile private var adbCached: Boolean? = null

    suspend fun hasRoot(): Boolean = rootCached ?: rootProvider().also { rootCached = it }
    suspend fun hasAdb(): Boolean = adbCached ?: adbProvider().also { adbCached = it }

    companion object {
        /** Pre-resolved snapshot — useful for tests that don't need lazy semantics. */
        fun fixed(hasRoot: Boolean, hasAdb: Boolean): CapabilitySnapshot =
            CapabilitySnapshot({ hasRoot }, { hasAdb })
    }
}

sealed interface RouteDecision {
    data class Allowed(val mode: AccessMode) : RouteDecision
    data object Denied : RouteDecision
}

data class Route(
    val mode: AccessMode,
    val ops: FileSystemOps<LocalPath, LocalPathLookup>,
    val batch: ClientBatchOps? = null,
    val session: ModeSession? = null,
)

class RouteUnavailableException(
    val path: LocalPath,
    val intent: AccessIntent,
) : Exception("No route available for $intent on $path")

enum class BatchOperation { COPY, MOVE, DELETE }

data class BatchEligibilityRequest(
    val operation: BatchOperation,
    val sourceRoot: LocalPath,
    val sourceIntent: AccessIntent,
    val destinationRoot: LocalPath?,
    val destinationIntent: AccessIntent?,
    val sourceRoute: Route,
    val destinationRoute: Route?,
    val options: Any,
)

sealed interface BatchEligibility {
    data class Eligible(
        val mode: AccessMode,
        val destinationModeOverride: AccessMode?,
        val ownershipFixup: OwnershipFixup,
    ) : BatchEligibility

    data class Ineligible(val reason: String) : BatchEligibility
}

sealed interface OwnershipFixup {
    data object None : OwnershipFixup
    data class InheritNearestExistingDestinationOwner(val owner: Ownership) : OwnershipFixup
}

interface ClientBatchOps {
    suspend fun copySubtreeExact(
        sourceRoot: LocalPath,
        destinationRoot: LocalPath,
        options: CopyAction.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>

    suspend fun moveSubtreeExact(
        sourceRoot: LocalPath,
        destinationRoot: LocalPath,
        options: MoveAction.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>

    suspend fun deleteSubtree(
        root: LocalPath,
        options: DeleteAction.Options<LocalPath>,
    ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>>
}

internal interface IntentAwareFileSystemOps<P : APath<P>, PL : APathLookup<P>> : FileSystemOps<P, PL> {
    suspend fun lookup(path: P, intent: AccessIntent, options: LookupOptions): PL
    suspend fun lookupFiles(path: P, intent: AccessIntent, options: LookupOptions): List<PL>
    suspend fun ensurePlanned(path: P, intent: AccessIntent)
    suspend fun modeOf(path: P, intent: AccessIntent): AccessMode
    fun proactiveChildren(parent: P): Set<P>
    suspend fun installLogicalAlias(alias: P, resolved: P, intent: AccessIntent)
    fun unknownLookup(path: P, error: Exception): PL?
}

internal suspend fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.lookupForIntent(
    path: P,
    intent: AccessIntent,
    options: LookupOptions,
): PL = when (this) {
    is IntentAwareFileSystemOps<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).lookup(path, intent, options)
    }
    else -> lookup(path, options)
}

internal suspend fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.lookupFilesForIntent(
    path: P,
    intent: AccessIntent,
    options: LookupOptions,
): List<PL> = when (this) {
    is IntentAwareFileSystemOps<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).lookupFiles(path, intent, options)
    }
    else -> lookupFiles(path, options)
}

internal suspend fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.ensurePlannedForIntent(
    path: P,
    intent: AccessIntent,
) {
    if (this is IntentAwareFileSystemOps<*, *>) {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).ensurePlanned(path, intent)
    }
}

internal suspend fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.modeForIntentOrNull(
    path: P,
    intent: AccessIntent,
): AccessMode? = when (this) {
    is IntentAwareFileSystemOps<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).modeOf(path, intent)
    }
    else -> null
}

internal fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.proactiveChildrenOf(parent: P): Set<P> =
    when (this) {
        is IntentAwareFileSystemOps<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            (this as IntentAwareFileSystemOps<P, PL>).proactiveChildren(parent)
        }
        else -> emptySet()
    }

internal fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.unknownLookupOrNull(
    path: P,
    error: Exception,
): PL? = when (this) {
    is IntentAwareFileSystemOps<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).unknownLookup(path, error)
    }
    else -> null
}

internal suspend fun <P : APath<P>, PL : APathLookup<P>> FileSystemOps<P, PL>.installLogicalAliasForIntent(
    alias: P,
    resolved: P,
    intent: AccessIntent,
) {
    if (this is IntentAwareFileSystemOps<*, *>) {
        @Suppress("UNCHECKED_CAST")
        (this as IntentAwareFileSystemOps<P, PL>).installLogicalAlias(alias, resolved, intent)
    }
}
