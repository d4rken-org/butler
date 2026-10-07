package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.sftp.SftpConnectionTester
import eu.darken.butler.common.files.sftp.SftpHostKeyChangedException
import eu.darken.butler.common.files.sftp.SftpLocationInput
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.SftpPrivateKeyReader
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpFormMode
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpHostKeyConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpLocationFormInput
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpRetrustConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.keepsCredentialFor
import eu.darken.butler.explorer.ui.explorer.dialogs.sftpEndpointLabel
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * SFTP location management: the add/edit/sign-in form, its connection test and the host key
 * decisions. Priced like [ExplorerSmbLocationController]: adding, testing and removing servers is
 * free, browsing one is Pro.
 *
 * Nothing is stored before the entered details connected with a host key the user accepted for
 * exactly that endpoint. A new endpoint is tested twice: once to learn the key the server presents,
 * once pinned to the key the user accepted, so a different server answering the second connection
 * fails instead of being trusted.
 */
class ExplorerSftpLocationController(
    private val locationManager: SftpLocationManager,
    private val credentialStore: SftpCredentialStore,
    private val connectionTester: SftpConnectionTester,
    private val keyReader: SftpPrivateKeyReader,
    private val upgradeRepo: UpgradeRepo,
    private val showUpgradeHint: (SmbUpgradeHint.Reason) -> Unit,
    private val dialogs: ExplorerDialogController,
    /** Opens a file picker whose result arrives in [onKeyPickerResult]; null if none opened. */
    private val launchKeyPicker: suspend () -> Workspace.Id?,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val currentLocation: () -> ExplorerLocation?,
    private val clearSelection: () -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    /** A key file read for the form [formId]; [generation] changes with every file picked. */
    private class PickedKey(val formId: Uuid, val bytes: ByteArray, val generation: Int) {
        override fun toString(): String = "PickedKey($formId, <redacted>)"
    }

    private class PendingPicker(val formId: Uuid, val pickerId: Workspace.Id)

    private val lock = Any()
    private var pickedKey: PickedKey? = null
    private var keyGeneration = 0
    private var pendingPicker: PendingPicker? = null

    fun showAddForm() {
        log(tag) { "showAddForm(sftp)" }
        dialogs.show(ExplorerDialogState.SftpLocationForm())
    }

    fun showEditForm(locationId: Uuid) = showForm(locationId, SftpFormMode.EDIT)

    fun promptSignIn(locationId: Uuid) = showForm(locationId, SftpFormMode.SIGN_IN)

    /** Re-reads the stored details, the selected row may have been drawn from an older listing. */
    private fun showForm(locationId: Uuid, mode: SftpFormMode) = doLaunch {
        log(tag) { "showForm(sftp=$locationId, $mode)" }
        val location = locationManager.get(locationId)
        if (location == null) {
            log(tag, ERROR) { "showForm(): Unknown SFTP location $locationId" }
            return@doLaunch
        }
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = mode, existing = location))
    }

    /**
     * Opens the edit form of the location whose key changed, with the re-trust question on top. The
     * question carries the endpoint and keys from [change] itself, the connection they were seen on.
     */
    fun reviewHostKeyChange(change: SftpHostKeyChangedException) = doLaunch {
        log(tag) { "reviewHostKeyChange(${change.locationId})" }
        val location = locationManager.get(change.locationId)
        if (location == null) {
            log(tag, WARN) { "reviewHostKeyChange(): ${change.locationId} was removed" }
            return@doLaunch
        }
        dialogs.show(
            ExplorerDialogState.SftpLocationForm(
                mode = SftpFormMode.EDIT,
                existing = location,
                retrustConfirmation = SftpRetrustConfirmation(
                    locationId = change.locationId,
                    host = change.host,
                    port = change.port,
                    endpoint = change.endpoint,
                    storedKey = change.storedKey,
                    presentedKey = change.presentedKey,
                    trustRevision = change.trustRevision,
                ),
            )
        )
    }

    // region key file

    fun pickKeyFile() = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return@doLaunch
        if (form.isTesting || form.isReadingKey) return@doLaunch
        log(tag) { "pickKeyFile(${form.formId})" }
        val pickerId = launchKeyPicker()
        if (pickerId == null) {
            log(tag, WARN) { "pickKeyFile(): No picker was opened" }
            return@doLaunch
        }
        synchronized(lock) { pendingPicker = PendingPicker(form.formId, pickerId) }
    }

    fun onKeyPickerCancelled(event: WorkspaceEvent.ResultCancelled) {
        synchronized(lock) {
            if (pendingPicker?.pickerId == event.workspaceId) pendingPicker = null
        }
    }

    fun onKeyPickerResult(result: WorkspaceEvent.PickerResult) = doLaunch {
        val pending = synchronized(lock) {
            pendingPicker?.takeIf { it.pickerId == result.workspaceId }?.also { pendingPicker = null }
        } ?: return@doLaunch
        val path = result.selectedPaths.singleOrNull() ?: return@doLaunch
        log(tag) { "onKeyPickerResult(${pending.formId}): $path" }

        if (!updateForm(pending.formId) { it.copy(isReadingKey = true, error = null) }) return@doLaunch

        when (val read = keyReader.read(path)) {
            is SftpPrivateKeyReader.Result.Loaded -> {
                val stored = synchronized(lock) {
                    pickedKey?.bytes?.fill(0)
                    keyGeneration++
                    PickedKey(pending.formId, read.bytes, keyGeneration).also { pickedKey = it }
                }
                val shown = updateForm(pending.formId) {
                    it.copy(isReadingKey = false, keyFileName = read.name, error = null)
                }
                if (!shown) forgetKey(stored)
            }

            SftpPrivateKeyReader.Result.TooLarge -> keyReadFailed(
                pending.formId,
                caString { it.getString(R.string.explorer_sftp_form_error_key_too_large, SftpPrivateKeyReader.MAX_BYTES / 1024) },
            )

            SftpPrivateKeyReader.Result.NotAFile -> keyReadFailed(
                pending.formId,
                R.string.explorer_sftp_form_error_key_not_a_file.toCaString(),
            )

            is SftpPrivateKeyReader.Result.Failed -> keyReadFailed(
                pending.formId,
                R.string.explorer_sftp_form_error_key_unreadable.toCaString(),
            )
        }
    }

    /** A rejected file also drops the one picked before it: the form shows no key, so none is used. */
    private fun keyReadFailed(formId: Uuid, error: CaString) {
        synchronized(lock) {
            pickedKey?.takeIf { it.formId == formId }?.let {
                it.bytes.fill(0)
                pickedKey = null
                keyGeneration++
            }
        }
        updateForm(formId) { it.copy(isReadingKey = false, keyFileName = null, error = error) }
    }

    private fun forgetKey(key: PickedKey) = synchronized(lock) {
        key.bytes.fill(0)
        if (pickedKey === key) pickedKey = null
    }

    /** Wipes a picked key as soon as the form it was picked for is gone. */
    fun onDialogState(state: ExplorerDialogState) {
        val formId = (state as? ExplorerDialogState.SftpLocationForm)?.formId
        synchronized(lock) {
            pickedKey?.takeIf { it.formId != formId }?.let {
                log(tag) { "Form closed, wiping its key file" }
                it.bytes.fill(0)
                pickedKey = null
            }
            if (pendingPicker?.formId != formId) pendingPicker = null
        }
    }

    // endregion

    // region submit

    fun onFormSubmit(input: SftpLocationFormInput) = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return@doLaunch
        if (form.isBusy()) return@doLaunch
        log(tag) { "onFormSubmit(existing=${form.existing?.id}, ${form.mode})" }

        val details = parse(form, input) ?: return@doLaunch
        val testing = form.copy(isTesting = true, error = null)
        if (!dialogs.replaceIfCurrent(form, testing)) return@doLaunch

        val existing = form.existing
        // An unchanged endpoint keeps its pin, a new one has to present its key first.
        val pinnedKey = existing?.hostKey?.takeIf { details.host == existing.host && details.port == existing.port }
        connectAndSave(testing, input, details, pinnedKey = pinnedKey, acceptedKey = null)
    }

    /**
     * Only acts if [confirmationId] is the question showing right now, and only if nothing the test
     * ran with changed since: the endpoint, any form field ([SftpLocationFormInput.revision]) and
     * the picked key file. Otherwise the acceptance is dropped and nothing is saved.
     */
    fun onHostKeyAccepted(confirmationId: Uuid, input: SftpLocationFormInput) = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return@doLaunch
        val confirmation = form.hostKeyConfirmation?.takeIf { it.id == confirmationId }
        if (confirmation == null) {
            log(tag, WARN) { "onHostKeyAccepted(): $confirmationId is not the question showing" }
            return@doLaunch
        }

        val details = (parseDetails(input) as? SftpLocationInput.Result.Valid)?.parsed
        val generation = synchronized(lock) { keyGeneration }
        val bound = details != null &&
            details.host == confirmation.host &&
            details.port == confirmation.port &&
            input.revision == confirmation.formRevision &&
            generation == confirmation.keyGeneration
        if (!bound) {
            log(tag, WARN) { "onHostKeyAccepted(): The form changed since the test, dropping the acceptance" }
            dialogs.showIfCurrent(
                form,
                form.copy(
                    hostKeyConfirmation = null,
                    error = R.string.explorer_sftp_form_error_host_key_stale.toCaString(),
                ),
            )
            return@doLaunch
        }

        val testing = form.copy(hostKeyConfirmation = null, isTesting = true, error = null)
        if (!dialogs.replaceIfCurrent(form, testing)) return@doLaunch
        log(tag, INFO) { "onHostKeyAccepted(): Testing again, pinned to ${confirmation.presentedKey}" }
        connectAndSave(testing, input, details!!, confirmation.presentedKey, confirmation.presentedKey)
    }

    fun onHostKeyRejected(confirmationId: Uuid) {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return
        if (form.hostKeyConfirmation?.id != confirmationId) return
        log(tag) { "onHostKeyRejected()" }
        dialogs.showIfCurrent(
            form,
            form.copy(
                hostKeyConfirmation = null,
                error = R.string.explorer_sftp_form_error_host_key_rejected.toCaString(),
            ),
        )
    }

    /**
     * @param pinnedKey the key the connection must present, null to learn the one it presents
     * @param acceptedKey the key the user accepted for a new endpoint, saved as its pin
     */
    private suspend fun connectAndSave(
        testing: ExplorerDialogState.SftpLocationForm,
        input: SftpLocationFormInput,
        details: SftpLocationInput.Parsed,
        pinnedKey: TrustedHostKey?,
        acceptedKey: TrustedHostKey?,
    ) {
        val secrets = resolveSecrets(testing, input, details) ?: return
        try {
            val result = try {
                connectionTester.test(
                    host = details.host,
                    port = details.port,
                    username = details.username,
                    authType = input.authType,
                    password = secrets.testPassword,
                    privateKey = secrets.testKey,
                    passphrase = secrets.testPassphrase,
                    basePath = details.basePath,
                    trustedKey = pinnedKey,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "connectAndSave(): Connection test failed: ${e.asLog()}" }
                fail(testing, e.localizedDescription())
                return
            }
            log(tag, INFO) { "connectAndSave(): Test result ${result::class.simpleName}" }

            when (result) {
                is SftpConnectionTester.Result.Success -> when {
                    pinnedKey == null -> fail(testing, R.string.explorer_sftp_form_error_unconfirmed.toCaString())
                    else -> save(testing, input, details, secrets, acceptedKey)
                }

                is SftpConnectionTester.Result.HostKeyUnknown -> when {
                    pinnedKey != null -> fail(testing, R.string.explorer_sftp_form_error_unconfirmed.toCaString())
                    else -> dialogs.showIfCurrent(
                        testing,
                        testing.copy(
                            isTesting = false,
                            hostKeyConfirmation = SftpHostKeyConfirmation(
                                host = details.host,
                                port = details.port,
                                presentedKey = result.presentedKey,
                                formRevision = input.revision,
                                keyGeneration = secrets.keyGeneration,
                            ),
                        ),
                    )
                }

                is SftpConnectionTester.Result.HostKeyMismatch -> {
                    val existing = testing.existing
                    if (existing != null && details.host == existing.host && details.port == existing.port) {
                        dialogs.showIfCurrent(
                            testing,
                            testing.copy(
                                isTesting = false,
                                error = result.message(details),
                                retrustConfirmation = SftpRetrustConfirmation(
                                    locationId = existing.id,
                                    host = existing.host,
                                    port = existing.port,
                                    endpoint = existing.endpointLabel,
                                    storedKey = existing.hostKey,
                                    presentedKey = result.presentedKey,
                                    trustRevision = existing.trustRevision,
                                    fromFormTest = true,
                                ),
                            ),
                        )
                    } else {
                        fail(testing, result.message(details))
                    }
                }

                else -> fail(testing, result.message(details))
            }
        } finally {
            secrets.wipe()
        }
    }

    private suspend fun save(
        testing: ExplorerDialogState.SftpLocationForm,
        input: SftpLocationFormInput,
        details: SftpLocationInput.Parsed,
        secrets: Secrets,
        acceptedKey: TrustedHostKey?,
    ) {
        if (dialogs.current() !== testing) {
            log(tag, WARN) { "save(): The form moved on while testing, nothing saved" }
            return
        }
        val existing = testing.existing
        val label = input.label.trim().takeIf { it.isNotEmpty() }
        try {
            if (existing == null) {
                locationManager.create(
                    label = label,
                    host = details.host,
                    port = details.port,
                    username = details.username,
                    basePath = details.basePath,
                    authType = input.authType,
                    rememberCredential = input.rememberCredential,
                    password = secrets.savePassword,
                    privateKey = secrets.saveKey,
                    passphrase = secrets.savePassphrase,
                    hostKey = requireNotNull(acceptedKey) { "A new location needs an accepted host key" },
                )
            } else {
                locationManager.update(
                    id = existing.id,
                    label = label,
                    host = details.host,
                    port = details.port,
                    username = details.username,
                    basePath = details.basePath,
                    authType = input.authType,
                    rememberCredential = input.rememberCredential,
                    password = secrets.savePassword,
                    privateKey = secrets.saveKey,
                    passphrase = secrets.savePassphrase,
                    hostKey = acceptedKey,
                )
            }
            log(tag, INFO) { "save(): Saved SFTP location" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "save(): Failed: ${e.asLog()}" }
            fail(testing, e.localizedDescription())
            return
        }

        dialogs.dismissIfCurrent(testing)
        clearSelection()
        refreshUnlessLive()
        // Only a clean "no purchase" says so: a paying user whose billing is still connecting, or whose
        // lookup failed, must not be told to upgrade.
        val upgrade = upgradeRepo.upgradeInfo.first()
        if (upgrade.isSettled && !upgrade.isPro && upgrade.error == null) {
            showUpgradeHint(SmbUpgradeHint.Reason.SAVED)
        }
    }

    // endregion

    // region re-trust

    fun onRetrustAccepted(confirmationId: Uuid) = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return@doLaunch
        val confirmation = form.retrustConfirmation?.takeIf { it.id == confirmationId } ?: return@doLaunch
        val working = form.copy(retrustConfirmation = null, isTesting = true, error = null)
        if (!dialogs.replaceIfCurrent(form, working)) return@doLaunch
        log(tag, INFO) { "onRetrustAccepted(${confirmation.locationId}): ${confirmation.presentedKey}" }

        val result = try {
            locationManager.retrust(
                id = confirmation.locationId,
                expectedHost = confirmation.host,
                expectedPort = confirmation.port,
                expectedTrustRevision = confirmation.trustRevision,
                newKey = confirmation.presentedKey,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "onRetrustAccepted(): Failed: ${e.asLog()}" }
            fail(working, e.localizedDescription())
            return@doLaunch
        }

        when (result) {
            is SftpLocationManager.RetrustResult.Retrusted -> if (confirmation.fromFormTest) {
                dialogs.showIfCurrent(working, working.copy(isTesting = false, existing = result.location))
            } else {
                dialogs.dismissIfCurrent(working)
                clearSelection()
                refreshUnlessLive()
            }

            SftpLocationManager.RetrustResult.EndpointChanged -> fail(
                working,
                R.string.explorer_sftp_form_error_retrust_endpoint_changed.toCaString(),
            )

            SftpLocationManager.RetrustResult.NotFound -> fail(
                working,
                R.string.explorer_sftp_form_error_retrust_not_found.toCaString(),
            )
        }
    }

    fun onRetrustRejected(confirmationId: Uuid) {
        val form = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return
        if (form.retrustConfirmation?.id != confirmationId) return
        log(tag) { "onRetrustRejected()" }
        dialogs.showIfCurrent(form, form.copy(retrustConfirmation = null))
    }

    // endregion

    /**
     * What the test runs with and what gets saved. The save side is null where the stored credential
     * stays; every array here belongs to this submit and is wiped by [wipe].
     */
    private class Secrets(
        val testPassword: CharArray? = null,
        val testKey: ByteArray? = null,
        val testPassphrase: CharArray? = null,
        val savePassword: CharArray? = null,
        val saveKey: ByteArray? = null,
        val savePassphrase: CharArray? = null,
        val keyGeneration: Int,
        private val stored: SftpCredential? = null,
    ) {
        fun wipe() {
            testPassword?.fill(Char(0))
            testKey?.fill(0)
            testPassphrase?.fill(Char(0))
            savePassword?.fill(Char(0))
            saveKey?.fill(0)
            savePassphrase?.fill(Char(0))
            stored?.wipe()
        }

        override fun toString(): String = "Secrets(<redacted>)"
    }

    private suspend fun resolveSecrets(
        testing: ExplorerDialogState.SftpLocationForm,
        input: SftpLocationFormInput,
        details: SftpLocationInput.Parsed,
    ): Secrets? {
        val existing = testing.existing
        val keepsStored = existing?.keepsCredentialFor(details.username, input.authType, input.rememberCredential) == true
        val generation = synchronized(lock) { keyGeneration }

        return when (input.authType) {
            SftpLocation.AuthType.PASSWORD -> {
                val typed = input.password.takeIf { it.isNotEmpty() }?.toCharArray()
                when {
                    typed != null -> Secrets(
                        testPassword = typed,
                        savePassword = typed.copyOf(),
                        keyGeneration = generation,
                    )

                    // A sign-in prompt exists because the stored password did not work.
                    !keepsStored || testing.mode == SftpFormMode.SIGN_IN -> {
                        fail(testing, R.string.explorer_sftp_form_error_password_required.toCaString())
                        null
                    }

                    else -> {
                        val stored = resolveStored(testing, existing!!) as? SftpCredential.Password ?: return null
                        Secrets(testPassword = stored.password, keyGeneration = generation, stored = stored)
                    }
                }
            }

            SftpLocation.AuthType.PRIVATE_KEY -> {
                val picked = synchronized(lock) {
                    pickedKey?.takeIf { it.formId == testing.formId }?.let { it.bytes.copyOf() to it.generation }
                }
                val passphrase = input.passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
                when {
                    picked != null -> Secrets(
                        testKey = picked.first,
                        testPassphrase = passphrase,
                        saveKey = picked.first.copyOf(),
                        savePassphrase = passphrase?.copyOf(),
                        keyGeneration = picked.second,
                    )

                    !keepsStored || (testing.mode == SftpFormMode.SIGN_IN && passphrase == null) -> {
                        passphrase?.fill(Char(0))
                        fail(testing, R.string.explorer_sftp_form_error_key_required.toCaString())
                        null
                    }

                    else -> {
                        val stored = resolveStored(testing, existing!!) as? SftpCredential.PrivateKey
                        if (stored == null) {
                            passphrase?.fill(Char(0))
                            return null
                        }
                        when (passphrase) {
                            null -> Secrets(
                                testKey = stored.keyBytes,
                                testPassphrase = stored.passphrase,
                                keyGeneration = generation,
                                stored = stored,
                            )
                            // A passphrase is stored with its key: a new one saves the stored key again.
                            else -> Secrets(
                                testKey = stored.keyBytes,
                                testPassphrase = passphrase,
                                saveKey = stored.keyBytes.copyOf(),
                                savePassphrase = passphrase.copyOf(),
                                keyGeneration = generation,
                                stored = stored,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Nothing typed while editing: the test runs against the stored secret, never against nothing. */
    private suspend fun resolveStored(
        testing: ExplorerDialogState.SftpLocationForm,
        location: SftpLocation,
    ): SftpCredential? = try {
        credentialStore.resolve(location)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "resolveStored(): Stored credential unusable: ${e.asLog()}" }
        fail(testing, e.localizedDescription())
        null
    }

    private fun parse(
        form: ExplorerDialogState.SftpLocationForm,
        input: SftpLocationFormInput,
    ): SftpLocationInput.Parsed? = when (val parsed = parseDetails(input)) {
        is SftpLocationInput.Result.Valid -> parsed.parsed
        is SftpLocationInput.Result.Invalid -> {
            dialogs.showIfCurrent(form, form.copy(error = parsed.issues.first().message()))
            null
        }
    }

    private fun parseDetails(input: SftpLocationFormInput) = SftpLocationInput.parse(
        host = input.host,
        port = input.port,
        username = input.username,
        basePath = input.basePath,
    )

    private fun fail(testing: ExplorerDialogState.SftpLocationForm, error: CaString) {
        dialogs.showIfCurrent(testing, testing.copy(isTesting = false, error = error))
    }

    /** Updates the form [formId] if it is still the dialog showing, whatever else changed on it. */
    private fun updateForm(
        formId: Uuid,
        block: (ExplorerDialogState.SftpLocationForm) -> ExplorerDialogState.SftpLocationForm,
    ): Boolean {
        while (true) {
            val current = dialogs.current() as? ExplorerDialogState.SftpLocationForm ?: return false
            if (current.formId != formId) return false
            if (dialogs.replaceIfCurrent(current, block(current))) return true
        }
    }

    private fun ExplorerDialogState.SftpLocationForm.isBusy(): Boolean = isTesting || isReadingKey ||
        hostKeyConfirmation != null || retrustConfirmation != null

    /** See [ExplorerSmbLocationController]: only a directory needs the reload to be back in it. */
    private suspend fun refreshUnlessLive() {
        if (currentLocation() is ExplorerLocation.Network) {
            log(tag) { "Network list is live, no refresh needed" }
            return
        }
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    private fun Throwable.localizedDescription(): CaString = caString { cx -> localized(cx).asText().get(cx) }

    private fun SftpConnectionTester.Result.message(details: SftpLocationInput.Parsed): CaString = when (this) {
        SftpConnectionTester.Result.AuthenticationFailed -> caString {
            it.getString(R.string.explorer_sftp_form_error_auth, details.host)
        }

        SftpConnectionTester.Result.KeyFormatInvalid -> R.string.explorer_sftp_form_error_key_format.toCaString()
        SftpConnectionTester.Result.KeyPassphraseInvalid -> R.string.explorer_sftp_form_error_key_passphrase.toCaString()
        SftpConnectionTester.Result.BasePathMissing -> R.string.explorer_sftp_form_error_base_path_missing.toCaString()
        SftpConnectionTester.Result.BasePathNotDirectory ->
            R.string.explorer_sftp_form_error_base_path_not_directory.toCaString()

        SftpConnectionTester.Result.BasePathAccessDenied ->
            R.string.explorer_sftp_form_error_base_path_denied.toCaString()

        is SftpConnectionTester.Result.Unreachable -> caString {
            it.getString(R.string.explorer_sftp_form_error_unreachable, sftpEndpointLabel(details.host, details.port))
        }

        is SftpConnectionTester.Result.HostKeyMismatch -> caString {
            it.getString(
                R.string.explorer_sftp_form_error_host_key_mismatch,
                expectedKey.fingerprint,
                presentedKey.fingerprint,
            )
        }

        is SftpConnectionTester.Result.HostKeyUnknown,
        is SftpConnectionTester.Result.Success -> R.string.explorer_sftp_form_error_unconfirmed.toCaString()
    }

    private fun SftpLocationInput.Issue.message(): CaString = when (this) {
        SftpLocationInput.Issue.HostBlank -> R.string.explorer_network_form_error_host_blank
        SftpLocationInput.Issue.HostNotBare -> R.string.explorer_network_form_error_host_not_bare
        SftpLocationInput.Issue.HostMalformed -> R.string.explorer_network_form_error_host_malformed
        SftpLocationInput.Issue.PortOutOfRange -> R.string.explorer_network_form_error_port
        SftpLocationInput.Issue.UsernameBlank -> R.string.explorer_network_form_error_username_blank
        SftpLocationInput.Issue.UsernameMalformed -> R.string.explorer_sftp_form_error_username_malformed
        SftpLocationInput.Issue.BasePathMalformed -> R.string.explorer_network_form_error_base_path
    }.toCaString()
}
