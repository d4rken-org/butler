package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.sftp.SftpConnectionTester
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.SftpPrivateKeyReader
import eu.darken.butler.explorer.ui.explorer.ExplorerDialogController
import eu.darken.butler.explorer.ui.explorer.ExplorerSftpLocationController
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.FakeUpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The SFTP form as the Explorer page shows it: the dialog host rendering the controller's dialog
 * state, its callbacks reaching the real [ExplorerSftpLocationController], and a server whose
 * answers the test releases one at a time.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class SftpLocationFormConnectionTestTest : ComposeTest() {

    private val presentedKey = TrustedHostKey(
        type = "ssh-ed25519",
        blob = ByteArray(51) { 2 },
        fingerprint = "SHA256:m3r9Qk7fN2hYbWc8vXzL1aPpT6uE0sJdG4iKoRtVyHQ",
    )

    private data class TestCall(val host: String, val port: Int, val trustedKey: TrustedHostKey?)

    private val testCalls = mutableListOf<TestCall>()
    private val answers = ArrayDeque<CompletableDeferred<SftpConnectionTester.Result>>()

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
            testCalls.add(TestCall(host = arg(0), port = arg(1), trustedKey = arg(8)))
            answers.removeFirst().await()
        }
    }

    private fun answer(): CompletableDeferred<SftpConnectionTester.Result> =
        CompletableDeferred<SftpConnectionTester.Result>().also { answers.add(it) }

    private data class Created(val host: String, val port: Int, val hostKey: TrustedHostKey)

    private val created = mutableListOf<Created>()

    private val locationManager = mockk<SftpLocationManager>().apply {
        coEvery {
            create(
                label = any(),
                host = any(),
                port = any(),
                username = any(),
                basePath = any(),
                authType = any(),
                rememberCredential = any(),
                password = any(),
                privateKey = any(),
                passphrase = any(),
                hostKey = any(),
            )
        } coAnswers {
            created.add(Created(host = arg(1), port = arg(2), hostKey = arg(10)))
            SftpLocation(
                id = Uuid.random(),
                label = null,
                host = arg(1),
                port = arg(2),
                username = arg(3),
                basePath = arg(4),
                authType = arg(5),
                rememberCredential = arg(6),
                credentialVersion = 1,
                hostKey = arg(10),
                trustRevision = 1,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        }
    }

    private val pickerId = Workspace.Id()
    private val keyReader = mockk<SftpPrivateKeyReader>().apply {
        coEvery { read(any()) } returns SftpPrivateKeyReader.Result.Loaded(
            name = "id_ed25519",
            bytes = "-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray(),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dialogs = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private val controller = ExplorerSftpLocationController(
        locationManager = locationManager,
        credentialStore = mockk(),
        connectionTester = tester,
        keyReader = keyReader,
        upgradeRepo = FakeUpgradeRepo(pro = true),
        showUpgradeHint = {},
        dialogs = dialogs,
        launchKeyPicker = { pickerId },
        workspace = { mockk<ExplorerWorkspace> { coEvery { navigate(any()) } just Runs } },
        currentLocation = { null },
        clearSelection = {},
        doLaunch = { block -> scope.launch(block = block) },
        tag = "test",
    )

    private val vm = mockk<ExplorerWorkspaceViewModel>(relaxed = true).apply {
        every { dismissDialog() } answers { dialogs.dismiss() }
        every { onSftpLocationFormSubmit(any()) } answers { controller.onFormSubmit(firstArg()) }
        every { onPickSftpKeyFile() } answers { controller.pickKeyFile() }
        every { onSftpHostKeyAccepted(any(), any()) } answers { controller.onHostKeyAccepted(firstArg(), secondArg()) }
        every { onSftpHostKeyRejected(any()) } answers { controller.onHostKeyRejected(firstArg()) }
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun setContent() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    val state by dialogs.state.collectAsState()
                    ExplorerDialogHost(
                        dialogState = state,
                        viewStyle = ExplorerViewStyle.default(),
                        trashEnabled = false,
                        vm = vm,
                    )
                }
            }
        }
    }

    // Disabled text fields drop their SetText action but keep their editable text
    private val isTextField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private fun field(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(isTextField and hasText(label)).performScrollTo()

    private fun chip(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText(label) and !isTextField).performScrollTo()

    private fun fillEndpoint() {
        field("Server address").performTextInput("build.lan")
        field("Username").performTextInput("darken")
    }

    private fun submit() {
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private fun assertNothingEditable(secretField: String) {
        listOf("Name (optional)", "Server address", "Port", "Username", "Base path (optional)", secretField)
            .forEach { field(it).assertIsNotEnabled() }
        chip("Password").assertIsNotEnabled()
        chip("Private key").assertIsNotEnabled()
        // "Remember credentials" is the only toggleable node in the form
        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `nothing can be edited while the password form is tested`() {
        answer()
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        setContent()
        fillEndpoint()
        field("Password").performTextInput("hunter2")

        submit()

        testCalls.single() shouldBe TestCall(host = "build.lan", port = 22, trustedKey = null)
        assertNothingEditable(secretField = "Password")
        created shouldBe emptyList()
    }

    @Test
    fun `nothing can be edited while the key form is tested`() {
        answer()
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        setContent()
        fillEndpoint()
        chip("Private key").performClick()
        composeTestRule.onNodeWithText("Choose file").performScrollTo().performClick()
        controller.onKeyPickerResult(
            WorkspaceEvent.PickerResult(pickerId, Workspace.Id(), listOf(LocalPath.build("/sdcard/id_ed25519"))),
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("id_ed25519").performScrollTo().assertIsDisplayed()

        submit()

        testCalls.single() shouldBe TestCall(host = "build.lan", port = 22, trustedKey = null)
        assertNothingEditable(secretField = "Key passphrase (optional)")
        created shouldBe emptyList()
    }

    @Test
    fun `the discovered key is confirmed above an untouchable form, then saved as tested`() {
        val discovery = answer()
        val reconnect = answer()
        dialogs.show(ExplorerDialogState.SftpLocationForm())
        setContent()
        fillEndpoint()
        field("Password").performTextInput("hunter2")
        submit()

        val presented = presentedKey
        discovery.complete(
            mockk<SftpConnectionTester.Result.HostKeyUnknown> { every { presentedKey } returns presented },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Trust this server?").assertIsDisplayed()
        val server = composeTestRule.onNode(isTextField and hasText("Server address"))
        server.assertIsNotFocused()
        // Tapping the covered field and typing, then moving focus by keyboard and typing again
        server.performClick()
        server.performKeyInput {
            pressKey(Key.X)
            repeat(4) {
                pressKey(Key.Tab)
                pressKey(Key.X)
            }
        }
        composeTestRule.waitForIdle()
        server.assertIsNotFocused()
        server.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("build.lan")))
        testCalls.size shouldBe 1

        composeTestRule.onNodeWithText("Trust key").performClick()
        composeTestRule.waitForIdle()

        testCalls shouldBe listOf(
            TestCall(host = "build.lan", port = 22, trustedKey = null),
            TestCall(host = "build.lan", port = 22, trustedKey = presentedKey),
        )
        created shouldBe emptyList()

        reconnect.complete(mockk<SftpConnectionTester.Result.Success>())
        composeTestRule.waitForIdle()

        created shouldBe listOf(Created(host = "build.lan", port = 22, hostKey = presentedKey))
        dialogs.state.value shouldBe ExplorerDialogState.None
        composeTestRule.onNodeWithText("Test & save").assertDoesNotExist()
    }
}
