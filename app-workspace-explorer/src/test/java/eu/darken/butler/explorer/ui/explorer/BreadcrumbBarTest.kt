package eu.darken.butler.explorer.ui.explorer

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
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
        target = ExplorerNavigation.Target.Home,
        label = "Home".toCaString(),
        icon = Icons.TwoTone.Home,
    )

    private val deviceBreadcrumb = ExplorerBreadcrumb(
        target = ExplorerNavigation.Target.Device,
        label = "Device".toCaString(),
        icon = Icons.TwoTone.PhoneAndroid,
    )

    private fun directoryBreadcrumb(name: String, path: String) = ExplorerBreadcrumb(
        target = ExplorerNavigation.Target.Directory(LocalPath.build(path)),
        label = name.toCaString(),
        icon = Icons.TwoTone.FolderOpen,
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

    @Test
    fun `clicking last directory breadcrumb enters edit mode and commit fires callback`() {
        var committed: Pair<APath<*>, String>? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        directoryBreadcrumb("/", "/"),
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                    onNavigateToPath = {},
                    onCommitEditedPath = { path, text -> committed = path to text },
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performClick()

        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("storage/Documents")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        committed?.first?.path shouldBe "/storage"
        committed?.second shouldBe "storage/Documents"
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape cancels edit mode without committing`() {
        var committed: Pair<APath<*>, String>? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        directoryBreadcrumb("/", "/"),
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                    onNavigateToPath = {},
                    onCommitEditedPath = { path, text -> committed = path to text },
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performClick()
        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()

        composeTestRule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithText("storage").assertIsDisplayed()
        committed shouldBe null
    }

    @Test
    fun `long press on directory breadcrumb offers copy path`() {
        var copiedPath: String? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                    onCopyPath = { copiedPath = it },
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Copy path").performClick()

        copiedPath shouldBe "/storage"
        composeTestRule.onNodeWithText("Copy path").assertDoesNotExist()
    }

    @Test
    fun `set as home from context menu fires callback and closes menu`() {
        var homeTarget: ExplorerNavigation.Target? = null

        composeTestRule.setContent {
            PreviewWrapper {
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        homeBreadcrumb,
                        deviceBreadcrumb,
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                    onSetAsHome = { homeTarget = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Device").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Set as home").performClick()

        homeTarget shouldBe ExplorerNavigation.Target.Device
        composeTestRule.onNodeWithText("Set as home").assertDoesNotExist()
    }

    @Test
    fun `back press exits edit mode without committing and is consumed before outer handlers`() {
        var committed: Pair<APath<*>, String>? = null
        var outerBackFired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                BackHandler(enabled = true) { outerBackFired = true }
                BreadcrumbBar(
                    breadcrumbs = listOf(
                        directoryBreadcrumb("/", "/"),
                        directoryBreadcrumb("storage", "/storage"),
                    ),
                    onBreadcrumbClick = {},
                    onNavigateToPath = {},
                    onCommitEditedPath = { path, text -> committed = path to text },
                )
            }
        }

        composeTestRule.onNodeWithText("storage").performClick()
        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithText("storage").assertIsDisplayed()
        committed shouldBe null
        outerBackFired shouldBe false

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle { outerBackFired shouldBe true }
    }
}
