package eu.darken.butler.explorer.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.ui.explorer.elements.BreadcrumbBar
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class BreadcrumbBarTest : ComposeTest() {

    private val homeBreadcrumb = ExplorerBreadcrumb(
        label = "Home".toCaString(),
        icon = Icons.TwoTone.Home,
        target = ExplorerNavigation.Target.Home,
        showIcon = true,
        showText = true,
    )

    private val deviceBreadcrumb = ExplorerBreadcrumb(
        label = "Device".toCaString(),
        icon = Icons.TwoTone.PhoneAndroid,
        target = ExplorerNavigation.Target.Device,
        showIcon = true,
        showText = true,
    )

    private fun directoryBreadcrumb(name: String, path: String) = ExplorerBreadcrumb(
        label = name.toCaString(),
        icon = Icons.TwoTone.FolderOpen,
        target = ExplorerNavigation.Target.Directory(LocalPath.build(path)),
        showIcon = false,
        showText = true,
    )

    @Test
    fun `displays loading state when breadcrumbs empty`() {
        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = emptyList(),
                    onBreadcrumbClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
    }

    @Test
    fun `displays single breadcrumb`() {
        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(homeBreadcrumb),
                    onBreadcrumbClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun `displays multiple breadcrumbs`() {
        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("storage").assertIsDisplayed()
    }

    @Test
    fun `clicking non-last breadcrumb triggers navigation`() {
        var navigatedTarget: ExplorerNavigation? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = { navigatedTarget = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Home").performClick()

        navigatedTarget shouldBe ExplorerNavigation.Target.Home
    }

    @Test
    fun `clicking middle breadcrumb navigates to that location`() {
        var navigatedTarget: ExplorerNavigation? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                        directoryBreadcrumb("emulated", "/storage/emulated"),
                    ),
                    onBreadcrumbClick = { navigatedTarget = it },
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performClick()

        (navigatedTarget as? ExplorerNavigation.Target.Directory)?.path?.path shouldBe "/storage"
    }

    @Test
    fun `clicking last breadcrumb does not navigate when no onNavigateToPath`() {
        var navigatedTarget: ExplorerNavigation? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = { navigatedTarget = it },
                    onNavigateToPath = null,
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performClick()

        navigatedTarget shouldBe null
    }

    @Test
    fun `nested path breadcrumbs display correctly`() {
        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                        directoryBreadcrumb("emulated", "/storage/emulated"),
                        directoryBreadcrumb("0", "/storage/emulated/0"),
                        directoryBreadcrumb("Documents", "/storage/emulated/0/Documents"),
                    ),
                    onBreadcrumbClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("emulated").assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Documents").assertIsDisplayed()
    }

    @Test
    fun `navigating backwards through breadcrumbs works`() {
        val navigatedTargets = mutableListOf<ExplorerNavigation>()

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                        directoryBreadcrumb("emulated", "/storage/emulated"),
                    ),
                    onBreadcrumbClick = { navigatedTargets.add(it) },
                )
            }
        }

        // Click on storage (second to last)
        composeTestRule.onNodeWithText("storage").performClick()

        navigatedTargets.size shouldBe 1
        (navigatedTargets[0] as? ExplorerNavigation.Target.Directory)?.path?.path shouldBe "/storage"
    }
}
