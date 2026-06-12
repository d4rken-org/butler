package eu.darken.butler.apps.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.AKnownPkg
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TagFilterConfigTest : BaseTest() {

    private fun createMockAppItem(
        packageName: String = "com.test.app",
        isSystemApp: Boolean = false,
        isEnabled: Boolean = true,
        installerPackageId: Pkg.Id? = null,
        userHandleId: Int = 0,
        userLabel: String? = null,
        isSplitApk: Boolean = false,
        isDebuggable: Boolean = false,
        isUpdatedSystemApp: Boolean = false,
    ): AppItem {
        val installerInfo = if (installerPackageId != null) {
            mockk<InstallerInfo> {
                every { allInstallers } returns listOf(mockk { every { id } returns installerPackageId })
            }
        } else {
            null
        }

        val pkg = mockk<Installed> {
            every { id } returns Pkg.Id(packageName)
        }

        return AppItem(
            pkg = pkg,
            label = "Test App".toCaString(),
            icon = null,
            packageName = packageName,
            versionName = "1.0",
            versionCode = 1L,
            appSize = null,
            isSystemApp = isSystemApp,
            isEnabled = isEnabled,
            isUpdatedSystemApp = isUpdatedSystemApp,
            installedAt = null,
            updatedAt = null,
            installerInfo = installerInfo,
            isSplitApk = isSplitApk,
            isDebuggable = isDebuggable,
            userProfile = UserProfile2(
                handle = UserHandle2(handleId = userHandleId),
                label = userLabel,
            ),
        )
    }

    @Test
    fun `empty filter matches all apps`() {
        val filter = TagFilterConfig()
        val userApp = createMockAppItem(isSystemApp = false)
        val systemApp = createMockAppItem(isSystemApp = true)
        val disabledApp = createMockAppItem(isEnabled = false)

        filter.matches(userApp) shouldBe true
        filter.matches(systemApp) shouldBe true
        filter.matches(disabledApp) shouldBe true
    }

    @Test
    fun `include System tag filters to only system apps`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.System))
        val userApp = createMockAppItem(isSystemApp = false)
        val systemApp = createMockAppItem(isSystemApp = true)

        filter.matches(userApp) shouldBe false
        filter.matches(systemApp) shouldBe true
    }

    @Test
    fun `include UserApp tag filters to only user apps`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.UserApp))
        val userApp = createMockAppItem(isSystemApp = false)
        val systemApp = createMockAppItem(isSystemApp = true)

        filter.matches(userApp) shouldBe true
        filter.matches(systemApp) shouldBe false
    }

    @Test
    fun `include Disabled tag filters to only disabled apps`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.Disabled))
        val enabledApp = createMockAppItem(isEnabled = true)
        val disabledApp = createMockAppItem(isEnabled = false)

        filter.matches(enabledApp) shouldBe false
        filter.matches(disabledApp) shouldBe true
    }

    @Test
    fun `include Enabled tag filters to only enabled apps`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.Enabled))
        val enabledApp = createMockAppItem(isEnabled = true)
        val disabledApp = createMockAppItem(isEnabled = false)

        filter.matches(enabledApp) shouldBe true
        filter.matches(disabledApp) shouldBe false
    }

    @Test
    fun `include multiple tags requires ALL tags (AND logic)`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.System, AppTag.Disabled))

        val enabledSystemApp = createMockAppItem(isSystemApp = true, isEnabled = true)
        val disabledUserApp = createMockAppItem(isSystemApp = false, isEnabled = false)
        val disabledSystemApp = createMockAppItem(isSystemApp = true, isEnabled = false)

        filter.matches(enabledSystemApp) shouldBe false
        filter.matches(disabledUserApp) shouldBe false
        filter.matches(disabledSystemApp) shouldBe true
    }

    @Test
    fun `exclude System tag removes system apps`() {
        val filter = TagFilterConfig(excludeTags = setOf(AppTag.System))
        val userApp = createMockAppItem(isSystemApp = false)
        val systemApp = createMockAppItem(isSystemApp = true)

        filter.matches(userApp) shouldBe true
        filter.matches(systemApp) shouldBe false
    }

    @Test
    fun `exclude any tag removes apps with that tag (OR logic)`() {
        val filter = TagFilterConfig(excludeTags = setOf(AppTag.System, AppTag.Disabled))

        val enabledUserApp = createMockAppItem(isSystemApp = false, isEnabled = true)
        val enabledSystemApp = createMockAppItem(isSystemApp = true, isEnabled = true)
        val disabledUserApp = createMockAppItem(isSystemApp = false, isEnabled = false)

        filter.matches(enabledUserApp) shouldBe true
        filter.matches(enabledSystemApp) shouldBe false
        filter.matches(disabledUserApp) shouldBe false
    }

    @Test
    fun `exclude takes precedence over include`() {
        val filter = TagFilterConfig(
            includeTags = setOf(AppTag.System),
            excludeTags = setOf(AppTag.Disabled),
        )

        val enabledSystemApp = createMockAppItem(isSystemApp = true, isEnabled = true)
        val disabledSystemApp = createMockAppItem(isSystemApp = true, isEnabled = false)

        filter.matches(enabledSystemApp) shouldBe true
        filter.matches(disabledSystemApp) shouldBe false
    }

    @Test
    fun `User tag matches by handleId regardless of label`() {
        val filterWithLabel = TagFilterConfig(includeTags = setOf(AppTag.User(10, "Work")))
        val filterWithoutLabel = TagFilterConfig(includeTags = setOf(AppTag.User(10, null)))

        val appWithLabel = createMockAppItem(userHandleId = 10, userLabel = "Work Profile")
        val appWithDifferentLabel = createMockAppItem(userHandleId = 10, userLabel = "Different")

        filterWithLabel.matches(appWithLabel) shouldBe true
        filterWithLabel.matches(appWithDifferentLabel) shouldBe true
        filterWithoutLabel.matches(appWithLabel) shouldBe true
        filterWithoutLabel.matches(appWithDifferentLabel) shouldBe true
    }

    @Test
    fun `User tag does not match different handleId`() {
        val filter = TagFilterConfig(includeTags = setOf(AppTag.User(10, "Work")))

        val mainUserApp = createMockAppItem(userHandleId = 0)
        val workProfileApp = createMockAppItem(userHandleId = 10, userLabel = "Work")
        val otherProfileApp = createMockAppItem(userHandleId = 20, userLabel = "Other")

        filter.matches(mainUserApp) shouldBe false
        filter.matches(workProfileApp) shouldBe true
        filter.matches(otherProfileApp) shouldBe false
    }

    @Test
    fun `isEmpty returns true for empty config`() {
        TagFilterConfig().isEmpty shouldBe true
        TagFilterConfig(includeTags = emptySet(), excludeTags = emptySet()).isEmpty shouldBe true
    }

    @Test
    fun `isEmpty returns false when includeTags is not empty`() {
        TagFilterConfig(includeTags = setOf(AppTag.System)).isEmpty shouldBe false
    }

    @Test
    fun `isEmpty returns false when excludeTags is not empty`() {
        TagFilterConfig(excludeTags = setOf(AppTag.Disabled)).isEmpty shouldBe false
    }
}

class AppItemTagsTest : BaseTest() {

    private fun createMockAppItem(
        isSystemApp: Boolean = false,
        isEnabled: Boolean = true,
        installerPackageId: Pkg.Id? = null,
        userHandleId: Int = 0,
        userLabel: String? = null,
        isSplitApk: Boolean = false,
        isDebuggable: Boolean = false,
        isUpdatedSystemApp: Boolean = false,
    ): AppItem {
        val installerInfo = if (installerPackageId != null) {
            mockk<InstallerInfo> {
                every { allInstallers } returns listOf(mockk { every { id } returns installerPackageId })
            }
        } else {
            null
        }

        val pkg = mockk<Installed> {
            every { id } returns Pkg.Id("com.test.app")
        }

        return AppItem(
            pkg = pkg,
            label = "Test App".toCaString(),
            icon = null,
            packageName = "com.test.app",
            versionName = "1.0",
            versionCode = 1L,
            appSize = null,
            isSystemApp = isSystemApp,
            isEnabled = isEnabled,
            isUpdatedSystemApp = isUpdatedSystemApp,
            installedAt = null,
            updatedAt = null,
            installerInfo = installerInfo,
            isSplitApk = isSplitApk,
            isDebuggable = isDebuggable,
            userProfile = UserProfile2(
                handle = UserHandle2(handleId = userHandleId),
                label = userLabel,
            ),
        )
    }

    @Test
    fun `tags includes System for system apps`() {
        val app = createMockAppItem(isSystemApp = true)
        app.tags shouldContain AppTag.System
    }

    @Test
    fun `tags does not include System for user apps`() {
        val app = createMockAppItem(isSystemApp = false)
        app.tags shouldNotContain AppTag.System
    }

    @Test
    fun `tags includes Disabled for disabled apps`() {
        val app = createMockAppItem(isEnabled = false)
        app.tags shouldContain AppTag.Disabled
    }

    @Test
    fun `tags does not include Disabled for enabled apps`() {
        val app = createMockAppItem(isEnabled = true)
        app.tags shouldNotContain AppTag.Disabled
    }

    @Test
    fun `tags includes User for non-zero handle`() {
        val app = createMockAppItem(userHandleId = 10, userLabel = "Work")
        app.tags shouldContain AppTag.User(10, "Work")
    }

    @Test
    fun `tags does not include User for main user (handle 0)`() {
        val app = createMockAppItem(userHandleId = 0)
        app.tags.none { it is AppTag.User } shouldBe true
    }

    @Test
    fun `tags includes SplitApk for split APK apps`() {
        val app = createMockAppItem(isSplitApk = true)
        app.tags shouldContain AppTag.SplitApk
    }

    @Test
    fun `tags includes Debug for debuggable apps`() {
        val app = createMockAppItem(isDebuggable = true)
        app.tags shouldContain AppTag.Debug
    }

    @Test
    fun `tags includes UpdatedSystem for updated system apps`() {
        val app = createMockAppItem(isSystemApp = true, isUpdatedSystemApp = true)
        app.tags shouldContain AppTag.UpdatedSystem
    }

    @Test
    fun `isSideloaded returns false for system apps`() {
        val app = createMockAppItem(isSystemApp = true)
        app.isSideloaded shouldBe false
    }

    @Test
    fun `isSideloaded returns true when no installer info`() {
        val app = createMockAppItem(isSystemApp = false, installerPackageId = null)
        app.isSideloaded shouldBe true
    }

    @Test
    fun `isSideloaded returns false for Play Store apps`() {
        val app = createMockAppItem(
            isSystemApp = false,
            installerPackageId = AKnownPkg.GooglePlay.id,
        )
        app.isSideloaded shouldBe false
    }

    @Test
    fun `isSideloaded returns true for non-Play-Store apps`() {
        val app = createMockAppItem(
            isSystemApp = false,
            installerPackageId = Pkg.Id("com.other.store"),
        )
        app.isSideloaded shouldBe true
    }

    @Test
    fun `tags includes Sideloaded for sideloaded apps`() {
        val app = createMockAppItem(isSystemApp = false, installerPackageId = null)
        app.tags shouldContain AppTag.Sideloaded
    }

    @Test
    fun `tags does not include Sideloaded for Play Store apps`() {
        val app = createMockAppItem(
            isSystemApp = false,
            installerPackageId = AKnownPkg.GooglePlay.id,
        )
        app.tags shouldNotContain AppTag.Sideloaded
    }

    @Test
    fun `toTagSet includes Enabled for enabled apps`() {
        val app = createMockAppItem(isEnabled = true)
        app.toTagSet() shouldContain AppTag.Enabled
    }

    @Test
    fun `toTagSet does not include Enabled for disabled apps`() {
        val app = createMockAppItem(isEnabled = false)
        app.toTagSet() shouldNotContain AppTag.Enabled
    }

    @Test
    fun `toTagSet includes UserApp for non-system apps`() {
        val app = createMockAppItem(isSystemApp = false)
        app.toTagSet() shouldContain AppTag.UserApp
    }

    @Test
    fun `toTagSet does not include UserApp for system apps`() {
        val app = createMockAppItem(isSystemApp = true)
        app.toTagSet() shouldNotContain AppTag.UserApp
    }

    @Test
    fun `tags are sorted by priority`() {
        val app = createMockAppItem(
            isSystemApp = true,
            isEnabled = false,
            isDebuggable = true,
        )
        val tags = app.tags
        val priorities = tags.map { it.priority }
        priorities shouldBe priorities.sorted()
    }
}
