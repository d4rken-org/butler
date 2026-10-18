package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.sftp.SftpConnectionTester
import eu.darken.butler.common.files.sftp.SftpHostKeyChangedException
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationChangedException
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.SftpPrivateKeyReader
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpFormMode
import eu.darken.butler.explorer.ui.explorer.dialogs.SftpLocationFormInput
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerSftpLocationControllerTest : BaseTest() {

    private fun hostKey(seed: Int) = TrustedHostKey(
        type = "ssh-ed25519",
        blob = ByteArray(51) { seed.toByte() },
        fingerprint = "SHA256:key$seed",
    )

    private val stored = SftpLocation(
        id = Uuid.parse("66666666-7777-8888-9999-000000000000"),
        label = "Build server",
        host = "build.lan",
        username = "darken",
        basePath = "/srv",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = hostKey(1),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun input(
        host: String = "build.lan",
        port: String = "22",
        username: String = "darken",
        authType: SftpLocation.AuthType = SftpLocation.AuthType.PASSWORD,
        password: String = "hunter2",
        passphrase: String = "",
        rememberPassphrase: Boolean = true,
        label: String = "",
        revision: Int = 5,
    ) = SftpLocationFormInput(
        label = label,
        host = host,
        port = port,
        username = username,
        basePath = "/srv",
        authType = authType,
        password = password,
        passphrase = passphrase,
        rememberCredential = true,
        rememberPassphrase = rememberPassphrase,
        revision = revision,
    )

    private val keyLocation = stored.copy(authType = SftpLocation.AuthType.PRIVATE_KEY, credentialVersion = 3)

    private val askingLocation = keyLocation.copy(rememberPassphrase = false)

    private fun keyInput(passphrase: String = "", rememberPassphrase: Boolean) = input(
        authType = SftpLocation.AuthType.PRIVATE_KEY,
        password = "",
        passphrase = passphrase,
        rememberPassphrase = rememberPassphrase,
    )

    // region fakes

    private data class TestCall(
        val host: String,
        val port: Int,
        val password: String?,
        val privateKey: String?,
        val passphrase: String?,
        val trustedKey: TrustedHostKey?,
    )

    private val testCalls = mutableListOf<TestCall>()
    private val testResults = ArrayDeque<SftpConnectionTester.Result>()

    private val tester = mockk<SftpConnectionTester>().apply {
        coEvery {
            test(
                host = any(),
                port = any(),
                username = any(),
                authType = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                basePath = any(),
                trustedKey = any(),
            )
        } coAnswers {
            testCalls.add(
                TestCall(
                    host = arg(0),
                    port = arg(1),
                    password = arg<CharArray?>(4)?.concatToString(),
                    privateKey = arg<ByteArray?>(5)?.decodeToString(),
                    passphrase = arg<CharArray?>(6)?.concatToString(),
                    trustedKey = arg(8),
                )
            )
            testResults.removeFirst()
        }
    }

    private fun success() = mockk<SftpConnectionTester.Result.Success>()

    private fun unknown(key: TrustedHostKey) = mockk<SftpConnectionTester.Result.HostKeyUnknown> {
        every { presentedKey } returns key
    }

    private fun mismatch(expected: TrustedHostKey, presented: TrustedHostKey) =
        mockk<SftpConnectionTester.Result.HostKeyMismatch> {
            every { expectedKey } returns expected
            every { presentedKey } returns presented
        }

    private data class Saved(
        val existingId: Uuid?,
        val host: String,
        val port: Int,
        val password: String?,
        val privateKey: String?,
        val passphrase: String?,
        val hostKey: TrustedHostKey?,
        val rememberPassphrase: Boolean = true,
        val expectedCredentialVersion: Int? = null,
    )

    private data class Hold(val id: Uuid, val credentialVersion: Int, val passphrase: String)

    private val saved = mutableListOf<Saved>()
    private val holds = mutableListOf<Hold>()
    private val managerCalls = mutableListOf<String>()
    private var updateFailure: Exception? = null
    private var holdResult = true
    private val retrusts = mutableListOf<List<Any>>()
    private var retrustResult: SftpLocationManager.RetrustResult =
        SftpLocationManager.RetrustResult.Retrusted(stored.copy(hostKey = hostKey(2), trustRevision = 2))

    private val locationManager = mockk<SftpLocationManager>().apply {
        coEvery { get(stored.id) } returns stored
        coEvery {
            create(
                label = any(),
                host = any(),
                port = any(),
                username = any(),
                basePath = any(),
                authType = any(),
                rememberCredential = any(),
                rememberPassphrase = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                hostKey = any(),
            )
        } coAnswers {
            saved.add(
                Saved(
                    existingId = null,
                    host = arg(1),
                    port = arg(2),
                    password = arg<CharArray?>(8)?.concatToString(),
                    privateKey = arg<ByteArray?>(9)?.decodeToString(),
                    passphrase = arg<CharArray?>(10)?.concatToString(),
                    hostKey = arg(11),
                    rememberPassphrase = arg(7),
                )
            )
            stored
        }
        coEvery {
            update(
                id = any(),
                label = any(),
                host = any(),
                port = any(),
                username = any(),
                basePath = any(),
                authType = any(),
                rememberCredential = any(),
                rememberPassphrase = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                hostKey = any(),
                expectedCredentialVersion = any(),
            )
        } coAnswers {
            managerCalls.add("update")
            updateFailure?.let { throw it }
            saved.add(
                Saved(
                    existingId = arg(0),
                    host = arg(2),
                    port = arg(3),
                    password = arg<CharArray?>(9)?.concatToString(),
                    privateKey = arg<ByteArray?>(10)?.decodeToString(),
                    passphrase = arg<CharArray?>(11)?.concatToString(),
                    hostKey = arg(12),
                    rememberPassphrase = arg(8),
                    expectedCredentialVersion = arg(13),
                )
            )
            stored
        }
        coEvery { holdPassphrase(any(), any(), any()) } coAnswers {
            managerCalls.add("hold")
            holds.add(Hold(arg(0), arg(1), arg<CharArray>(2).concatToString()))
            holdResult
        }
        coEvery { retrust(any(), any(), any(), any(), any()) } coAnswers {
            retrusts.add(listOf(arg<Uuid>(0), arg<String>(1), arg<Int>(2), arg<Int>(3), arg<TrustedHostKey>(4)))
            retrustResult
        }
    }

    private var storedPassphrase: String? = "stored-phrase"
    private var heldPassphrase: String? = null

    private val credentialStore = mockk<SftpCredentialStore>().apply {
        coEvery { resolve(any()) } answers { SftpCredential.Password("darken", "stored-secret".toCharArray()) }
        coEvery { resolveStoredKey(any(), any()) } answers {
            val location = firstArg<SftpLocation>()
            val passphrase = secondArg<CharArray?>()?.concatToString()
                ?: if (location.asksForPassphrase) heldPassphrase else storedPassphrase
            SftpCredential.PrivateKey("darken", "STORED-KEY".toByteArray(), passphrase?.toCharArray())
        }
    }

    private val keyReader = mockk<SftpPrivateKeyReader>()
    private val pickerId = Workspace.Id()
    private val keyPath = LocalPath.build("/storage/emulated/0/id_ed25519")

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
    ) = ExplorerSftpLocationController(
        locationManager = locationManager,
        credentialStore = credentialStore,
        connectionTester = tester,
        keyReader = keyReader,
        upgradeRepo = upgradeRepo,
        showUpgradeHint = { hints.add(it) },
        dialogs = dialogs,
        launchKeyPicker = { pickerId },
        workspace = { workspace },
        currentLocation = { null },
        clearSelection = {},
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    private fun ExplorerDialogController.form() = current().shouldBeInstanceOf<ExplorerDialogState.SftpLocationForm>()

    private fun ExplorerDialogController.errorText() = form().error.shouldNotBeNull().get(mockk(relaxed = true))

    // endregion

    // region Pro

    @Test
    fun `a free user gets the add form`() = runTest {
        val dialogs = dialogs()

        controller(dialogs, FakeUpgradeRepo(pro = false)).showAddForm()
        advanceUntilIdle()

        dialogs.form().mode shouldBe SftpFormMode.ADD
    }

    @Test
    fun `a free user gets the edit form`() = runTest {
        val dialogs = dialogs()

        controller(dialogs, FakeUpgradeRepo(pro = false)).showEditForm(stored.id)
        advanceUntilIdle()

        dialogs.form().mode shouldBe SftpFormMode.EDIT
    }

    @Test
    fun `a free user gets the sign-in form`() = runTest {
        val dialogs = dialogs()

        controller(dialogs, FakeUpgradeRepo(pro = false)).promptSignIn(stored.id)
        advanceUntilIdle()

        dialogs.form().mode shouldBe SftpFormMode.SIGN_IN
    }

    @Test
    fun `a free user reviews a changed key in the edit form`() = runTest {
        val dialogs = dialogs()

        controller(dialogs, FakeUpgradeRepo(pro = false)).reviewHostKeyChange(hostKeyChange())
        advanceUntilIdle()

        val form = dialogs.form()
        form.mode shouldBe SftpFormMode.EDIT
        form.existing shouldBe stored
        val confirmation = form.retrustConfirmation.shouldNotBeNull()
        confirmation.locationId shouldBe stored.id
        confirmation.storedKey shouldBe hostKey(1)
        confirmation.presentedKey shouldBe hostKey(2)
        confirmation.trustRevision shouldBe stored.trustRevision
        hints shouldBe emptyList()
        retrusts shouldBe emptyList()
    }

    /** Saving is free, browsing is not: the server is stored and the user is told why it stays shut. */
    @Test
    fun `a free user adds a server and gets the upgrade hint`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false))
        controller.showAddForm()

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()
        testResults.add(success())
        controller.onHostKeyAccepted(dialogs.form().hostKeyConfirmation!!.id, input())
        advanceUntilIdle()

        testCalls.size shouldBe 2
        saved.single().existingId shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
        hints shouldBe listOf(SmbUpgradeHint.Reason.SAVED)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.saveEdit(upgradeRepo: UpgradeRepo): ExplorerDialogController {
        val dialogs = dialogs()
        val controller = controller(dialogs, upgradeRepo)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        testResults.add(success())
        controller.onFormSubmit(input(label = "Renamed", password = ""))
        advanceUntilIdle()

        saved.single().existingId shouldBe stored.id
        coVerify(exactly = 1) { workspace.navigate(ExplorerNavigation.Refresh) }
        return dialogs
    }

    @Test
    fun `a free user edits a server and gets the upgrade hint`() = runTest {
        val dialogs = saveEdit(FakeUpgradeRepo(pro = false))

        dialogs.current() shouldBe ExplorerDialogState.None
        hints shouldBe listOf(SmbUpgradeHint.Reason.SAVED)
    }

    @Test
    fun `a pro user saves a server without the upgrade hint`() = runTest {
        saveEdit(FakeUpgradeRepo(pro = true))

        hints shouldBe emptyList()
    }

    /** A paying user whose billing is still connecting must neither wait for it nor be told to upgrade. */
    @Test
    fun `saving while billing connects refreshes right away and shows no hint`() = runTest {
        saveEdit(FakeUpgradeRepo(pro = false, settled = false))

        hints shouldBe emptyList()
    }

    @Test
    fun `saving after a failed billing lookup shows no hint`() = runTest {
        saveEdit(FakeUpgradeRepo(pro = false, error = IllegalStateException("billing is down")))

        hints shouldBe emptyList()
    }

    /** Re-trusting a key from the form only keeps it open for the next attempt, nothing was saved yet. */
    @Test
    fun `accepting a changed key as a free user shows no hint`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false))
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        testResults.add(mismatch(expected = hostKey(1), presented = hostKey(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        retrusts.size shouldBe 1
        dialogs.form().retrustConfirmation shouldBe null
        hints shouldBe emptyList()
    }

    // endregion

    // region forms

    @Test
    fun `the edit form opens with what is stored now, not what the row was drawn from`() = runTest {
        val current = stored.copy(label = "Renamed")
        coEvery { locationManager.get(stored.id) } returns current
        val dialogs = dialogs()

        controller(dialogs).showEditForm(stored.id)
        advanceUntilIdle()

        dialogs.form().existing shouldBe current
        dialogs.form().mode shouldBe SftpFormMode.EDIT
    }

    @Test
    fun `no form opens for a location that is gone`() = runTest {
        coEvery { locationManager.get(stored.id) } returns null
        val dialogs = dialogs()

        controller(dialogs).showEditForm(stored.id)
        advanceUntilIdle()

        dialogs.current() shouldBe ExplorerDialogState.None
    }

    // endregion

    // region add

    @Test
    fun `adding confirms the presented key, tests again pinned to it and saves it`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.showAddForm()
        advanceUntilIdle()

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        val confirmation = dialogs.form().hostKeyConfirmation.shouldNotBeNull()
        confirmation.host shouldBe "build.lan"
        confirmation.port shouldBe 22
        confirmation.presentedKey shouldBe hostKey(2)
        testCalls.single().trustedKey shouldBe null
        saved shouldBe emptyList()

        testResults.add(success())
        controller.onHostKeyAccepted(confirmation.id, input())
        advanceUntilIdle()

        testCalls.map { it.trustedKey } shouldBe listOf(null, hostKey(2))
        saved.single() shouldBe Saved(
            existingId = null,
            host = "build.lan",
            port = 22,
            password = "hunter2",
            privateKey = null,
            passphrase = null,
            hostKey = hostKey(2),
        )
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `cancelling the key question saves nothing`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()
        controller.onHostKeyRejected(dialogs.form().hostKeyConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().hostKeyConfirmation shouldBe null
        dialogs.errorText() shouldNotBe null
        testCalls.size shouldBe 1
        saved shouldBe emptyList()
    }

    @Test
    fun `acceptance rejects input whose endpoint or revision differs from discovery`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input(revision = 5))
        advanceUntilIdle()
        val confirmation = dialogs.form().hostKeyConfirmation!!

        controller.onHostKeyAccepted(confirmation.id, input(host = "evil.lan", revision = 6))
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved shouldBe emptyList()
        dialogs.form().hostKeyConfirmation shouldBe null
        dialogs.form().isTesting shouldBe false
    }

    @Test
    fun `any other field edited after the test started voids the acceptance`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input(revision = 5))
        advanceUntilIdle()

        controller.onHostKeyAccepted(dialogs.form().hostKeyConfirmation!!.id, input(username = "root", revision = 6))
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved shouldBe emptyList()
    }

    @Test
    fun `a stale question cannot be accepted`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()
        val confirmation = dialogs.form().hostKeyConfirmation!!

        // Closed and opened again: the old question belongs to the old form.
        dialogs.dismiss()
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        controller.onHostKeyAccepted(confirmation.id, input())
        advanceUntilIdle()

        // Dismissed outright.
        dialogs.dismiss()
        controller.onHostKeyAccepted(confirmation.id, input())
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved shouldBe emptyList()
    }

    @Test
    fun `a key that changed before the pinned test is reported, nothing is saved`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        testResults.add(mismatch(expected = hostKey(2), presented = hostKey(3)))
        controller.onHostKeyAccepted(dialogs.form().hostKeyConfirmation!!.id, input())
        advanceUntilIdle()

        testCalls.last().trustedKey shouldBe hostKey(2)
        saved shouldBe emptyList()
        dialogs.form().isTesting shouldBe false
        dialogs.form().hostKeyConfirmation shouldBe null
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `a form dismissed during the pinned test saves nothing`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs, FakeUpgradeRepo(pro = false))
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input())
        advanceUntilIdle()

        coEvery {
            tester.test(
                host = any(),
                port = any(),
                username = any(),
                authType = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                basePath = any(),
                trustedKey = any(),
            )
        } coAnswers {
            dialogs.dismiss()
            success()
        }
        controller.onHostKeyAccepted(dialogs.form().hostKeyConfirmation!!.id, input())
        advanceUntilIdle()

        saved shouldBe emptyList()
        hints shouldBe emptyList()
    }

    // endregion

    // region edit

    @Test
    fun `an edit that keeps the endpoint is tested against the pin and keeps it`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        testResults.add(success())
        controller.onFormSubmit(input(label = "Renamed", password = ""))
        advanceUntilIdle()

        testCalls.single().trustedKey shouldBe hostKey(1)
        testCalls.single().password shouldBe "stored-secret"
        saved.single().hostKey shouldBe null
        saved.single().password shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `an edit that moves the endpoint needs the new server's key confirmed`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        testResults.add(unknown(hostKey(2)))
        controller.onFormSubmit(input(port = "2222"))
        advanceUntilIdle()

        testCalls.single().trustedKey shouldBe null
        saved shouldBe emptyList()
        val confirmation = dialogs.form().hostKeyConfirmation.shouldNotBeNull()
        confirmation.port shouldBe 2222

        testResults.add(success())
        controller.onHostKeyAccepted(confirmation.id, input(port = "2222"))
        advanceUntilIdle()

        saved.single().existingId shouldBe stored.id
        saved.single().port shouldBe 2222
        saved.single().hostKey shouldBe hostKey(2)
    }

    @Test
    fun `a changed username needs the password again`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        controller.onFormSubmit(input(username = "root", password = ""))
        advanceUntilIdle()

        testCalls shouldBe emptyList()
        dialogs.form().error shouldNotBe null
    }

    // endregion

    // region sign-in

    @Test
    fun `the sign-in form asks for the secret again instead of reusing the stored one`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        dialogs.form().mode shouldBe SftpFormMode.SIGN_IN

        controller.onFormSubmit(input(password = ""))
        advanceUntilIdle()

        testCalls shouldBe emptyList()
        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { credentialStore.resolve(any()) }

        testResults.add(success())
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        testCalls.single().password shouldBe "new-secret"
        testCalls.single().trustedKey shouldBe hostKey(1)
        saved.single().password shouldBe "new-secret"
        saved.single().hostKey shouldBe null
    }

    // endregion

    // region stored key

    private suspend fun kotlinx.coroutines.test.TestScope.submitStoredKey(
        mode: SftpFormMode,
        location: SftpLocation,
        input: SftpLocationFormInput,
    ): ExplorerDialogController {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = mode, existing = location))
        testResults.add(success())
        controller.onFormSubmit(input)
        advanceUntilIdle()
        return dialogs
    }

    @Test
    fun `signing in to a key that asks for its passphrase tests and holds the typed one`() = runTest {
        heldPassphrase = null

        val dialogs = submitStoredKey(
            SftpFormMode.SIGN_IN,
            askingLocation,
            keyInput(passphrase = "phrase", rememberPassphrase = false),
        )

        testCalls.single().privateKey shouldBe "STORED-KEY"
        testCalls.single().passphrase shouldBe "phrase"
        testCalls.single().trustedKey shouldBe hostKey(1)
        saved.single() shouldBe Saved(
            existingId = stored.id,
            host = "build.lan",
            port = 22,
            password = null,
            privateKey = null,
            passphrase = null,
            hostKey = null,
            rememberPassphrase = false,
            expectedCredentialVersion = 3,
        )
        holds.single() shouldBe Hold(stored.id, 3, "phrase")
        managerCalls shouldBe listOf("update", "hold")
        coVerify(exactly = 0) { credentialStore.resolve(any()) }
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `a sign-in whose location moved to another credential is not saved or held`() = runTest {
        updateFailure = SftpLocationChangedException(stored.id, 3, 4)

        val dialogs = submitStoredKey(
            SftpFormMode.SIGN_IN,
            askingLocation,
            keyInput(passphrase = "phrase", rememberPassphrase = false),
        )

        testCalls.size shouldBe 1
        holds shouldBe emptyList()
        dialogs.form().isTesting shouldBe false
        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `a refused hold keeps the sign-in form open with an error`() = runTest {
        holdResult = false

        val dialogs = submitStoredKey(
            SftpFormMode.SIGN_IN,
            askingLocation,
            keyInput(passphrase = "phrase", rememberPassphrase = false),
        )

        holds.single() shouldBe Hold(stored.id, 3, "phrase")
        dialogs.form().isTesting shouldBe false
        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `signing in to a key that asks for its passphrase needs one typed`() = runTest {
        heldPassphrase = "held-phrase"

        val dialogs = submitStoredKey(SftpFormMode.SIGN_IN, askingLocation, keyInput(rememberPassphrase = false))

        testCalls shouldBe emptyList()
        saved shouldBe emptyList()
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `editing a key that asks for its passphrase tests with the held one`() = runTest {
        heldPassphrase = "held-phrase"

        val dialogs = submitStoredKey(SftpFormMode.EDIT, askingLocation, keyInput(rememberPassphrase = false))

        testCalls.single().privateKey shouldBe "STORED-KEY"
        testCalls.single().passphrase shouldBe "held-phrase"
        saved.single().privateKey shouldBe null
        saved.single().passphrase shouldBe null
        saved.single().rememberPassphrase shouldBe false
        holds shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `editing a key that asks for its passphrase with none held needs one`() = runTest {
        heldPassphrase = null

        val dialogs = submitStoredKey(SftpFormMode.EDIT, askingLocation, keyInput(rememberPassphrase = false))

        testCalls shouldBe emptyList()
        saved shouldBe emptyList()
        dialogs.form().isTesting shouldBe false
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `editing a key with a remembered passphrase tests with it and keeps it`() = runTest {
        val dialogs = submitStoredKey(SftpFormMode.EDIT, keyLocation, keyInput(rememberPassphrase = true))

        testCalls.single().privateKey shouldBe "STORED-KEY"
        testCalls.single().passphrase shouldBe "stored-phrase"
        saved.single().privateKey shouldBe null
        saved.single().passphrase shouldBe null
        saved.single().expectedCredentialVersion shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `a new remembered passphrase saves the stored key again`() = runTest {
        submitStoredKey(SftpFormMode.EDIT, keyLocation, keyInput(passphrase = "new-phrase", rememberPassphrase = true))

        testCalls.single().passphrase shouldBe "new-phrase"
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe "new-phrase"
        saved.single().rememberPassphrase shouldBe true
        holds shouldBe emptyList()
    }

    @Test
    fun `no longer remembering a passphrase saves the stored key with the typed one`() = runTest {
        submitStoredKey(SftpFormMode.EDIT, keyLocation, keyInput(passphrase = "new-phrase", rememberPassphrase = false))

        testCalls.single().privateKey shouldBe "STORED-KEY"
        testCalls.single().passphrase shouldBe "new-phrase"
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe "new-phrase"
        saved.single().rememberPassphrase shouldBe false
        holds shouldBe emptyList()
    }

    @Test
    fun `no longer remembering a passphrase saves the stored key with the stored one`() = runTest {
        storedPassphrase = "stored-phrase"

        submitStoredKey(SftpFormMode.EDIT, keyLocation, keyInput(rememberPassphrase = false))

        testCalls.single().passphrase shouldBe "stored-phrase"
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe "stored-phrase"
        saved.single().rememberPassphrase shouldBe false
    }

    @Test
    fun `asking for the passphrase of a key without one saves it without one`() = runTest {
        storedPassphrase = null

        val dialogs = submitStoredKey(SftpFormMode.EDIT, keyLocation, keyInput(rememberPassphrase = false))

        testCalls.single().privateKey shouldBe "STORED-KEY"
        testCalls.single().passphrase shouldBe null
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe null
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `remembering an asked-for passphrase saves the stored key with the typed one`() = runTest {
        heldPassphrase = null

        submitStoredKey(SftpFormMode.EDIT, askingLocation, keyInput(passphrase = "phrase", rememberPassphrase = true))

        testCalls.single().passphrase shouldBe "phrase"
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe "phrase"
        saved.single().rememberPassphrase shouldBe true
        saved.single().expectedCredentialVersion shouldBe null
        holds shouldBe emptyList()
    }

    @Test
    fun `remembering an asked-for passphrase saves the stored key with the held one`() = runTest {
        heldPassphrase = "held-phrase"

        submitStoredKey(SftpFormMode.EDIT, askingLocation, keyInput(rememberPassphrase = true))

        testCalls.single().passphrase shouldBe "held-phrase"
        saved.single().privateKey shouldBe "STORED-KEY"
        saved.single().passphrase shouldBe "held-phrase"
        saved.single().rememberPassphrase shouldBe true
    }

    @Test
    fun `remembering an asked-for passphrase needs one`() = runTest {
        heldPassphrase = null

        val dialogs = submitStoredKey(SftpFormMode.EDIT, askingLocation, keyInput(rememberPassphrase = true))

        testCalls shouldBe emptyList()
        saved shouldBe emptyList()
        dialogs.form().error shouldNotBe null
    }

    @Test
    fun `a picked key file is saved with the chosen passphrase setting`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = keyLocation))
        pickKey(controller, SftpPrivateKeyReader.Result.Loaded("id_ed25519", "KEY-BYTES".toByteArray()))

        testResults.add(success())
        controller.onFormSubmit(keyInput(passphrase = "phrase", rememberPassphrase = false))
        advanceUntilIdle()

        testCalls.single().privateKey shouldBe "KEY-BYTES"
        saved.single().privateKey shouldBe "KEY-BYTES"
        saved.single().passphrase shouldBe "phrase"
        saved.single().rememberPassphrase shouldBe false
        coVerify(exactly = 0) { credentialStore.resolveStoredKey(any(), any()) }
    }

    // endregion

    // region key file

    private suspend fun kotlinx.coroutines.test.TestScope.pickKey(
        controller: ExplorerSftpLocationController,
        result: SftpPrivateKeyReader.Result,
    ) {
        coEvery { keyReader.read(keyPath) } returns result
        controller.pickKeyFile()
        advanceUntilIdle()
        controller.onKeyPickerResult(WorkspaceEvent.PickerResult(pickerId, Workspace.Id(), listOf(keyPath)))
        advanceUntilIdle()
    }

    @Test
    fun `a picked key file is shown by name and used for the test and the save`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        pickKey(controller, SftpPrivateKeyReader.Result.Loaded("id_ed25519", "KEY-BYTES".toByteArray()))
        dialogs.form().keyFileName shouldBe "id_ed25519"

        testResults.add(unknown(hostKey(2)))
        val keyInput = input(authType = SftpLocation.AuthType.PRIVATE_KEY, password = "", passphrase = "phrase")
        controller.onFormSubmit(keyInput)
        advanceUntilIdle()
        testResults.add(success())
        controller.onHostKeyAccepted(dialogs.form().hostKeyConfirmation!!.id, keyInput)
        advanceUntilIdle()

        testCalls.map { it.privateKey } shouldBe listOf("KEY-BYTES", "KEY-BYTES")
        saved.single().privateKey shouldBe "KEY-BYTES"
        saved.single().passphrase shouldBe "phrase"
        saved.single().password shouldBe null
    }

    @Test
    fun `a key file picked after the test voids the acceptance`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        pickKey(controller, SftpPrivateKeyReader.Result.Loaded("id_ed25519", "KEY-A".toByteArray()))

        testResults.add(unknown(hostKey(2)))
        val keyInput = input(authType = SftpLocation.AuthType.PRIVATE_KEY, password = "")
        controller.onFormSubmit(keyInput)
        advanceUntilIdle()
        val confirmation = dialogs.form().hostKeyConfirmation!!

        // The question is modal in the UI; this is the race it cannot rule out.
        dialogs.show(dialogs.form().copy(hostKeyConfirmation = null))
        pickKey(controller, SftpPrivateKeyReader.Result.Loaded("id_rsa", "KEY-B".toByteArray()))
        dialogs.show(dialogs.form().copy(hostKeyConfirmation = confirmation))
        controller.onHostKeyAccepted(confirmation.id, keyInput)
        advanceUntilIdle()

        testCalls.size shouldBe 1
        saved shouldBe emptyList()
    }

    @Test
    fun `a key file over the size cap is rejected`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())

        pickKey(controller, SftpPrivateKeyReader.Result.TooLarge)

        dialogs.form().keyFileName shouldBe null
        dialogs.form().isReadingKey shouldBe false
        dialogs.form().error shouldNotBe null

        controller.onFormSubmit(input(authType = SftpLocation.AuthType.PRIVATE_KEY, password = ""))
        advanceUntilIdle()
        testCalls shouldBe emptyList()
    }

    @Test
    fun `a picked key is wiped once its form closes`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        val bytes = "KEY-BYTES".toByteArray()

        pickKey(controller, SftpPrivateKeyReader.Result.Loaded("id_ed25519", bytes))
        dialogs.dismiss()
        controller.onDialogState(dialogs.current())

        bytes.all { it == 0.toByte() } shouldBe true
    }

    @Test
    fun `a result from another picker is ignored`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        controller.pickKeyFile()
        advanceUntilIdle()

        controller.onKeyPickerResult(WorkspaceEvent.PickerResult(Workspace.Id(), Workspace.Id(), listOf(keyPath)))
        advanceUntilIdle()

        coVerify(exactly = 0) { keyReader.read(any()) }
        dialogs.form().keyFileName shouldBe null
    }

    // endregion

    // region re-trust

    private fun hostKeyChange() = SftpHostKeyChangedException(
        endpoint = stored.endpointLabel,
        locationId = stored.id,
        host = stored.host,
        port = stored.port,
        trustRevision = stored.trustRevision,
        storedKey = hostKey(1),
        presentedKey = hostKey(2),
    )

    @Test
    fun `reviewing a changed key asks before anything is replaced`() = runTest {
        val dialogs = dialogs()

        controller(dialogs).reviewHostKeyChange(hostKeyChange())
        advanceUntilIdle()

        val form = dialogs.form()
        form.mode shouldBe SftpFormMode.EDIT
        form.existing shouldBe stored
        val confirmation = form.retrustConfirmation.shouldNotBeNull()
        confirmation.storedKey shouldBe hostKey(1)
        confirmation.presentedKey shouldBe hostKey(2)
        retrusts shouldBe emptyList()
    }

    @Test
    fun `accepting the changed key re-trusts exactly the endpoint and key it was presented on`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewHostKeyChange(hostKeyChange())
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        retrusts.single() shouldBe listOf(stored.id, "build.lan", 22, stored.trustRevision, hostKey(2))
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `a re-trust for an endpoint that changed since changes nothing and says so`() = runTest {
        retrustResult = SftpLocationManager.RetrustResult.EndpointChanged
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewHostKeyChange(hostKeyChange())
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
        retrustResult = SftpLocationManager.RetrustResult.NotFound
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewHostKeyChange(hostKeyChange())
        advanceUntilIdle()

        controller.onRetrustAccepted(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `cancelling the re-trust leaves the key alone`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.reviewHostKeyChange(hostKeyChange())
        advanceUntilIdle()

        controller.onRetrustRejected(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().retrustConfirmation.shouldBeNull()
        retrusts shouldBe emptyList()
    }

    @Test
    fun `a changed key found by the sign-in test offers the re-trust question`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()

        testResults.add(mismatch(expected = hostKey(1), presented = hostKey(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        testCalls.single().trustedKey shouldBe hostKey(1)
        saved shouldBe emptyList()
        val confirmation = dialogs.form().retrustConfirmation.shouldNotBeNull()
        confirmation.locationId shouldBe stored.id
        confirmation.storedKey shouldBe hostKey(1)
        confirmation.presentedKey shouldBe hostKey(2)
    }

    @Test
    fun `a changed key found by the edit test offers the re-trust question`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        dialogs.show(ExplorerDialogState.SftpLocationForm(mode = SftpFormMode.EDIT, existing = stored))

        testResults.add(mismatch(expected = hostKey(1), presented = hostKey(2)))
        controller.onFormSubmit(input(label = "Renamed", password = ""))
        advanceUntilIdle()

        saved shouldBe emptyList()
        val confirmation = dialogs.form().retrustConfirmation.shouldNotBeNull()
        confirmation.storedKey shouldBe hostKey(1)
        confirmation.presentedKey shouldBe hostKey(2)
    }

    @Test
    fun `accepting a changed key found by the form keeps the form open for the next attempt`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        testResults.add(mismatch(expected = hostKey(1), presented = hostKey(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()
        val asked = dialogs.form()

        controller.onRetrustAccepted(asked.retrustConfirmation!!.id)
        advanceUntilIdle()

        retrusts.single() shouldBe listOf(stored.id, "build.lan", 22, stored.trustRevision, hostKey(2))
        val form = dialogs.form()
        form.formId shouldBe asked.formId
        form.mode shouldBe SftpFormMode.SIGN_IN
        form.existing shouldBe stored.copy(hostKey = hostKey(2), trustRevision = 2)
        form.retrustConfirmation shouldBe null
        form.isTesting shouldBe false
        saved shouldBe emptyList()
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `rejecting a changed key found by the form leaves the mismatch reported`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id)
        advanceUntilIdle()
        testResults.add(mismatch(expected = hostKey(1), presented = hostKey(2)))
        controller.onFormSubmit(input(password = "new-secret"))
        advanceUntilIdle()

        controller.onRetrustRejected(dialogs.form().retrustConfirmation!!.id)
        advanceUntilIdle()

        dialogs.form().retrustConfirmation shouldBe null
        dialogs.form().error shouldNotBe null
        retrusts shouldBe emptyList()
    }

    // endregion

    // region unlock

    private val rootTarget = ExplorerNavigation.Target.Directory(LocalPath.build("/srv"))

    private fun ExplorerDialogController.unlockDialog() =
        current().shouldBeInstanceOf<ExplorerDialogState.SftpUnlock>()

    private suspend fun kotlinx.coroutines.test.TestScope.promptUnlock(
        openOnUnlock: ExplorerNavigation.Target? = rootTarget,
    ): Pair<ExplorerDialogController, ExplorerSftpLocationController> {
        coEvery { locationManager.get(stored.id) } returns askingLocation
        val dialogs = dialogs()
        val controller = controller(dialogs)
        controller.promptSignIn(stored.id, openOnUnlock)
        advanceUntilIdle()
        return dialogs to controller
    }

    @Test
    fun `signing in to a key that asks for its passphrase opens the unlock dialog`() = runTest {
        val (dialogs, _) = promptUnlock()

        val dialog = dialogs.unlockDialog()
        dialog.location shouldBe askingLocation
        dialog.openOnUnlock shouldBe rootTarget
        dialog.isUnlocking shouldBe false
        dialog.error shouldBe null
    }

    @Test
    fun `signing in to a key with a remembered passphrase opens the sign-in form`() = runTest {
        coEvery { locationManager.get(stored.id) } returns keyLocation
        val dialogs = dialogs()

        controller(dialogs).promptSignIn(stored.id, rootTarget)
        advanceUntilIdle()

        dialogs.form().mode shouldBe SftpFormMode.SIGN_IN
        dialogs.form().existing shouldBe keyLocation
    }

    @Test
    fun `signing in to a password server opens the sign-in form`() = runTest {
        val dialogs = dialogs()

        controller(dialogs).promptSignIn(stored.id, rootTarget)
        advanceUntilIdle()

        dialogs.form().mode shouldBe SftpFormMode.SIGN_IN
        dialogs.form().existing shouldBe stored
    }

    @Test
    fun `unlocking from the network list holds the passphrase and opens the server`() = runTest {
        val (dialogs, controller) = promptUnlock()
        val typed = "phrase".toCharArray()

        testResults.add(success())
        controller.onUnlock(dialogs.unlockDialog().dialogId, typed)
        advanceUntilIdle()

        testCalls.single() shouldBe TestCall(
            host = "build.lan",
            port = 22,
            password = null,
            privateKey = "STORED-KEY",
            passphrase = "phrase",
            trustedKey = hostKey(1),
        )
        holds.single() shouldBe Hold(stored.id, 3, "phrase")
        managerCalls shouldBe listOf("hold")
        saved shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify(exactly = 1) { workspace.navigate(rootTarget) }
        coVerify(exactly = 0) { workspace.navigate(ExplorerNavigation.Refresh) }
        typed.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `unlocking after a failed load reloads the location`() = runTest {
        val (dialogs, controller) = promptUnlock(openOnUnlock = null)

        testResults.add(success())
        controller.onUnlock(dialogs.unlockDialog().dialogId, "phrase".toCharArray())
        advanceUntilIdle()

        holds.single() shouldBe Hold(stored.id, 3, "phrase")
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify(exactly = 1) { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `a wrong passphrase keeps the dialog open with an error and holds nothing`() = runTest {
        val (dialogs, controller) = promptUnlock()
        val typed = "wrong".toCharArray()

        testResults.add(SftpConnectionTester.Result.KeyPassphraseInvalid)
        controller.onUnlock(dialogs.unlockDialog().dialogId, typed)
        advanceUntilIdle()

        val dialog = dialogs.unlockDialog()
        dialog.isUnlocking shouldBe false
        dialog.error shouldNotBe null
        holds shouldBe emptyList()
        managerCalls shouldBe emptyList()
        coVerify(exactly = 0) { workspace.navigate(any()) }
        typed.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `a refused hold keeps the unlock dialog open with an error`() = runTest {
        holdResult = false
        val (dialogs, controller) = promptUnlock()

        testResults.add(success())
        controller.onUnlock(dialogs.unlockDialog().dialogId, "phrase".toCharArray())
        advanceUntilIdle()

        holds.single() shouldBe Hold(stored.id, 3, "phrase")
        val dialog = dialogs.unlockDialog()
        dialog.isUnlocking shouldBe false
        dialog.error shouldNotBe null
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `an unusable stored key keeps the unlock dialog open with an error`() = runTest {
        coEvery { credentialStore.resolveStoredKey(any(), any()) } throws IllegalStateException("No key stored")
        val (dialogs, controller) = promptUnlock()
        val typed = "phrase".toCharArray()

        controller.onUnlock(dialogs.unlockDialog().dialogId, typed)
        advanceUntilIdle()

        testCalls shouldBe emptyList()
        holds shouldBe emptyList()
        val dialog = dialogs.unlockDialog()
        dialog.isUnlocking shouldBe false
        dialog.error shouldNotBe null
        typed.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `edit server swaps the unlock dialog for the sign-in form`() = runTest {
        val (dialogs, controller) = promptUnlock()

        controller.onUnlockEditServer(dialogs.unlockDialog().dialogId)

        val form = dialogs.form()
        form.mode shouldBe SftpFormMode.SIGN_IN
        form.existing shouldBe askingLocation
    }

    @Test
    fun `an unlock finishing after its dialog was replaced holds nothing`() = runTest {
        val (dialogs, controller) = promptUnlock()
        val gate = CompletableDeferred<SftpConnectionTester.Result>()
        coEvery {
            tester.test(
                host = any(),
                port = any(),
                username = any(),
                authType = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                basePath = any(),
                trustedKey = any(),
            )
        } coAnswers { gate.await() }
        val typed = "phrase".toCharArray()

        controller.onUnlock(dialogs.unlockDialog().dialogId, typed)
        advanceUntilIdle()
        dialogs.unlockDialog().isUnlocking shouldBe true

        val reopened = ExplorerDialogState.SftpUnlock(askingLocation, rootTarget)
        dialogs.show(reopened)
        gate.complete(success())
        advanceUntilIdle()

        holds shouldBe emptyList()
        dialogs.current() shouldBe reopened
        coVerify(exactly = 0) { workspace.navigate(any()) }
        typed.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `an unlock for a dialog that is no longer showing is ignored`() = runTest {
        val (dialogs, controller) = promptUnlock()
        val stale = dialogs.unlockDialog().dialogId
        val reopened = ExplorerDialogState.SftpUnlock(askingLocation, rootTarget)
        dialogs.show(reopened)
        val typed = "phrase".toCharArray()

        controller.onUnlock(stale, typed)
        controller.onUnlockEditServer(stale)
        advanceUntilIdle()

        testCalls shouldBe emptyList()
        dialogs.current() shouldBe reopened
        typed.all { it == Char(0) } shouldBe true
    }

    // endregion
}
