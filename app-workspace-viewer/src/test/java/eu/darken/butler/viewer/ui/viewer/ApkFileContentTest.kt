package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.VersionComparison
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Also carries the not-installed and version-mismatch assertions: neither has a deterministic route
 * on a QA image, which cannot produce a parseable-but-absent package or a differing-version pair.
 */
class ApkFileContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val apkInfo = ApkArchiveInfo(
        id = "eu.darken.butler".toPkgId(),
        label = "Butler",
        versionName = "1.4.0",
        versionCode = 140,
        minSdk = 26,
        targetSdk = 36,
        requestedPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
        ),
    )

    private fun setContent(
        installState: ApkInstallState,
        info: ApkArchiveInfo = apkInfo,
        onShowIcon: () -> Unit = {},
        onSaveIcon: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                ApkFileContent(
                    apkInfo = info,
                    installState = installState,
                    onShowIcon = onShowIcon,
                    onSaveIcon = onSaveIcon,
                )
            }
        }
    }

    private fun iconInfo() = apkInfo.copy(
        icon = createBitmap(48, 48).apply { eraseColor(0xFF3DDC84.toInt()) },
    )

    @Test
    fun `shows label, package name and version`() {
        setContent(ApkInstallState.NotInstalled)

        composeTestRule.onNodeWithText("Butler").assertIsDisplayed()
        composeTestRule.onNodeWithText("eu.darken.butler").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.4.0 (140)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `falls back to the package name without a label`() {
        setContent(ApkInstallState.NotInstalled, info = apkInfo.copy(label = null))

        // Title falls back to the package name, and the package name row keeps showing it: two nodes.
        composeTestRule.onAllNodesWithText("eu.darken.butler").assertCountEquals(2)
        composeTestRule.onNodeWithText("Butler").assertDoesNotExist()
    }

    @Test
    fun `an absent package reads as not installed`() {
        setContent(ApkInstallState.NotInstalled)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_not_installed_value))
            .assertIsDisplayed()
    }

    @Test
    fun `an older installed version shows both the version and the hint`() {
        setContent(
            ApkInstallState.Installed(
                versionName = "1.3.0",
                versionCode = 130,
                comparison = VersionComparison.APK_NEWER,
            ),
        )

        composeTestRule.onNodeWithText("1.3.0 (130)", substring = true).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_installed_older_hint), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a newer installed version shows the opposite hint`() {
        setContent(
            ApkInstallState.Installed(
                versionName = "1.5.0",
                versionCode = 150,
                comparison = VersionComparison.INSTALLED_NEWER,
            ),
        )

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_installed_newer_hint), substring = true)
            .assertIsDisplayed()
    }

    /** A failed lookup is a different statement than "not installed" and must not borrow its wording. */
    @Test
    fun `a failed lookup reads as unknown, not as not installed`() {
        setContent(ApkInstallState.Unknown)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_installed_unknown_value))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_not_installed_value))
            .assertDoesNotExist()
    }

    @Test
    fun `the permissions header reveals the permissions`() {
        setContent(ApkInstallState.NotInstalled)

        composeTestRule.onNodeWithText("android.permission.INTERNET").assertDoesNotExist()

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_permissions_header, 2))
            .performClick()

        composeTestRule.onNodeWithText("android.permission.INTERNET").assertExists()
        composeTestRule.onNodeWithText("android.permission.POST_NOTIFICATIONS").assertExists()
    }

    /** The icon is the only source for an export, so without one the action must not be offered. */
    @Test
    fun `an apk without an icon offers no save action`() {
        setContent(ApkInstallState.NotInstalled)

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.viewer_apk_icon_save_action))
            .assertDoesNotExist()
    }

    @Test
    fun `saving the icon is offered once an icon exists`() {
        var saved = 0
        setContent(ApkInstallState.NotInstalled, info = iconInfo(), onSaveIcon = { saved++ })

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.viewer_apk_icon_save_action))
            .performClick()

        saved shouldBe 1
    }

    @Test
    fun `tapping the icon opens it`() {
        var shown = 0
        setContent(ApkInstallState.NotInstalled, info = iconInfo(), onShowIcon = { shown++ })

        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.viewer_apk_icon_content_description, "Butler"),
            )
            .performClick()

        shown shouldBe 1
    }

    @Test
    fun `an apk without permissions has no permissions header`() {
        setContent(ApkInstallState.NotInstalled, info = apkInfo.copy(requestedPermissions = emptyList()))

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_apk_permissions_header, 0))
            .assertDoesNotExist()
    }
}
