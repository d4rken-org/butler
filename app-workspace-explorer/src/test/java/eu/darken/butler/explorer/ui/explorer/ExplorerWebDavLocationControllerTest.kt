package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.webdav.WebDavCertificateException
import eu.darken.butler.common.files.webdav.WebDavConnectionTester
import eu.darken.butler.common.files.webdav.WebDavLocationInput
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavFormMode
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavLocationFormInput
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerWebDavLocationControllerTest : BaseTest() {

    private fun pin(seed: Int): TrustedCertificate = MockDataProvider.mockTrustedCertificate(seed)

    private fun certificate(seed: Int) = MockDataProvider.mockWebDavCertificate(seed)

    private val storedUrl = "https://cloud.example.com/remote.php/dav/files/alice/"

    private val stored = WebDavLocation(
        id = Uuid.parse("88888888-9999-aaaa-bbbb-cccccccccccc"),
        label = "Nextcloud",
        url = storedUrl,
        authType = WebDavLocation.AuthType.BASIC,
        username = "alice",
        rememberCredential = true,
        credentialVersion = 1,
        pinnedCertificate = pin(1),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun input(
        url: String = storedUrl,
        authType: WebDavLocation.AuthType = WebDavLocation.AuthType.BASIC,
        username: String = "alice",
        password: String = "hunter2",
        label: String = "",
        revision: Int = 5,
    ) = WebDavLocationFormInput(
        label = label,
        url = url,
        authType = authType,
        username = username,
        password = password,
        rememberCredential = true,
        revision = revision,
    )

    // region fakes

    private data class TestCall(
        val url: String,
        val authType: WebDavLocation.AuthType,
        val username: String?,
        val password: String?,
        val pin: TrustedCertificate?,
    )

    private val testCalls = mutableListOf<TestCall>()
    private val testedPasswords = mutableListOf<CharArray>()
    private val testResults = ArrayDeque<WebDavConnectionTester.Result>()

    private val tester = mockk<WebDavConnectionTester>().apply {
        coEvery { test(any(), any(), any(), any(), any()) } coAnswers {
            val password = arg<CharArray?>(3)
            password?.let { testedPasswords.add(it) }
            testCalls.add(
                TestCall(
                    url = arg(0),
                    authType = arg(1),
                    username = arg(2),
                    password = password?.concatToString(),
                    pin = arg(4),
                )
            )
            testResults.removeFirst()
        }
    }

    private data class Saved(
        val existingId: Uuid?,
        val url: String,
        val authType: WebDavLocation.AuthType,
        val username: String?,
        val password: String?,
        val pin: TrustedCertificate?,
    )

    private val saved = mutableListOf<Saved>()
    private val savedPasswords = mutableListOf<CharArray>()
    private val retrusts = mutableListOf<List<Any>>()
    private var retrustResult: WebDavLocationManager.RetrustResult =
        WebDavLocationManager.RetrustResult.Retrusted(stored.copy(pinnedCertificate = pin(2), trustRevision = 2))

    private val locationManager = mockk<WebDavLocationManager>().apply {
        coEvery { get(stored.id) } returns stored
        coEvery { create(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val password = arg<CharArray?>(5)
            password?.let { savedPasswords.add(it) }
            saved.add(
                Saved(
                    existingId = null,
                    url = arg(1),
                    authType = arg(2),
                    username = arg(3),
                    password = password?.concatToString(),
                    pin = arg(6),
                )
            )
            stored
        }
        coEvery { update(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val password = arg<CharArray?>(6)
            password?.let { savedPasswords.add(it) }
            saved.add(
                Saved(
                    existingId = arg(0),
                    url = arg(2),
                    authType = arg(3),
                    username = arg(4),
                    password = password?.concatToString(),
                    pin = arg(7),
                )
            )
            stored
        }
        coEvery { retrust(any(), any(), any(), any()) } coAnswers {
            retrusts.add(listOf(arg<Uuid>(0), arg<String>(1), arg<Int>(2), arg<TrustedCertificate>(3)))
            retrustResult
        }
    }

    private val storedCredential = mutableListOf<WebDavCredential.Password>()

    private val credentialStore = mockk<WebDavCredentialStore>().apply {
        coEvery { resolve(any()) } answers {
            WebDavCredential.Password("alice", "stored-secret".toCharArray()).also { storedCredential.add(it) }
        }
    }

    private val workspace = mockk<ExplorerWorkspace>().apply {
        coEvery { navigate(any()) } just Runs
    }

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private val hints = mutableListOf<SmbUpgradeHint.Reason>()

    private fun CoroutineScope.controller(
        dialogs: ExplorerDialogController,
        upgradeRepo: UpgradeRepo = FakeUpgradeRepo(pro = true),
    ) = ExplorerWebDavLocationController(
        locationManager = locationManager,
        credentialStore = credentialStore,
        connectionTester = tester,
        upgradeRepo = upgradeRepo,
        showUpgradeHint = { hints.add(it) },
        dialogs = dialogs,
        workspace = { workspace },
        currentLocation = { null },
        clearSelection = {},
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    private fun ExplorerDialogController.form() = current().shouldBeInstanceOf<ExplorerDialogState.WebDavLocationForm>()

    private fun certificateProblem(stored: Int?) = WebDavCertificateException(
        locationId = this.stored.id,
        endpoint = this.stored.endpointLabel,
        presented = certificate(2),
        stored = stored?.let { certificate(it) },
        url = storedUrl,
        trustRevision = 3,
    )

    // endregion

    // region Pro

    @Test
    fun `a free user gets the add, edit and sign-in forms`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false))

        controller.showAddForm()
        dialogs.form().mode shouldBe WebDavFormMode.ADD

        controller.showEditForm(stored.id)
        advanceUntilIdle()
        dialogs.form().mode shouldBe WebDavFormMode.EDIT

        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        dialogs.form().mode shouldBe WebDavFormMode.SIGN_IN
        dialogs.form().existing shouldBe stored
    }

    /** Saving is free, browsing is not: the server is stored and the user is told why it stays shut. */
    @Test
    fun `a free user adds a server and gets the upgrade hint`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false))
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input())
        advanceUntilIdle()

        saved.single().existingId shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
        hints shouldBe listOf(SmbUpgradeHint.Reason.SAVED)
    }

    @Test
    fun `a pro user saves a server without the upgrade hint`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = true))
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input())
        advanceUntilIdle()

        saved.size shouldBe 1
        hints.shouldBeEmpty()
    }

    @Test
    fun `saving while billing connects shows no hint`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false, settled = false))
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input())
        advanceUntilIdle()

        saved.size shouldBe 1
        hints.shouldBeEmpty()
    }

    // endregion

    // region add

    @Test
    fun `a server the system trusts is saved without a pin`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(url = " HTTPS://cloud.example.com/remote.php/dav/files/alice", label = " Cloud "))
        advanceUntilIdle()

        testCalls.single() shouldBe TestCall(
            url = storedUrl,
            authType = WebDavLocation.AuthType.BASIC,
            username = "alice",
            password = "hunter2",
            pin = null,
        )
        saved.single() shouldBe Saved(
            existingId = null,
            url = storedUrl,
            authType = WebDavLocation.AuthType.BASIC,
            username = "alice",
            password = "hunter2",
            pin = null,
        )
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
        coVerify { locationManager.create("Cloud", any(), any(), any(), true, any(), null) }
    }

    @Test
    fun `a plain HTTP server is saved without a pin`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(url = "http://nas.local:8080/dav"))
        advanceUntilIdle()

        testCalls.single().url shouldBe "http://nas.local:8080/dav/"
        testCalls.single().pin shouldBe null
        saved.single().url shouldBe "http://nas.local:8080/dav/"
        saved.single().pin shouldBe null
    }

    @Test
    fun `an anonymous server is tested and saved without any credential`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(authType = WebDavLocation.AuthType.ANONYMOUS, username = "ignored", password = ""))
        advanceUntilIdle()

        testCalls.single() shouldBe TestCall(
            url = storedUrl,
            authType = WebDavLocation.AuthType.ANONYMOUS,
            username = null,
            password = null,
            pin = null,
        )
        saved.single().authType shouldBe WebDavLocation.AuthType.ANONYMOUS
        saved.single().username shouldBe null
        saved.single().password shouldBe null
    }

    @Test
    fun `an untrusted certificate is confirmed, tested again pinned to it and saved as the pin`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        val confirmation = dialogs.form().certificateConfirmation.shouldNotBeNull()
        confirmation.url shouldBe storedUrl
        confirmation.presented shouldBe certificate(2)
        confirmation.formRevision shouldBe 5
        dialogs.form().isTesting shouldBe false
        saved.shouldBeEmpty()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onCertificateAccepted(confirmation.id, input())
        advanceUntilIdle()

        testCalls.map { it.pin } shouldBe listOf(null, pin(2))
        saved.single().pin shouldBe pin(2)
        saved.single().existingId shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `rejecting the certificate saves nothing`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()
        controller.onCertificateRejected(dialogs.form().certificateConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().certificateConfirmation shouldBe null
        dialogs.form().error shouldNotBe null
        testCalls.size shouldBe 1
        saved.shouldBeEmpty()
    }

    @Test
    fun `an acceptance after the form was edited is dropped`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input(revision = 5))
        advanceUntilIdle()
        val confirmation = dialogs.form().certificateConfirmation!!

        controller.onCertificateAccepted(confirmation.id, input(password = "other", revision = 6))
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved.shouldBeEmpty()
        dialogs.form().certificateConfirmation shouldBe null
        dialogs.form().isTesting shouldBe false
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `an acceptance for another URL is dropped`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        controller.onCertificateAccepted(
            dialogs.form().certificateConfirmation!!.id,
            input(url = "https://evil.example.com/remote.php/dav/files/alice/"),
        )
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved.shouldBeEmpty()
    }

    @Test
    fun `a question from a closed form cannot be accepted`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()
        val confirmation = dialogs.form().certificateConfirmation!!

        dialogs.dismiss()
        dialogs.show(ExplorerDialogState.WebDavLocationForm())
        controller.onCertificateAccepted(confirmation.id, input())
        advanceUntilIdle()

        dialogs.dismiss()
        controller.onCertificateAccepted(confirmation.id, input())
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved.shouldBeEmpty()
    }

    @Test
    fun `a certificate that changed before the pinned test is reported, nothing is saved`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.CertificateUntrusted(certificate(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        testResults.add(WebDavConnectionTester.Result.CertificateMismatch(certificate(2), certificate(1)))
        controller.onCertificateAccepted(dialogs.form().certificateConfirmation!!.id, input())
        advanceUntilIdle()

        testCalls.last().pin shouldBe pin(2)
        saved.shouldBeEmpty()
        dialogs.form().isTesting shouldBe false
        dialogs.form().retrustConfirmation shouldBe null
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `an invalid URL is reported on its field and nothing is tested`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        controller.onFormSubmit(input(url = "ftp://cloud.example.com/", username = "al:ice", revision = 7))
        advanceUntilIdle()

        testCalls.shouldBeEmpty()
        dialogs.form().issues shouldBe listOf(
            WebDavLocationInput.Issue.UrlSchemeUnsupported,
            WebDavLocationInput.Issue.UsernameMalformed,
        )
        dialogs.form().issuesRevision shouldBe 7
    }

    @Test
    fun `the password arrays are wiped once the submit is done`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input())
        advanceUntilIdle()

        saved.single().password shouldBe "hunter2"
        (testedPasswords + savedPasswords).size shouldBe 2
        (testedPasswords + savedPasswords).forEach { array -> array.all { it == Char(0) } shouldBe true }
    }

    @Test
    fun `the stored password is wiped after an edit tested with it`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(password = ""))
        advanceUntilIdle()

        testCalls.single().password shouldBe "stored-secret"
        storedCredential.single().password.all { it == Char(0) } shouldBe true
    }

    // endregion

    // region edit

    @Test
    fun `an edit that keeps the origin is tested against the pin and keeps it`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(url = "https://cloud.example.com/remote.php/dav/files/alice/Photos/", password = ""))
        advanceUntilIdle()

        testCalls.single().pin shouldBe pin(1)
        testCalls.single().password shouldBe "stored-secret"
        saved.single().existingId shouldBe stored.id
        saved.single().pin shouldBe pin(1)
        saved.single().password shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `an edit that moves to another server drops the pin unless the new one is confirmed`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(url = "https://dav.example.org/", password = ""))
        advanceUntilIdle()

        testCalls.single().pin shouldBe null
        saved.single().url shouldBe "https://dav.example.org/"
        saved.single().pin shouldBe null
    }

    @Test
    fun `an edit to plain HTTP on the same host drops the pin`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(url = "http://cloud.example.com:443/remote.php/dav/files/alice/", password = ""))
        advanceUntilIdle()

        testCalls.single().pin shouldBe null
        saved.single().pin shouldBe null
    }

    @Test
    fun `a changed username needs the password again`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        controller.onFormSubmit(input(username = "bob", password = ""))
        advanceUntilIdle()

        testCalls.shouldBeEmpty()
        dialogs.form().error shouldNotBe null
    }

    // endregion

    // region sign-in

    @Test
    fun `the sign-in form asks for the password again instead of reusing the stored one`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()

        controller.onFormSubmit(input(password = ""))
        advanceUntilIdle()

        testCalls.shouldBeEmpty()
        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { credentialStore.resolve(any()) }

        testResults.add(WebDavConnectionTester.Result.Success)
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        testCalls.single().password shouldBe "new-secret"
        testCalls.single().pin shouldBe pin(1)
        saved.single().password shouldBe "new-secret"
        saved.single().pin shouldBe pin(1)
    }

    @Test
    fun `a rejected password keeps the sign-in form open with the reason`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()

        testResults.add(WebDavConnectionTester.Result.AuthenticationFailed)
        controller.onFormSubmit(input(password = "wrong"))
        advanceUntilIdle()

        saved.shouldBeEmpty()
        dialogs.form().mode shouldBe WebDavFormMode.SIGN_IN
        dialogs.form().isTesting shouldBe false
        dialogs.form().error shouldNotBe null
    }

    // endregion

    // region re-trust

    @Test
    fun `a changed certificate found by the sign-in test offers the re-trust question`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()

        testResults.add(WebDavConnectionTester.Result.CertificateMismatch(certificate(1), certificate(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        saved.shouldBeEmpty()
        val confirmation = dialogs.form().retrustConfirmation.shouldNotBeNull()
        confirmation.locationId shouldBe stored.id
        confirmation.url shouldBe storedUrl
        confirmation.stored shouldBe certificate(1)
        confirmation.presented shouldBe certificate(2)
        confirmation.trustRevision shouldBe stored.trustRevision
        confirmation.fromFormTest shouldBe true
    }

    @Test
    fun `accepting a changed certificate found by the form re-trusts it and keeps the form open`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        testResults.add(WebDavConnectionTester.Result.CertificateMismatch(certificate(1), certificate(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()
        val asked = dialogs.form()

        controller.onRetrustAccepted(asked.retrustConfirmation!!.id)
        advanceUntilIdle()

        retrusts.single() shouldBe listOf(stored.id, storedUrl, stored.trustRevision, pin(2))
        val form = dialogs.form()
        form.formId shouldBe asked.formId
        form.mode shouldBe WebDavFormMode.SIGN_IN
        form.existing shouldBe stored.copy(pinnedCertificate = pin(2), trustRevision = 2)
        form.retrustConfirmation shouldBe null
        form.isTesting shouldBe false
        saved.shouldBeEmpty()
        hints.shouldBeEmpty()
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `rejecting a changed certificate found by the form leaves the mismatch reported`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        testResults.add(WebDavConnectionTester.Result.CertificateMismatch(certificate(1), certificate(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        controller.onRetrustRejected(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().retrustConfirmation.shouldBeNull()
        dialogs.form().error shouldNotBe null
        retrusts.shouldBeEmpty()
    }

    @Test
    fun `reviewing a changed certificate asks before anything is replaced`() = runTest {
        val dialogs = dialogs()

        controller(dialogs, FakeUpgradeRepo(pro = false)).reviewCertificateProblem(certificateProblem(stored = 1))
        advanceUntilIdle()

        val form = dialogs.form()
        form.mode shouldBe WebDavFormMode.EDIT
        form.existing shouldBe stored
        val confirmation = form.retrustConfirmation.shouldNotBeNull()
        confirmation.url shouldBe storedUrl
        confirmation.stored shouldBe certificate(1)
        confirmation.presented shouldBe certificate(2)
        confirmation.trustRevision shouldBe 3
        confirmation.fromFormTest shouldBe false
        retrusts.shouldBeEmpty()
        hints.shouldBeEmpty()
    }

    @Test
    fun `reviewing an untrusted certificate of a stored server asks to trust it`() = runTest {
        val dialogs = dialogs()

        controller(dialogs).reviewCertificateProblem(certificateProblem(stored = null))
        advanceUntilIdle()

        val confirmation = dialogs.form().retrustConfirmation.shouldNotBeNull()
        confirmation.stored shouldBe null
        confirmation.presented shouldBe certificate(2)
    }

    @Test
    fun `accepting a reviewed certificate re-trusts exactly what the failed connection saw`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewCertificateProblem(certificateProblem(stored = null))
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        retrusts.single() shouldBe listOf(stored.id, storedUrl, 3, pin(2))
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `a re-trust for a location that changed since changes nothing and says so`() = runTest {
        retrustResult = WebDavLocationManager.RetrustResult.EndpointChanged
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewCertificateProblem(certificateProblem(stored = 1))
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().isTesting shouldBe false
        dialogs.form().retrustConfirmation shouldBe null
        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `a re-trust for a removed location changes nothing and says so`() = runTest {
        retrustResult = WebDavLocationManager.RetrustResult.NotFound
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewCertificateProblem(certificateProblem(stored = 1))
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `no review opens for a location that is gone`() = runTest {
        coEvery { locationManager.get(stored.id) } returns null
        val dialogs = dialogs()

        controller(dialogs).reviewCertificateProblem(certificateProblem(stored = 1))
        advanceUntilIdle()

        dialogs.current() shouldBe ExplorerDialogState.None
    }

    // endregion
}
