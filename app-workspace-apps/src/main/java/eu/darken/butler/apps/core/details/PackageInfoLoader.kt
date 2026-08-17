package eu.darken.butler.apps.core.details

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * Lazy loader for the Package info route: nothing is inspected until the route is entered at least
 * once, and every re-entry re-runs the (cheap, package-query-first) load.
 *
 * `flatMapLatest` provides latest-wins cancellation: a re-entry or an app update cancels an
 * in-flight load. Leaving the route does NOT cancel one - the trigger simply does not change.
 * Nothing caches a failure, so re-entering is also the retry path after [PackageInfoState.Unavailable].
 */
class PackageInfoLoader(
    scope: CoroutineScope,
    appInfo: Flow<AppInfo?>,
    private val load: suspend (AppInfo) -> PackageInfoState,
) {

    private val tag = logTag("AppDetails", "PackageInfo", "Loader")

    private val trigger = MutableStateFlow(0)

    val state: StateFlow<PackageInfoState> = combine(
        trigger.filter { it > 0 },
        appInfo.filterNotNull().distinctUntilChangedBy { it.identity },
    ) { attempt, app -> attempt to app }
        .flatMapLatest { (attempt, app) ->
            flow {
                log(tag) { "Loading package info for ${app.packageName} (attempt $attempt)" }
                emit(PackageInfoState.Loading)
                emit(load(app))
            }
        }
        .stateIn(scope, SharingStarted.Lazily, PackageInfoState.Loading)

    /** Called on every entry into the route; the first one starts the very first load. */
    fun onRequested() {
        trigger.update { it + 1 }
    }

    /** An app update re-keys this, which cancels an in-flight load and re-runs it. */
    private val AppInfo.identity: Triple<Any, Long, Instant?>
        get() = Triple(installId, versionCode, updatedAt)
}
