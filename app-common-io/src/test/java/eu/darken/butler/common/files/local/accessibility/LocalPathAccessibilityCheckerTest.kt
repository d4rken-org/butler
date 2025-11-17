package eu.darken.butler.common.files.local.accessibility

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
import testhelpers.BaseTest
import testhelpers.EmptyApp

/**
 * Tests for LocalPathAccessChecker.
 *
 * Tests are organized into three main categories:
 * 1. Tier 1 (Fast Path): Hardcoded universal rules - no mocking needed
 * 2. Tier 2 (Smart Path): Dynamic detection via StorageEnvironment - requires mocking
 * 3. Edge Cases: Unusual scenarios, path aliases, scoped storage
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalPathAccessibilityCheckerTest : BaseTest() {

    private lateinit var mockStorageEnvironment: StorageEnvironment
    private lateinit var checker: LocalPathAccessChecker

    @Before
    fun setup() {
        mockStorageEnvironment = mockk(relaxed = false)

        every { mockStorageEnvironment.publicStorages } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/emulated/0"),
            LocalPath.build("/storage/ABCD-12324"),
        )
        every { mockStorageEnvironment.publicDataDirs } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/emulated/0/Android/data"),
            LocalPath.build("/storage/ABCD-12324/Android/data"),
        )
        every { mockStorageEnvironment.ourPrivateDirs } returns listOf(
            LocalPath.build("/data/user/0/eu.darken.butler"),
        )
        every { mockStorageEnvironment.ourPublicDirs } returns listOf(
            LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler"),
            LocalPath.build("/storage/ABCD-12324/Android/data/eu.darken.butler"),
        )

        checker = LocalPathAccessChecker(mockStorageEnvironment)
    }

    // ========================================================================
    // Tier 1: Fast Path - Kernel Filesystems
    // ========================================================================

    @Test
    fun `root path should not try normal access for both read and write`() {
        val path = LocalPath.build("/")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `proc filesystem should try normal access for reads but not writes`() {
        val paths = listOf(
            "/proc/cpuinfo",
            "/proc/meminfo",
            "/proc/self/status",
            "/proc/1/cmdline"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            // Many /proc files are readable via normal access
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            // But never writable without root
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `sys filesystem should not try normal access`() {
        val paths = listOf(
            "/sys/class/net/wlan0/address",
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            "/sys/kernel/debug/tracing/trace"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `dev filesystem should not try normal access for both read and write`() {
        val paths = listOf(
            "/dev/null",
            "/dev/zero",
            "/dev/random",
            "/dev/block/mmcblk0"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    // ========================================================================
    // Tier 1: Fast Path - System Partitions
    // ========================================================================

    @Test
    fun `system partition should try normal access for reads but not writes`() {
        val paths = listOf(
            "/system/bin/sh",
            "/system/framework/framework.jar",
            "/system/app/Settings.apk",
            "/system/build.prop"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            // Many system files are readable via normal access
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            // But never writable without root
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `vendor partition should try normal access for reads but not writes`() {
        val paths = listOf(
            "/vendor/lib/libvendor.so",
            "/vendor/etc/vintf/manifest.xml",
            "/vendor/firmware/firmware.bin"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            // Vendor files are often readable via normal access
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            // But never writable without root
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `product partition should try normal access for reads but not writes`() {
        val path = LocalPath.build("/product/app/MyApp.apk")

        // Product files are often readable via normal access
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        // But never writable without root
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `system_ext partition should try normal access for reads but not writes`() {
        val path = LocalPath.build("/system_ext/lib/libext.so")

        // System extension files are often readable via normal access
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        // But never writable without root
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `apex partition should try normal access for reads but not writes`() {
        val path = LocalPath.build("/apex/com.android.runtime/lib/libart.so")

        // APEX module files are often readable via normal access
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        // But never writable without root
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    // ========================================================================
    // Tier 1: Fast Path - Common Accessible Paths
    // ========================================================================

    @Test
    fun `sdcard paths should try normal access for both read and write`() {
        val paths = listOf(
            "/sdcard/Download/file.zip",
            "/sdcard/DCIM/photo.jpg",
            "/sdcard/Documents/doc.pdf",
            "/sdcard/test.txt"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `storage emulated paths should try normal access for both read and write`() {
        val paths = listOf(
            "/storage/emulated/0/Download/file.zip",
            "/storage/emulated/0/DCIM/photo.jpg",
            "/storage/emulated/0/test.txt"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
        }
    }

    // ========================================================================
    // Tier 1: Mount Point Edge Cases
    // ========================================================================

    @Test
    fun `storage emulated with different user ID should not try normal access`() {
        val paths = listOf(
            "/storage/emulated/11/Documents/file.txt",
            "/storage/emulated/999/file.txt",
            "/storage/emulated/10/Download/test.zip"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    fun `storage self with non-primary storage should not try normal access`() {
        val paths = listOf(
            "/storage/self/secondary/file.txt",
            "/storage/self/other/document.pdf"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    // ========================================================================
    // Tier 1: Fast Path - Scoped Storage Restrictions (API 30+)
    // ========================================================================

    @Test
    @Config(sdk = [30])
    fun `Android data directory should not try normal access on API 30+ for both read and write`() {
        // Setup scoped storage restrictions
        every { mockStorageEnvironment.publicDataDirs } returns listOf(
            LocalPath.build("/sdcard/Android/data"),
            LocalPath.build("/storage/emulated/0/Android/data")
        )

        // Recreate checker to pick up new mocks
        checker = LocalPathAccessChecker(mockStorageEnvironment)

        val paths = listOf(
            "/sdcard/Android/data/com.other.app/files/data.db",
            "/storage/emulated/0/Android/data/com.other.app/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    @Test
    @Config(sdk = [29])
    fun `Android data directory should try normal access on API 29 for both read and write`() {
        // On API 29, scoped storage not enforced
        every { mockStorageEnvironment.publicDataDirs } returns emptyList()

        checker = LocalPathAccessChecker(mockStorageEnvironment)

        val path = LocalPath.build("/sdcard/Android/data/com.other.app/files/data.db")

        // Should try normal access on older API levels
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    // ========================================================================
    // Tier 2: Smart Path - Dynamic Storage Detection
    // ========================================================================

    @Test
    fun `custom SD card detected by StorageEnvironment should try normal access`() {
        // Mock StorageEnvironment to include custom SD card
        every { mockStorageEnvironment.publicStorages } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/1234-5678") // SD card UUID
        )

        checker = LocalPathAccessChecker(mockStorageEnvironment)

        val path = LocalPath.build("/storage/1234-5678/Documents/file.pdf")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `USB OTG drive detected by StorageEnvironment should try normal access`() {
        // Mock StorageEnvironment to include USB OTG
        every { mockStorageEnvironment.publicStorages } returns listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/usbotg")
        )

        checker = LocalPathAccessChecker(mockStorageEnvironment)

        val path = LocalPath.build("/storage/usbotg/backup/data.zip")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `app own data directory should always try normal access`() {
        val paths = listOf(
            "/data/data/eu.darken.butler/cache/temp.db",
            "/data/data/eu.darken.butler/files/config.json",
            "/data/user/0/eu.darken.butler/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
        }
    }

    @Test
    fun `other app data directory should not try normal access`() {
        val paths = listOf(
            "/data/data/com.other.app/databases/db.db",
            "/data/data/com.other.app/files/file.txt",
            "/data/user/0/com.other.app/cache/cache.db"
        )

        paths.forEach { pathStr ->
            val path = LocalPath.build(pathStr)
            checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
            checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun `unknown path should conservatively try normal access`() {
        // Custom ROM partition not in StorageEnvironment
        val path = LocalPath.build("/mnt/vendor/persist/data.db")

        // Conservative fallback: try normal access first
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `path that starts with restricted prefix but is actually under accessible storage`() {
        // Edge case: file named "system" under sdcard
        val path = LocalPath.build("/sdcard/system/backup.tar")

        // Should NOT be confused with /system/ partition
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `path aliases are handled consistently`() {
        // /sdcard is a symlink to /storage/emulated/0
        val path1 = LocalPath.build("/sdcard/test.txt")
        val path2 = LocalPath.build("/storage/emulated/0/test.txt")

        // Both should try normal access
        checker.shouldTryNormalAccess(path1, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path1, forWriting = true) shouldBe true

        checker.shouldTryNormalAccess(path2, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path2, forWriting = true) shouldBe true
    }

    @Test
    fun `empty StorageEnvironment externalDirs still has baseline accessible paths`() {
        // Mock empty external dirs (unusual but possible)
        every { mockStorageEnvironment.publicStorages } returns emptyList()

        checker = LocalPathAccessChecker(mockStorageEnvironment)

        // Common paths should still work via hardcoded Tier 1 rules
        val path = LocalPath.build("/sdcard/test.txt")
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `data local tmp should not try normal access by default`() {
        // /data/local/tmp is sometimes accessible but not reliably
        val path = LocalPath.build("/data/local/tmp/test.txt")

        // Not our package, so don't try normal access
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `cache partition should not try normal access`() {
        // /cache is a system partition
        val path = LocalPath.build("/cache/recovery/last_log")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `mnt paths should conservatively try normal access unless in StorageEnvironment`() {
        val path = LocalPath.build("/mnt/media_rw/1234-5678/test.txt")

        // Not in StorageEnvironment, but try normal access anyway (conservative)
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    // ========================================================================
    // Root Directory Tests (matches(), not just isDescendantOf())
    // Regression tests for bug where root directories themselves weren't recognized
    // ========================================================================

    @Test
    fun `storage emulated 0 root itself should try normal access`() {
        // This is the primary bug fix - /storage/emulated/0 ITSELF should be accessible
        val path = LocalPath.build("/storage/emulated/0")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `sdcard root itself should try normal access`() {
        val path = LocalPath.build("/sdcard")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `external SD card root itself should try normal access`() {
        val path = LocalPath.build("/storage/ABCD-12324")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `system partition root itself should try normal access for read only`() {
        val path = LocalPath.build("/system")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `proc root itself should try normal access for read only`() {
        val path = LocalPath.build("/proc")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `vendor root itself should try normal access for read only`() {
        val path = LocalPath.build("/vendor")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `product root itself should try normal access for read only`() {
        val path = LocalPath.build("/product")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `system_ext root itself should try normal access for read only`() {
        val path = LocalPath.build("/system_ext")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `apex root itself should try normal access for read only`() {
        val path = LocalPath.build("/apex")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `storage root itself should not try normal access`() {
        // /storage is a mount point container, not directly accessible
        val path = LocalPath.build("/storage")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `data root itself should not try normal access`() {
        val path = LocalPath.build("/data")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `dev root itself should not try normal access`() {
        val path = LocalPath.build("/dev")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `sys root itself should not try normal access`() {
        val path = LocalPath.build("/sys")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `cache root itself should not try normal access`() {
        val path = LocalPath.build("/cache")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }

    @Test
    fun `app private data dir root itself should try normal access`() {
        val path = LocalPath.build("/data/user/0/eu.darken.butler")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    fun `app public data dir root itself should try normal access`() {
        val path = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler")

        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe true
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe true
    }

    @Test
    @Config(sdk = [30])
    fun `Android data dir root itself should not try normal access on API 30+`() {
        // Setup scoped storage restrictions
        every { mockStorageEnvironment.publicDataDirs } returns listOf(
            LocalPath.build("/storage/emulated/0/Android/data")
        )

        checker = LocalPathAccessChecker(mockStorageEnvironment)

        val path = LocalPath.build("/storage/emulated/0/Android/data")

        // The blocked directory itself should be blocked
        checker.shouldTryNormalAccess(path, forWriting = false) shouldBe false
        checker.shouldTryNormalAccess(path, forWriting = true) shouldBe false
    }
}
