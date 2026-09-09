package eu.darken.butler.apps.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ApkExportNamingTest : BaseTest() {

    @Test
    fun `label, package, version name and version code`() {
        apkExportFileName(
            label = "Butler",
            packageName = "eu.darken.butler",
            versionName = "0.1.0-beta0",
            versionCode = 1000000,
        ) shouldBe "Butler_eu.darken.butler_0.1.0-beta0_1000000.apk"

        apkExportFileName(
            label = "WhatsApp",
            packageName = "com.whatsapp",
            versionName = "2.24.1.78",
            versionCode = 241178000,
        ) shouldBe "WhatsApp_com.whatsapp_2.24.1.78_241178000.apk"
    }

    @Test
    fun `an app that reports no label carries only its package name`() {
        // Both AppItem and AppInfo substitute the package name when an app reports no label.
        apkExportFileName(
            label = "eu.darken.butler",
            packageName = "eu.darken.butler",
            versionName = "0.1.0-beta0",
            versionCode = 1000000,
        ) shouldBe "eu.darken.butler_0.1.0-beta0_1000000.apk"
    }

    @Test
    fun `a package name longer than the label cap is not kept as a truncated label`() {
        val packageName = "com.example." + "a".repeat(40)

        apkExportFileName(
            label = packageName,
            packageName = packageName,
            versionName = "1.0",
            versionCode = 7,
        ) shouldBe "${packageName}_1.0_7.apk"
    }

    @Test
    fun `a blank label is dropped`() {
        apkExportFileName(
            label = "   ",
            packageName = "com.example",
            versionName = "1.0",
            versionCode = 7,
        ) shouldBe "com.example_1.0_7.apk"
    }

    @Test
    fun `a missing or blank version name is dropped`() {
        apkExportFileName(
            label = "Butler",
            packageName = "eu.darken.butler",
            versionName = null,
            versionCode = 1000000,
        ) shouldBe "Butler_eu.darken.butler_1000000.apk"

        apkExportFileName(
            label = "Butler",
            packageName = "eu.darken.butler",
            versionName = "  ",
            versionCode = 1000000,
        ) shouldBe "Butler_eu.darken.butler_1000000.apk"
    }

    @Test
    fun `a label carrying path separators is sanitized`() {
        apkExportFileName(
            label = "A/B \"C\"",
            packageName = "com.example",
            versionName = "1.0",
            versionCode = 7,
        ) shouldBe "A_B _C_com.example_1.0_7.apk"
    }

    @Test
    fun `a long label is capped at 48 code points`() {
        apkExportFileName(
            label = "a".repeat(60),
            packageName = "com.example",
            versionName = "1.0",
            versionCode = 7,
        ) shouldBe "a".repeat(48) + "_com.example_1.0_7.apk"
    }

    @Test
    fun `a multi-byte label shrinks until the name fits the byte budget`() {
        // 48 CJK label characters plus 32 CJK version characters are 240 UTF-8 bytes on their own,
        // past the 247 a path component may occupy here. The label gives way, the package name and
        // version code stay intact.
        val name = apkExportFileName(
            label = "厨".repeat(48),
            packageName = "com.example.app",
            versionName = "房".repeat(32),
            versionCode = 1234567890,
        )

        name shouldBe "厨".repeat(39) + "_com.example.app_" + "房".repeat(32) + "_1234567890.apk"
        name.toByteArray(Charsets.UTF_8).size shouldBe 245
    }
}
