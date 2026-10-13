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
import eu.darken.butler.common.files.webdav.WebDavCertificateException
import eu.darken.butler.common.files.webdav.WebDavConnectionTester
import eu.darken.butler.common.files.webdav.WebDavLocationInput
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavCertificateConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavFormMode
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavLocationFormInput
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavRetrustConfirmation
import eu.darken.butler.explorer.ui.explorer.dialogs.keepsCredentialFor
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * WebDAV location management: the add/edit/sign-in form, its connection test and the certificate
 * decisions. Priced like [ExplorerSmbLocationController]: adding, testing and removing servers is
 * free, browsing one is Pro.
 *
 * A server the system trusts, or one reached over plain HTTP, is saved without a pin. A certificate
 * the system does not trust is shown first, and the details are tested again pinned to exactly that
 * certificate, so a different server answering the second connection fails instead of being trusted.
 */
class ExplorerWebDavLocationController(
    private val locationManager: WebDavLocationManager,
    private val credentialStore: WebDavCredentialStore,
    private val connectionTester: WebDavConnectionTester,
    private val upgradeRepo: UpgradeRepo,
    private val showUpgradeHint: (SmbUpgradeHint.Reason) -> Unit,
    private val dialogs: ExplorerDialogController,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val currentLocation: () -> ExplorerLocation?,
    private val clearSelection: () -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    fun showAddForm() {
        log(tag) { "showAddForm(webdav)" }
        dialogs.show(ExplorerDialogState.WebDavLocationForm())
    }

    fun showEditForm(locationId: Uuid) = showForm(locationId, WebDavFormMode.EDIT)

    fun promptSignIn(locationId: Uuid) = showForm(locationId, WebDavFormMode.SIGN_IN)

    /** Re-reads the stored details, the selected row may have been drawn from an older listing. */
    private fun showForm(locationId: Uuid, mode: WebDavFormMode) = doLaunch {
        log(tag) { "showForm(webdav=$locationId, $mode)" }
        val location = locationManager.get(locationId)
        if (location == null) {
            log(tag, ERROR) { "showForm(): Unknown WebDAV location $locationId" }
            return@doLaunch
        }
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = mode, existing = location))
    }

    /**
     * Opens the edit form of the location whose certificate was refused, with the trust question on
     * top. The question carries the URL, trust revision and certificates from [problem] itself, the
     * connection they were seen on.
     */
    fun reviewCertificateProblem(problem: WebDavCertificateException) = doLaunch {
        log(tag) { "reviewCertificateProblem(${problem.locationId})" }
        val location = locationManager.get(problem.locationId)
        if (location == null) {
            log(tag, WARN) { "reviewCertificateProblem(): ${problem.locationId} was removed" }
            return@doLaunch
        }
        dialogs.show(
            ExplorerDialogState.WebDavLocationForm(
                mode = WebDavFormMode.EDIT,
                existing = location,
                retrustConfirmation = WebDavRetrustConfirmation(
                    locationId = problem.locationId,
                    url = problem.url,
                    endpoint = problem.endpoint,
                    stored = problem.stored,
                    presented = problem.presented,
                    trustRevision = problem.trustRevision,
                ),
            )
        )
    }

    // region submit

    fun onFormSubmit(input: WebDavLocationFormInput) = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.WebDavLocationForm ?: return@doLaunch
        if (form.isBusy()) return@doLaunch
        log(tag) { "onFormSubmit(existing=${form.existing?.id}, ${form.mode})" }

        val details = when (val parsed = parseDetails(input)) {
            is WebDavLocationInput.Result.Valid -> parsed.parsed
            is WebDavLocationInput.Result.Invalid -> {
                dialogs.showIfCurrent(
                    form,
                    form.copy(error = null, issues = parsed.issues, issuesRevision = input.revision),
                )
                return@doLaunch
            }
        }
        val testing = form.copy(isTesting = true, error = null, issues = emptyList())
        if (!dialogs.replaceIfCurrent(form, testing)) return@doLaunch

        connectAndSave(testing, input, details, pin = form.existing?.pinFor(details))
    }

    /**
     * Only acts if [confirmationId] is the question showing right now, and only if nothing the test
     * ran with changed since: the URL and any form field ([WebDavLocationFormInput.revision]).
     * Otherwise the acceptance is dropped and nothing is saved.
     */
    fun onCertificateAccepted(confirmationId: Uuid, input: WebDavLocationFormInput) = doLaunch {
        val form = dialogs.current() as? ExplorerDialogState.WebDavLocationForm ?: return@doLaunch
        val confirmation = form.certificateConfirmation?.takeIf { it.id == confirmationId }
        if (confirmation == null) {
            log(tag, WARN) { "onCertificateAccepted(): $confirmationId is not the question showing" }
            return@doLaunch
        }

        val details = (parseDetails(input) as? WebDavLocationInput.Result.Valid)?.parsed
        if (details == null || details.url != confirmation.url || input.revision != confirmation.formRevision) {
            log(tag, WARN) { "onCertificateAccepted(): The form changed since the test, dropping the acceptance" }
            dialogs.showIfCurrent(
                form,
                form.copy(
                    certificateConfirmation = null,
                    error = R.string.explorer_webdav_form_error_certificate_stale.toCaString(),
                ),
            )
            return@doLaunch
        }

        val testing = form.copy(certificateConfirmation = null, isTesting = true, error = null)
        if (!dialogs.replaceIfCurrent(form, testing)) return@doLaunch
        val accepted = confirmation.presented.toTrustedCertificate()
        log(tag, INFO) { "onCertificateAccepted(): Testing again, pinned to $accepted" }
        connectAndSave(testing, input, details, pin = accepted)
    }

    fun onCertificateRejected(confirmationId: Uuid) {
        val form = dialogs.current() as? ExplorerDialogState.WebDavLocationForm ?: return
        if (form.certificateConfirmation?.id != confirmationId) return
        log(tag) { "onCertificateRejected()" }
        dialogs.showIfCurrent(
            form,
            form.copy(
                certificateConfirmation = null,
                error = R.string.explorer_webdav_form_error_certificate_rejected.toCaString(),
            ),
        )
    }

    /**
     * @param pin the certificate the connection must present, saved as the location's pin once it
     * did; null accepts only what the system trusts and saves no pin
     */
    private suspend fun connectAndSave(
        testing: ExplorerDialogState.WebDavLocationForm,
        input: WebDavLocationFormInput,
        details: WebDavLocationInput.Parsed,
        pin: TrustedCertificate?,
    ) {
        val secrets = resolveSecrets(testing, input, details) ?: return
        try {
            val result = try {
                connectionTester.test(
                    url = details.url,
                    authType = details.authType,
                    username = details.username,
                    password = secrets.testPassword,
                    trustedCertificate = pin,
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
                WebDavConnectionTester.Result.Success -> save(testing, input, details, secrets, pin)

                is WebDavConnectionTester.Result.CertificateUntrusted -> when {
                    pin != null -> fail(testing, R.string.explorer_webdav_form_error_unconfirmed.toCaString())
                    else -> dialogs.showIfCurrent(
                        testing,
                        testing.copy(
                            isTesting = false,
                            certificateConfirmation = WebDavCertificateConfirmation(
                                url = details.url,
                                presented = result.presented,
                                formRevision = input.revision,
                            ),
                        ),
                    )
                }

                is WebDavConnectionTester.Result.CertificateMismatch -> {
                    val existing = testing.existing
                    if (existing != null && pin != null && pin == existing.pinnedCertificate) {
                        dialogs.showIfCurrent(
                            testing,
                            testing.copy(
                                isTesting = false,
                                error = result.message(details),
                                retrustConfirmation = WebDavRetrustConfirmation(
                                    locationId = existing.id,
                                    url = existing.url,
                                    endpoint = existing.endpointLabel,
                                    stored = result.expected,
                                    presented = result.presented,
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
        testing: ExplorerDialogState.WebDavLocationForm,
        input: WebDavLocationFormInput,
        details: WebDavLocationInput.Parsed,
        secrets: Secrets,
        pin: TrustedCertificate?,
    ) {
        if (dialogs.current() !== testing) {
            log(tag, WARN) { "save(): The form moved on while testing, nothing saved" }
            return
        }
        val existing = testing.existing
        try {
            if (existing == null) {
                locationManager.create(
                    label = details.label,
                    url = details.url,
                    authType = details.authType,
                    username = details.username,
                    rememberCredential = input.rememberCredential,
                    password = secrets.savePassword,
                    pinnedCertificate = pin,
                )
            } else {
                locationManager.update(
                    id = existing.id,
                    label = details.label,
                    url = details.url,
                    authType = details.authType,
                    username = details.username,
                    rememberCredential = input.rememberCredential,
                    password = secrets.savePassword,
                    pinnedCertificate = pin,
                )
            }
            log(tag, INFO) { "save(): Saved WebDAV location, pinned=${pin != null}" }
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
        val form = dialogs.current() as? ExplorerDialogState.WebDavLocationForm ?: return@doLaunch
        val confirmation = form.retrustConfirmation?.takeIf { it.id == confirmationId } ?: return@doLaunch
        val working = form.copy(retrustConfirmation = null, isTesting = true, error = null)
        if (!dialogs.replaceIfCurrent(form, working)) return@doLaunch
        val accepted = confirmation.presented.toTrustedCertificate()
        log(tag, INFO) { "onRetrustAccepted(${confirmation.locationId}): $accepted" }

        val result = try {
            locationManager.retrust(
                id = confirmation.locationId,
                expectedUrl = confirmation.url,
                expectedTrustRevision = confirmation.trustRevision,
                newCertificate = accepted,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "onRetrustAccepted(): Failed: ${e.asLog()}" }
            fail(working, e.localizedDescription())
            return@doLaunch
        }

        when (result) {
            is WebDavLocationManager.RetrustResult.Retrusted -> if (confirmation.fromFormTest) {
                dialogs.showIfCurrent(working, working.copy(isTesting = false, existing = result.location))
            } else {
                dialogs.dismissIfCurrent(working)
                clearSelection()
                refreshUnlessLive()
            }

            WebDavLocationManager.RetrustResult.EndpointChanged -> fail(
                working,
                R.string.explorer_webdav_form_error_retrust_endpoint_changed.toCaString(),
            )

            WebDavLocationManager.RetrustResult.NotFound -> fail(
                working,
                R.string.explorer_webdav_form_error_retrust_not_found.toCaString(),
            )
        }
    }

    fun onRetrustRejected(confirmationId: Uuid) {
        val form = dialogs.current() as? ExplorerDialogState.WebDavLocationForm ?: return
        if (form.retrustConfirmation?.id != confirmationId) return
        log(tag) { "onRetrustRejected()" }
        dialogs.showIfCurrent(form, form.copy(retrustConfirmation = null))
    }

    // endregion

    /**
     * What the test runs with and what gets saved. [savePassword] is null where the stored password
     * stays or there is none; every array here belongs to this submit and is wiped by [wipe].
     */
    private class Secrets(
        val testPassword: CharArray? = null,
        val savePassword: CharArray? = null,
        private val stored: WebDavCredential? = null,
    ) {
        fun wipe() {
            testPassword?.fill(Char(0))
            savePassword?.fill(Char(0))
            stored?.wipe()
        }

        override fun toString(): String = "Secrets(<redacted>)"
    }

    private suspend fun resolveSecrets(
        testing: ExplorerDialogState.WebDavLocationForm,
        input: WebDavLocationFormInput,
        details: WebDavLocationInput.Parsed,
    ): Secrets? {
        if (details.authType == WebDavLocation.AuthType.ANONYMOUS) return Secrets()

        val existing = testing.existing
        val keepsStored = existing?.keepsCredentialFor(
            username = details.username.orEmpty(),
            authType = details.authType,
            rememberCredential = input.rememberCredential,
        ) == true
        val typed = input.password.takeIf { it.isNotEmpty() }?.toCharArray()

        return when {
            typed != null -> Secrets(testPassword = typed, savePassword = typed.copyOf())

            // A sign-in prompt exists because the stored password did not work.
            !keepsStored || testing.mode == WebDavFormMode.SIGN_IN -> {
                fail(testing, R.string.explorer_webdav_form_error_password_required.toCaString())
                null
            }

            else -> {
                val stored = resolveStored(testing, existing!!) as? WebDavCredential.Password ?: return null
                Secrets(testPassword = stored.password, stored = stored)
            }
        }
    }

    /** Nothing typed while editing: the test runs against the stored password, never against nothing. */
    private suspend fun resolveStored(
        testing: ExplorerDialogState.WebDavLocationForm,
        location: WebDavLocation,
    ): WebDavCredential? = try {
        credentialStore.resolve(location)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "resolveStored(): Stored credential unusable: ${e.asLog()}" }
        fail(testing, e.localizedDescription())
        null
    }

    /** An unchanged HTTPS origin keeps its pin, anything else has to be trusted by the system or confirmed. */
    private fun WebDavLocation.pinFor(details: WebDavLocationInput.Parsed): TrustedCertificate? = pinnedCertificate
        ?.takeIf { !WebDavLocationInput.isCleartext(details.url) && details.host == host && details.port == port }

    private fun parseDetails(input: WebDavLocationFormInput) = WebDavLocationInput.parse(
        url = input.url,
        label = input.label,
        authType = input.authType,
        username = input.username,
    )

    private fun fail(testing: ExplorerDialogState.WebDavLocationForm, error: CaString) {
        dialogs.showIfCurrent(testing, testing.copy(isTesting = false, error = error))
    }

    private fun ExplorerDialogState.WebDavLocationForm.isBusy(): Boolean = isTesting ||
        certificateConfirmation != null || retrustConfirmation != null

    /** See [ExplorerSmbLocationController]: only a directory needs the reload to be back in it. */
    private suspend fun refreshUnlessLive() {
        if (currentLocation() is ExplorerLocation.Network) {
            log(tag) { "Network list is live, no refresh needed" }
            return
        }
        workspace().navigate(ExplorerNavigation.Refresh)
    }

    private fun Throwable.localizedDescription(): CaString = caString { cx -> localized(cx).asText().get(cx) }

    private fun WebDavConnectionTester.Result.message(details: WebDavLocationInput.Parsed): CaString = when (this) {
        WebDavConnectionTester.Result.AuthenticationFailed -> caString {
            it.getString(R.string.explorer_webdav_form_error_auth, details.host)
        }

        WebDavConnectionTester.Result.AccessDenied -> R.string.explorer_webdav_form_error_access_denied.toCaString()
        WebDavConnectionTester.Result.NotACollection -> R.string.explorer_webdav_form_error_not_collection.toCaString()
        WebDavConnectionTester.Result.Missing -> R.string.explorer_webdav_form_error_missing.toCaString()
        is WebDavConnectionTester.Result.Unreachable -> caString {
            it.getString(R.string.explorer_webdav_form_error_unreachable, details.url)
        }

        is WebDavConnectionTester.Result.Failed -> cause.localizedDescription()

        is WebDavConnectionTester.Result.CertificateMismatch -> caString {
            it.getString(
                R.string.explorer_webdav_form_error_certificate_mismatch,
                expected.sha256Fingerprint,
                presented.sha256Fingerprint,
            )
        }

        is WebDavConnectionTester.Result.CertificateUntrusted,
        WebDavConnectionTester.Result.Success -> R.string.explorer_webdav_form_error_unconfirmed.toCaString()
    }
}
