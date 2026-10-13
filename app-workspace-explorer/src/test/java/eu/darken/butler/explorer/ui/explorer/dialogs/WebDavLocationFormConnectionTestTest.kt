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
import eu.darken.butler.common.files.webdav.WebDavConnectionTester
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.explorer.ExplorerDialogController
import eu.darken.butler.explorer.ui.explorer.ExplorerWebDavLocationController
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.FakeUpgradeRepo
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
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
 * The WebDAV form as the Explorer page shows it: the dialog host rendering the controller's dialog
 * state, its callbacks reaching the real [ExplorerWebDavLocationController], and a server whose
 * answers the test releases one at a time.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class WebDavLocationFormConnectionTestTest : ComposeTest() {

    private val url = "https://cloud.example.com/remote.php/dav/files/alice/"
    private val presented = MockDataProvider.mockWebDavCertificate(2)

    private data class TestCall(val url: String, val pin: TrustedCertificate?)

    private val testCalls = mutableListOf<TestCall>()
    private val answers = ArrayDeque<CompletableDeferred<WebDavConnectionTester.Result>>()

    private val tester = mockk<WebDavConnectionTester>().apply {
        coEvery { test(any(), any(), any(), any(), any()) } coAnswers {
            testCalls.add(TestCall(url = arg(0), pin = arg(4)))
            answers.removeFirst().await()
        }
    }

    private fun answer(): CompletableDeferred<WebDavConnectionTester.Result> =
        CompletableDeferred<WebDavConnectionTester.Result>().also { answers.add(it) }

    private data class Created(val url: String, val pin: TrustedCertificate?)

    private val created = mutableListOf<Created>()

    private val locationManager = mockk<WebDavLocationManager>().apply {
        coEvery { create(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            created.add(Created(url = arg(1), pin = arg(6)))
            WebDavLocation(
                id = Uuid.random(),
                label = null,
                url = arg(1),
                authType = arg(2),
                username = arg(3),
                rememberCredential = arg(4),
                credentialVersion = 1,
                pinnedCertificate = arg(6),
                trustRevision = 1,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val dialogs = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private val controller = ExplorerWebDavLocationController(
        locationManager = locationManager,
        credentialStore = mockk(),
        connectionTester = tester,
        upgradeRepo = FakeUpgradeRepo(pro = true),
        showUpgradeHint = {},
        dialogs = dialogs,
        workspace = { mockk<ExplorerWorkspace> { coEvery { navigate(any()) } just Runs } },
        currentLocation = { null },
        clearSelection = {},
        doLaunch = { block -> scope.launch(block = block) },
        tag = "test",
    )

    private val vm = mockk<ExplorerWorkspaceViewModel>(relaxed = true).apply {
        every { dismissDialog() } answers { dialogs.dismiss() }
        every { onWebDavLocationFormSubmit(any()) } answers { controller.onFormSubmit(firstArg()) }
        every { onWebDavCertificateAccepted(any(), any()) } answers {
            controller.onCertificateAccepted(firstArg(), secondArg())
        }
        every { onWebDavCertificateRejected(any()) } answers { controller.onCertificateRejected(firstArg()) }
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

    private fun fill() {
        field("Server URL").performTextInput(url)
        field("Username").performTextInput("alice")
        field("Password").performTextInput("hunter2")
    }

    private fun submit() {
        composeTestRule.onNodeWithText("Test & save").performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `nothing can be edited while the form is tested`() {
        answer()
        dialogs.show(ExplorerDialogState.WebDavLocationForm())
        setContent()
        fill()

        submit()

        testCalls.single() shouldBe TestCall(url = url, pin = null)
        listOf("Name (optional)", "Server URL", "Username", "Password").forEach { field(it).assertIsNotEnabled() }
        chip("Anonymous").assertIsNotEnabled()
        chip("Username and password").assertIsNotEnabled()
        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsNotEnabled()
        created shouldBe emptyList()
    }

    @Test
    fun `the untrusted certificate is confirmed above an untouchable form, then saved as tested`() {
        val discovery = answer()
        val reconnect = answer()
        dialogs.show(ExplorerDialogState.WebDavLocationForm())
        setContent()
        fill()
        submit()

        discovery.complete(WebDavConnectionTester.Result.CertificateUntrusted(presented))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Trust this certificate?").assertIsDisplayed()
        composeTestRule.onNodeWithText(presented.sha256Fingerprint).assertIsDisplayed()
        val server = composeTestRule.onNode(isTextField and hasText("Server URL"))
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
        server.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(url)))
        testCalls.size shouldBe 1

        composeTestRule.onNodeWithText("Trust certificate").performClick()
        composeTestRule.waitForIdle()

        testCalls shouldBe listOf(
            TestCall(url = url, pin = null),
            TestCall(url = url, pin = presented.toTrustedCertificate()),
        )
        created shouldBe emptyList()

        reconnect.complete(WebDavConnectionTester.Result.Success)
        composeTestRule.waitForIdle()

        created shouldBe listOf(Created(url = url, pin = presented.toTrustedCertificate()))
        dialogs.state.value shouldBe ExplorerDialogState.None
        composeTestRule.onNodeWithText("Test & save").assertDoesNotExist()
    }

    @Test
    fun `a server the system trusts is saved without asking`() {
        val discovery = answer()
        dialogs.show(ExplorerDialogState.WebDavLocationForm())
        setContent()
        fill()
        submit()

        discovery.complete(WebDavConnectionTester.Result.Success)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Trust this certificate?").assertDoesNotExist()
        created shouldBe listOf(Created(url = url, pin = null))
        dialogs.state.value shouldBe ExplorerDialogState.None
    }
}
