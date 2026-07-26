package eu.darken.butler.apps.core.details.components

import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Instant

/**
 * Component data and sheet selection for the App Details page.
 *
 * Owned by the ViewModel rather than by composition: the page's `Content` and `Overlays` slots are
 * sibling subtrees that share only the ViewModel, so both have to read one source of truth.
 *
 * Loading is two-phase. The overview stays as cheap as it was — the manifest listing only — and the
 * per-component enablement pass runs solely for the Components route, upgrading the chips in place.
 */
class AppComponentsController(
    private val scope: CoroutineScope,
    private val loader: AppComponentsLoader,
) {

    private val tag = logTag("AppDetails", "Components", "Controller")

    private data class AppIdentity(
        val packageName: String,
        val versionCode: Long,
        val updatedAt: Instant?,
    )

    private val identity = MutableStateFlow<AppIdentity?>(null)
    private val routeActive = MutableStateFlow(false)
    private val selectedKey = MutableStateFlow<String?>(null)

    /**
     * `flatMapLatest` on the identity is what makes the ordering safe structurally: an identity
     * change cancels the whole inner flow — load *and* enrichment — so a stale result can never be
     * emitted afterwards and no generation counter is needed.
     */
    val state: StateFlow<ComponentsUiState> = identity
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(ComponentsUiState.Loading)
            } else {
                flow {
                    emit(ComponentsUiState.Loading)
                    val data = try {
                        loader.load(id.packageName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(tag, WARN) { "Failed to load components for ${id.packageName}: ${e.asLog()}" }
                        emit(ComponentsUiState.Error)
                        return@flow
                    }
                    emit(ComponentsUiState.Ready(data))
                    // Fires as soon as the route is active — including when it already was before
                    // the load finished, because routeActive is a StateFlow — and re-fires on every
                    // re-entry, which is the refresh path. List membership is never reloaded: with
                    // MATCH_DISABLED_COMPONENTS a component is listed regardless of its state, so
                    // only the state can change under us.
                    emitAll(
                        routeActive.filter { it }.mapLatest {
                            try {
                                ComponentsUiState.Ready(data.withEnabledStates(loader.resolveEnabledStates(data)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log(tag, WARN) { "Failed to resolve enabled states: ${e.asLog()}" }
                                ComponentsUiState.Error
                            }
                        }
                    )
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ComponentsUiState.Loading)

    /**
     * The component the sheet shows, resolved from the key against the current data so the sheet
     * always reflects post-enrichment state and closes when the component disappears.
     *
     * Eagerly shared and gated on the route: an `Overlays` remount (rotation, pane-layout change)
     * must read the current selection immediately, because a null first frame would briefly unmount
     * the sheet's layer and re-arm the page's back handler.
     */
    val selectedComponent: StateFlow<ComponentEntry?> = combine(
        state,
        selectedKey,
        routeActive,
    ) { current, key, active ->
        if (!active || key == null) {
            null
        } else {
            (current as? ComponentsUiState.Ready)?.data?.all?.firstOrNull { it.key == key }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** `null` (workspace closed, package gone) cancels both phases through `flatMapLatest`. */
    fun onAppChanged(app: AppInfo?) {
        val next = app?.let { AppIdentity(it.packageName, it.versionCode, it.updatedAt) }
        if (next == identity.value) return
        log(tag) { "onAppChanged(): $next" }
        identity.value = next
        selectedKey.value = null
    }

    fun onComponentsRouteActive(active: Boolean) {
        if (routeActive.value == active) return
        log(tag) { "onComponentsRouteActive($active)" }
        routeActive.value = active
        if (!active) selectedKey.value = null
    }

    fun select(entry: ComponentEntry) {
        log(tag) { "select(${entry.key})" }
        selectedKey.value = entry.key
    }

    fun dismiss() {
        log(tag) { "dismiss()" }
        selectedKey.value = null
    }
}
