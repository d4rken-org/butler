package eu.darken.butler.common.trash

import androidx.room.Room
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.room.APathConverter
import eu.darken.butler.common.files.room.APathLookupConverter
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.common.trash.db.TrashDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What [TrashRepo.syncWithFileSystem] does to the database rows for each answer of the strict
 * existence probe. A row stands for a file that was moved into the trash, so dropping it on a
 * probe that only failed to reach the volume orphans that file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashRepoSyncTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val gatewaySwitch: GatewaySwitch = mockk()

    private lateinit var database: TrashDatabase
    private lateinit var appScope: CoroutineScope

    @Before
    fun setup() {
        database = Room
            .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TrashDatabase::class.java)
            .addTypeConverter(APathConverter(json))
            .addTypeConverter(APathLookupConverter(json))
            .allowMainThreadQueries()
            .build()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun teardown() {
        database.close()
        appScope.cancel()
    }

    /**
     * The constructor launches an initial sync; waiting for it keeps its probes out of the
     * verifications below.
     */
    private suspend fun createRepo(): TrashRepo {
        val repo = TrashRepo(
            appScope = appScope,
            dispatcherProvider = TestDispatcherProvider(),
            gatewaySwitch = gatewaySwitch,
            database = database,
        )
        appScope.coroutineContext.job.children.toList().forEach { it.join() }
        return repo
    }

    private fun trashItem(name: String): TrashRepo.TrashItem {
        val originalPath = LocalPath.build("data", "local", "tmp", name)
        val trashPath = LocalPath.build(
            "storage", "emulated", "0", "Android", "data", "eu.darken.butler", ".trash", "${name}_x",
        )
        return TrashRepo.TrashItem(
            id = Uuid.random(),
            originalLookup = LocalPathLookup(
                lookedUp = originalPath,
                fileType = FileType.FILE,
                size = 34L,
                modifiedAt = Instant.fromEpochMilliseconds(0),
            ),
            trashPath = trashPath,
            trashLookup = null,
            size = 34L,
        )
    }

    private suspend fun storedIds(): List<Uuid> = database.trashDao().getAll().first().map { it.id }

    @Test
    fun `a trashed file that is definitely gone is dropped`() = runTest {
        val repo = createRepo()
        val item = trashItem("gone.txt")
        repo.insert(item)
        coEvery { gatewaySwitch.existsStrict(item.trashPath) } returns Existence.ABSENT

        repo.syncWithFileSystem()

        storedIds().shouldBeEmpty()
    }

    @Test
    fun `a trashed file that is still there is kept`() = runTest {
        val repo = createRepo()
        val item = trashItem("present.txt")
        repo.insert(item)
        coEvery { gatewaySwitch.existsStrict(item.trashPath) } returns Existence.PRESENT

        repo.syncWithFileSystem()

        storedIds() shouldContainExactlyInAnyOrder listOf(item.id)
    }

    @Test
    fun `a trashed file that cannot be checked is kept`() = runTest {
        val repo = createRepo()
        val item = trashItem("unknown.txt")
        repo.insert(item)
        coEvery { gatewaySwitch.existsStrict(item.trashPath) } returns Existence.UNKNOWN

        repo.syncWithFileSystem()

        storedIds() shouldContainExactlyInAnyOrder listOf(item.id)
    }

    @Test
    fun `a cancelled probe stops the sync instead of dropping the remaining rows`() = runTest {
        val repo = createRepo()
        val items = listOf(trashItem("one.txt"), trashItem("two.txt"), trashItem("three.txt"))
        items.forEach { repo.insert(it) }
        coEvery { gatewaySwitch.existsStrict(any()) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> { repo.syncWithFileSystem() }

        coVerify(exactly = 1) { gatewaySwitch.existsStrict(any()) }
        storedIds() shouldContainExactlyInAnyOrder items.map { it.id }
    }
}
