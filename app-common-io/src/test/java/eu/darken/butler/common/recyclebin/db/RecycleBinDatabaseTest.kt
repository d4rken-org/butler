package eu.darken.butler.common.recyclebin.db

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.room.InstantConverter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for RecycleBin database components.
 *
 * Tests cover:
 * - RecycleBinEntity structure and creation
 * - Type converters (InstantConverter)
 * - Entity equality and copying
 * - JSON serialization of lookup data
 *
 * Note: Full DAO integration tests require instrumented tests with a real database.
 * This test focuses on entity structure and type conversion logic.
 */
class RecycleBinDatabaseTest : BaseTest() {

    private fun createTestLookup(
        path: String = "/storage/emulated/0/test.txt",
        fileType: FileType = FileType.FILE,
        size: Long = 1024L,
    ): LocalPathLookup {
        return LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = size,
            modifiedAt = null,
        )
    }

    // ============ ENTITY TESTS ============

    @Test
    fun `RecycleBinEntity - creates entity with all fields`() {
        // Given
        val id = Uuid.random()
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val originalPath = LocalPath.build("/storage/emulated/0/test.txt")
        val recycleBinPath = LocalPath.build("/storage/emulated/0/.recycle_bin/test.txt")
        val lookup = createTestLookup("/storage/emulated/0/test.txt")

        // When
        val entity = RecycleBinEntity(
            id = id,
            originalPath = originalPath,
            originalLookup = lookup,
            recycleBinPath = recycleBinPath,
            deletedAt = now,
            size = 1024L,
            fileType = FileType.FILE,
        )

        // Then
        entity.apply {
            this.id shouldBe id
            this.originalPath shouldBe originalPath
            this.originalLookup shouldBe lookup
            this.recycleBinPath shouldBe recycleBinPath
            this.deletedAt shouldBe now
            this.size shouldBe 1024L
            this.fileType shouldBe FileType.FILE
        }
    }

    @Test
    fun `RecycleBinEntity - generates UUID when not provided`() {
        // When
        val entity = RecycleBinEntity(
            originalPath = LocalPath.build("/test.txt"),
            originalLookup = createTestLookup("/test.txt"),
            recycleBinPath = LocalPath.build("/recycle_bin/test.txt"),
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 100L,
            fileType = FileType.FILE,
        )

        // Then
        entity.id shouldNotBe null
        // Verify it's a valid UUID
        entity.id.toString() shouldNotBe ""
    }

    @Test
    fun `RecycleBinEntity - equality works correctly`() {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val originalPath = LocalPath.build("/test.txt")
        val recycleBinPath = LocalPath.build("/recycle_bin/test.txt")
        val lookupJson = createTestLookup("/test.txt")

        val entity1 = RecycleBinEntity(
            id = id,
            originalPath = originalPath,
            originalLookup = lookupJson,
            recycleBinPath = recycleBinPath,
            deletedAt = now,
            size = 100L,
            fileType = FileType.FILE,
        )

        val entity2 = RecycleBinEntity(
            id = id,
            originalPath = originalPath,
            originalLookup = lookupJson,
            recycleBinPath = recycleBinPath,
            deletedAt = now,
            size = 100L,
            fileType = FileType.FILE,
        )

        // Then
        entity1 shouldBe entity2
    }

    @Test
    fun `RecycleBinEntity - copy works correctly`() {
        // Given
        val testId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val originalPath = LocalPath.build("/original.txt")
        val recycleBinPath = LocalPath.build("/recycle_bin/original.txt")
        val original = RecycleBinEntity(
            id = testId,
            originalPath = originalPath,
            originalLookup = createTestLookup("/original.txt"),
            recycleBinPath = recycleBinPath,
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 1024L,
            fileType = FileType.FILE,
        )

        // When
        val modified = original.copy(size = 2048L)

        // Then
        modified.apply {
            id shouldBe testId
            this.recycleBinPath shouldBe recycleBinPath
            size shouldBe 2048L
            fileType shouldBe FileType.FILE
        }
    }

    @Test
    fun `RecycleBinEntity - stores different file types`() {
        // Given/When
        val fileEntity = RecycleBinEntity(
            originalPath = LocalPath.build("/file.txt"),
            originalLookup = createTestLookup("/file.txt", FileType.FILE),
            recycleBinPath = LocalPath.build("/bin/file.txt"),
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 100L,
            fileType = FileType.FILE,
        )

        val dirEntity = RecycleBinEntity(
            originalPath = LocalPath.build("/folder"),
            originalLookup = createTestLookup("/folder", FileType.DIRECTORY),
            recycleBinPath = LocalPath.build("/bin/folder"),
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 4096L,
            fileType = FileType.DIRECTORY,
        )

        // Then
        fileEntity.fileType shouldBe FileType.FILE
        dirEntity.fileType shouldBe FileType.DIRECTORY
    }

    // ============ TYPE CONVERTER TESTS ============

    @Test
    fun `InstantConverter - converts Instant to milliseconds`() {
        // Given
        val converter = InstantConverter()
        val instant = Instant.fromEpochMilliseconds(1234567890000L)

        // When
        val millis = converter.fromInstant(instant)

        // Then
        millis shouldBe 1234567890000L
    }

    @Test
    fun `InstantConverter - converts milliseconds to Instant`() {
        // Given
        val converter = InstantConverter()
        val millis = 1234567890000L

        // When
        val instant = converter.toInstant(millis)

        // Then
        instant shouldBe Instant.fromEpochMilliseconds(1234567890000L)
    }

    @Test
    fun `InstantConverter - handles null Instant`() {
        // Given
        val converter = InstantConverter()

        // When
        val millis = converter.fromInstant(null)

        // Then
        millis shouldBe null
    }

    @Test
    fun `InstantConverter - handles null milliseconds`() {
        // Given
        val converter = InstantConverter()

        // When
        val instant = converter.toInstant(null)

        // Then
        instant shouldBe null
    }

    @Test
    fun `InstantConverter - roundtrip conversion preserves value`() {
        // Given
        val converter = InstantConverter()
        val original = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        // When - convert to millis and back
        val millis = converter.fromInstant(original)
        val restored = converter.toInstant(millis)

        // Then
        restored shouldBe original
    }

    @Test
    fun `InstantConverter - handles epoch zero`() {
        // Given
        val converter = InstantConverter()
        val epochZero = Instant.fromEpochMilliseconds(0L)

        // When
        val millis = converter.fromInstant(epochZero)
        val restored = converter.toInstant(millis)

        // Then
        millis shouldBe 0L
        restored shouldBe epochZero
    }

    @Test
    fun `InstantConverter - handles distant past and future`() {
        // Given
        val converter = InstantConverter()
        val distantPast = Instant.fromEpochMilliseconds(-2208988800000L) // 1900-01-01
        val distantFuture = Instant.fromEpochMilliseconds(4102444800000L) // 2100-01-01

        // When
        val pastMillis = converter.fromInstant(distantPast)
        val futureMillis = converter.fromInstant(distantFuture)

        val pastRestored = converter.toInstant(pastMillis)
        val futureRestored = converter.toInstant(futureMillis)

        // Then
        pastRestored shouldBe distantPast
        futureRestored shouldBe distantFuture
    }
}
