package eu.darken.butler.explorer.core.hidden

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationCommonModule
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExplorerHiddenPathsRepoTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = SerializationIOModule().json(SerializationCommonModule().json())

    /** One instance per test, see ExplorerFavoritesRepoTest. */
    private fun freshSettings() = ExplorerSettings(context, json)

    private fun freshScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private fun freshRepo(
        scope: CoroutineScope = freshScope(),
        settings: ExplorerSettings = freshSettings(),
    ) = ExplorerHiddenPathsRepo(appScope = scope, settings = settings)

    private suspend fun ExplorerHiddenPathsRepo.settledKeys(): Set<String> = state.filterNotNull().first().keys

    @Test
    fun `state settles to the stored list`() = runTest {
        val repo = freshRepo()

        repo.state.filterNotNull().first().entries.shouldBeEmpty()
    }

    @Test
    fun `hideAll - dedupes against storage and within the batch, returns what was added`() = runTest {
        val repo = freshRepo()
        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        val c = LocalPath.build("/p/c")

        repo.hideAll(listOf(a)) shouldContainExactly listOf(a)
        repo.hideAll(listOf(a, b, b, LocalPath.build("/p/b"), c)) shouldContainExactly listOf(b, c)

        repo.state.first { it?.entries?.size == 3 }!!.entries shouldContainExactly listOf(
            HiddenEntry(a.sortPathKey(), a),
            HiddenEntry(b.sortPathKey(), b),
            HiddenEntry(c.sortPathKey(), c),
        )
    }

    @Test
    fun `isHidden - matches by key, not by instance`() = runTest {
        val repo = freshRepo()
        repo.hideAll(listOf(LocalPath.build("/p/a")))
        repo.state.first { it?.keys?.size == 1 }

        repo.isHidden(LocalPath.build("/p/a")) shouldBe true
        repo.isHidden(LocalPath.build("/p/b")) shouldBe false
        repo.isHidden(
            SAFPath(
                treeRoot = "content://com.android.externalstorage.documents/tree/primary%3Ap",
                segments = listOf("a"),
            ),
        ) shouldBe false
    }

    @Test
    fun `isHidden - nothing is hidden before the list is read`() {
        // A dispatcher that is never advanced, so the stored list is never read.
        val repo = freshRepo(scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()))

        repo.state.value shouldBe null
        repo.isHidden(LocalPath.build("/p/a")) shouldBe false
    }

    @Test
    fun `unhideAll - removes the entries of the given paths`() = runTest {
        val repo = freshRepo()
        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.hideAll(listOf(a, b))

        repo.unhideAll(listOf(LocalPath.build("/p/a")))

        repo.state.first { it?.keys?.size == 1 }!!.keys shouldBe setOf(b.sortPathKey())
        repo.isHidden(a) shouldBe false
    }

    @Test
    fun `unhideKeys - removes by stored key`() = runTest {
        val repo = freshRepo()
        val a = LocalPath.build("/p/a")
        val b = LocalPath.build("/p/b")
        repo.hideAll(listOf(a, b))

        repo.unhideKeys(setOf(b.sortPathKey(), "local/never/hidden"))

        repo.state.first { it?.keys?.size == 1 }!!.keys shouldBe setOf(a.sortPathKey())
    }

    @Test
    fun `the list survives a new repo on the same settings`() = runTest {
        val settings = freshSettings()
        val a = LocalPath.build("/p/a")
        freshRepo(settings = settings).hideAll(listOf(a))

        val second = freshRepo(settings = settings)

        second.settledKeys() shouldBe setOf(a.sortPathKey())
        settings.hiddenEntries.value() shouldContainExactly listOf(HiddenEntry(a.sortPathKey(), a))
    }

    @Test
    fun `a corrupt payload reads as an empty list`() = runTest {
        val settings = freshSettings()
        settings.dataStore.edit { prefs -> prefs[stringPreferencesKey("explorer.hidden.entries")] = "{not json" }

        freshRepo(settings = settings).settledKeys() shouldBe emptySet()
    }

    @Test
    fun `an unknown path type in the payload reads as an empty list`() = runTest {
        val settings = freshSettings()
        settings.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("explorer.hidden.entries")] =
                """[{"key":"local/p/x","path":{"type":"NOPE","path":"/p/x"}}]"""
        }

        val repo = freshRepo(settings = settings)
        repo.settledKeys() shouldBe emptySet()

        val c = LocalPath.build("/p/c")
        repo.hideAll(listOf(c)) shouldContainExactly listOf(c)
        settings.hiddenEntries.value() shouldContainExactly listOf(HiddenEntry(c.sortPathKey(), c))
    }
}
