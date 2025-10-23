package eu.darken.butler.common.files.metadata

import android.content.Context
import android.content.pm.PackageManager
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OwnershipResolverTest : BaseTest() {

    private val mockContext = mockk<Context>()
    private val mockPackageManager = mockk<PackageManager>()
    private val mockLibcoreTool = mockk<LibcoreTool>()

    private val resolver = run {
        every { mockContext.packageManager } returns mockPackageManager
        OwnershipResolver(mockContext, mockLibcoreTool)
    }

    @AfterEach
    fun cleanup() {
        resolver.clearCache()
    }

    @Test
    fun `resolve returns ownership with LibcoreTool success`() {
        // Given
        every { mockLibcoreTool.getNameForUid(1000) } returns "system"
        every { mockLibcoreTool.getNameForGid(1000) } returns "system"

        // When
        val result = resolver.resolve(userId = 1000, groupId = 1000)

        // Then
        result.userId shouldBe 1000L
        result.groupId shouldBe 1000L
        result.userName shouldBe "system"
        result.groupName shouldBe "system"
    }

    @Test
    fun `resolve uses system UID mapping when LibcoreTool fails`() {
        // Given - LibcoreTool returns null
        every { mockLibcoreTool.getNameForUid(1001) } returns null
        every { mockLibcoreTool.getNameForGid(1001) } returns null

        // When - requesting system UID (radio=1001)
        val result = resolver.resolve(userId = 1001, groupId = 1001)

        // Then - should fall back to hardcoded mapping
        result.userId shouldBe 1001L
        result.groupId shouldBe 1001L
        result.userName shouldBe "radio"
        result.groupName shouldBe "radio"
    }

    @Test
    fun `resolve uses PackageManager for app UIDs`() {
        // Given - LibcoreTool fails for app UID
        every { mockLibcoreTool.getNameForUid(10123) } returns null
        every { mockLibcoreTool.getNameForGid(10123) } returns null

        // PackageManager returns package names
        every { mockPackageManager.getPackagesForUid(10123) } returns arrayOf(
            "com.example.app",
            "com.example.app.provider"
        )

        // When - requesting app UID
        val result = resolver.resolve(userId = 10123, groupId = 10123)

        // Then - should resolve via PackageManager
        result.userId shouldBe 10123L
        result.groupId shouldBe 10123L
        result.userName shouldBe "com.example.app"  // First package
        result.groupName shouldBe "com.example.app"

        verify(exactly = 1) { mockPackageManager.getPackagesForUid(10123) }
    }

    @Test
    fun `resolve returns null when all strategies fail`() {
        // Given - all strategies return null
        every { mockLibcoreTool.getNameForUid(5555) } returns null
        every { mockLibcoreTool.getNameForGid(5555) } returns null
        // 5555 is not in system UID range (0-9999 but not in the map)
        // 5555 is not in app UID range (10000+)

        // When
        val result = resolver.resolve(userId = 5555, groupId = 5555)

        // Then - ownership object with null names
        result.userId shouldBe 5555L
        result.groupId shouldBe 5555L
        result.userName shouldBe null
        result.groupName shouldBe null
    }

    @Test
    fun `cache is used for subsequent lookups`() {
        // Given
        every { mockLibcoreTool.getNameForUid(1000) } returns "system"
        every { mockLibcoreTool.getNameForGid(1000) } returns "system"

        // When
        val result1 = resolver.resolve(userId = 1000, groupId = 1000)
        val result2 = resolver.resolve(userId = 1000, groupId = 1000)

        // Then
        result1.userName shouldBe "system"
        result2.userName shouldBe "system"

        // LibcoreTool should only be called once (second time uses cache)
        verify(exactly = 1) { mockLibcoreTool.getNameForUid(1000) }
        verify(exactly = 1) { mockLibcoreTool.getNameForGid(1000) }
    }

    @Test
    fun `clearCache invalidates cached entries`() {
        // Given
        every { mockLibcoreTool.getNameForUid(1000) } returns "system"
        every { mockLibcoreTool.getNameForGid(1000) } returns "system"

        // When
        resolver.resolve(userId = 1000, groupId = 1000)
        resolver.clearCache()
        resolver.resolve(userId = 1000, groupId = 1000)

        // Then - LibcoreTool should be called twice (cache was cleared)
        verify(exactly = 2) { mockLibcoreTool.getNameForUid(1000) }
        verify(exactly = 2) { mockLibcoreTool.getNameForGid(1000) }
    }

    @Test
    fun `different UIDs and GIDs are cached separately`() {
        // Given
        every { mockLibcoreTool.getNameForUid(1000) } returns "system"
        every { mockLibcoreTool.getNameForUid(1001) } returns "radio"
        every { mockLibcoreTool.getNameForGid(1000) } returns "system"
        every { mockLibcoreTool.getNameForGid(1001) } returns "radio"

        // When
        val result1 = resolver.resolve(userId = 1000, groupId = 1000)
        val result2 = resolver.resolve(userId = 1001, groupId = 1001)

        // Then
        result1.userName shouldBe "system"
        result1.groupName shouldBe "system"
        result2.userName shouldBe "radio"
        result2.groupName shouldBe "radio"

        verify(exactly = 1) { mockLibcoreTool.getNameForUid(1000) }
        verify(exactly = 1) { mockLibcoreTool.getNameForUid(1001) }
        verify(exactly = 1) { mockLibcoreTool.getNameForGid(1000) }
        verify(exactly = 1) { mockLibcoreTool.getNameForGid(1001) }
    }

    @Test
    fun `ownership object is always returned even when resolution fails`() {
        // Given
        every { mockLibcoreTool.getNameForUid(any()) } returns null
        every { mockLibcoreTool.getNameForGid(any()) } returns null

        // When
        val result = resolver.resolve(userId = 5678, groupId = 8765)

        // Then - ownership object should still be created with numeric IDs
        result shouldNotBe null
        result.userId shouldBe 5678L
        result.groupId shouldBe 8765L
        result.userName shouldBe null
        result.groupName shouldBe null
    }

    @Test
    fun `PackageManager exception is handled gracefully`() {
        // Given - LibcoreTool fails
        every { mockLibcoreTool.getNameForUid(10456) } returns null
        every { mockLibcoreTool.getNameForGid(10456) } returns null

        // PackageManager throws exception
        every { mockPackageManager.getPackagesForUid(10456) } throws RuntimeException("Test exception")

        // When
        val result = resolver.resolve(userId = 10456, groupId = 10456)

        // Then - should handle exception and return null names
        result.userId shouldBe 10456L
        result.groupId shouldBe 10456L
        result.userName shouldBe null
        result.groupName shouldBe null
    }

    @Test
    fun `system UID cache persists across multiple lookups`() {
        // Given - LibcoreTool fails
        every { mockLibcoreTool.getNameForUid(1002) } returns null
        every { mockLibcoreTool.getNameForGid(1002) } returns null

        // When - lookup bluetooth UID multiple times
        val result1 = resolver.resolve(userId = 1002, groupId = 1002)
        val result2 = resolver.resolve(userId = 1002, groupId = 1002)
        val result3 = resolver.resolve(userId = 1002, groupId = 1002)

        // Then - all should resolve to "bluetooth" from cache
        result1.userName shouldBe "bluetooth"
        result2.userName shouldBe "bluetooth"
        result3.userName shouldBe "bluetooth"

        // LibcoreTool should only be called once (subsequent lookups use cache)
        verify(exactly = 1) { mockLibcoreTool.getNameForUid(1002) }
        verify(exactly = 1) { mockLibcoreTool.getNameForGid(1002) }
    }
}
