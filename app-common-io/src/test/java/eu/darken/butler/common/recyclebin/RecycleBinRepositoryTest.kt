package eu.darken.butler.common.recyclebin

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.recyclebin.db.RecycleBinDao
import eu.darken.butler.common.recyclebin.db.RecycleBinDatabase
import eu.darken.butler.common.recyclebin.db.RecycleBinEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for RecycleBinRepository - database operations and domain model conversion.
 *
 * Tests cover:
 * - Flow-based data access (getAllItems)
 * - Entity to domain model conversion with JSON deserialization
 * - Availability checking via gateway
 * - File system synchronization
 * - Statistics queries
 */
class RecycleBinRepositoryTest : BaseTest() {

    private lateinit var database: RecycleBinDatabase
    private lateinit var dao: RecycleBinDao
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var dispatcherProvider: DispatcherProvider
    private lateinit var appScope: CoroutineScope

    private lateinit var repository: RecycleBinRepo

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        database = mockk {
            every { recycleBinDao() } returns dao
        }

        gatewaySwitch = mockk {
            coEvery { exists(any()) } returns true
        }

        dispatcherProvider = TestDispatcherProvider()
        appScope = CoroutineScope(SupervisorJob() + dispatcherProvider.Default)

        // Prevent init block from running sync
        coEvery { dao.getAll() } returns flowOf(emptyList())

        repository = RecycleBinRepo(
            database = database,
            gatewaySwitch = gatewaySwitch,
            dispatcherProvider = dispatcherProvider,
            appScope = appScope,
        )
    }

    private fun createTestEntity(
        id: String = "00000000-0000-0000-0000-000000000001",
        originalPath: String = "/storage/emulated/0/test.txt",
        recycleBinPath: String = "/storage/emulated/0/.recycle_bin/test.txt",
        deletedAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        size: Long = 1024L,
        fileType: FileType = FileType.FILE,
    ): RecycleBinEntity {
        val lookup = LocalPathLookup(
            lookedUp = LocalPath.build(originalPath),
            fileType = fileType,
            size = size,
            modifiedAt = null,
        )
        return RecycleBinEntity(
            id = Uuid.parse(id),
            originalPath = LocalPath.build(originalPath),
            originalLookup = lookup,
            recycleBinPath = LocalPath.build(recycleBinPath),
            deletedAt = deletedAt,
            size = size,
            fileType = fileType,
        )
    }

    // ============ GET ALL ITEMS TESTS ============

    @Test
    fun `getAllItems - returns empty list when no items`() = runTest {
        // Given
        coEvery { dao.getAll() } returns flowOf(emptyList())

        // When
        val items = repository.getAllItems().first()

        // Then
        items.shouldBeEmpty()
    }

    @Test
    fun `getAllItems - converts entities to domain models`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val entity = createTestEntity(
            id = "00000000-0000-0000-0000-000000000001",
            originalPath = "/storage/emulated/0/test.txt",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/test.txt",
            deletedAt = now,
            size = 1024L,
        )

        coEvery { dao.getAll() } returns flowOf(listOf(entity))
        coEvery { gatewaySwitch.exists(any()) } returns true

        // When
        val items = repository.getAllItems().first()

        // Then
        items shouldHaveSize 1
        items[0].apply {
            id shouldBe "00000000-0000-0000-0000-000000000001"
            originalPath shouldBe LocalPath.build("/storage/emulated/0/test.txt")
            recycleBinPath shouldBe LocalPath.build("/storage/emulated/0/.recycle_bin/test.txt")
            deletedAt shouldBe now
            isAvailable shouldBe true
        }
    }

    @Test
    fun `getAllItems - converts directory entities correctly`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val entity = createTestEntity(
            id = "00000000-0000-0000-0000-000000000002",
            originalPath = "/storage/emulated/0/Documents",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/Documents",
            deletedAt = now,
            size = 4096L,
            fileType = FileType.DIRECTORY,
        )

        coEvery { dao.getAll() } returns flowOf(listOf(entity))
        coEvery { gatewaySwitch.exists(any()) } returns true

        // When
        val items = repository.getAllItems().first()

        // Then
        items shouldHaveSize 1
        items[0].originalLookup.fileType shouldBe FileType.DIRECTORY
    }

    // ============ AVAILABILITY CHECK TESTS ============

    @Test
    fun `items with missing files are marked unavailable`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val entity = createTestEntity(
            id = "00000000-0000-0000-0000-000000000003",
            originalPath = "/storage/emulated/0/test.txt",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/test.txt",
            deletedAt = now,
            size = 1024L,
        )

        coEvery { dao.getAll() } returns flowOf(listOf(entity))
        coEvery { gatewaySwitch.exists(any()) } returns false // File doesn't exist

        // When
        val items = repository.getAllItems().first()

        // Then
        items shouldHaveSize 1
        items[0].isAvailable shouldBe false
    }

    // ============ SYNC WITH FILE SYSTEM TESTS ============

    @Test
    fun `syncWithFileSystem - removes orphaned database entries`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val orphanedEntity = createTestEntity(
            id = "00000000-0000-0000-0000-000000000004",
            originalPath = "/storage/emulated/0/missing.txt",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/missing.txt",
            deletedAt = now,
            size = 1024L,
        )

        coEvery { dao.getAll() } returns flowOf(listOf(orphanedEntity))

        coEvery { gatewaySwitch.exists(orphanedEntity.recycleBinPath) } returns false

        // When
        repository.syncWithFileSystem()

        // Then
        coVerify { dao.delete(orphanedEntity.id) }
    }

    @Test
    fun `syncWithFileSystem - keeps entries for existing files`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val validEntity = createTestEntity(
            id = "00000000-0000-0000-0000-000000000005",
            originalPath = "/storage/emulated/0/exists.txt",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/exists.txt",
            deletedAt = now,
            size = 1024L,
        )

        coEvery { dao.getAll() } returns flowOf(listOf(validEntity))

        coEvery { gatewaySwitch.exists(validEntity.recycleBinPath) } returns true

        // When
        repository.syncWithFileSystem()

        // Then
        coVerify(exactly = 0) { dao.delete(validEntity) }
    }

    // ============ STATISTICS TESTS ============

    @Test
    fun `getItemCount - returns correct count`() = runTest {
        // Given
        coEvery { dao.getItemCount() } returns 42

        // When
        val count = repository.getItemCount()

        // Then
        count shouldBe 42
    }

    @Test
    fun `getTotalSize - returns correct size`() = runTest {
        // Given
        coEvery { dao.getTotalSize() } returns 1024L * 1024L

        // When
        val size = repository.getTotalSize()

        // Then
        size shouldBe 1024L * 1024L
    }

    @Test
    fun `getTotalSize - returns zero when null`() = runTest {
        // Given
        coEvery { dao.getTotalSize() } returns null

        // When
        val size = repository.getTotalSize()

        // Then
        size shouldBe 0L
    }

    // ============ DELETE OPERATIONS TESTS ============

    @Test
    fun `deleteById - removes item from database`() = runTest {
        // Given
        val itemId = "00000000-0000-0000-0000-000000000001"

        // When
        repository.deleteById(itemId)

        // Then
        coVerify { dao.delete(Uuid.parse(itemId)) }
    }

    @Test
    fun `deleteAll - removes all items`() = runTest {
        // When
        repository.deleteAll()

        // Then
        coVerify { dao.deleteAll() }
    }
}
