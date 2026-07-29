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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
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
    private val selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    private val refreshTicks = MutableStateFlow(0)

    /**
     * `flatMapLatest` on the identity is what makes the ordering safe structurally: an identity
     * change cancels the whole inner flow — load *and* enrichment — so a stale result can never be
     * emitted afterwards and no generation counter is needed.
     */
    val state: StateFlow<ComponentsUiState> = identity
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<ComponentsUiState>(ComponentsUiState.Loading)
            } else {
                // Pinned: inferred from the first emit, the builder's element type would narrow to
                // ComponentsUiState.Loading and reject every later emission.
                flow<ComponentsUiState> {
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
                    // re-entry and on every refresh() tick, which is how a toggle's result reaches
                    // the chips. List membership is never reloaded: with MATCH_DISABLED_COMPONENTS a
                    // component is listed regardless of its state, so only the state can change
                    // under us.
                    //
                    // transformLatest, not filter + mapLatest: the inactive edge has to reach the
                    // operator so it cancels an in-flight pass. Filtering it away would let the N
                    // binder calls run to completion after the user left the route and then emit —
                    // into a state the Overview summary card renders from too. The `false` edge
                    // emits nothing, so the last good result persists while the route is inactive.
                    emitAll(
                        combine(routeActive, refreshTicks) { active, _ -> active }.transformLatest { active ->
                            if (!active) return@transformLatest
                            val resolved: ComponentsUiState = try {
                                ComponentsUiState.Ready(data.withEnabledStates(loader.resolveEnabledStates(data)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log(tag, WARN) { "Failed to resolve enabled states: ${e.asLog()}" }
                                ComponentsUiState.Error
                            }
                            emit(resolved)
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

    /**
     * The multi-selection, resolved from the keys against the current data for the same reasons
     * [selectedComponent] is: a refresh keeps the entries current, keys that stopped resolving drop
     * out, and the list is empty while the route is inactive.
     */
    val selectedComponents: StateFlow<List<ComponentEntry>> = combine(
        state,
        selectedKeys,
        routeActive,
    ) { current, keys, active ->
        if (!active || keys.isEmpty()) {
            emptyList()
        } else {
            (current as? ComponentsUiState.Ready)?.data?.all?.filter { it.key in keys } ?: emptyList()
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** `null` (workspace closed, package gone) cancels both phases through `flatMapLatest`. */
    fun onAppChanged(app: AppInfo?) {
        val next = app?.let { AppIdentity(it.packageName, it.versionCode, it.updatedAt) }
        if (next == identity.value) return
        log(tag) { "onAppChanged(): $next" }
        identity.value = next
        selectedKey.value = null
        selectedKeys.value = emptySet()
    }

    fun onComponentsRouteActive(active: Boolean) {
        if (routeActive.value == active) return
        log(tag) { "onComponentsRouteActive($active)" }
        routeActive.value = active
        if (!active) {
            selectedKey.value = null
            selectedKeys.value = emptySet()
        }
    }

    /** Re-runs the enrichment pass against the loaded listing, e.g. after a component was toggled. */
    fun refresh() {
        log(tag) { "refresh()" }
        refreshTicks.update { it + 1 }
    }

    fun select(entry: ComponentEntry) {
        log(tag) { "select(${entry.key})" }
        selectedKey.value = entry.key
    }

    fun dismiss() {
        log(tag) { "dismiss()" }
        selectedKey.value = null
    }

    fun toggleSelection(entry: ComponentEntry) {
        log(tag) { "toggleSelection(${entry.key})" }
        selectedKeys.update { current ->
            if (entry.key in current) current - entry.key else current + entry.key
        }
    }

    fun clearSelection() {
        log(tag) { "clearSelection()" }
        selectedKeys.value = emptySet()
    }

    /** Mirrors `ExplorerSelectionController`: a tap extends an active selection, else it opens the sheet. */
    fun onItemClick(entry: ComponentEntry) {
        if (selectedKeys.value.isNotEmpty()) {
            toggleSelection(entry)
        } else {
            select(entry)
        }
    }

    fun onItemLongClick(entry: ComponentEntry) = toggleSelection(entry)
}
