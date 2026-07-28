package eu.darken.butler.apps.ui.apps.preview

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaDrawable
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.io.R as IoR
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.container.toStub
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

object AppsMockDataProvider {

    object MockSizes {
        const val KB = 1024L
        const val MB = KB * 1024
        const val GB = MB * 1024

        fun kb(value: Long) = value * KB
        fun mb(value: Long) = value * MB
        fun gb(value: Long) = value * GB
    }

    private object MockTimes {
        fun hoursAgo(hours: Long): Instant = kotlin.time.Clock.System.now() - hours.hours
        fun daysAgo(days: Long): Instant = kotlin.time.Clock.System.now() - days.days
    }

    fun createMockInstalled(
        packageName: String = "com.example.app",
        label: String = "Example App",
        isEnabled: Boolean = true,
        versionName: String = "1.0.0",
        versionCode: Long = 1,
        isSystemApp: Boolean = false,
        targetSdk: Int? = 34,
        minSdk: Int? = 24,
        uid: Int? = 10234,
        installedAt: Instant? = null,
        updatedAt: Instant? = null,
    ): Installed {
        return object : Installed {
            override val id = Pkg.Id(packageName)

            override val packageInfo: PackageInfo = PackageInfo().apply {
                this.packageName = packageName
                this.versionName = versionName
                @Suppress("DEPRECATION")
                this.versionCode = versionCode.toInt()

                // Set install/update timestamps
                installedAt?.let { firstInstallTime = it.toEpochMilliseconds() }
                updatedAt?.let { lastUpdateTime = it.toEpochMilliseconds() }

                applicationInfo = ApplicationInfo().apply {
                    enabled = isEnabled
                    this.packageName = packageName

                    // Set system app flag
                    if (isSystemApp) {
                        flags = flags or ApplicationInfo.FLAG_SYSTEM
                    }

                    // Set SDK versions
                    targetSdk?.let { targetSdkVersion = it }
                    minSdk?.let {
                        @Suppress("DEPRECATION")
                        minSdkVersion = it
                    }

                    // Set UID
                    uid?.let { this.uid = it }
                }
            }

            override val label: CaString = label.toCaString()
            override val icon: CaDrawable? = null
            override val userHandle: UserHandle2 = UserHandle2(0)
        }
    }

    fun createMockAppInfo(
        packageName: String = "com.example.app",
        label: String = "Example App",
        isEnabled: Boolean = true,
        versionName: String = "1.0.0",
        versionCode: Long = 1,
        appSize: Long? = MockSizes.mb(50),
        dataSize: Long? = MockSizes.mb(10),
        cacheSize: Long? = MockSizes.mb(5),
        isSystemApp: Boolean = false,
        hoursAgo: Long = 24,
        targetSdk: Int? = 34,
        minSdk: Int? = 24,
        uid: Int? = 10234,
        permissionCount: Int = 5,
    ): AppInfo {
        val installedAt = MockTimes.hoursAgo(hoursAgo)
        val updatedAt = MockTimes.hoursAgo(hoursAgo / 2)

        return AppInfo(
            install = createMockInstalled(
                packageName = packageName,
                label = label,
                isEnabled = isEnabled,
                versionName = versionName,
                versionCode = versionCode,
                isSystemApp = isSystemApp,
                targetSdk = targetSdk,
                minSdk = minSdk,
                uid = uid,
                installedAt = installedAt,
                updatedAt = updatedAt,
            ),
            appSize = appSize,
            dataSize = dataSize,
            cacheSize = cacheSize,
        )
    }

    fun createMockAppItem(
        packageName: String = "com.example.app",
        label: String = "Example App",
        isSystem: Boolean = false,
        isEnabled: Boolean = true,
        versionName: String? = "1.0.0",
        versionCode: Long = 1,
        appSize: Long? = MockSizes.mb(50),
        hoursAgo: Long = 24,
        targetSdk: Int? = 34,
        minSdk: Int? = 24,
        uid: Int? = 10234,
        isUpdatedSystemApp: Boolean = false,
        isSplitApk: Boolean = false,
        isDebuggable: Boolean = false,
        userProfile: UserProfile2 = UserProfile2(handle = UserHandle2(0)),
        icon: CaDrawable? = null,
        installerInfo: InstallerInfo? = null,
    ): AppItem {
        val installedAt = MockTimes.hoursAgo(hoursAgo)
        val updatedAt = MockTimes.hoursAgo(hoursAgo / 2)

        val mockPkg = createMockInstalled(
            packageName = packageName,
            label = label,
            isEnabled = isEnabled,
            versionName = versionName ?: "1.0.0",
            versionCode = versionCode,
            isSystemApp = isSystem,
            targetSdk = targetSdk,
            minSdk = minSdk,
            uid = uid,
            installedAt = installedAt,
            updatedAt = updatedAt,
        )

        return AppItem(
            pkg = mockPkg,
            label = label.toCaString(),
            icon = icon,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            appSize = appSize,
            isSystemApp = isSystem,
            isEnabled = isEnabled,
            isUpdatedSystemApp = isUpdatedSystemApp,
            installedAt = installedAt,
            updatedAt = updatedAt,
            installerInfo = installerInfo,
            isSplitApk = isSplitApk,
            isDebuggable = isDebuggable,
            userProfile = userProfile,
        )
    }

    fun createMockAppsState(
        apps: List<AppItem> = emptyList(),
        selectedIds: Set<eu.darken.butler.common.pkgs.features.InstallId> = emptySet(),
    ): AppsState {
        return AppsState(
            apps = apps,
            filteredApps = apps,
            selectedAppIds = selectedIds,
            isLoading = false,
            filterConfig = eu.darken.butler.workspace.contracts.apps.TagFilterConfig(),
            sortSettings = eu.darken.butler.workspace.contracts.apps.SortSettings(),
        )
    }

    // Preset mock apps for common scenarios
    object Presets {
        val chrome = createMockAppInfo(
            packageName = "com.android.chrome",
            label = "Chrome",
            isEnabled = true,
            versionName = "120.0.6099.144",
            versionCode = 609914400,
            appSize = MockSizes.mb(120),
            dataSize = MockSizes.mb(45),
            cacheSize = MockSizes.mb(15),
            isSystemApp = false,
            hoursAgo = 240,
            targetSdk = 34,
            minSdk = 24,
            uid = 10123,
            permissionCount = 12,
        )

        val settings = createMockAppInfo(
            packageName = "com.android.settings",
            label = "Settings",
            isEnabled = true,
            versionName = "15",
            versionCode = 35,
            appSize = MockSizes.mb(30),
            dataSize = MockSizes.mb(5),
            cacheSize = MockSizes.mb(2),
            isSystemApp = true,
            hoursAgo = 8760,
            targetSdk = 34,
            minSdk = 21,
            uid = 1000,
            permissionCount = 25,
        )

        val disabledApp = createMockAppInfo(
            packageName = "com.example.disabled",
            label = "Disabled App",
            isEnabled = false,
            versionName = "2.3.1",
            versionCode = 231,
            appSize = MockSizes.mb(25),
            dataSize = MockSizes.mb(8),
            cacheSize = MockSizes.mb(3),
            isSystemApp = false,
            hoursAgo = 168,
            targetSdk = 33,
            minSdk = 23,
            uid = 10234,
            permissionCount = 8,
        )

        val largeApp = createMockAppInfo(
            packageName = "com.example.game",
            label = "Large Game",
            isEnabled = true,
            versionName = "4.5.2",
            versionCode = 452000,
            appSize = MockSizes.gb(3),
            dataSize = MockSizes.mb(500),
            cacheSize = MockSizes.mb(200),
            isSystemApp = false,
            hoursAgo = 72,
            targetSdk = 34,
            minSdk = 26,
            uid = 10345,
            permissionCount = 15,
        )

        val chromeItem = createMockAppItem(
            packageName = "com.android.chrome",
            label = "Chrome",
            isSystem = false,
            isEnabled = true,
        )

        val settingsItem = createMockAppItem(
            packageName = "com.android.settings",
            label = "Settings",
            isSystem = true,
            isEnabled = true,
        )

        val notesItem = createMockAppItem(
            packageName = "com.example.notes",
            label = "Notes",
            isSystem = false,
            isEnabled = true,
        )

        val systemUiItem = createMockAppItem(
            packageName = "com.android.systemui",
            label = "System UI",
            isSystem = true,
            isEnabled = true,
        )

        val disabledAppItem = createMockAppItem(
            packageName = "com.spotify.music",
            label = "Spotify",
            isSystem = false,
            isEnabled = false,
        )

        val splitApkItem = createMockAppItem(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            isSystem = false,
            isEnabled = true,
            isSplitApk = true,
        )

        val debugAppItem = createMockAppItem(
            packageName = "com.developer.debug",
            label = "Debug App",
            isSystem = false,
            isEnabled = true,
            isDebuggable = true,
        )

        val multiTagAppItem = createMockAppItem(
            packageName = "com.android.phone",
            label = "Phone",
            isSystem = true,
            isEnabled = false,
            isUpdatedSystemApp = true,
        )

        val updatedSystemItem = createMockAppItem(
            packageName = "com.android.webview",
            label = "WebView",
            isSystem = true,
            isEnabled = true,
            isUpdatedSystemApp = true,
            isSplitApk = true,
        )

        val workProfileAppItem = createMockAppItem(
            packageName = "com.slack",
            label = "Slack",
            isSystem = false,
            isEnabled = true,
            userProfile = UserProfile2(
                handle = UserHandle2(10),
                label = "Work",
            ),
        )

        /** Any non-null icon puts the row on its AsyncImage branch; the drawable itself is never shown. */
        private val placeholderIcon: CaDrawable = IoR.drawable.ic_default_app_icon_24.toCaDrawable()

        private val storeInstaller = InstallerInfo(installingPkg = Pkg.Id("com.android.vending").toStub())

        private fun playStoreItem(
            packageName: String,
            label: String,
            appSize: Long,
            versionName: String,
            versionCode: Long,
            hoursAgo: Long,
            isSystem: Boolean = false,
            isEnabled: Boolean = true,
            isUpdatedSystemApp: Boolean = false,
            isSplitApk: Boolean = false,
            fromStore: Boolean = true,
            userProfile: UserProfile2 = UserProfile2(handle = UserHandle2(0)),
        ) = createMockAppItem(
            packageName = packageName,
            label = label,
            isSystem = isSystem,
            isEnabled = isEnabled,
            versionName = versionName,
            versionCode = versionCode,
            appSize = appSize,
            hoursAgo = hoursAgo,
            isUpdatedSystemApp = isUpdatedSystemApp,
            isSplitApk = isSplitApk,
            userProfile = userProfile,
            icon = placeholderIcon,
            installerInfo = if (fromStore) storeInstaller else null,
        )

        /**
         * The Play Store screenshot's app list: invented labels and invented package names only,
         * no real-world app, vendor or trademark. No entry is debuggable, so the shot's active
         * "exclude debug apps" filter is true of every visible row.
         */
        val playStoreItems: List<AppItem> = listOf(
            playStoreItem(
                packageName = "com.novabyte.lumen",
                label = "Lumen",
                appSize = MockSizes.mb(84),
                versionName = "2.11.3",
                versionCode = 21103,
                hoursAgo = 6,
            ),
            playStoreItem(
                packageName = "io.pinepath.tracker",
                label = "PinePath Tracker",
                appSize = MockSizes.mb(42),
                versionName = "1.0",
                versionCode = 1,
                hoursAgo = 30,
                fromStore = false,
            ),
            playStoreItem(
                packageName = "net.quillstone.reader",
                label = "Quillstone Reader",
                appSize = MockSizes.mb(156),
                versionName = "7.0.4-beta",
                versionCode = 70400,
                hoursAgo = 72,
                isSplitApk = true,
            ),
            playStoreItem(
                packageName = "com.emberleaf.notes",
                label = "Emberleaf Notes",
                appSize = MockSizes.mb(12),
                versionName = "3.2",
                versionCode = 320,
                hoursAgo = 480,
                isEnabled = false,
                fromStore = false,
            ),
            playStoreItem(
                packageName = "app.tidewave.player",
                label = "Tidewave",
                appSize = MockSizes.mb(2),
                versionName = "2026.07.1",
                versionCode = 20260701,
                hoursAgo = 12,
            ),
            playStoreItem(
                packageName = "com.harborglass.vault",
                label = "Harborglass Vault",
                appSize = MockSizes.mb(68),
                versionName = "4.8.2",
                versionCode = 4820,
                hoursAgo = 168,
                userProfile = UserProfile2(handle = UserHandle2(10), label = "Work"),
            ),
            playStoreItem(
                packageName = "com.driftmark.system",
                label = "Driftmark System",
                appSize = MockSizes.mb(30),
                versionName = "15",
                versionCode = 15,
                hoursAgo = 8760,
                isSystem = true,
            ),
            playStoreItem(
                packageName = "org.cinderfield.gallery",
                label = "Cinderfield Gallery",
                appSize = MockSizes.mb(210),
                versionName = "9.1.0",
                versionCode = 90100,
                hoursAgo = 96,
                isSystem = true,
                isUpdatedSystemApp = true,
                isSplitApk = true,
            ),
            playStoreItem(
                packageName = "com.starlark.maps",
                label = "Starlark Maps",
                appSize = MockSizes.mb(3482),
                versionName = "5.19.2",
                versionCode = 51902,
                hoursAgo = 48,
            ),
            playStoreItem(
                packageName = "com.slateforge.keyboard",
                label = "Slateforge Keyboard",
                appSize = MockSizes.mb(24),
                versionName = "12.4",
                versionCode = 1240,
                hoursAgo = 4380,
                isSystem = true,
                isEnabled = false,
            ),
        )
    }
}
