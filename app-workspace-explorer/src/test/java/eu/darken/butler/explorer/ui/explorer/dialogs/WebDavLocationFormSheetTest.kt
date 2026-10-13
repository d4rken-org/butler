package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.webdav.WebDavLocationInput
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class WebDavLocationFormSheetTest : ComposeTest() {

    private var submitted: WebDavLocationFormInput? = null

    private val stored = previewWebDavLocation()

    private fun setSheetContent(state: ExplorerDialogState.WebDavLocationForm, isPro: Boolean = true) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    WebDavLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                        isPro = isPro,
                    )
                }
            }
        }
    }

    // Disabled text fields drop their SetText action but keep their editable text
    private val isTextField = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    private fun field(label: String): SemanticsNodeInteraction =
        composeTestRule.onNode(isTextField and hasText(label)).performScrollTo()

    private fun saveButton() = composeTestRule.onNodeWithText("Test & save").performScrollTo()

    private val cleartextWarning = "Unencrypted connection: your password and files are sent in plain text."

    @Test
    fun `saving is blocked until URL, username and password are filled in`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm())
        saveButton().assertIsNotEnabled()

        field("Server URL").performTextInput("https://cloud.example.com/remote.php/dav/files/alice/")
        field("Username").performTextInput("alice")
        saveButton().assertIsNotEnabled()

        field("Password").performTextInput("hunter2")
        saveButton().assertIsEnabled()
    }

    @Test
    fun `anonymous access needs only the URL`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm())
        field("Server URL").performTextInput("https://dav.example.org/public/")

        composeTestRule.onNode(hasText("Anonymous") and !isTextField).performScrollTo().performClick()

        composeTestRule.onNode(isTextField and hasText("Username")).assertDoesNotExist()
        composeTestRule.onNode(isTextField and hasText("Password")).assertDoesNotExist()
        saveButton().assertIsEnabled()
        saveButton().performClick()
        submitted!!.authType shouldBe WebDavLocation.AuthType.ANONYMOUS
    }

    @Test
    fun `a plain HTTP URL shows the unencrypted warning while it is typed`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm())
        composeTestRule.onNodeWithText(cleartextWarning).assertDoesNotExist()

        field("Server URL").performTextInput("http://nas.local:8080/dav/")
        composeTestRule.onNodeWithText(cleartextWarning).performScrollTo().assertIsDisplayed()

        field("http://nas.local:8080/dav/").performTextReplacement("https://nas.local/dav/")
        composeTestRule.onNodeWithText(cleartextWarning).assertDoesNotExist()
    }

    @Test
    fun `a free user is told that browsing needs Pro`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm(), isPro = false)

        composeTestRule
            .onNodeWithText("Adding and testing a location is free. Browsing it needs Butler Pro.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a pro user gets no Pro notice`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm(), isPro = true)

        composeTestRule
            .onNodeWithText("Adding and testing a location is free. Browsing it needs Butler Pro.")
            .assertDoesNotExist()
    }

    @Test
    fun `editing keeps the stored password while the account stays the same`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.EDIT, existing = stored))

        saveButton().assertIsEnabled()

        field("alice").performTextReplacement("bob")
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `the sign-in form needs the password again even though one is stored`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm(mode = WebDavFormMode.SIGN_IN, existing = stored))

        composeTestRule.onNodeWithText("Sign in to Nextcloud").assertIsDisplayed()
        saveButton().assertIsNotEnabled()

        field("Password").performTextInput("new-secret")
        saveButton().assertIsEnabled()
    }

    @Test
    fun `submitting hands over the fields and how often they were edited`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm())

        field("Server URL").performTextInput("https://cloud.example.com/remote.php/dav/files/alice/")
        field("Username").performTextInput("alice")
        field("Password").performTextInput("hunter2")
        saveButton().performClick()

        val first = submitted!!
        first.url shouldBe "https://cloud.example.com/remote.php/dav/files/alice/"
        first.username shouldBe "alice"
        first.password shouldBe "hunter2"
        first.authType shouldBe WebDavLocation.AuthType.BASIC
        first.rememberCredential shouldBe true

        field("alice").performTextReplacement("bob")
        saveButton().performClick()
        submitted!!.revision shouldBeGreaterThan first.revision
    }

    @Test
    fun `issues found by the last submit are shown on their field until it is edited`() {
        var state by mutableStateOf(ExplorerDialogState.WebDavLocationForm())
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    WebDavLocationFormSheet(
                        state = state,
                        onDismiss = {},
                        onSubmit = { submitted = it },
                        isPro = true,
                    )
                }
            }
        }
        field("Server URL").performTextInput("ftp://cloud.example.com/")
        field("Username").performTextInput("al:ice")
        field("Password").performTextInput("hunter2")
        saveButton().performClick()

        state = state.copy(
            issues = listOf(
                WebDavLocationInput.Issue.UrlSchemeUnsupported,
                WebDavLocationInput.Issue.UsernameMalformed,
            ),
            issuesRevision = submitted!!.revision,
        )

        composeTestRule.onNodeWithText("The URL has to start with https:// or http://").performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("The username cannot contain a colon (:) or control characters")
            .performScrollTo()
            .assertIsDisplayed()

        field("al:ice").performTextReplacement("alice")
        composeTestRule.onNodeWithText("The URL has to start with https:// or http://").assertDoesNotExist()
    }

    @Test
    fun `saving is blocked while testing and an error is shown inline`() {
        setSheetContent(
            ExplorerDialogState.WebDavLocationForm(
                mode = WebDavFormMode.EDIT,
                existing = stored,
                isTesting = true,
                error = "cloud.example.com rejected the username or password".toCaString(),
            )
        )

        composeTestRule.onNodeWithText("Connecting…").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("cloud.example.com rejected the username or password").performScrollTo()
            .assertIsDisplayed()
        saveButton().assertIsNotEnabled()
    }

    @Test
    fun `nothing in the form can be changed while a connection test runs`() {
        setSheetContent(ExplorerDialogState.WebDavLocationForm(isTesting = true))

        listOf("Name (optional)", "Server URL", "Username", "Password").forEach { field(it).assertIsNotEnabled() }
        composeTestRule.onNode(hasText("Anonymous") and !isTextField).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNode(hasText("Username and password") and !isTextField).performScrollTo()
            .assertIsNotEnabled()
        // "Remember password" is the only toggleable node in the add form
        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsNotEnabled()
    }
}
