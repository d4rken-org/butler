package eu.darken.butler.common.files.local.accessibility

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.storage.StorageEnvironment
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelper.EmptyApp
import testhelpers.BaseTest

/**
 * Tests for LocalPathAccessibilityChecker.
 *
 * Tests are organized into three main categories:
 * 1. Tier 1 (Fast Path): Hardcoded universal rules - no mocking needed
 * 2. Tier 2 (Smart Path): Dynamic detection via StorageEnvironment - requires mocking
 * 3. Edge Cases: Unusual scenarios, path aliases, scoped storage
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalPathAccessibilityCheckerTest : BaseTest() {

    private lateinit var mockContext: Context
    private lateinit var mockStorageEnvironment: StorageEnvironment
    private lateinit var checker: LocalPathAccessibilityChecker

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockStorageEnvironment = mockk(relaxed = true)

        // Default mock setup: Basic external storage and package name
        every { mockContext.packageName } returns "eu.darken.butler"
        every { mockStorageEnvironment.externalDirs } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/emulated/0")
        )
        every { mockStorageEnvironment.publicDataDirs } returns emptyList()

        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)
    }

    // ========================================================================
    // Tier 1: Fast Path - Kernel Filesystems
    // ========================================================================

    @Test
    fun `root path is definitely inaccessible for both read and write`() {
        val path = LocalPath.build("/")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `proc filesystem is definitely inaccessible for both read and write`() {
        val paths = listOf(
            "/proc/cpuinfo",
            "/proc/meminfo",
            "/proc/self/status",
            "/proc/1/cmdline"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `sys filesystem is definitely inaccessible for both read and write`() {
        val paths = listOf(
            "/sys/class/net/wlan0/address",
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            "/sys/kernel/debug/tracing/trace"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `dev filesystem is definitely inaccessible for both read and write`() {
        val paths = listOf(
            "/dev/null",
            "/dev/zero",
            "/dev/random",
            "/dev/block/mmcblk0"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    // ========================================================================
    // Tier 1: Fast Path - System Partitions
    // ========================================================================

    @Test
    fun `system partition is definitely inaccessible for both read and write`() {
        val paths = listOf(
            "/system/bin/sh",
            "/system/framework/framework.jar",
            "/system/app/Settings.apk",
            "/system/build.prop"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            // Conservative: treat as inaccessible even for read
            // (some files might be readable but unreliable)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `vendor partition is definitely inaccessible for both read and write`() {
        val paths = listOf(
            "/vendor/lib/libvendor.so",
            "/vendor/etc/vintf/manifest.xml",
            "/vendor/firmware/firmware.bin"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `product partition is definitely inaccessible for both read and write`() {
        val path = LocalPath.build("/product/app/MyApp.apk")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `system_ext partition is definitely inaccessible for both read and write`() {
        val path = LocalPath.build("/system_ext/lib/libext.so")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `apex partition is definitely inaccessible for both read and write`() {
        val path = LocalPath.build("/apex/com.android.runtime/lib/libart.so")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    // ========================================================================
    // Tier 1: Fast Path - Common Accessible Paths
    // ========================================================================

    @Test
    fun `sdcard paths are accessible for both read and write`() {
        val paths = listOf(
            "/sdcard/Download/file.zip",
            "/sdcard/DCIM/photo.jpg",
            "/sdcard/Documents/doc.pdf",
            "/sdcard/test.txt"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `storage emulated paths are accessible for both read and write`() {
        val paths = listOf(
            "/storage/emulated/0/Download/file.zip",
            "/storage/emulated/0/DCIM/photo.jpg",
            "/storage/emulated/0/test.txt"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `storage self primary paths are accessible for both read and write`() {
        val path = LocalPath.build("/storage/self/primary/Download/file.zip")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    // ========================================================================
    // Tier 1: Mount Point Edge Cases
    // ========================================================================

    @Test
    fun `storage emulated with different user ID is inaccessible`() {
        val paths = listOf(
            "/storage/emulated/11/Documents/file.txt",
            "/storage/emulated/999/file.txt",
            "/storage/emulated/10/Download/test.zip"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `storage self with non-primary storage is inaccessible`() {
        val paths = listOf(
            "/storage/self/secondary/file.txt",
            "/storage/self/other/document.pdf"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    // ========================================================================
    // Tier 1: Fast Path - Scoped Storage Restrictions (API 30+)
    // ========================================================================

    @Test
    @Config(sdk = [30])
    fun `Android data directory is inaccessible on API 30+ for both read and write`() {
        // Setup scoped storage restrictions
        every { mockStorageEnvironment.publicDataDirs } returns listOf(
            LocalPath.build("/sdcard/Android/data"),
            LocalPath.build("/storage/emulated/0/Android/data")
        )

        // Recreate checker to pick up new mocks
        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)

        val paths = listOf(
            "/sdcard/Android/data/com.other.app/files/data.db",
            "/storage/emulated/0/Android/data/com.other.app/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    @Test
    @Config(sdk = [29])
    fun `Android data directory is accessible on API 29 for both read and write`() {
        // On API 29, scoped storage not enforced
        every { mockStorageEnvironment.publicDataDirs } returns emptyList()

        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)

        val path = LocalPath.build("/sdcard/Android/data/com.other.app/files/data.db")

        // Accessible on older API levels
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    // ========================================================================
    // Tier 2: Smart Path - Dynamic Storage Detection
    // ========================================================================

    @Test
    fun `custom SD card detected by StorageEnvironment is accessible`() {
        // Mock StorageEnvironment to include custom SD card
        every { mockStorageEnvironment.externalDirs } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/1234-5678") // SD card UUID
        )

        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)

        val path = LocalPath.build("/storage/1234-5678/Documents/file.pdf")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    @Test
    fun `USB OTG drive detected by StorageEnvironment is accessible`() {
        // Mock StorageEnvironment to include USB OTG
        every { mockStorageEnvironment.externalDirs } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/usbotg")
        )

        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)

        val path = LocalPath.build("/storage/usbotg/backup/data.zip")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    @Test
    fun `app own data directory is always accessible`() {
        val paths = listOf(
            "/data/data/eu.darken.butler/cache/temp.db",
            "/data/data/eu.darken.butler/files/config.json",
            "/data/user/0/eu.darken.butler/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `other app data directory is definitely inaccessible`() {
        val paths = listOf(
            "/data/data/com.other.app/databases/db.db",
            "/data/data/com.other.app/files/file.txt",
            "/data/user/0/com.other.app/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
            checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `unknown path is conservatively treated as inaccessible`() {
        // Custom ROM partition not in StorageEnvironment
        val path = LocalPath.build("/mnt/vendor/persist/data.db")

        // Conservative fallback: unknown = inaccessible
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `path that starts with restricted prefix but is actually under accessible storage`() {
        // Edge case: file named "system" under sdcard
        val path = LocalPath.build("/sdcard/system/backup.tar")

        // Should NOT be confused with /system/ partition
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    @Test
    fun `path aliases are handled consistently`() {
        // /sdcard is a symlink to /storage/emulated/0
        val path1 = LocalPath.build("/sdcard/test.txt")
        val path2 = LocalPath.build("/storage/emulated/0/test.txt")

        // Both should be accessible
        checker.isDefinitelyInaccessible(path1, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path1, forWriting = true) shouldBe false

        checker.isDefinitelyInaccessible(path2, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path2, forWriting = true) shouldBe false
    }

    @Test
    fun `empty StorageEnvironment externalDirs still has baseline accessible paths`() {
        // Mock empty external dirs (unusual but possible)
        every { mockStorageEnvironment.externalDirs } returns emptyList()

        checker = LocalPathAccessibilityChecker(mockContext, mockStorageEnvironment)

        // Common paths should still work via hardcoded Tier 1 rules
        val path = LocalPath.build("/sdcard/test.txt")
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe false
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe false
    }

    @Test
    fun `data local tmp is treated as inaccessible by default`() {
        // /data/local/tmp is sometimes accessible but not reliably
        val path = LocalPath.build("/data/local/tmp/test.txt")

        // Not our package, so treated as inaccessible
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `cache partition is inaccessible`() {
        // /cache is a system partition
        val path = LocalPath.build("/cache/recovery/last_log")

        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }

    @Test
    fun `mnt paths are conservatively treated as inaccessible unless in StorageEnvironment`() {
        val path = LocalPath.build("/mnt/media_rw/1234-5678/test.txt")

        // Not in StorageEnvironment, so treated as inaccessible
        checker.isDefinitelyInaccessible(path, forWriting = false) shouldBe true
        checker.isDefinitelyInaccessible(path, forWriting = true) shouldBe true
    }
}
