package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.recent.RecentFilesReader
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentLocationLoaderTest : BaseTest() {

    private val reader = mockk<RecentFilesReader>()

    private val needsStorageAccess = PathRequirements(combos = setOf(setOf(SetupModule.Type.STORAGE)))

    private fun loader(requirements: MutableStateFlow<PathRequirements>) = RecentLocationLoader(
        workspaceId = Workspace.Id(),
        recentFilesReader = reader,
        pathPermissionCheck = mockk<PathPermissionCheck> {
            every { monitor(any<APath<*>>()) } returns requirements
        },
    )

    private fun entry(name: String, indexedAt: Instant, size: Long = 10L) = RecentFilesReader.Entry(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = FileType.FILE,
            size = size,
            modifiedAt = indexedAt,
        ),
        indexedAt = indexedAt,
    )

    private val now = Instant.fromEpochSeconds(1_700_000_000L)

    @Test
    fun `the listing keeps the reader's order and carries its index times`() = runTest {
        val newest = entry("newest.pdf", now, size = 100L)
        val older = entry("older.pdf", now - 3.days, size = 20L)
        coEvery { reader.read(any(), any()) } returns listOf(newest, older)

        val emissions = mutableListOf<ExplorerLocation>()
        backgroundScope.launch { loader(MutableStateFlow(PathRequirements())).loadRecent().toList(emissions) }
        // runCurrent, not advanceUntilIdle: the latter returns while only background work is left.
        runCurrent()

        val settled = emissions.last() as ExplorerLocation.Recent
        settled.isLoading shouldBe false
        settled.items!!.map { (it as ExplorerItem.Lookup).lookup.name } shouldContainExactly
            listOf("newest.pdf", "older.pdf")
        settled.indexedAt shouldBe mapOf(
            newest.lookup.path to newest.indexedAt,
            older.lookup.path to older.indexedAt,
        )
        settled.info shouldBe ExplorerLocation.Recent.Info(fileCount = 2, totalSize = 120L, windowDays = 30)
    }

    @Test
    fun `the query window reaches a month back and is capped`() = runTest {
        val cutoff = slot<Instant>()
        val limit = slot<Int>()
        coEvery { reader.read(capture(cutoff), capture(limit)) } returns emptyList()

        backgroundScope.launch { loader(MutableStateFlow(PathRequirements())).loadRecent().toList() }
        runCurrent()

        val requestedWindow = Clock.System.now() - cutoff.captured
        (requestedWindow >= 29.days) shouldBe true
        (requestedWindow <= 31.days) shouldBe true
        limit.captured shouldBe 500
    }

    @Test
    fun `an empty result still settles instead of loading forever`() = runTest {
        coEvery { reader.read(any(), any()) } returns emptyList()

        val emissions = mutableListOf<ExplorerLocation>()
        backgroundScope.launch { loader(MutableStateFlow(PathRequirements())).loadRecent().toList(emissions) }
        runCurrent()

        val settled = emissions.last() as ExplorerLocation.Recent
        settled.isLoading shouldBe false
        settled.items shouldBe emptyList()
        settled.info shouldBe ExplorerLocation.Recent.Info(fileCount = 0, totalSize = 0L, windowDays = 30)
    }

    @Test
    fun `a location that needs setup settles without querying`() = runTest {
        coEvery { reader.read(any(), any()) } returns emptyList()

        val emissions = mutableListOf<ExplorerLocation>()
        backgroundScope.launch { loader(MutableStateFlow(needsStorageAccess)).loadRecent().toList(emissions) }
        runCurrent()

        val settled = emissions.last() as ExplorerLocation.Recent
        settled.setupRequirements shouldBe needsStorageAccess
        settled.isLoading shouldBe false
        settled.items shouldBe null
        coVerify(exactly = 0) { reader.read(any(), any()) }
    }

    /**
     * The monitor stays subscribed, so granting access reruns the load. Sampling it once would
     * leave the setup card up until the user navigated away and back.
     */
    @Test
    fun `granting access reruns the load without a manual refresh`() = runTest {
        coEvery { reader.read(any(), any()) } returns listOf(entry("fresh.pdf", now))
        val requirements = MutableStateFlow(needsStorageAccess)

        val emissions = mutableListOf<ExplorerLocation>()
        backgroundScope.launch { loader(requirements).loadRecent().toList(emissions) }
        runCurrent()
        (emissions.last() as ExplorerLocation.Recent).items shouldBe null

        requirements.value = PathRequirements()
        runCurrent()

        val settled = emissions.last() as ExplorerLocation.Recent
        settled.isLoading shouldBe false
        settled.items!!.map { (it as ExplorerItem.Lookup).lookup.name } shouldContainExactly listOf("fresh.pdf")
        coVerify(exactly = 1) { reader.read(any(), any()) }
    }
}
