package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.DefaultStartLocation
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

@HiltViewModel
class ExplorerSettingsViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val explorerSettings: ExplorerSettings,
    private val trashSettings: TrashSettings,
    private val folderSortRules: FolderSortRulesRepo,
    private val hostedPickers: HostedPickerRequests,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings", "Screen", "VM")) {

    private val startLocationDialog = MutableStateFlow<StartLocationDialog?>(null)
    private var pickerJob: Job? = null
    private var nextDialogId = 0L

    val state = combine(
        explorerSettings.sortSettings.flow,
        explorerSettings.useRegexPatterns.flow,
        explorerSettings.useBackButtonForNavigation.flow,
        explorerSettings.openFilesOnTap.flow,
        explorerSettings.showSystemLocations.flow,
        trashSettings.enabled.flow,
        trashSettings.expiresAfter.flow,
        trashSettings.maxTrashSize.flow,
        folderSortRules.count,
        trashSettings.confirmTrashMove.flow,
        explorerSettings.defaultStartLocation.flow,
        startLocationDialog,
    ) { sortSettings, useRegexPatterns, useBackButtonForNavigation, openFilesOnTap, showSystemLocations, recycleBinEnabled, expiresAfter, maxSize, folderSortRuleCount, confirmTrashMove, startLocation, dialog ->
        State(
            sortSettings = sortSettings,
            folderSortRuleCount = folderSortRuleCount,
            useRegexPatterns = useRegexPatterns,
            useBackButtonForNavigation = useBackButtonForNavigation,
            openFilesOnTap = openFilesOnTap,
            showSystemLocations = showSystemLocations,
            trashEnabled = recycleBinEnabled,
            trashAutoDeleteDays = expiresAfter.inWholeDays.toInt(),
            trashMaxSizeMB = maxSize / 1048576L,
            confirmTrashMove = confirmTrashMove,
            startLocation = startLocation ?: DefaultStartLocation.Home,
            startLocationDialog = dialog,
        )
    }.asStateFlow()

    fun openStartLocationDialog() = launch {
        log(tag) { "openStartLocationDialog()" }
        val current = explorerSettings.defaultStartLocation.value() ?: DefaultStartLocation.Home
        startLocationDialog.value = StartLocationDialog(
            id = nextDialogId++,
            selection = current,
            folder = (current as? DefaultStartLocation.Directory)?.path,
        )
    }

    fun selectStartLocation(selection: DefaultStartLocation) {
        log(tag) { "selectStartLocation($selection)" }
        startLocationDialog.update { it?.copy(selection = selection) }
    }

    fun chooseStartFolder() {
        log(tag) { "chooseStartFolder()" }
        val draft = startLocationDialog.value?.takeIf { !it.picking } ?: return
        startLocationDialog.update { it.ifDraft(draft.id) { copy(picking = true) } }
        val ticket = hostedPickers.register(HostedPickerRequests.Request(startPath = draft.folder))
        // Undispatched, so the wait (and with it the abandon-on-cancel) is in place before anything can cancel it
        pickerJob = vmScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = hostedPickers.awaitOutcome(ticket)
                if (outcome !is HostedPickerRequests.Outcome.Picked) return@launch
                val path = outcome.paths.singleOrNull()
                if (path == null) {
                    log(tag, WARN) { "Expected exactly one folder, got ${outcome.paths}" }
                    return@launch
                }
                startLocationDialog.update {
                    it.ifDraft(draft.id) { copy(selection = DefaultStartLocation.Directory(path), folder = path) }
                }
            } finally {
                startLocationDialog.update { it.ifDraft(draft.id) { copy(picking = false) } }
            }
        }
        navTo(ticket.destination)
    }

    fun confirmStartLocation() {
        val draft = startLocationDialog.value?.takeIf { !it.picking } ?: return
        log(tag) { "confirmStartLocation(${draft.selection})" }
        launch {
            explorerSettings.defaultStartLocation.value(draft.selection)
            withContext(dispatcherProvider.Main) {
                if (startLocationDialog.value?.id == draft.id) dismissStartLocationDialog()
            }
        }
    }

    fun dismissStartLocationDialog() {
        log(tag) { "dismissStartLocationDialog()" }
        pickerJob?.cancel()
        pickerJob = null
        startLocationDialog.value = null
    }

    private inline fun StartLocationDialog?.ifDraft(
        id: Long,
        transform: StartLocationDialog.() -> StartLocationDialog,
    ): StartLocationDialog? = if (this?.id == id) transform() else this

    fun toggleRegexPatterns(enabled: Boolean) = launch {
        explorerSettings.useRegexPatterns.value(enabled)
    }

    fun toggleBackButtonNavigation(enabled: Boolean) = launch {
        explorerSettings.useBackButtonForNavigation.value(enabled)
    }

    fun toggleOpenFilesOnTap(enabled: Boolean) = launch {
        log(tag) { "toggleOpenFilesOnTap($enabled)" }
        explorerSettings.openFilesOnTap.value(enabled)
    }

    fun toggleShowSystemLocations(enabled: Boolean) = launch {
        explorerSettings.showSystemLocations.value(enabled)
    }

    fun toggleTrash(enabled: Boolean) = launch {
        log(tag) { "toggleTrash($enabled)" }
        trashSettings.enabled.value(enabled)
    }

    fun toggleConfirmTrashMove(enabled: Boolean) = launch {
        log(tag) { "toggleConfirmTrashMove($enabled)" }
        trashSettings.confirmTrashMove.value(enabled)
    }

    fun setTrashAutoDeleteDays(days: Int) = launch {
        log(tag) { "setTrashAutoDeleteDays($days)" }
        trashSettings.expiresAfter.value(days.days)
    }

    fun setTrashMaxSizeMB(sizeMB: Long) = launch {
        log(tag) { "setTrashMaxSizeMB($sizeMB)" }
        trashSettings.maxTrashSize.value(sizeMB * 1048576L)
    }

    fun clearFolderSortRules() = launch {
        log(tag) { "clearFolderSortRules()" }
        folderSortRules.clearAll()
    }

    data class State(
        val sortSettings: SortSettings,
        val useRegexPatterns: Boolean,
        val useBackButtonForNavigation: Boolean,
        val openFilesOnTap: Boolean = false,
        val showSystemLocations: Boolean = false,
        val trashEnabled: Boolean = true,
        val confirmTrashMove: Boolean = true,
        val trashAutoDeleteDays: Int = 30,
        val trashMaxSizeMB: Long = 500L,
        val folderSortRuleCount: Int = 0,
        val startLocation: DefaultStartLocation = DefaultStartLocation.Home,
        val startLocationDialog: StartLocationDialog? = null,
    )

    /**
     * Unsaved choice of the start location dialog. [folder] stays offered after picking Home or
     * Device, so the user can switch back to it before confirming. [picking] hides the dialog while
     * the folder picker is open; [id] keeps a request from touching a later dialog.
     */
    data class StartLocationDialog(
        val id: Long,
        val selection: DefaultStartLocation,
        val folder: APath<*>?,
        val picking: Boolean = false,
    )
}