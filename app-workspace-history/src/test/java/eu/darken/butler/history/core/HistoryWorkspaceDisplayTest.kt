package eu.darken.butler.history.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.history.R
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `an unfiltered tab is named after the workspace`() {
        val display = deriveHistoryDisplay(HistoryArguments.Default())

        display.title!!.get(context) shouldBe context.getString(R.string.history_workspace_title)
        display.subtitle!!.get(context) shouldBe context.getString(R.string.history_workspace_subtitle)
    }

    @Test
    fun `a filtered tab is named after its filter`() {
        val display = deriveHistoryDisplay(
            HistoryArguments.Default(filter = HistoryFilter(outcomes = setOf(HistoryOutcome.FAILED))),
        )

        display.title!!.get(context) shouldBe context.getString(R.string.history_workspace_title_outcome_failed)
    }

    @Test
    fun `a path-scoped tab is named after its scope`() {
        val display = deriveHistoryDisplay(
            HistoryArguments.Default(filter = HistoryFilter(pathScopes = setOf("/sdcard/DCIM"))),
        )

        display.title!!.get(context) shouldBe
            context.getString(R.string.history_workspace_title_scoped, "/sdcard/DCIM")
    }

    @Test
    fun `the live seed matches the derivation`() {
        val arguments = HistoryArguments.Default(filter = HistoryFilter(outcomes = setOf(HistoryOutcome.FAILED)))
        val workspace = HistoryWorkspace(
            id = Workspace.Id(),
            creationArguments = arguments,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher()),
        )
        val derived = deriveHistoryDisplay(arguments)

        workspace.info.value.title.get(context) shouldBe derived.title!!.get(context)
        workspace.info.value.subtitle!!.get(context) shouldBe derived.subtitle!!.get(context)
    }
}
