package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.storage.saf.SAFPickerIntentBuilder
import eu.darken.butler.common.storage.saf.StorageProviderSuggester
import eu.darken.butler.common.storage.saf.StorageProviderSuggestion
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.permissions.core.SAFPickerGrant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * SAF storage location management: the add-storage sheet, the system directory picker
 * round-trips (manual and Android/data-grant driven), and location rename/removal.
 */
class ExplorerSafLocationController(
    private val context: Context,
    private val safLocationManager: SAFLocationManager,
    private val safPickerIntentBuilder: SAFPickerIntentBuilder,
    private val storageProviderSuggester: StorageProviderSuggester,
    private val dialogs: ExplorerDialogController,
    private val scope: CoroutineScope,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val currentLocation: suspend () -> ExplorerLocation?,
    private val clearSelection: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    private val showAddStorageSheetFlow = MutableStateFlow(false)
    val showAddStorageSheet: StateFlow<Boolean> = showAddStorageSheetFlow

    /**
     * Derived from the sheet, never written by a side job: [flatMapLatest] cancels a load whose
     * sheet was closed or reopened, so a stale list can neither overwrite a newer one nor be shown
     * again after an app was uninstalled.
     */
    val storageSuggestions: StateFlow<List<StorageProviderSuggestion>> = showAddStorageSheetFlow
        .flatMapLatest { visible ->
            when {
                visible -> flow { emit(storageProviderSuggester.getSuggestions()) }
                else -> flowOf(emptyList())
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _pendingSAFPickerGrant = MutableStateFlow<SAFPickerGrant?>(null)
    val pendingSAFPickerGrant: Flow<SAFPickerGrant?> = _pendingSAFPickerGrant

    val safPickerEvents = SingleEventFlow<Intent>()

    /** Drop a staged grant whose picker never opened, so it can't be applied to a later result. */
    fun clearPendingSAFPickerGrant() {
        _pendingSAFPickerGrant.value = null
    }

    fun showAddStorageSheet() {
        log(tag) { "showAddStorageSheet(): Showing add storage sheet" }
        showAddStorageSheetFlow.value = true
    }

    fun dismissAddStorageSheet() {
        log(tag) { "dismissAddStorageSheet(): Dismissing add storage sheet" }
        showAddStorageSheetFlow.value = false
    }

    fun addSAFLocation() = doLaunch {
        log(tag) { "addSAFLocation(): Launching SAF directory picker" }
        _pendingSAFPickerGrant.value = null  // Clear grant for manual addition
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        safPickerEvents.emit(intent)
    }

    fun addSuggestedSAFLocation(suggestion: StorageProviderSuggestion) = doLaunch {
        log(tag) { "addSuggestedSAFLocation($suggestion)" }
        _pendingSAFPickerGrant.value = null
        val known = suggestion.known
        val intent = when {
            known != null -> safPickerIntentBuilder.buildPickerIntent(
                authority = known.authorityFor(suggestion.packageName),
                rootId = known.rootIdFor(suggestion.packageName),
            )
            else -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
        }
        safPickerEvents.emit(intent)
    }

    suspend fun handleSAFPickerResult(treeUri: Uri) {
        log(tag) { "handleSAFPickerResult(treeUri=$treeUri)" }
        try {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val locationId = safLocationManager.grantPermission(treeUri)

            val providerLabel = treeUri.authority?.let { storageProviderSuggester.labelForAuthority(it) }
            dialogs.show(ExplorerDialogState.LocationStorageName(locationId, currentName = providerLabel))

            // Auto-refresh if currently viewing Device location to show new SAF storage immediately
            if (currentLocation() is ExplorerLocation.Device) {
                log(tag) { "Auto-refreshing Device location to show new SAF storage" }
                workspace().navigate(ExplorerNavigation.Refresh)
            }

            log(tag, INFO) { "Successfully added SAF location: $treeUri (locationId=$locationId)" }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to handle SAF picker result: ${e.message}" }
            onError(e)
        }
    }

    fun launchAndroidDataSAFPicker(grant: SAFPickerGrant) = doLaunch {
        log(tag) { "launchAndroidDataSAFPicker(): Launching SAF picker for ${grant.targetPath}" }
        _pendingSAFPickerGrant.value = grant  // Store grant for auto-labeling
        safPickerEvents.emit(grant.intent)
    }

    suspend fun handleAndroidDataSAFPickerResult(
        treeUri: Uri?,
        grant: SAFPickerGrant
    ) {
        if (treeUri == null) {
            log(tag, WARN) { "SAF picker cancelled for ${grant.targetPath}" }
            return
        }

        log(tag) { "handleAndroidDataSAFPickerResult(): $treeUri for ${grant.targetPath}" }

        try {
            // Take persistable permission
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            log(tag, INFO) { "Successfully granted SAF permission for ${grant.targetPath}" }

            // Register with SAFLocationManager and auto-label
            val locationId = safLocationManager.grantPermission(treeUri)
            log(tag, VERBOSE) { "SAF location registered with ID: $locationId" }

            // Auto-label based on target path
            val label = when {
                grant.targetPath.path.contains("/Android/data") ->
                    context.getString(R.string.explorer_saf_location_android_data_label)
                grant.targetPath.path.contains("/Android/obb") ->
                    context.getString(R.string.explorer_saf_location_android_obb_label)
                else -> null
            }

            if (label != null) {
                safLocationManager.setLocationLabel(locationId, label)
                log(tag) { "Auto-labeled SAF location as: $label" }
            }

            // Convert to SAF path and navigate there
            val safPath = safLocationManager.toSAFPath(grant.targetPath)

            if (safPath != null) {
                log(tag) { "Navigating to SAF path: $safPath" }
                workspace().navigate(ExplorerNavigation.Target.Directory(safPath))
            } else {
                log(tag, WARN) { "Failed to convert ${grant.targetPath} to SAFPath after permission grant" }
                // Fallback: just refresh current location
                workspace().navigate(ExplorerNavigation.Refresh)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to handle Android/data SAF picker result: ${e.message}" }
            onError(e)
        } finally {
            _pendingSAFPickerGrant.value = null  // Clear grant after handling
        }
    }

    fun onRemoveLocationConfirmed() = doLaunch {
        val dialogState = dialogs.current() as? ExplorerDialogState.RemoveLocationConfirmation ?: return@doLaunch
        log(tag) { "onRemoveLocationConfirmed(): Removing ${dialogState.items.size} locations" }

        dialogs.dismiss()

        dialogState.items.forEach { item ->
            safLocationManager.revokePermission(item.location.id)
        }
        clearSelection()

        workspace().navigate(ExplorerNavigation.Refresh)
    }

    fun onLocationStorageName(name: String?) = doLaunch {
        val dialogState = dialogs.current() as? ExplorerDialogState.LocationStorageName ?: return@doLaunch
        log(tag) { "onLocationStorageName(locationId=${dialogState.locationId}, name=$name)" }

        dialogs.dismiss()

        // Empty or whitespace-only = use default name (null)
        val trimmedName = name?.trim()?.takeIf { it.isNotEmpty() }
        safLocationManager.setLocationLabel(dialogState.locationId, trimmedName)

        clearSelection()
        delay(500.milliseconds)
        workspace().navigate(ExplorerNavigation.Refresh)
    }
}
