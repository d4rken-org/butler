package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.sorting.rules.db.FolderSortRuleDao
import eu.darken.butler.explorer.core.sorting.rules.db.FolderSortRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The persistent layer of per-folder sort rules.
 *
 * Lookups return ALL applicable candidates rather than a pre-reduced winner, because the resolver
 * needs the runner-up to tell the user which rule a marker or a nearer rule is hiding.
 */
@Singleton
class FolderSortRulesRepo @Inject constructor(
    private val dao: FolderSortRuleDao,
    private val json: Json,
) {

    data class ResolvedRule(
        val pathKey: String,
        val path: APath<*>,
        /** Null means the folder carries a "use the default here" marker. */
        val settings: SortSettings?,
        val subtree: Boolean,
    )

    /** Candidates for [path]: its own rule plus every ancestor rule, un-reduced. */
    fun observeRulesFor(path: APath<*>): Flow<List<ResolvedRule>> {
        val keys = path.sortAncestorKeys()
        return dao.observeForKeys(keys).map { rows -> rows.mapNotNull { it.resolve() } }
    }

    val count: Flow<Int> = dao.observeCount()

    fun observeAll(): Flow<List<ResolvedRule>> = dao.observeAll().map { rows -> rows.mapNotNull { it.resolve() } }

    suspend fun set(path: APath<*>, settings: SortSettings, subtree: Boolean) {
        log(TAG) { "set($path, $settings, subtree=$subtree)" }
        dao.upsert(
            FolderSortRuleEntity(
                pathKey = path.sortPathKey(),
                path = path.serialize(),
                followsDefault = false,
                mode = settings.mode.name,
                reversed = settings.reversed,
                subtree = subtree,
                updatedAt = Clock.System.now(),
            )
        )
    }

    suspend fun setFollowsDefault(path: APath<*>) {
        log(TAG) { "setFollowsDefault($path)" }
        dao.upsert(
            FolderSortRuleEntity(
                pathKey = path.sortPathKey(),
                path = path.serialize(),
                followsDefault = true,
                mode = null,
                reversed = false,
                subtree = false,
                updatedAt = Clock.System.now(),
            )
        )
    }

    suspend fun clear(path: APath<*>) {
        log(TAG) { "clear($path)" }
        dao.delete(path.sortPathKey())
    }

    suspend fun clearAll() {
        log(TAG) { "clearAll()" }
        dao.deleteAll()
    }

    private fun APath<*>.serialize(): String = json.encodeToString(PolymorphicSerializer(APath::class), this)

    /**
     * Rows that this build cannot make sense of are skipped per row, never thrown: one row written by
     * a newer build - or corrupted - must not take the whole observer down and leave the listing
     * without a sort.
     */
    private fun FolderSortRuleEntity.resolve(): ResolvedRule? {
        val decodedPath = try {
            json.decodeFromString(PolymorphicSerializer(APath::class), path)
        } catch (e: Exception) {
            log(TAG, WARN) { "Skipping rule $pathKey, its path is unreadable: ${e.asLog()}" }
            return null
        }

        val settings = when {
            followsDefault -> null
            else -> {
                val parsedMode = SortSettings.Mode.entries.firstOrNull { it.name == mode }
                if (parsedMode == null) {
                    log(TAG, WARN) { "Skipping rule $pathKey, mode '$mode' is unknown to this build" }
                    return null
                }
                SortSettings(mode = parsedMode, reversed = reversed)
            }
        }

        return ResolvedRule(
            pathKey = pathKey,
            path = decodedPath,
            settings = settings,
            subtree = subtree,
        )
    }

    companion object {
        private val TAG = logTag("Explorer", "Sorting", "RulesRepo")
    }
}
