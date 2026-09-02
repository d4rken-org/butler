package eu.darken.butler.history.ui

import android.content.Context
import android.content.Intent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.history.core.HistoryWorkspace
import eu.darken.butler.history.core.buildHistoryShareText
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@HiltViewModel(assistedFactory = HistoryWorkspaceViewModel.Factory::class)
class HistoryWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
    private val historyRepo: OperationHistoryRepo,
    private val historySettings: HistorySettings,
) : ViewModel3(dispatchers, logTag("History", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource = workspaceProvider.retrieve(id)
        .map { it as? HistoryWorkspace }
        .filterNotNull()

    private val filterFlow = workspaceSource.flatMapLatest { it.filter }

    private val maxItemsFlow = historySettings.maxHistoryItems.flow

    private val filterAndEntries = combine(filterFlow, maxItemsFlow) { f, m -> f to m }
        .flatMapLatest { (filter, max) ->
            historyRepo.query(filter, max).map { entries -> filter to entries }
        }

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val state = combine(
        filterAndEntries,
        historyRepo.observeCount(),
        _selectedIds,
    ) { pair, totalCount, rawSelected ->
        val (filter, entries) = pair
        State(
            id = id,
            filter = filter,
            groups = groupByDate(entries, Clock.System.now(), ZoneId.systemDefault()),
            entryCount = entries.size,
            totalCount = totalCount,
            hasAnyHistory = totalCount > 0,
            // Pruned on read: a retention trim or a delete in another history tab removes rows this
            // tab still has ids for, and a stale id would keep the action bar showing a count that
            // no longer exists.
            selectedIds = rawSelected intersect entries.map { it.id }.toSet(),
        )
    }.asStateFlow()

    // Overlay visibility lives here rather than in the page: the overlays are composed as a sibling
    // of the page, so a `remember` in the page would be a different instance from the one the
    // overlays read.
    private val _overlayState = MutableStateFlow(OverlayState())
    val overlayState: StateFlow<OverlayState> = _overlayState

    init {
        log(tag) { "Initialized for workspace $id" }
    }

    // Not `launch`: visibility must be applied in call order, and the coroutine dispatcher is a
    // multithreaded pool - two rapid taps could otherwise apply their updates in either order and
    // leave the wrong entry open.
    fun showEntryDetails(entry: HistoryEntry?) {
        _overlayState.update { it.copy(detailEntry = entry, attemptedPaths = emptyList(), attemptedPathsTotal = 0) }
        // Entries without reported changes would show an empty sheet, so fill it with what the
        // operation tried to touch. Loaded on demand: the list query doesn't carry the scope index.
        if (entry == null || entry.paths.isNotEmpty()) return
        launch {
            val attempted = historyRepo.getAttemptedPaths(entry.id)
            _overlayState.update {
                if (it.detailEntry?.id != entry.id) {
                    it
                } else {
                    it.copy(attemptedPaths = attempted.paths, attemptedPathsTotal = attempted.totalCount)
                }
            }
        }
    }

    fun setAddFilterOpen(open: Boolean) {
        _overlayState.update { it.copy(addFilterOpen = open) }
    }

    fun openPathScopePicker() {
        _overlayState.update { it.copy(addFilterOpen = false, pathScopeOpen = true) }
    }

    fun closePathScopePicker() {
        _overlayState.update { it.copy(pathScopeOpen = false) }
    }

    fun toggleOutcome(outcome: HistoryOutcome) = launch {
        applyFilter { current ->
            current.copy(
                outcomes = if (outcome in current.outcomes) current.outcomes - outcome else current.outcomes + outcome
            )
        }
    }

    fun toggleKind(kind: Operation.Metadata.Kind) = launch {
        applyFilter { current ->
            current.copy(
                kinds = if (kind in current.kinds) current.kinds - kind else current.kinds + kind
            )
        }
    }

    fun addPathScope(rawScope: String) = launch {
        val normalized = OperationHistoryRepo.normalizePathScope(rawScope) ?: return@launch
        applyFilter { it.copy(pathScopes = it.pathScopes + normalized) }
    }

    fun removePathScope(scope: String) = launch {
        applyFilter { it.copy(pathScopes = it.pathScopes - scope) }
    }

    fun clearFilter() = launch {
        applyFilter { HistoryFilter() }
    }

    // Not `launch`, for the same call-order reason as showEntryDetails.
    fun toggleSelection(entryId: String) {
        _selectedIds.update { if (entryId in it) it - entryId else it + entryId }
    }

    fun setSelection(ids: Set<String>) {
        _selectedIds.value = ids
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun onActionClick(item: HistoryActionBarItem) {
        log(tag) { "onActionClick($item)" }
        when (item) {
            is HistoryActionBarItem.SelectAll -> setSelection(item.ids)
            is HistoryActionBarItem.DeselectAll -> clearSelection()
            is HistoryActionBarItem.Share -> shareEntries(item.entries)
            is HistoryActionBarItem.Delete -> _overlayState.update { it.copy(deleteConfirmEntries = item.entries) }
        }
    }

    // Selection and dialog are reset only after the delete returns: a failed delete has to leave
    // both standing, so the error the handler shows still matches what the user sees.
    fun confirmDeleteSelected() = launch {
        val ids = _overlayState.value.deleteConfirmEntries.map { it.id }
        log(tag, INFO) { "confirmDeleteSelected(): ${ids.size} entries" }
        historyRepo.delete(ids)
        clearSelection()
        dismissDeleteConfirm()
    }

    fun dismissDeleteConfirm() {
        _overlayState.update { it.copy(deleteConfirmEntries = emptyList()) }
    }

    fun shareEntry(entry: HistoryEntry) {
        val overlay = _overlayState.value
        // The sheet has already loaded these for an entry that reported no changes; a bulk share
        // has none and says so instead of issuing a query per entry.
        val attempted = overlay.attemptedPaths
            .takeIf { it.isNotEmpty() && overlay.detailEntry?.id == entry.id }
            ?.let { OperationHistoryRepo.AttemptedPaths(it, overlay.attemptedPathsTotal) }
        shareEntries(listOf(entry), attempted)
    }

    private fun shareEntries(
        entries: List<HistoryEntry>,
        attemptedPaths: OperationHistoryRepo.AttemptedPaths? = null,
    ) {
        log(tag, INFO) { "shareEntries(): ${entries.size} entries" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildHistoryShareText(context, entries, attemptedPaths))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        clearSelection()
    }

    // Every filter mutation funnels through here, so this is what ends selection mode on a filter
    // change. Pruning alone would empty the visible selection while keeping the raw ids, and
    // resetting the filter would bring rows back already checked.
    private suspend fun applyFilter(transform: (HistoryFilter) -> HistoryFilter) {
        _selectedIds.value = emptySet()
        val workspace = workspaceSource.first()
        workspace.updateFilter(transform)
    }

    data class State(
        val id: Workspace.Id,
        val filter: HistoryFilter,
        val groups: List<DateGroup>,
        val entryCount: Int,
        val totalCount: Int,
        val hasAnyHistory: Boolean,
        val selectedIds: Set<String> = emptySet(),
    ) {
        val selectionActive: Boolean get() = selectedIds.isNotEmpty()

        val selectedEntries: List<HistoryEntry>
            get() = groups.flatMap { it.entries }.filter { it.id in selectedIds }

        val availableActions: List<HistoryActionBarItem>
            get() = historyActionsFor(selectedEntries, groups.flatMap { it.entries })
    }

    data class OverlayState(
        val detailEntry: HistoryEntry? = null,
        val attemptedPaths: List<String> = emptyList(),
        val attemptedPathsTotal: Int = 0,
        val addFilterOpen: Boolean = false,
        val pathScopeOpen: Boolean = false,
        val deleteConfirmEntries: List<HistoryEntry> = emptyList(),
    )

    data class DateGroup(
        val key: GroupKey,
        val entries: List<HistoryEntry>,
    )

    enum class GroupKey { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, OLDER }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): HistoryWorkspaceViewModel
    }

    companion object {
        /**
         * Buckets by *calendar* day in [zone]. Dividing epoch millis by a day length buckets by UTC
         * day instead, which puts entries under the wrong header everywhere but UTC - the boundary
         * sits at 2am in Berlin and at 5pm the previous day in Los Angeles.
         */
        internal fun groupByDate(
            entries: List<HistoryEntry>,
            now: Instant,
            zone: ZoneId,
        ): List<DateGroup> {
            if (entries.isEmpty()) return emptyList()
            val today = now.toLocalDate(zone)
            val grouped = entries.groupBy { entry ->
                val ageDays = ChronoUnit.DAYS.between(entry.completedAt.toLocalDate(zone), today)
                when {
                    ageDays <= 0 -> GroupKey.TODAY
                    ageDays == 1L -> GroupKey.YESTERDAY
                    ageDays in 2..6 -> GroupKey.THIS_WEEK
                    ageDays in 7..29 -> GroupKey.THIS_MONTH
                    else -> GroupKey.OLDER
                }
            }
            return GroupKey.entries.mapNotNull { key ->
                grouped[key]?.let { DateGroup(key, it) }
            }
        }

        private fun Instant.toLocalDate(zone: ZoneId): LocalDate =
            toJavaInstant().atZone(zone).toLocalDate()
    }
}
