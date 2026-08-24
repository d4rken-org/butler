package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.smb.SmbConnectionTester
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.SmbLocationFormInput
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

/**
 * Network location management: the add/edit form, its connection test, and rename/removal.
 *
 * Nothing is stored until the entered details actually connect, so a location in the list is always
 * one that worked at least once.
 */
class ExplorerSmbLocationController(
    private val locationManager: SmbLocationManager,
    private val credentialStore: SmbCredentialStore,
    private val connectionTester: SmbConnectionTester,
    private val dialogs: ExplorerDialogController,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val clearSelection: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    fun showAddForm() {
        log(tag) { "showAddForm()" }
        dialogs.show(ExplorerDialogState.SmbLocationForm())
    }

    fun showEditForm(location: SmbLocation) {
        log(tag) { "showEditForm(${location.id})" }
        dialogs.show(ExplorerDialogState.SmbLocationForm(existing = location))
    }

    fun showRenameDialog(item: ExplorerItem.Storage.Network) {
        log(tag) { "showRenameDialog(${item.location.id})" }
        dialogs.show(
            ExplorerDialogState.LocationStorageName(
                locationId = item.location.id.toString(),
                currentName = item.location.label,
                kind = ExplorerDialogState.LocationStorageName.Kind.NETWORK,
            )
        )
    }

    fun showRemoveConfirmation(items: List<ExplorerItem.Storage.Network>) {
        log(tag) { "showRemoveConfirmation(${items.size} items)" }
        dialogs.show(ExplorerDialogState.RemoveLocationConfirmation(items))
    }

    fun onFormSubmit(input: SmbLocationFormInput) = doLaunch {
        val formState = dialogs.current() as? ExplorerDialogState.SmbLocationForm ?: return@doLaunch
        val existing = formState.existing
        log(tag) { "onFormSubmit(existing=${existing?.id})" }

        val parsed = SmbLocationInput.parse(
            host = input.host,
            port = input.port,
            share = input.share,
            basePath = input.basePath,
            domain = input.domain,
            username = input.username,
            requireUsername = input.authType == SmbLocation.AuthType.PASSWORD,
        )
        if (parsed is SmbLocationInput.Result.Invalid) {
            dialogs.show(formState.copy(error = parsed.issues.first().message()))
            return@doLaunch
        }
        val details = (parsed as SmbLocationInput.Result.Valid).parsed

        val password = input.password.takeIf { it.isNotEmpty() }?.toCharArray()
        val usesPassword = input.authType == SmbLocation.AuthType.PASSWORD

        // A username, domain or remember-switch change invalidates the stored credential, see
        // SmbLocationManager.update
        val keepsStoredCredential = existing != null &&
            details.username == existing.username &&
            details.domain == SmbLocationInput.normalizeDomain(existing.domain) &&
            input.rememberCredential == existing.rememberCredential
        if (usesPassword && password == null && (existing == null || !keepsStoredCredential)) {
            dialogs.show(
                formState.copy(error = R.string.explorer_network_form_error_password_required.toCaString())
            )
            return@doLaunch
        }

        dialogs.show(formState.copy(isTesting = true, error = null))

        // Nothing typed while editing: the test has to run against the stored password, otherwise a
        // location could be saved with details nobody ever verified.
        val storedCredential = when {
            usesPassword && password == null && existing != null -> try {
                credentialStore.resolve(existing)
            } catch (e: Exception) {
                log(tag, ERROR) { "onFormSubmit(): Stored credential unusable: ${e.asLog()}" }
                dialogs.show(formState.copy(isTesting = false, error = e.localizedDescription()))
                return@doLaunch
            }

            else -> null
        }

        try {
            connectionTester.test(
                host = details.host,
                port = details.port,
                share = details.share,
                username = if (usesPassword) details.username else null,
                domain = details.domain,
                password = if (usesPassword) password ?: storedCredential?.password else null,
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "onFormSubmit(): Connection test failed: ${e.asLog()}" }
            dialogs.show(formState.copy(isTesting = false, error = e.localizedDescription()))
            return@doLaunch
        } finally {
            storedCredential?.wipe()
        }

        try {
            if (existing == null) {
                locationManager.create(
                    label = input.label.trim().takeIf { it.isNotEmpty() },
                    host = details.host,
                    port = details.port,
                    share = details.share,
                    basePath = details.basePath,
                    domain = details.domain,
                    username = details.username,
                    authType = input.authType,
                    rememberCredential = input.rememberCredential,
                    password = password,
                )
            } else {
                locationManager.update(
                    id = existing.id,
                    label = input.label.trim().takeIf { it.isNotEmpty() },
                    host = details.host,
                    port = details.port,
                    share = details.share,
                    basePath = details.basePath,
                    domain = details.domain,
                    username = details.username,
                    authType = input.authType,
                    rememberCredential = input.rememberCredential,
                    password = password,
                )
            }
            log(tag, INFO) { "onFormSubmit(): Saved network location" }
        } catch (e: Exception) {
            log(tag, ERROR) { "onFormSubmit(): Failed to save: ${e.asLog()}" }
            dialogs.show(formState.copy(isTesting = false, error = e.localizedDescription()))
            return@doLaunch
        } finally {
            password?.fill(Char(0))
        }

        dialogs.dismiss()
        clearSelection()
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    fun onRemoveConfirmed(items: List<ExplorerItem.Storage.Network>) = doLaunch {
        log(tag) { "onRemoveConfirmed(${items.size} items)" }
        dialogs.dismiss()
        try {
            items.forEach { locationManager.delete(it.location.id) }
        } catch (e: Exception) {
            log(tag, ERROR) { "onRemoveConfirmed(): Failed: ${e.asLog()}" }
            onError(e)
        }
        clearSelection()
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    fun onRename(locationId: String, name: String?) = doLaunch {
        log(tag) { "onRename($locationId, $name)" }
        dialogs.dismiss()
        try {
            locationManager.setLabel(Uuid.parse(locationId), name?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            log(tag, ERROR) { "onRename(): Failed: ${e.asLog()}" }
            onError(e)
        }
        clearSelection()
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    /** Opens the form for a location whose password has to be entered again. */
    fun promptSignIn(locationId: Uuid) = doLaunch {
        val location = locationManager.get(locationId)
        if (location == null) {
            log(tag, ERROR) { "promptSignIn(): Unknown location $locationId" }
            return@doLaunch
        }
        showEditForm(location)
    }

    private fun Throwable.localizedDescription(): CaString =
        eu.darken.butler.common.ca.caString { cx -> localized(cx).asText().get(cx) }

    private fun SmbLocationInput.Issue.message(): CaString = when (this) {
        SmbLocationInput.Issue.HostBlank -> R.string.explorer_network_form_error_host_blank
        SmbLocationInput.Issue.HostNotBare -> R.string.explorer_network_form_error_host_not_bare
        SmbLocationInput.Issue.HostMalformed -> R.string.explorer_network_form_error_host_malformed
        SmbLocationInput.Issue.PortOutOfRange -> R.string.explorer_network_form_error_port
        SmbLocationInput.Issue.ShareBlank -> R.string.explorer_network_form_error_share_blank
        SmbLocationInput.Issue.ShareNotSingleComponent -> R.string.explorer_network_form_error_share_not_single
        SmbLocationInput.Issue.ShareMalformed -> R.string.explorer_network_form_error_share_malformed
        SmbLocationInput.Issue.BasePathMalformed -> R.string.explorer_network_form_error_base_path
        SmbLocationInput.Issue.UsernameBlank -> R.string.explorer_network_form_error_username_blank
    }.toCaString()
}
