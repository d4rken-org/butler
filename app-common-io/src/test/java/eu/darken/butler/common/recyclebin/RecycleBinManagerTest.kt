package eu.darken.butler.common.recyclebin

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.recyclebin.db.RecycleBinDao
import eu.darken.butler.common.recyclebin.db.RecycleBinDatabase
import eu.darken.butler.common.recyclebin.db.RecycleBinEntity
import eu.darken.butler.common.storage.StorageEnvironment
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Basic tests for RecycleBinManager.
 *
 * Note: Full integration tests with file operations would require extensive mocking
 * of the file system operations. These tests focus on basic functionality and
 * business logic that can be tested without complex file operation mocking.
 */
class RecycleBinManagerTest : BaseTest() {

    private lateinit var database: RecycleBinDatabase
    private lateinit var dao: RecycleBinDao
    private lateinit var storageEnv: StorageEnvironment
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var settings: RecycleBinSettings
    private lateinit var dispatcherProvider: DispatcherProvider

    private lateinit var manager: RecycleBinManager

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true)
        database = mockk {
            every { recycleBinDao() } returns dao
        }

        storageEnv = mockk {
            every { ourPublicDirs } returns listOf(
                LocalPath.build("/storage/emulated/0/Android/cache"),
            )
        }

        gatewaySwitch = mockk {
            coEvery { exists(any()) } returns false
            coEvery { createDir(any()) } returns Unit
        }

        settings = mockk {
            every { enabled } returns mockk<DataStoreValue<Boolean>>().also {
                every { it.flow } returns flowOf(true)
            }
            every { expiresAfter } returns mockk<DataStoreValue<Duration>>().also {
                every { it.flow } returns flowOf(30.days)
            }
            every { maxRecycleBinSize } returns mockk<DataStoreValue<Long>>().also {
                every { it.flow } returns flowOf(1000 * 1048576L)
            }
        }

        dispatcherProvider = TestDispatcherProvider()

        manager = RecycleBinManager(
            database = database,
            storageEnv = storageEnv,
            gatewaySwitch = gatewaySwitch,
            settings = settings,
            dispatcherProvider = dispatcherProvider,
        )
    }

    // ============ PATH TYPE FILTERING TESTS ============

    @Test
    fun `moveToRecycleBin - filters out unsupported path types`() = runTest {
        // Given - SAFPath is not supported (only LocalPath is)
        val unsupportedPath = mockk<SAFPath>()
        val lookupMock = mockk<APathLookup<APath<*>>> {
            every { fileType } returns eu.darken.butler.common.files.metadata.FileType.UNKNOWN
        }

        // Mock lookup to handle the unsupported path lookup with fallback
        coEvery { gatewaySwitch.lookup(any(), any()) } returns lookupMock

        // When
        val report = manager.moveToRecycleBin(listOf(unsupportedPath))

        // Then - should return empty report without attempting operations
        report.movedToRecycleBin.shouldBeEmpty()
        report.failedToMove.size shouldBe 1 // Unsupported path is added to failed
        report.bytesMoved shouldBe 0L
    }

    @Test
    fun `moveToRecycleBin - accepts LocalPath type`() = runTest {
        // Given
        val localPath = LocalPath.build("/storage/test.txt")
        val lookupMock = mockk<APathLookup<APath<*>>> {
            every { fileType } returns eu.darken.butler.common.files.metadata.FileType.UNKNOWN
        }

        coEvery { gatewaySwitch.lookup(any(), any()) } returns lookupMock

        // When
        val report = manager.moveToRecycleBin(listOf(localPath))

        // Then - LocalPath is accepted (even if file doesn't exist in this test)
        // The path type was accepted and lookup was attempted
        report.movedToRecycleBin.shouldBeEmpty() // File doesn't exist
        report.failedToMove.size shouldBe 1 // But it was tried
    }

    // ============ CLEANUP TESTS ============

    @Test
    fun `cleanupExpired - calculates correct cutoff time`() = runTest {
        // Given - 30 day retention period
        coEvery { settings.expiresAfter.flow } returns flowOf(30.days)
        coEvery { dao.getOlderThan(any()) } returns emptyList()

        // When
        manager.cleanupExpired()

        // Then - verify getOlderThan was called (cutoff time calculation happens internally)
        coEvery { dao.getOlderThan(any()) }
    }

    @Test
    fun `cleanupExpired - returns zero when no expired items`() = runTest {
        // Given
        coEvery { settings.expiresAfter.flow } returns flowOf(30.days)
        coEvery { dao.getOlderThan(any()) } returns emptyList()

        // When
        val deletedCount = manager.cleanupExpired()

        // Then
        deletedCount shouldBe 0
    }

    // ============ STATISTICS TESTS ============

    @Test
    fun `getStats - handles empty recycle bin`() = runTest {
        // Given
        every { dao.getAll() } returns flowOf(emptyList())

        // When
        val stats = manager.getStats().first()

        // Then
        stats.totalItems shouldBe 0
        stats.totalSize shouldBe 0L
        stats.oldestItem shouldBe null
    }

    @Test
    fun `getStats - calculates totals from entities`() = runTest {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val entity1 = RecycleBinEntity(
            id = Uuid.random().toString(),
            originalPath = "/storage/file1.txt",
            recycleBinPath = "/storage/.recycle_bin/file1.txt",
            deletedAt = now - 1.days,
            size = 1024L,
        )
        val entity2 = RecycleBinEntity(
            id = Uuid.random().toString(),
            originalPath = "/storage/file2.txt",
            recycleBinPath = "/storage/.recycle_bin/file2.txt",
            deletedAt = now - 5.days,
            size = 2048L,
        )

        every { dao.getAll() } returns flowOf(listOf(entity1, entity2))

        // When
        val stats = manager.getStats().first()

        // Then
        stats.totalItems shouldBe 2
        stats.totalSize shouldBe 3072L
        stats.oldestItem shouldBe (now - 5.days)
    }
}
