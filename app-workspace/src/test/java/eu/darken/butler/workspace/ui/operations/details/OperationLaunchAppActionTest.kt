package eu.darken.butler.workspace.ui.operations.details

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.InstallMobile
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Clock

/**
 * "Open app" is offered for a successful install whose reported package has a launcher, and closes
 * the sheet whatever happens when it is pressed.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h1600dp")
class OperationLaunchAppActionTest : ComposeTest() {

    private val label = "Open app"
    private val historyLabel = "Show in history"

    private val pkgName = "com.example.installed"
    private val launcher = ComponentName(pkgName, "$pkgName.MainActivity")

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun registerLauncher() {
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        application.packageManager.getLaunchIntentForPackage(pkgName).shouldNotBeNull()
    }

    private fun removeLauncher() {
        shadowOf(application.packageManager).apply {
            clearIntentFilterForActivity(launcher)
            removeActivity(launcher)
        }
        application.packageManager.getLaunchIntentForPackage(pkgName).shouldBeNull()
    }

    private fun startedActivity(): Intent? = shadowOf(application).nextStartedActivity

    private fun outcome(
        status: Operation.Report.Packages.Outcome.Status = Operation.Report.Packages.Outcome.Status.DONE,
        packageName: String = pkgName,
    ) = Operation.Report.Packages.Outcome(
        label = "Installed".toCaString(),
        packageName = packageName,
        status = status,
    )

    private fun packagesReport(
        outcomes: List<Operation.Report.Packages.Outcome> = listOf(outcome()),
    ) = Operation.Report.Packages(
        summary = "Installed was installed.".toCaString(),
        outcomes = outcomes,
    )

    private fun completed(report: Operation.Report? = packagesReport()) = OperationDisplay.State.Completed(
        summary = "Installed was installed.".toCaString(),
        completedAt = Clock.System.now(),
        report = report,
    )

    private fun operation(
        kind: Operation.Metadata.Kind? = Operation.Metadata.Kind.INSTALL,
        state: OperationDisplay.State = completed(),
    ) = OperationDisplay(
        id = Operation.Id(),
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.InstallMobile,
        title = "App installation".toCaString(),
        description = "Install \"Installed\".".toCaString(),
        kind = kind,
        state = state,
    )

    private fun setHost(
        operation: OperationDisplay,
        onShowInHistory: ((Operation.Id) -> Unit)? = {},
        historyEnabled: Boolean = true,
        onDismiss: () -> Unit = {},
        wrapContext: ((Context) -> Context)? = null,
        lifecycleOwner: LifecycleOwner? = null,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    val base = LocalContext.current
                    val context = remember(base) { wrapContext?.invoke(base) ?: base }
                    val owner = lifecycleOwner ?: LocalLifecycleOwner.current
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalLifecycleOwner provides owner,
                    ) {
                        OperationDialogHost(
                            dialogState = OperationDialogState.OperationDetails(operation.id),
                            operations = listOf(operation),
                            onDismissDialog = onDismiss,
                            onShowInHistory = onShowInHistory,
                            historyEnabled = historyEnabled,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a finished install opens the app and dismisses the sheet`() {
        var dismissed = 0
        setHost(operation(), onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val started = startedActivity().shouldNotBeNull()
        started.component?.packageName shouldBe pkgName
        dismissed shouldBe 1
    }

    @Test
    fun `the action does not depend on the history recording`() {
        setHost(operation(), historyEnabled = false)

        composeTestRule.onNodeWithText(label).assertIsDisplayed()
        composeTestRule.onNodeWithText(historyLabel).assertDoesNotExist()
    }

    @Test
    fun `a host that cannot show history still offers the action`() {
        setHost(operation(), onShowInHistory = null)

        composeTestRule.onNodeWithText(label).assertIsDisplayed()
        composeTestRule.onNodeWithText(historyLabel).assertDoesNotExist()
    }

    @Test
    fun `no action for an app without a launcher`() {
        setHost(
            operation(
                state = completed(packagesReport(listOf(outcome(packageName = "com.example.headless")))),
            ),
        )

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for a package operation that is not an install`() {
        setHost(operation(kind = Operation.Metadata.Kind.ENABLE))

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for an install that reported no package`() {
        setHost(operation(state = completed(report = null)))

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for an install report without outcomes`() {
        setHost(operation(state = completed(packagesReport(outcomes = emptyList()))))

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for an install report without a done outcome`() {
        setHost(
            operation(
                state = completed(
                    packagesReport(
                        outcomes = listOf(
                            outcome(status = Operation.Report.Packages.Outcome.Status.FAILED),
                            outcome(status = Operation.Report.Packages.Outcome.Status.DECLINED),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for a failed install`() {
        setHost(
            operation(
                state = OperationDisplay.State.Failed(
                    summary = "Failed".toCaString(),
                    completedAt = Clock.System.now(),
                    report = packagesReport(),
                ),
            ),
        )

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for a cancelled install`() {
        setHost(
            operation(
                state = OperationDisplay.State.Cancelled(
                    completedAt = Clock.System.now(),
                    report = packagesReport(),
                ),
            ),
        )

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action for a running install`() {
        setHost(operation(state = OperationDisplay.State.Running()))

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `a launch without a matching activity still dismisses the sheet`() {
        var dismissed = 0
        setHost(
            operation(),
            onDismiss = { dismissed++ },
            wrapContext = { StartFailingContext(it, ActivityNotFoundException("No activity")) },
        )

        composeTestRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        startedActivity().shouldBeNull()
        dismissed shouldBe 1
    }

    @Test
    fun `a launch the system refuses still dismisses the sheet`() {
        var dismissed = 0
        setHost(
            operation(),
            onDismiss = { dismissed++ },
            wrapContext = { StartFailingContext(it, SecurityException("Not exported")) },
        )

        composeTestRule.onNodeWithText(label).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        startedActivity().shouldBeNull()
        dismissed shouldBe 1
    }

    @Test
    fun `an app that lost its launcher since the sheet opened is not started`() {
        var dismissed = 0
        setHost(operation(), onDismiss = { dismissed++ })
        composeTestRule.onNodeWithText(label).assertIsDisplayed()

        removeLauncher()
        composeTestRule.onNodeWithText(label).performClick()
        composeTestRule.waitForIdle()

        startedActivity().shouldBeNull()
        dismissed shouldBe 1
    }

    @Test
    fun `a resume drops the action for an app that lost its launcher`() {
        val owner = TestLifecycleOwner()
        setHost(operation(), lifecycleOwner = owner)
        composeTestRule.onNodeWithText(label).assertIsDisplayed()

        removeLauncher()
        composeTestRule.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    private class StartFailingContext(base: Context, private val error: Exception) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            throw error
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }
}
