package eu.darken.butler.explorer.core.sorting.rules

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.sorting.rules.db.FolderSortRuleDatabase
import eu.darken.butler.explorer.core.sorting.rules.db.FolderSortRuleEntity
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderSortRulesRepoTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private lateinit var database: FolderSortRuleDatabase
    private lateinit var repo: FolderSortRulesRepo

    private val logs = mutableListOf<Pair<Logging.Priority, String>>()
    private val logCapture = object : Logging.Logger {
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            logs.add(priority to message)
        }
    }

    private val download = LocalPath.build("/sdcard/Download")
    private val nested = LocalPath.build("/sdcard/Download/butler-qa")
    private val bySize = SortSettings(mode = SortSettings.Mode.SIZE, reversed = false)
    private val byName = SortSettings(mode = SortSettings.Mode.NAME, reversed = true)

    @Before
    fun setup() {
        Logging.install(logCapture)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            FolderSortRuleDatabase::class.java,
        ).build()
        repo = FolderSortRulesRepo(database.folderSortRuleDao(), json)
    }

    @After
    fun teardown() {
        database.close()
        Logging.remove(logCapture)
        logs.clear()
    }

    private fun warnings() = logs.filter { it.first == Logging.Priority.WARN }.map { it.second }

    private suspend fun insertRaw(entity: FolderSortRuleEntity) = database.folderSortRuleDao().upsert(entity)

    private fun serialize(path: APath<*>) =
        json.encodeToString(kotlinx.serialization.PolymorphicSerializer(APath::class), path)

    @Test
    fun `a rule is stored and read back`() = runTest {
        repo.set(download, bySize, subtree = false)

        val rules = repo.observeRulesFor(download).first()

        rules.size shouldBe 1
        rules.single().pathKey shouldBe download.sortPathKey()
        rules.single().path shouldBe download
        rules.single().settings shouldBe bySize
        rules.single().subtree shouldBe false
    }

    @Test
    fun `setting a rule twice replaces it`() = runTest {
        repo.set(download, bySize, subtree = false)
        repo.set(download, byName, subtree = true)

        val rule = repo.observeRulesFor(download).first().single()
        rule.settings shouldBe byName
        rule.subtree shouldBe true
        repo.count.first() shouldBe 1
    }

    @Test
    fun `a follow-default marker is stored as a settings-less rule`() = runTest {
        repo.setFollowsDefault(download)

        repo.observeRulesFor(download).first().single().settings shouldBe null
    }

    @Test
    fun `clear removes only the named rule`() = runTest {
        repo.set(download, bySize, subtree = false)
        repo.set(nested, byName, subtree = false)

        repo.clear(nested)

        repo.observeAll().first().map { it.pathKey } shouldContainExactlyInAnyOrder listOf(download.sortPathKey())
    }

    @Test
    fun `clearAll empties the table`() = runTest {
        repo.set(download, bySize, subtree = false)
        repo.set(nested, byName, subtree = false)

        repo.clearAll()

        repo.observeAll().first() shouldBe emptyList()
        repo.count.first() shouldBe 0
    }

    @Test
    fun `a lookup returns the folder's own rule and every ancestor rule`() = runTest {
        repo.set(download, bySize, subtree = true)
        repo.set(nested, byName, subtree = false)
        repo.set(LocalPath.build("/sdcard/Other"), byName, subtree = true)

        repo.observeRulesFor(nested).first().map { it.pathKey } shouldContainExactlyInAnyOrder listOf(
            download.sortPathKey(),
            nested.sortPathKey(),
        )
    }

    /**
     * Reduction is the resolver's job, so the repo hands over candidates un-filtered - but the two
     * together must not let a folder-only rule leak into its children.
     */
    @Test
    fun `an ancestor rule without subtree does not reach a descendant`() = runTest {
        repo.set(download, bySize, subtree = false)

        val candidates = repo.observeRulesFor(nested).first()
        val resolution = EffectiveSortResolver.resolve(
            ancestorKeys = nested.sortAncestorKeys(),
            tabRules = emptyMap(),
            savedRules = candidates.associate {
                it.pathKey to SortRuleCandidate(it.settings, it.subtree, it.path)
            },
            tabDefault = null,
            globalDefault = SortSettings(),
        )

        resolution.winnerKey shouldBe null
    }

    /**
     * An unknown mode is a row this build cannot honour - treating it as a marker would silently
     * suppress valid ancestor rules instead.
     */
    @Test
    fun `a row with an unknown mode is skipped rather than treated as a marker`() = runTest {
        insertRaw(
            FolderSortRuleEntity(
                pathKey = download.sortPathKey(),
                path = serialize(download),
                followsDefault = false,
                mode = "SOMETHING_NEWER",
                reversed = false,
                subtree = false,
                updatedAt = Instant.DISTANT_PAST,
            )
        )
        repo.set(nested, byName, subtree = false)

        val rules = repo.observeRulesFor(nested).first()

        rules.map { it.pathKey } shouldBe listOf(nested.sortPathKey())
        warnings().any { it.contains("SOMETHING_NEWER") } shouldBe true
    }

    @Test
    fun `a row with an undecodable path is skipped without killing the observer`() = runTest {
        insertRaw(
            FolderSortRuleEntity(
                pathKey = download.sortPathKey(),
                path = "not-json-at-all",
                followsDefault = false,
                mode = SortSettings.Mode.SIZE.name,
                reversed = false,
                subtree = true,
                updatedAt = Instant.DISTANT_PAST,
            )
        )
        repo.set(nested, byName, subtree = false)

        val rules = repo.observeRulesFor(nested).first()

        rules.map { it.pathKey } shouldBe listOf(nested.sortPathKey())
        warnings().any { it.contains("unreadable") } shouldBe true
    }
}
