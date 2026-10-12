package eu.darken.butler.apps.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ApkExportTest : BaseTest() {

    private fun export(
        sourceDir: String?,
        splitSources: Set<String>?,
    ) = apkExport(
        sourceDir = sourceDir?.let { LocalPath.build(it) },
        splitSources = splitSources?.map { LocalPath.build(it) }?.toSet(),
        label = "Foo",
        packageName = "com.foo",
        versionName = "1.0",
        versionCode = 42,
    )

    @Test
    fun `a split app exports one apks archive with the base stored as base apk`() {
        export(
            sourceDir = "/system/app/Foo/Foo.apk",
            splitSources = setOf(
                "/system/app/Foo/split_config.xxhdpi.apk",
                "/system/app/Foo/split_config.de.apk",
            ),
        ) shouldBe ApkExport(
            uri = "file:///system/app/Foo/Foo.apk",
            name = "Foo_com.foo_1.0_42.apks",
            bundle = listOf(
                SaverArguments.BundleMember(uri = "file:///system/app/Foo/Foo.apk", entryName = "base.apk"),
                SaverArguments.BundleMember(
                    uri = "file:///system/app/Foo/split_config.de.apk",
                    entryName = "split_config.de.apk",
                ),
                SaverArguments.BundleMember(
                    uri = "file:///system/app/Foo/split_config.xxhdpi.apk",
                    entryName = "split_config.xxhdpi.apk",
                ),
            ),
        )
    }

    @Test
    fun `an app without splits exports its plain apk`() {
        val expected = ApkExport(
            uri = "file:///data/app/~~a/com.foo-1/base.apk",
            name = "Foo_com.foo_1.0_42.apk",
            bundle = emptyList(),
        )

        export(sourceDir = "/data/app/~~a/com.foo-1/base.apk", splitSources = null) shouldBe expected
        export(sourceDir = "/data/app/~~a/com.foo-1/base.apk", splitSources = emptySet()) shouldBe expected
    }

    @Test
    fun `an app without a source has nothing to export`() {
        export(sourceDir = null, splitSources = null).shouldBeNull()
        export(
            sourceDir = null,
            splitSources = setOf("/data/app/~~a/com.foo-1/split_config.de.apk"),
        ).shouldBeNull()
    }

    @Test
    fun `members colliding on one entry name are not exported`() {
        export(
            sourceDir = "/system/app/Foo/Foo.apk",
            splitSources = setOf("/system/app/Foo/splits/base.apk"),
        ).shouldBeNull()

        export(
            sourceDir = "/data/app/~~a/com.foo-1/base.apk",
            splitSources = setOf(
                "/data/app/~~a/com.foo-1/a/split_config.de.apk",
                "/data/app/~~a/com.foo-1/b/split_config.de.apk",
            ),
        ).shouldBeNull()
    }
}
