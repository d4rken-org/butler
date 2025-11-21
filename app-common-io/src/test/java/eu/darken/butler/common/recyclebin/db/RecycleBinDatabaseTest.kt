package eu.darken.butler.common.recyclebin.db

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
 *
 * Note: Full DAO integration tests require instrumented tests with a real database.
 * This test focuses on entity structure and type conversion logic.
 */
class RecycleBinDatabaseTest : BaseTest() {

    // ============ ENTITY TESTS ============

    @Test
    fun `RecycleBinEntity - creates entity with all fields`() {
        // Given
        val id = Uuid.random().toString()
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        // When
        val entity = RecycleBinEntity(
            id = id,
            originalPath = "/storage/emulated/0/test.txt",
            recycleBinPath = "/storage/emulated/0/.recycle_bin/test.txt",
            deletedAt = now,
            size = 1024L,
        )

        // Then
        entity.apply {
            this.id shouldBe id
            originalPath shouldBe "/storage/emulated/0/test.txt"
            recycleBinPath shouldBe "/storage/emulated/0/.recycle_bin/test.txt"
            deletedAt shouldBe now
            size shouldBe 1024L
        }
    }

    @Test
    fun `RecycleBinEntity - generates UUID when not provided`() {
        // When
        val entity = RecycleBinEntity(
            originalPath = "/test.txt",
            recycleBinPath = "/recycle_bin/test.txt",
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 100L,
        )

        // Then
        entity.id shouldNotBe ""
        // Verify it's a valid UUID string
        Uuid.parse(entity.id) shouldNotBe null
    }

    @Test
    fun `RecycleBinEntity - equality works correctly`() {
        // Given
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val id = "same-id"

        val entity1 = RecycleBinEntity(
            id = id,
            originalPath = "/test.txt",
            recycleBinPath = "/recycle_bin/test.txt",
            deletedAt = now,
            size = 100L,
        )

        val entity2 = RecycleBinEntity(
            id = id,
            originalPath = "/test.txt",
            recycleBinPath = "/recycle_bin/test.txt",
            deletedAt = now,
            size = 100L,
        )

        // Then
        entity1 shouldBe entity2
    }

    @Test
    fun `RecycleBinEntity - copy works correctly`() {
        // Given
        val original = RecycleBinEntity(
            id = "test-id",
            originalPath = "/original.txt",
            recycleBinPath = "/recycle_bin/original.txt",
            deletedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            size = 1024L,
        )

        // When
        val modified = original.copy(size = 2048L)

        // Then
        modified.apply {
            id shouldBe "test-id"
            originalPath shouldBe "/original.txt"
            recycleBinPath shouldBe "/recycle_bin/original.txt"
            size shouldBe 2048L
        }
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
