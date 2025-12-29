package eu.darken.butler.apps.ui.apps.preview

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
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
            icon = null,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            appSize = appSize,
            isSystemApp = isSystem,
            isEnabled = isEnabled,
            isUpdatedSystemApp = isUpdatedSystemApp,
            installedAt = installedAt,
            updatedAt = updatedAt,
            installerInfo = null,
            isSplitApk = isSplitApk,
            isDebuggable = isDebuggable,
            userProfile = userProfile,
        )
    }

    fun createMockAppsState(
        apps: List<AppItem> = emptyList(),
        selectedIds: Set<String> = emptySet(),
    ): AppsState {
        return AppsState(
            apps = apps,
            filteredApps = apps,
            selectedAppIds = selectedIds,
            isLoading = false,
            filterConfig = eu.darken.butler.apps.core.TagFilterConfig(),
            sortSettings = eu.darken.butler.apps.core.SortSettings(),
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
    }
}
