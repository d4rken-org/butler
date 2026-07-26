package eu.darken.butler.apps.core.details.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * Reads an app's manifest components and their effective enablement.
 *
 * Both phases run on [DispatcherProvider.IO]: `ViewModel2.launch()` dispatches on `Default`, so
 * without this the blocking binder calls would occupy computation threads.
 */
class AppComponentsLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val tag = logTag("AppDetails", "Components", "Loader")

    /**
     * Cheap phase: the manifest listing, including components disabled at runtime
     * ([PackageManager.MATCH_DISABLED_COMPONENTS]) so a disabled component appears at all.
     * Entries come back [ComponentEnabledState.UNRESOLVED].
     */
    suspend fun load(packageName: String): ComponentsData = withContext(dispatchers.IO) {
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        ComponentsData(
            activities = packageInfo.activities?.map { it.toEntry(ComponentKind.ACTIVITY, packageName) } ?: emptyList(),
            services = packageInfo.services?.map { it.toEntry(packageName) } ?: emptyList(),
            receivers = packageInfo.receivers?.map { it.toEntry(ComponentKind.RECEIVER, packageName) } ?: emptyList(),
            providers = packageInfo.providers?.map { it.toEntry(packageName) } ?: emptyList(),
        ).also { log(tag, VERBOSE) { "load($packageName): ${it.total} components" } }
    }

    /**
     * Expensive phase: one binder call per component, mapped by [ComponentEntry.key].
     *
     * Only a vanished component ([IllegalArgumentException] — the package was updated mid-pass)
     * falls back to the manifest baseline. Anything else propagates so the caller can surface an
     * error instead of silently marking hundreds of components enabled.
     */
    suspend fun resolveEnabledStates(data: ComponentsData): Map<String, Boolean> = withContext(dispatchers.IO) {
        val entries = data.all
        val packageName = entries.firstOrNull()?.packageName ?: return@withContext emptyMap()
        val pm = context.packageManager
        val started = TimeSource.Monotonic.markNow()

        // Read fresh, and effective: ApplicationInfo.enabled already folds the manifest flag and any
        // runtime override into one answer, so enabling a previously disabled app is picked up on the
        // next route entry without re-running phase 1. MATCH_DISABLED_COMPONENTS so a disabled app
        // resolves at all. A package removed mid-pass propagates — the workspace auto-closes on that.
        val appEnabled = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS).enabled

        var vanished = 0
        val states = HashMap<String, Boolean>(entries.size)
        entries.forEachIndexed { index, entry ->
            if (index % 32 == 0) coroutineContext.ensureActive()
            val setting = try {
                pm.getComponentEnabledSetting(ComponentName(entry.packageName, entry.className))
            } catch (e: IllegalArgumentException) {
                vanished++
                states[entry.key] = appEnabled && entry.manifestEnabled
                return@forEachIndexed
            }
            states[entry.key] = resolveEnabled(
                componentSetting = setting,
                appEnabled = appEnabled,
                manifestEnabled = entry.manifestEnabled,
            )
        }

        if (vanished > 0) {
            log(tag, WARN) { "resolveEnabledStates($packageName): $vanished of ${entries.size} components vanished" }
        }
        log(tag, VERBOSE) {
            "resolveEnabledStates($packageName): ${entries.size} entries in ${started.elapsedNow().inWholeMilliseconds}ms"
        }
        states
    }

    private fun ActivityInfo.toEntry(kind: ComponentKind, pkg: String) = ComponentEntry(
        kind = kind,
        packageName = pkg,
        className = name,
        isExported = exported,
        manifestEnabled = enabled,
        permission = permission,
        processName = processName?.takeIf { it != pkg },
        launchMode = launchMode.takeIf { kind == ComponentKind.ACTIVITY },
    )

    private fun ServiceInfo.toEntry(pkg: String) = ComponentEntry(
        kind = ComponentKind.SERVICE,
        packageName = pkg,
        className = name,
        isExported = exported,
        manifestEnabled = enabled,
        permission = permission,
        processName = processName?.takeIf { it != pkg },
    )

    private fun ProviderInfo.toEntry(pkg: String) = ComponentEntry(
        kind = ComponentKind.PROVIDER,
        packageName = pkg,
        className = name,
        isExported = exported,
        manifestEnabled = enabled,
        permission = readPermission,
        writePermission = writePermission,
        authority = authority,
        processName = processName?.takeIf { it != pkg },
    )
}
